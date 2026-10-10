@file:UseSerializers(UUIDSerializer::class)

package app.kairo.routes

import app.kairo.Services
import app.kairo.agent.AgentEvent
import app.kairo.agent.AgentRequest
import app.kairo.domain.SettingsService
import app.kairo.domain.VoiceOption
import app.kairo.util.UUIDSerializer
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.flow
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Serializable data class ChatRequest(val message: String, val conversationId: UUID? = null, val channel: String = "chat")
@Serializable data class TranscriptionResponse(val text: String, val language: String?, val duration: Double?)
@Serializable data class SpeakRequest(val text: String, val voice: String? = null, val speed: Double? = null)
@Serializable data class BriefingResponse(val text: String)
@Serializable data class CaptureJson(val text: String? = null, val url: String? = null, val conversationId: UUID? = null)
@Serializable data class VoicesResponse(val voices: List<VoiceOption>)

class Upload(val fields: Map<String, String>, val file: ByteArray?, val fileName: String?, val contentType: String?)

/**
 * Lee un formulario multipart con un archivo. Topes: [maxBytes] en total (archivo y campos), 16 partes,
 * y el archivo se lee sin pasar del tope. Si el archivo es una imagen, su tipo sale de los bytes (no del
 * cliente) y sólo se aceptan JPEG, PNG, WebP, GIF y HEIC/HEIF: un SVG o un HTML disfrazado se rechaza.
 */
