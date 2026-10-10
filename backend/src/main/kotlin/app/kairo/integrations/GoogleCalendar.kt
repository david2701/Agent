package app.kairo.integrations

import app.kairo.util.proxyFromEnv
import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.db.Jsonb
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.domain.Event
import app.kairo.domain.EventsRepo
import app.kairo.domain.GoogleEventData
import app.kairo.realtime.EventBus
import app.kairo.util.Crypto
import app.kairo.util.KairoJson
import app.kairo.util.zoneOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class GoogleStatus(
    val available: Boolean,
    val connected: Boolean,
    val calendarId: String? = null,
    /** Calendario de Google (compartido con la familia) donde van los eventos familiares que crea este usuario. */
    val familyCalendarId: String? = null,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
    val redirectUri: String,
)

@Serializable
data class GoogleCalendarInfo(val id: String, val summary: String, val primary: Boolean)

data class SyncReport(val pushed: Int, val pulled: Int, val deleted: Int)

/**
 * Sincronización bidireccional con Google Calendar (OAuth 2.0, flujo web con refresh token).
 * Se activa definiendo GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET. Ver docs/GOOGLE_CALENDAR.md.
 */
class GoogleCalendarService(
    private val cfg: AppConfig,
    private val db: Db,
    private val crypto: Crypto,
    private val events: EventsRepo,
    private val bus: EventBus,
) {
    private val log = LoggerFactory.getLogger(GoogleCalendarService::class.java)
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        expectSuccess = false
    }
    val redirectUri: String get() = "${cfg.publicUrl}/api/integrations/google/callback"

    /**
     * URL de autorización de Google. [nonce] es un valor aleatorio que el servidor guarda también en una
     * cookie HttpOnly del navegador que pidió la URL: va firmado dentro de `state` (sin viajar en claro),
     * así que el callback sólo vale en ese mismo navegador. Sin esto, alguien podría hacer que otra persona
     * abra un callback con su propio `code` y conectar su Google a la cuenta de ella (o al revés).
     */
    fun authUrl(userId: UUID, nonce: String): String {
        val exp = Instant.now().epochSecond + OAUTH_STATE_TTL
        val state = "$userId.$exp.${crypto.sign(stateData(userId.toString(), exp.toString(), nonce))}"
        return URLBuilder("https://accounts.google.com/o/oauth2/v2/auth").apply {
            parameters.append("client_id", cfg.googleClientId ?: "")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", SCOPES.joinToString(" "))
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
            parameters.append("include_granted_scopes", "true")
            parameters.append("state", state)
        }.buildString()
    }

    /** `state` ya usados (firma -> vencimiento): cada uno vale una sola vez. */
    private val usedStates = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Valida `state` con el [nonce] de la cookie del navegador y devuelve el usuario. Un solo uso:
     * un segundo callback con el mismo `state` se rechaza.
     */
    fun verifyState(state: String, nonce: String?): UUID? {
        if (nonce.isNullOrBlank()) return null
        val parts = state.split('.')
        if (parts.size != 3) return null
        val exp = parts[1].toLongOrNull() ?: return null
        val now = Instant.now().epochSecond
        if (exp < now) return null
        if (!crypto.verify(stateData(parts[0], parts[1], nonce), parts[2])) return null
        val userId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
        usedStates.entries.removeIf { it.value < now }
        if (usedStates.putIfAbsent(parts[2], exp) != null) return null
        return userId
    }

    private fun stateData(userId: String, exp: String, nonce: String) = "google:$userId:$exp:$nonce"

    suspend fun handleCallback(userId: UUID, code: String) {
        val resp = http.submitForm("https://oauth2.googleapis.com/token", Parameters.build {
            append("code", code)
            append("client_id", cfg.googleClientId!!)
            append("client_secret", cfg.googleClientSecret!!)
            append("redirect_uri", redirectUri)
            append("grant_type", "authorization_code")
        })
        val body = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        val refresh = (body["refresh_token"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("Google no devolvió refresh_token. Revoca el acceso en tu cuenta de Google y vuelve a conectar.")
        db.tx {
            exec(
                """insert into integrations(user_id, provider, secret, meta, status, updated_at) values (?, 'google', ?, ?, 'connected', now())
                   on conflict (user_id, provider) do update set secret = excluded.secret, status = 'connected', last_error = null, updated_at = now()""",
                userId, crypto.encrypt(refresh), Jsonb(buildJsonObject { put("calendarId", "primary") }.toString()),
            )
        }
    }

    suspend fun status(userId: UUID): GoogleStatus {
        val row = db.read {
            one("select * from integrations where user_id = ? and provider = 'google'", userId) {
                Triple(it.str("meta"), it.instantOrNull("last_sync_at"), it.strOrNull("last_error"))
            }
        }
        val meta = row?.first?.let { KairoJson.parseToJsonElement(it).jsonObject }
        val calendarId = (meta?.get("calendarId") as? JsonPrimitive)?.content
        val familyCalendarId = (meta?.get("familyCalendarId") as? JsonPrimitive)?.contentOrNull
        return GoogleStatus(cfg.googleEnabled, row != null, calendarId, familyCalendarId, row?.second?.toString(), row?.third, redirectUri)
    }

    suspend fun disconnect(userId: UUID) {
        val secret = refreshToken(userId)
        if (secret != null) runCatching { http.post("https://oauth2.googleapis.com/revoke") { parameter("token", secret) } }
        db.tx { exec("delete from integrations where user_id = ? and provider = 'google'", userId) }
    }

    suspend fun setCalendar(userId: UUID, calendarId: String) = db.tx {
        exec(
            "update integrations set meta = jsonb_set(meta, '{calendarId}', to_jsonb(?::text)) where user_id = ? and provider = 'google'",
            calendarId, userId,
        )
    }

    /** Calendario para los eventos familiares (null = el mismo de siempre). */
    suspend fun setFamilyCalendar(userId: UUID, calendarId: String?) = db.tx {
        exec(
            "update integrations set meta = case when ?::text is null then meta - 'familyCalendarId' else jsonb_set(meta, '{familyCalendarId}', to_jsonb(?::text)) end where user_id = ? and provider = 'google'",
            calendarId, calendarId, userId,
        )
    }

    suspend fun calendars(userId: UUID): List<GoogleCalendarInfo> {
        val token = accessToken(userId) ?: return emptyList()
        val resp = http.get("$API/users/me/calendarList") { header("Authorization", "Bearer $token") }
        val items = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["items"] as? JsonArray ?: return emptyList()
        return items.map { it.jsonObject }.map {
            GoogleCalendarInfo(it["id"]!!.jsonPrimitive.content, (it["summary"] as? JsonPrimitive)?.content ?: "", (it["primary"] as? JsonPrimitive)?.content == "true")
        }
    }

    suspend fun connectedUsers(): List<UUID> = db.read {
        query("select user_id from integrations where provider = 'google' and status = 'connected'") { it.getObject("user_id", UUID::class.java) }
    }

    /** Empuja cambios locales y trae cambios de Google en una ventana de -30 a +180 días. */
    suspend fun sync(userId: UUID): SyncReport {
        val token = accessToken(userId) ?: throw IllegalStateException("Google Calendar no está conectado")
        val st = status(userId)
        val calendarId = st.calendarId ?: "primary"
        val calPath = "$API/calendars/${calendarId.encodeURLPathPart()}/events"
        var pushed = 0
        var pulled = 0
        var deleted = 0
        try {
            for (d in events.dirty(userId)) {
                val e = d.event
                val insertInto = if (e.shared) st.familyCalendarId ?: calendarId else calendarId
                val target = "${API}/calendars/${(d.googleCalendarId ?: insertInto).encodeURLPathPart()}/events"
                when {
                    d.deleted && e.googleEventId != null -> {
                        val r = http.delete("$target/${e.googleEventId!!.encodeURLPathPart()}") { header("Authorization", "Bearer $token") }
                        if (r.status.isSuccess() || r.status.value == 404 || r.status.value == 410) {
                            events.markSynced(e.id, null, null, null); deleted++
                        }
                    }
                    d.deleted -> events.markSynced(e.id, null, null, null)
                    e.googleEventId == null -> {
                        val r = http.post(target) {
                            header("Authorization", "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(toGoogle(e).toString())
                        }
                        if (r.status.isSuccess()) {
                            val o = KairoJson.parseToJsonElement(r.bodyAsText()).jsonObject
                            events.markSynced(e.id, o["id"]?.jsonPrimitive?.content, insertInto, o["etag"]?.jsonPrimitive?.content); pushed++
                        } else log.warn("Google insert {}: {}", r.status.value, r.bodyAsText().take(200))
                    }
                    else -> {
                        val r = http.patch("$target/${e.googleEventId!!.encodeURLPathPart()}") {
                            header("Authorization", "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(toGoogle(e).toString())
                        }
                        if (r.status.isSuccess()) {
                            val o = KairoJson.parseToJsonElement(r.bodyAsText()).jsonObject
                            events.markSynced(e.id, null, null, o["etag"]?.jsonPrimitive?.content); pushed++
                        } else if (r.status.value == 404 || r.status.value == 410) {
                            events.markSynced(e.id, null, null, null)
                        } else log.warn("Google patch {}: {}", r.status.value, r.bodyAsText().take(200))
                    }
                }
            }

            val localRecurringIds = db.read {
                query("select google_event_id from events where user_id = ? and rrule is not null and google_event_id is not null", userId) { it.str("google_event_id") }
            }.toSet()
            val lastSync = st.lastSyncAt?.let { Instant.parse(it) }
            var pageToken: String? = null
            do {
                val r = http.get(calPath) {
                    header("Authorization", "Bearer $token")
                    parameter("singleEvents", "true")
                    parameter("showDeleted", "true")
                    parameter("maxResults", "250")
                    parameter("timeMin", Instant.now().minusSeconds(30L * 86400).toString())
                    parameter("timeMax", Instant.now().plusSeconds(180L * 86400).toString())
                    lastSync?.let { parameter("updatedMin", it.minusSeconds(120).toString()) }
                    pageToken?.let { parameter("pageToken", it) }
                }
                if (!r.status.isSuccess()) throw IllegalStateException("Google respondió ${r.status.value}: ${r.bodyAsText().take(200)}")
                val page = KairoJson.parseToJsonElement(r.bodyAsText()).jsonObject
                (page["items"] as? JsonArray)?.forEach { item ->
                    val g = fromGoogle(item.jsonObject, calendarId) ?: return@forEach
                    if (g.recurringId != null && g.recurringId in localRecurringIds) return@forEach
                    when (events.upsertFromGoogle(userId, g)) {
                        "created", "updated" -> pulled++
                        "deleted" -> deleted++
                    }
                }
                pageToken = (page["nextPageToken"] as? JsonPrimitive)?.content
            } while (pageToken != null)

            db.tx { exec("update integrations set last_sync_at = now(), last_error = null where user_id = ? and provider = 'google'", userId) }
            if (pulled + deleted + pushed > 0) bus.entityChanged(userId, "event", null, "synced")
            return SyncReport(pushed, pulled, deleted)
        } catch (e: Exception) {
            db.tx { exec("update integrations set last_error = ? where user_id = ? and provider = 'google'", e.message?.take(300), userId) }
            throw e
        }
    }

    private suspend fun refreshToken(userId: UUID): String? = db.read {
        one("select secret from integrations where user_id = ? and provider = 'google'", userId) { it.str("secret") }
    }?.let { runCatching { crypto.decrypt(it) }.getOrNull() }

    private suspend fun accessToken(userId: UUID): String? {
        if (!cfg.googleEnabled) return null
        val refresh = refreshToken(userId) ?: return null
        val resp = http.submitForm("https://oauth2.googleapis.com/token", Parameters.build {
            append("client_id", cfg.googleClientId!!)
            append("client_secret", cfg.googleClientSecret!!)
            append("refresh_token", refresh)
            append("grant_type", "refresh_token")
        })
        val body = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        if (!resp.status.isSuccess()) {
            val err = (body["error"] as? JsonPrimitive)?.content
            db.tx { exec("update integrations set last_error = ?, status = case when ? = 'invalid_grant' then 'expired' else status end where user_id = ? and provider = 'google'", "Token: $err", err, userId) }
            return null
        }
        return body["access_token"]?.jsonPrimitive?.content
    }

    private fun toGoogle(e: Event): JsonObject = buildJsonObject {
        put("summary", e.title)
        put("description", e.description)
        put("location", e.location)
        val zone = zoneOf(e.timezone)
        if (e.allDay) {
            val startDate = e.startAt.atZone(zone).toLocalDate()
            var endDate = e.endAt.atZone(zone).toLocalDate()
            if (!endDate.isAfter(startDate)) endDate = startDate.plusDays(1)
            put("start", buildJsonObject { put("date", startDate.toString()) })
            put("end", buildJsonObject { put("date", endDate.toString()) })
        } else {
            put("start", buildJsonObject { put("dateTime", e.startAt.atZone(zone).toOffsetDateTime().toString()); put("timeZone", e.timezone) })
            put("end", buildJsonObject { put("dateTime", e.endAt.atZone(zone).toOffsetDateTime().toString()); put("timeZone", e.timezone) })
        }
        e.rrule?.let { put("recurrence", buildJsonArray { add(JsonPrimitive("RRULE:$it")) }) }
        put("reminders", buildJsonObject {
            put("useDefault", false)
            put("overrides", buildJsonArray {
                e.reminderMinutes.take(5).forEach { m -> add(buildJsonObject { put("method", "popup"); put("minutes", m) }) }
            })
        })
    }

    private fun fromGoogle(o: JsonObject, calendarId: String): GoogleEventData? {
        val id = (o["id"] as? JsonPrimitive)?.content ?: return null
        val cancelled = (o["status"] as? JsonPrimitive)?.content == "cancelled"
        val start = o["start"] as? JsonObject
        val end = o["end"] as? JsonObject
        val tz = (start?.get("timeZone") as? JsonPrimitive)?.content ?: "UTC"
        fun parse(x: JsonObject?): Pair<Instant, Boolean>? {
            x ?: return null
            (x["dateTime"] as? JsonPrimitive)?.content?.let { return OffsetDateTime.parse(it).toInstant() to false }
            (x["date"] as? JsonPrimitive)?.content?.let { return LocalDate.parse(it).atStartOfDay(zoneOf(tz)).toInstant() to true }
            return null
        }
        val s = parse(start)
        val en = parse(end)
        if (s == null && !cancelled) return null
        return GoogleEventData(
            googleId = id,
            calendarId = calendarId,
            etag = (o["etag"] as? JsonPrimitive)?.content,
            recurringId = (o["recurringEventId"] as? JsonPrimitive)?.content,
            title = (o["summary"] as? JsonPrimitive)?.content ?: "(Sin título)",
            description = (o["description"] as? JsonPrimitive)?.content ?: "",
            location = (o["location"] as? JsonPrimitive)?.content ?: "",
            startAt = s?.first ?: Instant.EPOCH,
            endAt = en?.first ?: s?.first?.plusSeconds(3600) ?: Instant.EPOCH,
            allDay = s?.second ?: false,
            timezone = tz,
            cancelled = cancelled,
        )
    }

    companion object {
        private const val API = "https://www.googleapis.com/calendar/v3"

        /** Segundos que vale un `state` de OAuth (y la cookie con su nonce). */
        const val OAUTH_STATE_TTL = 600L

        /** Cookie HttpOnly con el nonce que ata el callback de OAuth al navegador que lo inició. */
        const val OAUTH_COOKIE = "kairo_google_oauth"
        const val OAUTH_COOKIE_PATH = "/api/integrations/google"

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
        )
    }
}
