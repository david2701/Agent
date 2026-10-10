package app.kairo.routes

import app.kairo.agent.AgentEvent
import app.kairo.agent.GeoPoint
import app.kairo.domain.User
import app.kairo.integrations.GoogleCalendarService
import app.kairo.util.ApiJson
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

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

@Serializable
data class ApiError(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

fun notFound(what: String = "Recurso"): Nothing = throw ApiException(HttpStatusCode.NotFound, "$what no encontrado")
fun badRequest(msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)
fun payloadTooLarge(msg: String = "El cuerpo de la petición es demasiado grande"): Nothing = throw ApiException(HttpStatusCode.PayloadTooLarge, msg)
fun unsupportedMediaType(msg: String): Nothing = throw ApiException(HttpStatusCode.UnsupportedMediaType, msg)

/** Lee como mucho [max] bytes del canal (para detectar cuerpos más grandes que el tope, pide uno más). */
suspend fun ByteReadChannel.readAtMost(max: Long): ByteArray = readBuffer(max).readByteArray()

/** Content-Length que declara el cliente (null con transferencia por bloques). */
fun ApplicationCall.declaredContentLength(): Long? = request.contentLength()

fun ApplicationCall.userId(): UUID = principal<JWTPrincipal>()?.subject?.let(UUID::fromString)
    ?: throw ApiException(HttpStatusCode.Unauthorized, "No autenticado")

fun ApplicationCall.deviceId(): UUID? = principal<JWTPrincipal>()?.getClaim("did", String::class)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

val IA_LIMIT = RateLimitName("ia")
val IA_HOURLY_LIMIT = RateLimitName("ia-hora")
val RECORDING_LIMIT = RateLimitName("grabacion")

/** Clave de los límites por persona: el usuario del token, o la IP si la ruta no tiene sesión. */
fun userLimitKey(call: ApplicationCall): String =
    call.principal<JWTPrincipal>()?.subject?.let { "u:$it" } ?: ("ip:" + rateLimitKey(call.request.origin.remoteAddress))

/** Rutas que llaman a Nan: tope por minuto y por hora para cada persona. */
fun Route.iaLimited(build: Route.() -> Unit) {
    rateLimit(IA_LIMIT) { rateLimit(IA_HOURLY_LIMIT) { build() } }
}

fun ApplicationCall.uuidParam(name: String = "id"): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: badRequest("Id inválido")

fun ApplicationCall.instantQuery(name: String): Instant? = request.queryParameters[name]?.let {
    runCatching { parseInstant(it) }.getOrElse { badRequest("Fecha inválida en '$name'") }
}

/** Ubicación opcional enviada por la app: cabecera `X-Kairo-Location: lat,lon`. */
fun ApplicationCall.clientLocation(): GeoPoint? = request.headers["X-Kairo-Location"]?.split(',')?.let { p ->
    val lat = p.getOrNull(0)?.trim()?.toDoubleOrNull()
    val lon = p.getOrNull(1)?.trim()?.toDoubleOrNull()
    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
}

/** Capacidades del cliente: cabecera `X-Kairo-Capabilities: timer,alarm,location`. */
fun ApplicationCall.clientCapabilities(): Set<String> =
    request.headers["X-Kairo-Capabilities"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

/** Tope de un cuerpo JSON normal (notas largas incluidas). */
const val MAX_JSON_BODY = 1L * 1024 * 1024

/** Tope de los cuerpos de las rutas de /api/auth: son públicas y caben de sobra en 16 KB. */
const val MAX_AUTH_BODY = 16L * 1024

fun ApplicationCall.isJsonRequest(): Boolean = request.contentType().match(ContentType.Application.Json)

/**
 * Lee el cuerpo como texto UTF-8 sin pasar de [maxBytes] (413 si es mayor), mirando antes Content-Length.
 * Si trae contenido, tiene que ser `Content-Type: application/json` (415): un formulario o un `text/plain`
 * de otra página no puede hacerse pasar por una petición de la app (CSRF), porque el navegador exige
 * permiso CORS para mandar JSON a otro origen.
 */
suspend fun ApplicationCall.receiveJsonText(maxBytes: Long = MAX_JSON_BODY): String {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) payloadTooLarge()
    if (declared == 0L) return ""
    val bytes = receiveChannel().readAtMost(maxBytes + 1)
    if (bytes.size > maxBytes) payloadTooLarge()
    val text = bytes.toString(Charsets.UTF_8)
    if (text.isNotBlank() && !isJsonRequest()) {
        unsupportedMediaType("El cuerpo debe ser JSON (Content-Type: application/json)")
    }
    return text
}

suspend fun ApplicationCall.receiveJsonObject(maxBytes: Long = MAX_JSON_BODY): JsonObject {
    val text = receiveJsonText(maxBytes)
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { KairoJson.parseToJsonElement(text).jsonObject }.getOrElse { badRequest("JSON inválido") }
}

suspend inline fun <reified T> ApplicationCall.receiveBody(maxBytes: Long = MAX_JSON_BODY): T {
    val text = receiveJsonText(maxBytes)
    // Sin detalles del parser: no se devuelven nombres internos de clases ni fragmentos del cuerpo
    return runCatching { KairoJson.decodeFromString<T>(text) }.getOrElse { badRequest("Cuerpo inválido: revisa que el JSON tenga los campos esperados") }
}

/**
 * Mensaje que se puede mostrar al usuario para un IllegalArgumentException, o null para usar uno genérico.
 * Los `require(...)` de Kairo traen mensajes pensados para la persona; los que vienen de librerías
 * (el parser JSON, UUID.fromString, números mal formados) traen detalles internos que no deben salir.
 */
fun publicMessage(e: IllegalArgumentException): String? {
    if (e is SerializationException || e is NumberFormatException) return null
    val msg = e.message?.takeIf { it.isNotBlank() } ?: return null
    val origin = e.stackTrace.firstOrNull()?.className ?: return null
    return if (origin.startsWith("app.kairo.")) msg.take(300) else null
}

/**
 * Mensaje de un error cualquiera para el cliente: el de un `require`/`error` de Kairo si lo es, si no [fallback].
 * Los fallos de red, base de datos o librerías nunca salen con su texto (se quedan en el log).
 */
fun publicMessage(e: Throwable, fallback: String): String {
    val own = when (e) {
        is IllegalArgumentException -> publicMessage(e)
        is IllegalStateException -> e.message?.takeIf {
            it.isNotBlank() && e.stackTrace.firstOrNull()?.className?.startsWith("app.kairo.") == true
        }?.take(300)
        else -> null
    }
    return own ?: fallback
}

/** Imágenes que se aceptan al subir y se muestran en línea. */
val RASTER_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")

/**
 * Tipo real de una imagen según sus primeros bytes (JPEG, PNG, GIF, WebP, HEIC/HEIF), o null si no es
 * ninguna de ellas. No se confía en el Content-Type que manda el cliente: un SVG o un HTML con
 * `image/png` se rechaza.
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
 * ¿Se puede servir en línea un archivo con este tipo? Sólo imágenes rasterizadas y audio: ninguno ejecuta
 * código en el navegador. Lo demás (SVG, HTML, PDF...) se descarga como adjunto.
 */
fun isInlineSafe(type: ContentType): Boolean {
    val t = type.withoutParameters()
    return t.contentType == "audio" || "${t.contentType}/${t.contentSubtype}".lowercase() in RASTER_IMAGE_TYPES
}

/** Transmite los eventos del agente como Server-Sent Events (`data: {json}`). */
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

// ---------------------------------------------------------------- Archivos servidos con URL firmada

/** Un archivo subido nunca puede ejecutar nada en el origen de Kairo, aunque se abra directamente. */
const val FILE_CSP = "sandbox; default-src 'none'; img-src 'self'"

/**
 * Responde un archivo guardado. Sólo las imágenes rasterizadas y el audio se sirven en línea; lo demás
 * (SVG, HTML...) va como adjunto `application/octet-stream`. `X-Content-Type-Options: nosniff` ya lo
 * añade DefaultHeaders a todas las respuestas.
 */
suspend fun ApplicationCall.respondStoredFile(bytes: ByteArray, storedType: String) {
    val type = runCatching { ContentType.parse(storedType) }.getOrNull()?.withoutParameters()
    val inline = type != null && isInlineSafe(type)
    response.header(HttpHeaders.CacheControl, "private, max-age=86400")
    response.header("Content-Security-Policy", FILE_CSP)
    if (!inline) response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.toString())
    respondBytes(bytes, if (inline) type else ContentType.Application.OctetStream)
}

