package app.kairo.routes

import app.kairo.agent.AgentEvent
import app.kairo.agent.GeoPoint
import app.kairo.domain.User
import app.kairo.util.ApiJson
import app.kairo.util.KairoJson
import app.kairo.util.parseInstant
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

@Serializable
data class ApiError(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

fun notFound(what: String = "Recurso"): Nothing = throw ApiException(HttpStatusCode.NotFound, "$what no encontrado")
fun badRequest(msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)

fun ApplicationCall.userId(): UUID = principal<JWTPrincipal>()?.subject?.let(UUID::fromString)
    ?: throw ApiException(HttpStatusCode.Unauthorized, "No autenticado")

fun ApplicationCall.deviceId(): UUID? = principal<JWTPrincipal>()?.getClaim("did", String::class)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

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

suspend fun ApplicationCall.receiveJsonObject(): JsonObject {
    val text = receiveText()
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { KairoJson.parseToJsonElement(text).jsonObject }.getOrElse { badRequest("JSON inválido") }
}

suspend inline fun <reified T> ApplicationCall.receiveBody(): T {
    val text = receiveText()
    return runCatching { KairoJson.decodeFromString<T>(text) }.getOrElse { badRequest("Cuerpo inválido: ${it.message?.take(120)}") }
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
