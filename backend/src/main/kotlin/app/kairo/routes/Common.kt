package app.kairo.routes

import app.kairo.agent.AgentEvent
import app.kairo.agent.GeoPoint
import app.kairo.domain.User
import app.kairo.integrations.GoogleCalendarService
import app.kairo.Services
import app.kairo.util.ApiJson
import app.kairo.util.InvalidInput
import app.kairo.util.Lang
import app.kairo.util.Localizable
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.Say
import app.kairo.util.Crypto
import app.kairo.util.KairoJson
import app.kairo.util.parseInstant
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import app.kairo.auth.rateLimitKey

/**
 * An API error. The text is kept as a [Localizable] and rendered in the reader's language by the
 * StatusPages handler; [message] is the English text (logs, tests).
 */
class ApiException(val status: HttpStatusCode, private val localized: Localizable) :
    RuntimeException(localized.text(Lang.EN)), Localizable {
    constructor(status: HttpStatusCode, message: String) : this(status, Plain(message))
    constructor(status: HttpStatusCode, msg: M, vararg args: Any?) : this(status, Say(msg, args.toList()))

    override val message: String get() = localized.text(Lang.EN)

    override fun text(lang: Lang): String = localized.text(lang)
}

@Serializable
data class ApiError(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** 404 with the "<thing> not found" message from the catalog ([M.NF_NOTE], [M.NF_LIST]...). */
fun notFound(what: M = M.NF_RESOURCE): Nothing = throw ApiException(HttpStatusCode.NotFound, what)
fun badRequest(msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)
fun badRequest(msg: M, vararg args: Any?): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg, *args)
fun badRequest(msg: Localizable): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)
fun payloadTooLarge(msg: M = M.PAYLOAD_TOO_LARGE): Nothing = throw ApiException(HttpStatusCode.PayloadTooLarge, msg)
fun unsupportedMediaType(msg: M): Nothing = throw ApiException(HttpStatusCode.UnsupportedMediaType, msg)

/**
 * Language for text written in reply to this request: the signed-in user's `language` setting, or, when that is
 * `auto` or there is no session (errors before authentication), the `Accept-Language` header, or the server default.
 */
suspend fun ApplicationCall.lang(s: Services): Lang {
    val userId = principal<JWTPrincipal>()?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val setting = userId?.let { runCatching { s.settings.get(it).language }.getOrNull() }
    return Lang.resolve(setting, request.headers[HttpHeaders.AcceptLanguage], s.config.defaultLanguage)
}

/** Language of the request taken only from `Accept-Language` (public routes, where nobody is signed in yet). */
fun ApplicationCall.acceptedLang(default: Lang = Lang.DEFAULT): Lang =
    Lang.resolve(null, request.headers[HttpHeaders.AcceptLanguage], default)

/** Reads at most [max] bytes from the channel (asks for one more to detect bodies bigger than the cap). */
suspend fun ByteReadChannel.readAtMost(max: Long): ByteArray = readBuffer(max).readByteArray()

/** Content-Length the client declares (null with chunked transfer). */
fun ApplicationCall.declaredContentLength(): Long? = request.contentLength()

fun ApplicationCall.userId(): UUID = principal<JWTPrincipal>()?.subject?.let(UUID::fromString)
    ?: throw ApiException(HttpStatusCode.Unauthorized, M.NOT_AUTHENTICATED)

fun ApplicationCall.deviceId(): UUID? = principal<JWTPrincipal>()?.getClaim("did", String::class)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

val IA_LIMIT = RateLimitName("ai")
val IA_HOURLY_LIMIT = RateLimitName("ai-hourly")
val RECORDING_LIMIT = RateLimitName("recording")

/** Key of the per-person limits: the token's user, or the IP when the route has no session. */
fun userLimitKey(call: ApplicationCall): String =
    call.principal<JWTPrincipal>()?.subject?.let { "u:$it" } ?: ("ip:" + rateLimitKey(call.request.origin.remoteAddress))

/** Routes that call Nan: a per-minute and a per-hour cap for each person. */
fun Route.iaLimited(build: Route.() -> Unit) {
    rateLimit(IA_LIMIT) { rateLimit(IA_HOURLY_LIMIT) { build() } }
}

fun ApplicationCall.uuidParam(name: String = "id"): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: badRequest(M.INVALID_ID)

fun ApplicationCall.instantQuery(name: String): Instant? = request.queryParameters[name]?.let {
    runCatching { parseInstant(it) }.getOrElse { badRequest(M.INVALID_DATE_IN, name) }
}

