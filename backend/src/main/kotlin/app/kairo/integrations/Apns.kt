package app.kairo.integrations

import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.jdkProxySelector
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
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

/** Contenido de un push de APNs: el mismo `{type, data}` del WebSocket en `kairo`, más la alerta visible. */
object ApnsPayload {
    /** Límite de Apple para notificaciones normales. */
    const val MAX_BYTES = 4096
    private const val MAX_TITLE = 200
    /** Los campos cortos de `data` (ids, fechas) nunca se recortan. */
    private const val MIN_FIELD = 64

    fun build(event: ServerEvent, title: String, body: String, maxBytes: Int = MAX_BYTES): String {
        val t = ellipsize(title.trim(), MAX_TITLE)
        val b = body.trim()
        val longest = event.data.values.maxOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.length ?: 0 } ?: 0
        // Recorta el cuerpo y los textos largos de `data` al mismo largo máximo: el mayor que quepa (búsqueda binaria)
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
        // Último recurso (no pasa con los eventos actuales): sólo los campos cortos de `data`
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
            // Despierta la app en segundo plano para que sincronice (además de mostrar la alerta)
            put("content-available", 1)
        })
        put("kairo", buildJsonObject {
            put("type", event.type)
            put("data", data)
        })
        // Lo mismo que ponen las notificaciones locales de la app: para "Hecho", "Posponer" y abrir la sección
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

    /** Categorías que registra la app (botones "Hecho" / "En 10 min" y "Escuchar"). */
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
     * `apns-collapse-id`: la misma clave con la que la app deduplica (y con la que programa la alarma local),
     * así el aviso que llega por push reemplaza al local en vez de duplicarse. Máximo 64 bytes.
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
 * Token de proveedor de APNs (JWT ES256 con la clave .p8): `kid` = id de la clave, `iss` = Team ID, `iat`.
 * Apple rechaza tokens de más de una hora y renovarlo más de una vez cada 20 minutos, así que se reutiliza ~50 min.
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

    /** Tras `ExpiredProviderToken`: descarta el token si ya pasó el mínimo de 20 minutos. Devuelve si lo descartó. */
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

        /** Lee la clave `.p8` de Apple (PKCS#8 en PEM). Acepta `\n` literales y el contenido base64 sin cabeceras. */
        fun parsePrivateKey(pem: String): ECPrivateKey {
            val text = pem.replace("\\n", "\n")
            require(!text.contains("BEGIN EC PRIVATE KEY")) { "La clave debe ser PKCS#8 (el archivo .p8 de Apple, BEGIN PRIVATE KEY)" }
            val b64 = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
            val der = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrElse { throw IllegalArgumentException("La clave de APNs no es PEM/base64 válido") }
            val key = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) }
                .getOrElse { throw IllegalArgumentException("La clave de APNs no es una clave EC PKCS#8 válida") }
            return key as? ECPrivateKey ?: throw IllegalArgumentException("La clave de APNs no es una clave EC")
        }
    }
}

/**
 * Push nativo de Apple (APNs) para la app de iPhone: llega con la app cerrada. Es opcional; sin credenciales
 * los tokens se guardan igual (funcionan en cuanto se configure) y la app usa sus alarmas locales y el WebSocket.
 * HTTP/2 con el cliente del JDK y autenticación por token (.p8), sin certificados ni dependencias extra.
 */