// ---------------------------------------------------------------- OAuth de Google atado al navegador

/** Crea el nonce del flujo OAuth y lo deja en una cookie HttpOnly del navegador que lo inicia. */
fun ApplicationCall.issueGoogleOAuthNonce(secure: Boolean): String {
    val nonce = Crypto.randomToken(24)
    response.cookies.append(oauthCookie(nonce, GoogleCalendarService.OAUTH_STATE_TTL.toInt(), secure))
    return nonce
}

/** Lee el nonce del callback y borra la cookie (un solo uso). */
fun ApplicationCall.takeGoogleOAuthNonce(secure: Boolean): String? {
    val nonce = request.cookies[GoogleCalendarService.OAUTH_COOKIE, CookieEncoding.RAW]
    response.cookies.append(oauthCookie("", 0, secure))
    return nonce?.takeIf { it.isNotBlank() }
}

// SameSite=Lax (no Strict): el regreso desde accounts.google.com es una navegación desde otro sitio
private fun oauthCookie(value: String, maxAge: Int, secure: Boolean) = Cookie(
    name = GoogleCalendarService.OAUTH_COOKIE, value = value, encoding = CookieEncoding.RAW, maxAge = maxAge,
    path = GoogleCalendarService.OAUTH_COOKIE_PATH, secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
)

// ---------------------------------------------------------------- WebSocket

const val WS_PING_MS = 25_000L

/** Por qué termina la sesión de un WebSocket, o null si sigue valiendo. */
fun wsSessionEnd(nowMs: Long, expiresAtMs: Long, deviceActive: Boolean): String? = when {
    nowMs >= expiresAtMs -> "Sesión expirada"
    !deviceActive -> "Sesión cerrada"
    else -> null
}

/**
 * Mantiene vivo el WebSocket con un ping cada 25 s mientras la sesión valga: en cada ping comprueba que el
 * dispositivo siga vigente, y al vencer el access token cierra la conexión (el cliente se reconecta con uno
 * nuevo). Sin esto, un WebSocket abierto seguiría recibiendo eventos después de cerrar la sesión.
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
