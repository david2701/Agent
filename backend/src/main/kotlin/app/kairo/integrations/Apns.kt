package app.kairo.integrations

import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.InvalidInput
import app.kairo.util.M
import app.kairo.util.jdkProxySelector
import app.kairo.util.requireInput
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Content of an APNs push: the same `{type, data}` of the WebSocket under `kairo`, plus the visible alert. */
object ApnsPayload {
    /** Apple's limit for regular notifications. */
    const val MAX_BYTES = 4096
    private const val MAX_TITLE = 200
    /** The short `data` fields (ids, dates) are never trimmed. */
    private const val MIN_FIELD = 64

    fun build(event: ServerEvent, title: String, body: String, maxBytes: Int = MAX_BYTES): String {
        val t = ellipsize(title.trim(), MAX_TITLE)
        val b = body.trim()
        val longest = event.data.values.maxOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.length ?: 0 } ?: 0
        // Trim the body and the long `data` texts to the same maximum length: the largest that fits (binary search)
        fun attempt(cap: Int) = encode(event, t, ellipsize(b, cap), shrink(event.data, maxOf(cap, MIN_FIELD)))
        fun fits(json: String) = json.toByteArray(Charsets.UTF_8).size <= maxBytes
        var hi = maxOf(b.length, longest)
        attempt(hi).let { if (fits(it)) return it }
        var best = attempt(0)
        if (fits(best)) {
            var lo = 0
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                val json = attempt(mid)
                if (fits(json)) { lo = mid; best = json } else hi = mid
            }
            return best
        }
        // Last resort (does not happen with the current events): only the short `data` fields
        val short = JsonObject(event.data.filterValues { it !is JsonPrimitive || !it.isString || it.content.length <= MIN_FIELD })
        return encode(event, ellipsize(t, 60), "", short).takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
            ?: encode(ServerEvent(event.type), ellipsize(t, 60), "", JsonObject(emptyMap()))
    }

    private fun encode(event: ServerEvent, title: String, body: String, data: JsonObject): String = buildJsonObject {
        put("aps", buildJsonObject {
            put("alert", buildJsonObject {
                put("title", title)
                put("body", body)
            })
            put("sound", "default")
            put("thread-id", threadId(event))
            put("interruption-level", interruptionLevel(event))
            category(event)?.let { put("category", it) }
            // Wakes the app in the background so it syncs (besides showing the alert)
            put("content-available", 1)
        })
        put("kairo", buildJsonObject {
            put("type", event.type)
            put("data", data)
        })
        // Same as the app's local notifications carry: for "Done", "Snooze" and opening the section
        entityId(event)?.let { put("entityId", it) }
        put("destination", destination(event))
    }.toString()

    fun threadId(event: ServerEvent): String = when (event.type) {
        EventTypes.REMINDER_FIRE -> "reminder"
        EventTypes.EVENT_ALERT -> "event"
        EventTypes.BRIEFING_READY -> "briefing"
        EventTypes.FAMILY_NOTICE -> "family"
        else -> event.type
    }

    fun interruptionLevel(event: ServerEvent): String = when (event.type) {
        EventTypes.REMINDER_FIRE, EventTypes.EVENT_ALERT -> "time-sensitive"
        else -> "active"
    }

    /** Categories the app registers ("Done" / "In 10 min" and "Listen" buttons). */
    private fun category(event: ServerEvent): String? = when (event.type) {
        EventTypes.REMINDER_FIRE -> "kairo.reminder"
        EventTypes.BRIEFING_READY -> "kairo.briefing"
        else -> null
    }

    private fun entityId(event: ServerEvent): String? = when (event.type) {
        EventTypes.REMINDER_FIRE, EventTypes.EVENT_ALERT -> event.str("id")
        EventTypes.FAMILY_NOTICE -> event.str("entityId")
        else -> null
    }

    private fun destination(event: ServerEvent): String = when (event.type) {
        EventTypes.REMINDER_FIRE -> "reminders"
        EventTypes.EVENT_ALERT -> "calendar"
        EventTypes.BRIEFING_READY -> "today"
        else -> when (event.str("entityType")) {
            "note" -> "notes"
            "task" -> "tasks"
            "event" -> "calendar"
            "list" -> "lists"
            "reminder" -> "reminders"
            else -> "family"
        }
    }

    /**
     * `apns-collapse-id`: the same key the app uses to deduplicate (and to schedule the local alarm),
     * so the notice that arrives by push replaces the local one instead of being duplicated. 64 bytes at most.
     */
    fun collapseId(event: ServerEvent): String? {
        val id = event.str("id")
        fun epoch(key: String) = event.str(key)?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
        val key = when (event.type) {
            EventTypes.REMINDER_FIRE -> id?.let { i -> epoch("fireAt")?.let { "rem:$i:$it" } ?: "rem:$i" }
            EventTypes.EVENT_ALERT -> id?.let { i -> epoch("startAt")?.let { "ev:$i:$it:${event.str("minutes") ?: 0}" } ?: "ev:$i" }
            EventTypes.BRIEFING_READY -> event.str("date")?.let { "brief:$it" }
            EventTypes.FAMILY_NOTICE -> id?.let { "notice:$it" }
            else -> id?.let { "${event.type}:$it" }
        }
        return key?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 64 }
    }

    private fun shrink(data: JsonObject, cap: Int): JsonObject = JsonObject(
        data.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString && v.content.length > cap) JsonPrimitive(ellipsize(v.content, cap)) else v },
    )

    internal fun ellipsize(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max <= 0) return ""
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    private fun ServerEvent.str(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * APNs provider token (ES256 JWT with the .p8 key): `kid` = key id, `iss` = Team ID, `iat`.
 * Apple rejects tokens older than an hour and renewing more than once every 20 minutes, so it is reused for ~50 min.
 */
class ApnsJwt(
    private val keyId: String,
    private val teamId: String,
    private val key: ECPrivateKey,
    private val clock: () -> Instant = Instant::now,
) {
    private var cached: Pair<String, Instant>? = null

    @Synchronized
    fun token(): String {
        val now = clock()
        cached?.let { (t, at) -> if (Duration.between(at, now) < REFRESH) return t }
        return sign(now).also { cached = it to now }
    }

    /** After `ExpiredProviderToken`: discards the token if the 20-minute minimum has passed. Returns whether it discarded it. */
    @Synchronized
    fun invalidate(): Boolean {
        val at = cached?.second ?: return true
        if (Duration.between(at, clock()) < MIN_REFRESH) return false
        cached = null
        return true
    }

    private fun sign(now: Instant): String = JWT.create()
        .withKeyId(keyId)
        .withIssuer(teamId)
        .withIssuedAt(Date.from(now))
        .sign(Algorithm.ECDSA256(key))

    companion object {
        val REFRESH: Duration = Duration.ofMinutes(50)
        val MIN_REFRESH: Duration = Duration.ofMinutes(20)

        /** Reads Apple's `.p8` key (PKCS#8 in PEM). Accepts literal `\n` and base64 content without headers. */
        fun parsePrivateKey(pem: String): ECPrivateKey {
            val text = pem.replace("\\n", "\n")
            require(!text.contains("BEGIN EC PRIVATE KEY")) { "The key must be PKCS#8 (Apple's .p8 file, BEGIN PRIVATE KEY)" }
            val b64 = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
            val der = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrElse { throw IllegalArgumentException("The APNs key is not valid PEM/base64") }
            val key = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) }
                .getOrElse { throw IllegalArgumentException("The APNs key is not a valid PKCS#8 EC key") }
            return key as? ECPrivateKey ?: throw IllegalArgumentException("The APNs key is not an EC key")
        }
    }
}