class ApnsService(private val db: Db, private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(ApnsService::class.java)

    private val jwt: ApnsJwt? = loadJwt()

    /** Credenciales de APNs configuradas y válidas en el servidor. */
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
                log.warn("APNs desactivado: faltan APNS_KEY_ID, APNS_TEAM_ID, APNS_TOPIC o la clave (APNS_PRIVATE_KEY / APNS_PRIVATE_KEY_FILE)")
            }
            return null
        }
        return runCatching {
            val pem = cfg.apnsPrivateKey?.takeIf { it.isNotBlank() }
                ?: runCatching { File(cfg.apnsPrivateKeyFile!!).readText() }
                    .getOrElse { throw IllegalArgumentException("no se pudo leer APNS_PRIVATE_KEY_FILE (${cfg.apnsPrivateKeyFile})") }
            ApnsJwt(cfg.apnsKeyId!!, cfg.apnsTeamId!!, ApnsJwt.parsePrivateKey(pem)).also { it.token() }
        }.onSuccess { log.info("APNs activo para {}", cfg.apnsTopic) }
            .onFailure { log.warn("APNs desactivado: {}", it.message) }
            .getOrNull()
    }

    suspend fun register(userId: UUID, deviceId: UUID?, token: String, environment: String, kind: String = KIND_DEVICE, activityId: String? = null) {
        val t = normalizeToken(token) ?: throw IllegalArgumentException("Token de APNs inválido (hexadecimal, 64 a 200 caracteres)")
        val env = normalizeEnvironment(environment) ?: throw IllegalArgumentException("environment debe ser sandbox o production")
        val k = kind.trim().lowercase().ifEmpty { KIND_DEVICE }
        require(k in KINDS) { "kind no soportado: usa device" }
        val activity = activityId?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        db.tx {
            // Un token por dispositivo: si la app recibió uno nuevo (reinstalación, restauración), el viejo ya no sirve
            if (deviceId != null) exec("delete from apns_tokens where device_id = ? and kind = ? and token <> ?", deviceId, k, t)
            exec(
                """insert into apns_tokens(user_id, device_id, token, environment, kind, activity_id) values (?, ?, ?, ?, ?, ?)
                   on conflict (token) do update set user_id = excluded.user_id, device_id = excluded.device_id,
                   environment = excluded.environment, kind = excluded.kind, activity_id = excluded.activity_id, updated_at = now()""",
                userId, deviceId, t, env, k, activity,
            )
        }
    }

    suspend fun unregister(userId: UUID, deviceId: UUID?) = db.tx {
        if (deviceId != null) exec("delete from apns_tokens where user_id = ? and device_id = ?", userId, deviceId)
    }

    suspend fun registeredDevices(userId: UUID): Int =
        db.read { one("select count(*) as n from apns_tokens where user_id = ?", userId) { it.getInt("n") } } ?: 0

    /** Envía el evento como alerta a los iPhone del usuario. Devuelve cuántos lo aceptó Apple. */
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
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("APNs ({}) no respondió: {}", t.environment, (e.message ?: e.javaClass.simpleName).replace(t.token, "…"))
            return false
        }
        val status = response.statusCode()
        if (status == 200) return true
        val reason = runCatching { Json.parseToJsonElement(response.body()).jsonObject["reason"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        when {
            status == 410 || (status == 400 && reason in DEAD_TOKEN_REASONS) -> {
                // El token ya no sirve (app desinstalada, otro entorno u otra app): la app se volverá a registrar
                db.tx { exec("delete from apns_tokens where id = ?", t.id) }
                log.info("APNs ({}): token descartado ({})", t.environment, reason ?: status)
            }
            status == 403 && reason in PROVIDER_TOKEN_REASONS -> {
                val renewed = signer.invalidate()
                log.warn("APNs ({}) rechazó el token de proveedor: {}{}", t.environment, reason, if (renewed) "; se renovará" else "")
            }
            else -> log.warn("APNs ({}) falló: {} {}", t.environment, status, reason ?: "")
        }
        return false
    }

    private data class Target(val id: UUID, val token: String, val environment: String)

    companion object {
        const val ENV_SANDBOX = "sandbox"
        const val ENV_PRODUCTION = "production"
        const val KIND_DEVICE = "device"
        private val KINDS = setOf(KIND_DEVICE)
        private const val PRODUCTION_HOST = "https://api.push.apple.com"
        private const val SANDBOX_HOST = "https://api.sandbox.push.apple.com"
        private val DEAD_TOKEN_REASONS = setOf("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
        private val PROVIDER_TOKEN_REASONS = setOf("ExpiredProviderToken", "InvalidProviderToken")
        private val TOKEN_RE = Regex("^[0-9a-f]{64,200}$")

        /** Token del dispositivo en hexadecimal (como lo arma la app con `%02x`), sin espacios ni `<>`; null si no es válido. */
        fun normalizeToken(token: String): String? =
            token.trim().removePrefix("<").removeSuffix(">").replace(" ", "").lowercase().takeIf { TOKEN_RE.matches(it) }

        /** "sandbox" | "production" ("development", el valor de `aps-environment` en Xcode, equivale a sandbox). */
        fun normalizeEnvironment(env: String): String? = when (env.trim().lowercase()) {
            "sandbox", "development" -> ENV_SANDBOX
            "production" -> ENV_PRODUCTION
            else -> null
        }
    }
}