suspend fun ApplicationCall.receiveUpload(maxBytes: Long = 30L * 1024 * 1024): Upload {
    val tooLarge = "El archivo es demasiado grande"
    // Margen para las cabeceras de cada parte y los campos de texto
    declaredContentLength()?.let { if (it > maxBytes + 64 * 1024) payloadTooLarge(tooLarge) }
    val fields = HashMap<String, String>()
    var bytes: ByteArray? = null
    var name: String? = null
    var type: String? = null
    var total = 0L
    var parts = 0
    try {
        receiveMultipart(formFieldLimit = maxBytes).forEachPart { part ->
            try {
                if (++parts > 16) payloadTooLarge("Demasiadas partes en el formulario")
                when (part) {
                    is PartData.FormItem -> {
                        total += part.value.length
                        if (total > maxBytes) payloadTooLarge(tooLarge)
                        part.name?.let { fields[it] = part.value }
                    }
                    is PartData.FileItem -> if (bytes == null) {
                        val data = part.provider().readAtMost(maxBytes + 1)
                        total += data.size
                        if (total > maxBytes) payloadTooLarge(tooLarge)
                        val declared = part.contentType?.toString()
                        val sniffed = sniffImageType(data)
                        if (sniffed == null && declared?.lowercase()?.startsWith("image/") == true) {
                            unsupportedMediaType("Solo se aceptan imágenes JPEG, PNG, WebP, GIF o HEIC")
                        }
                        bytes = data
                        name = part.originalFileName
                        type = sniffed ?: declared
                    }
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }
    } catch (e: java.io.IOException) {
        // El parser de Ktor corta las partes que pasan de formFieldLimit
        if (e.message?.startsWith("Multipart content length exceeds limit") == true) payloadTooLarge(tooLarge)
        throw e
    }
    return Upload(fields, bytes, name, type)
}

private val CHANNELS = setOf("chat", "voice", "wear", "dashboard", "share")

fun Route.agentRoutes(s: Services) {

    post("/chat") {
        val b = call.receiveBody<ChatRequest>()
        if (b.message.isBlank()) badRequest("Mensaje vacío")
        val user = s.users.byId(call.userId())!!
        call.streamAgent(
            s.agent.run(
                AgentRequest(
                    user = user, text = b.message.take(8000), conversationId = b.conversationId,
                    channel = b.channel.takeIf { it in CHANNELS } ?: "chat",
                    location = call.clientLocation(), capabilities = call.clientCapabilities(),
                ),
            ),
        )
    }

    // ------------------------------------------------------------ Voz
    post("/voice/transcribe") {
        val up = call.receiveUpload()
        val audio = up.file ?: badRequest("Falta el archivo de audio")
        val t = s.voice.transcribe(call.userId(), audio, up.fileName ?: "audio.m4a", up.contentType ?: "audio/mp4")
        call.respond(TranscriptionResponse(if (s.voice.isNoise(t.text)) "" else t.text, t.language, t.durationSeconds))
    }

    /** Turno de voz completo: audio -> Whisper -> agente (SSE). El cliente pide luego /voice/speak con el texto. */
    post("/voice/turn") {
        val up = call.receiveUpload()
        val audio = up.file ?: badRequest("Falta el archivo de audio")
        val userId = call.userId()
        val user = s.users.byId(userId)!!
        val conversationId = up.fields["conversationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val channel = up.fields["channel"]?.takeIf { it in CHANNELS } ?: "voice"
        val location = call.clientLocation()
        val caps = call.clientCapabilities()
        call.streamAgent(flow {
            val t = runCatching { s.voice.transcribe(userId, audio, up.fileName ?: "audio.m4a", up.contentType ?: "audio/mp4") }.getOrElse {
                emit(AgentEvent.Error("No pude transcribir el audio: ${it.message?.take(100)}"))
                return@flow
            }
            if (s.voice.isNoise(t.text)) {
                emit(AgentEvent.Error("No te escuché bien. ¿Puedes repetirlo?"))
                return@flow
            }
            emit(AgentEvent.Transcript(t.text))
            s.agent.run(AgentRequest(user, t.text, conversationId, channel, location, caps)).collect { emit(it) }
        })
    }

    post("/voice/speak") {
        val b = call.receiveBody<SpeakRequest>()
        val audio = s.voice.speak(call.userId(), b.text, b.voice, b.speed)
        call.respondBytes(audio, ContentType("audio", "mpeg"))
    }

    get("/voice/voices") { call.respond(VoicesResponse(SettingsService.VOICES)) }

    // ------------------------------------------------------------ Compartir con Kairo
    post("/capture") {
        val user = s.users.byId(call.userId())!!
        val isMultipart = call.request.contentType().match(ContentType.MultiPart.FormData)
        val (text, url, image, type, conv) = if (isMultipart) {
            val up = call.receiveUpload(20L * 1024 * 1024)
            if (up.file != null && up.contentType?.startsWith("image/") != true) badRequest("Solo se aceptan imágenes")
            CaptureInput(up.fields["text"], up.fields["url"], up.file, up.contentType, up.fields["conversationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() })
        } else {
            val b = call.receiveBody<CaptureJson>()
            CaptureInput(b.text, b.url, null, null, b.conversationId)
        }
        if (text.isNullOrBlank() && url.isNullOrBlank() && image == null) badRequest("No hay nada que guardar")
        // Si el "texto" es solo un enlace, trátalo como enlace
        val linkInText = text?.trim()?.takeIf { url == null && Regex("^https?://\\S+$").matches(it) }
        call.streamAgent(
            s.capture.capture(
                user, if (linkInText != null) null else text, linkInText ?: url, image, type, conv,
                call.clientLocation(), call.clientCapabilities(),
            ),
        )
    }

    // ------------------------------------------------------------ Conversaciones
    get("/conversations") { call.respond(s.conversations.list(call.userId())) }
    get("/conversations/{id}/messages") {
        val id = call.uuidParam()
        s.conversations.get(call.userId(), id) ?: notFound("Conversación")
        call.respond(s.conversations.messages(call.userId(), id))
    }
    delete("/conversations/{id}") {
        if (!s.conversations.delete(call.userId(), call.uuidParam())) notFound("Conversación")
        call.respond(OkResponse())
    }

    // ------------------------------------------------------------ Hoy y resumen
    get("/today") {
        val user = s.users.byId(call.userId())!!
        val date = call.request.queryParameters["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        call.respond(s.briefing.today(user, date))
    }
    post("/briefing") {
        val user = s.users.byId(call.userId())!!
        call.respond(BriefingResponse(s.briefing.generate(user)))
    }
}

private data class CaptureInput(val text: String?, val url: String?, val image: ByteArray?, val type: String?, val conversationId: UUID?)