/** Optional location sent by the app: header `X-Kairo-Location: lat,lon`. */
fun ApplicationCall.clientLocation(): GeoPoint? = request.headers["X-Kairo-Location"]?.split(',')?.let { p ->
    val lat = p.getOrNull(0)?.trim()?.toDoubleOrNull()
    val lon = p.getOrNull(1)?.trim()?.toDoubleOrNull()
    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
}

/** Client capabilities: header `X-Kairo-Capabilities: timer,alarm,location`. */
fun ApplicationCall.clientCapabilities(): Set<String> =
    request.headers["X-Kairo-Capabilities"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

/** Cap for a normal JSON body (long notes included). */
const val MAX_JSON_BODY = 1L * 1024 * 1024

/** Cap for the bodies of the /api/auth routes: they are public and fit comfortably in 16 KB. */
const val MAX_AUTH_BODY = 16L * 1024

fun ApplicationCall.isJsonRequest(): Boolean = request.contentType().match(ContentType.Application.Json)

/**
 * Reads the body as UTF-8 text without going over [maxBytes] (413 if larger), checking Content-Length first.
 * If it has content, it must be `Content-Type: application/json` (415): a form or a `text/plain` from
 * another page cannot pass as a request from the app (CSRF), because the browser requires CORS
 * permission to send JSON to another origin.
 */
suspend fun ApplicationCall.receiveJsonText(maxBytes: Long = MAX_JSON_BODY): String {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) payloadTooLarge()
    if (declared == 0L) return ""
    val bytes = receiveChannel().readAtMost(maxBytes + 1)
    if (bytes.size > maxBytes) payloadTooLarge()
    val text = bytes.toString(Charsets.UTF_8)
    if (text.isNotBlank() && !isJsonRequest()) {
        unsupportedMediaType(M.BODY_NOT_JSON)
    }
    return text
}

suspend fun ApplicationCall.receiveJsonObject(maxBytes: Long = MAX_JSON_BODY): JsonObject {
    val text = receiveJsonText(maxBytes)
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { KairoJson.parseToJsonElement(text).jsonObject }.getOrElse { badRequest(M.INVALID_JSON) }
}

suspend inline fun <reified T> ApplicationCall.receiveBody(maxBytes: Long = MAX_JSON_BODY): T {
    val text = receiveJsonText(maxBytes)
    // No parser details: internal class names and body fragments are never returned
    return runCatching { KairoJson.decodeFromString<T>(text) }.getOrElse { badRequest(M.INVALID_BODY) }
}

/**
 * The text that can be shown to the user for an IllegalArgumentException, or null to use a generic one.
 * Kairo's own validation errors ([InvalidInput], or a `require(...)` raised inside `app.kairo`) carry messages
 * meant for the person; the ones that come from libraries (the JSON parser, UUID.fromString, malformed
 * numbers) carry internal details that must not leak.
 */
fun publicError(e: IllegalArgumentException): Localizable? {
    if (e is InvalidInput) return e
    if (e is SerializationException || e is NumberFormatException) return null
    val msg = e.message?.takeIf { it.isNotBlank() } ?: return null
    val origin = e.stackTrace.firstOrNull()?.className ?: return null
    return if (origin.startsWith("app.kairo.")) Plain(msg.take(300)) else null
}

/** [publicError] rendered in [lang] (English by default), for tests and logs. */
fun publicMessage(e: IllegalArgumentException, lang: Lang = Lang.EN): String? = publicError(e)?.text(lang)

/**
 * The text of any error for the client: Kairo's own `require`/`error`/[InvalidInput] message if it is one,
 * otherwise [fallback]. Network, database or library failures never go out with their text (it stays in the log).
 */
fun publicError(e: Throwable, fallback: M): Localizable {
    val own: Localizable? = when (e) {
        is IllegalArgumentException -> publicError(e)
        is IllegalStateException -> e.message?.takeIf {
            it.isNotBlank() && e.stackTrace.firstOrNull()?.className?.startsWith("app.kairo.") == true
        }?.let { Plain(it.take(300)) }
        else -> null
    }
    return own ?: Say(fallback)
}

/** Images accepted on upload and shown inline. */
val RASTER_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")

/**
 * Real type of an image from its first bytes (JPEG, PNG, GIF, WebP, HEIC/HEIF), or null if it is none
 * of them. The Content-Type the client sends is not trusted: an SVG or an HTML sent as `image/png`
 * is rejected.
 */
fun sniffImageType(b: ByteArray): String? {
    fun at(offset: Int, vararg bytes: Int) = b.size >= offset + bytes.size && bytes.indices.all { b[offset + it] == bytes[it].toByte() }
    fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
    return when {
        at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
        at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        ascii(4, "ftyp") && b.size >= 12 -> when (String(b, 8, 4, Charsets.ISO_8859_1)) {
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs" -> "image/heic"
            "mif1", "msf1" -> "image/heif"
            else -> null
        }
        else -> null
    }
}