/**
 * Apple native push (APNs) for the iPhone app: it arrives with the app closed. It is optional; without credentials
 * the tokens are stored anyway (they work as soon as it is configured) and the app uses its local alarms and the WebSocket.
 * HTTP/2 with the JDK client and token authentication (.p8), with no certificates or extra dependencies.
 */
class ApnsService(private val db: Db, private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(ApnsService::class.java)

    private val jwt: ApnsJwt? = loadJwt()

    /** APNs credentials configured and valid on the server. */
    val available: Boolean get() = jwt != null

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(jdkProxySelector())
            .build()
    }

    private fun loadJwt(): ApnsJwt? {
        if (!cfg.apnsConfigured) {
            val started = listOf(cfg.apnsKeyId, cfg.apnsTeamId, cfg.apnsPrivateKey, cfg.apnsPrivateKeyFile).any { !it.isNullOrBlank() }
            if (started) {
                log.warn("APNs disabled: APNS_KEY_ID, APNS_TEAM_ID, APNS_TOPIC or the key (APNS_PRIVATE_KEY / APNS_PRIVATE_KEY_FILE) is missing")
            }
            return null
        }
        return runCatching {
            val pem = cfg.apnsPrivateKey?.takeIf { it.isNotBlank() }
                ?: runCatching { File(cfg.apnsPrivateKeyFile!!).readText() }
                    .getOrElse { throw IllegalArgumentException("could not read APNS_PRIVATE_KEY_FILE (${cfg.apnsPrivateKeyFile})") }
            ApnsJwt(cfg.apnsKeyId!!, cfg.apnsTeamId!!, ApnsJwt.parsePrivateKey(pem)).also { it.token() }
        }.onSuccess { log.info("APNs active for {}", cfg.apnsTopic) }
            .onFailure { log.warn("APNs disabled: {}", it.message) }
            .getOrNull()
    }

    suspend fun register(userId: UUID, deviceId: UUID?, token: String, environment: String, kind: String = KIND_DEVICE, activityId: String? = null) {
        val t = normalizeToken(token) ?: throw InvalidInput(M.APNS_BAD_TOKEN)
        val env = normalizeEnvironment(environment) ?: throw InvalidInput(M.APNS_BAD_ENV)
        val k = kind.trim().lowercase().ifEmpty { KIND_DEVICE }
        requireInput(k in KINDS, M.APNS_BAD_KIND)
        val activity = activityId?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        db.tx {
            // One token per device: if the app received a new one (reinstall, restore), the old one is no longer useful
            if (deviceId != null) exec("delete from apns_tokens where device_id = ? and kind = ? and token <> ?", deviceId, k, t)
            // A token of another account is not reassigned: this way nobody can take someone else's push away or receive theirs
            val n = exec(
                """insert into apns_tokens(user_id, device_id, token, environment, kind, activity_id) values (?, ?, ?, ?, ?, ?)
                   on conflict (token) do update set device_id = excluded.device_id,
                   environment = excluded.environment, kind = excluded.kind, activity_id = excluded.activity_id, updated_at = now()
                   where apns_tokens.user_id = excluded.user_id""",
                userId, deviceId, t, env, k, activity,
            )
            requireInput(n > 0, M.APNS_TOKEN_TAKEN)
        }
    }

    suspend fun unregister(userId: UUID, deviceId: UUID?) = db.tx {
        if (deviceId != null) exec("delete from apns_tokens where user_id = ? and device_id = ?", userId, deviceId)
    }

    suspend fun registeredDevices(userId: UUID): Int =
        db.read { one("select count(*) as n from apns_tokens where user_id = ?", userId) { it.getInt("n") } } ?: 0

    /** Sends the event as an alert to the user's iPhones. Returns how many Apple accepted. */
    suspend fun send(userId: UUID, event: ServerEvent, title: String, body: String): Int {
        val signer = jwt ?: return 0
        val targets = db.read {
            query("select id, token, environment from apns_tokens where user_id = ? and kind = ?", userId, KIND_DEVICE) {
                Target(it.uuid("id"), it.str("token"), it.str("environment"))
            }
        }
        if (targets.isEmpty()) return 0
        val payload = ApnsPayload.build(event, title, body)
        val collapseId = ApnsPayload.collapseId(event)
        val expiration = Instant.now().plusSeconds(3600).epochSecond.toString()
        return coroutineScope {
            targets.map { t -> async { if (sendOne(signer, t, payload, collapseId, expiration)) 1 else 0 } }.awaitAll().sum()
        }
    }

    private suspend fun sendOne(signer: ApnsJwt, t: Target, payload: String, collapseId: String?, expiration: String): Boolean {
        val host = if (t.environment == ENV_SANDBOX) SANDBOX_HOST else PRODUCTION_HOST
        val request = HttpRequest.newBuilder(URI.create("$host/3/device/${t.token}"))
            .timeout(Duration.ofSeconds(15))
            .header("authorization", "bearer ${signer.token()}")
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header("apns-topic", cfg.apnsTopic!!)
            .header("apns-expiration", expiration)
            .apply { collapseId?.let { header("apns-collapse-id", it) } }
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = try {
            val r = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            // Apple answers short JSON; at most MAX_RESPONSE_BYTES are read and the rest is closed
            val body = withContext(Dispatchers.IO) { r.body().use { String(it.readNBytes(MAX_RESPONSE_BYTES), Charsets.UTF_8) } }
            r.statusCode() to body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("APNs ({}) did not respond: {}", t.environment, (e.message ?: e.javaClass.simpleName).replace(t.token, "…"))
            return false
        }
        val (status, responseBody) = response
        if (status == 200) return true
        val reason = runCatching { Json.parseToJsonElement(responseBody).jsonObject["reason"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        when {
            status == 410 || (status == 400 && reason in DEAD_TOKEN_REASONS) -> {
                // The token is no longer useful (app uninstalled, another environment or another app): the app will register again
                db.tx { exec("delete from apns_tokens where id = ?", t.id) }
                log.info("APNs ({}): token discarded ({})", t.environment, reason ?: status)
            }
            status == 403 && reason in PROVIDER_TOKEN_REASONS -> {
                val renewed = signer.invalidate()
                log.warn("APNs ({}) rejected the provider token: {}{}", t.environment, reason, if (renewed) "; it will be renewed" else "")
            }
            else -> log.warn("APNs ({}) failed: {} {}", t.environment, status, reason ?: "")
        }
        return false
    }

    private data class Target(val id: UUID, val token: String, val environment: String)

    companion object {
        const val ENV_SANDBOX = "sandbox"
        const val ENV_PRODUCTION = "production"
        const val KIND_DEVICE = "device"
        private val KINDS = setOf(KIND_DEVICE)
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val PRODUCTION_HOST = "https://api.push.apple.com"
        private const val SANDBOX_HOST = "https://api.sandbox.push.apple.com"
        private val DEAD_TOKEN_REASONS = setOf("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
        private val PROVIDER_TOKEN_REASONS = setOf("ExpiredProviderToken", "InvalidProviderToken")
        private val TOKEN_RE = Regex("^[0-9a-f]{64,200}$")

        /** Device token in hexadecimal (as the app builds it with `%02x`), without spaces or `<>`; null if not valid. */
        fun normalizeToken(token: String): String? =
            token.trim().removePrefix("<").removeSuffix(">").replace(" ", "").lowercase().takeIf { TOKEN_RE.matches(it) }

        /** "sandbox" | "production" ("development", the `aps-environment` value in Xcode, is the same as sandbox). */
        fun normalizeEnvironment(env: String): String? = when (env.trim().lowercase()) {
            "sandbox", "development" -> ENV_SANDBOX
            "production" -> ENV_PRODUCTION
            else -> null
        }
    }
}