/**
 * Can a file of this type be served inline? Only raster images and audio: none of them runs code in the
 * browser. Everything else (SVG, HTML, PDF...) is downloaded as an attachment.
 */
fun isInlineSafe(type: ContentType): Boolean {
    val t = type.withoutParameters()
    return t.contentType == "audio" || "${t.contentType}/${t.contentSubtype}".lowercase() in RASTER_IMAGE_TYPES
}

/** Streams the agent events as Server-Sent Events (`data: {json}`). */
suspend fun ApplicationCall.streamAgent(flow: Flow<AgentEvent>) {
    response.header("Cache-Control", "no-cache")
    response.header("X-Accel-Buffering", "no")
    respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
        writeStringUtf8(": kairo\n\n")
        flush()
        flow.collect { ev ->
            val json = ApiJson.encodeToString(AgentEvent.serializer(), ev)
            writeStringUtf8("data: $json\n\n")
            flush()
        }
    }
}

@Serializable
data class UserResponse(val user: User)

// ---------------------------------------------------------------- Files served with a signed URL

/** An uploaded file can never run anything on the Kairo origin, even when opened directly. */
const val FILE_CSP = "sandbox; default-src 'none'; img-src 'self'"

/**
 * Responds with a stored file. Only raster images and audio are served inline; everything else
 * (SVG, HTML...) goes as an `application/octet-stream` attachment. `X-Content-Type-Options: nosniff` is
 * already added by DefaultHeaders to every response.
 */
suspend fun ApplicationCall.respondStoredFile(bytes: ByteArray, storedType: String) {
    val type = runCatching { ContentType.parse(storedType) }.getOrNull()?.withoutParameters()
    val inline = type != null && isInlineSafe(type)
    response.header(HttpHeaders.CacheControl, "private, max-age=86400")
    response.header("Content-Security-Policy", FILE_CSP)
    if (!inline) response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.toString())
    respondBytes(bytes, if (inline) type else ContentType.Application.OctetStream)
}

// ---------------------------------------------------------------- Google OAuth bound to the browser

/** Creates the OAuth flow nonce and leaves it in an HttpOnly cookie of the browser that starts it. */
fun ApplicationCall.issueGoogleOAuthNonce(secure: Boolean): String {
    val nonce = Crypto.randomToken(24)
    response.cookies.append(oauthCookie(nonce, GoogleCalendarService.OAUTH_STATE_TTL.toInt(), secure))
    return nonce
}

/** Reads the nonce at the callback and deletes the cookie (single use). */
fun ApplicationCall.takeGoogleOAuthNonce(secure: Boolean): String? {
    val nonce = request.cookies[GoogleCalendarService.OAUTH_COOKIE, CookieEncoding.RAW]
    response.cookies.append(oauthCookie("", 0, secure))
    return nonce?.takeIf { it.isNotBlank() }
}

// SameSite=Lax (not Strict): the return from accounts.google.com is a navigation from another site
private fun oauthCookie(value: String, maxAge: Int, secure: Boolean) = Cookie(
    name = GoogleCalendarService.OAUTH_COOKIE, value = value, encoding = CookieEncoding.RAW, maxAge = maxAge,
    path = GoogleCalendarService.OAUTH_COOKIE_PATH, secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
)

// ---------------------------------------------------------------- WebSocket

const val WS_PING_MS = 25_000L

/** Why a WebSocket session ends, or null if it is still valid (the reason is the close frame text, always English). */
fun wsSessionEnd(nowMs: Long, expiresAtMs: Long, deviceActive: Boolean): String? = when {
    nowMs >= expiresAtMs -> M.SESSION_EXPIRED.en
    !deviceActive -> M.SESSION_CLOSED.en
    else -> null
}

/**
 * Keeps the WebSocket alive with a ping every 25 s while the session is valid: on every ping it checks that
 * the device is still active, and when the access token expires it closes the connection (the client reconnects
 * with a new one). Without this, an open WebSocket would keep receiving events after the session was closed.
 */
suspend fun WebSocketSession.keepAliveWhileValid(expiresAtMs: Long, deviceActive: suspend () -> Boolean, onEnd: () -> Unit) {
    while (true) {
        val left = expiresAtMs - System.currentTimeMillis()
        if (left > 0) delay(minOf(WS_PING_MS, left))
        val now = System.currentTimeMillis()
        val reason = wsSessionEnd(now, expiresAtMs, now >= expiresAtMs || deviceActive())
        if (reason != null) {
            onEnd()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
            return
        }
        send(Frame.Text("""{"type":"ping","data":{}}"""))
    }
}
