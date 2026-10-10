@file:UseSerializers(UUIDSerializer::class)

package app.kairo.routes

import app.kairo.Services
import app.kairo.agent.AgentEvent
import app.kairo.agent.AgentRequest
import app.kairo.domain.SettingsService
import app.kairo.domain.VoiceOption
import app.kairo.util.M
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
 * Reads a multipart form with a file. Caps: [maxBytes] in total (file and fields), 16 parts,
 * and the file is read without going over the cap. If the file is an image, its type comes from the bytes
 * (not from the client) and only JPEG, PNG, WebP, GIF and HEIC/HEIF are accepted: a disguised SVG or HTML is rejected.
 */
suspend fun ApplicationCall.receiveUpload(maxBytes: Long = 30L * 1024 * 1024): Upload {
    // Margin for the headers of each part and the text fields
    declaredContentLength()?.let { if (it > maxBytes + 64 * 1024) payloadTooLarge(M.FILE_TOO_LARGE) }
    val fields = HashMap<String, String>()
    var bytes: ByteArray? = null
    var name: String? = null
    var type: String? = null
    var total = 0L
    var parts = 0
    try {
        receiveMultipart(formFieldLimit = maxBytes).forEachPart { part ->
            try {
                if (++parts > 16) payloadTooLarge(M.TOO_MANY_PARTS)
                when (part) {
                    is PartData.FormItem -> {
                        total += part.value.length
                        if (total > maxBytes) payloadTooLarge(M.FILE_TOO_LARGE)
                        part.name?.let { fields[it] = part.value }
                    }
                    is PartData.FileItem -> if (bytes == null) {
                        val data = part.provider().readAtMost(maxBytes + 1)
                        total += data.size
                        if (total > maxBytes) payloadTooLarge(M.FILE_TOO_LARGE)
                        val declared = part.contentType?.toString()
                        val sniffed = sniffImageType(data)
                        if (sniffed == null && declared?.lowercase()?.startsWith("image/") == true) {
                            unsupportedMediaType(M.ONLY_SOME_IMAGES)
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
        // Ktor's parser cuts the parts that go over formFieldLimit
        if (e.message?.startsWith("Multipart content length exceeds limit") == true) payloadTooLarge(M.FILE_TOO_LARGE)
        throw e
    }
    return Upload(fields, bytes, name, type)
}

private val CHANNELS = setOf("chat", "voice", "wear", "dashboard", "share")

fun Route.agentRoutes(s: Services) {

    iaLimited {
        post("/chat") {
            val b = call.receiveBody<ChatRequest>()
            if (b.message.isBlank()) badRequest(M.EMPTY_MESSAGE)
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

        // ------------------------------------------------------------ Voice
        post("/voice/transcribe") {
            val up = call.receiveUpload()
            val audio = up.file ?: badRequest(M.MISSING_AUDIO_FILE)
            val t = s.voice.transcribe(call.userId(), audio, up.fileName ?: "audio.m4a", up.contentType ?: "audio/mp4")
            call.respond(TranscriptionResponse(if (s.voice.isNoise(t.text)) "" else t.text, t.language, t.durationSeconds))
        }

        /** Full voice turn: audio -> Whisper -> agent (SSE). The client then asks /voice/speak with the text. */
        post("/voice/turn") {
            val up = call.receiveUpload()
            val audio = up.file ?: badRequest(M.MISSING_AUDIO_FILE)
            val userId = call.userId()
            val user = s.users.byId(userId)!!
            val conversationId = up.fields["conversationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val channel = up.fields["channel"]?.takeIf { it in CHANNELS } ?: "voice"
            val location = call.clientLocation()
            val caps = call.clientCapabilities()
            val lang = call.lang(s)
            call.streamAgent(flow {
                val t = runCatching { s.voice.transcribe(userId, audio, up.fileName ?: "audio.m4a", up.contentType ?: "audio/mp4") }.getOrElse {
                    // The detail (Nan's response) goes only to the log; the client gets a generic message
                    org.slf4j.LoggerFactory.getLogger("app.kairo.routes.AgentRoutes")
                        .warn("Transcription failed: {}", app.kairo.util.logSafe(it.message ?: it.javaClass.simpleName))
                    emit(AgentEvent.Error(M.TRANSCRIBE_FAILED.t(lang)))
                    return@flow
                }
                if (s.voice.isNoise(t.text)) {
                    emit(AgentEvent.Error(M.NOT_HEARD.t(lang)))
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

        get("/voice/voices") { call.respond(VoicesResponse(SettingsService.voices(call.lang(s)))) }

        // ------------------------------------------------------------ Save to Kairo (share)
        post("/capture") {
            val user = s.users.byId(call.userId())!!
            val isMultipart = call.request.contentType().match(ContentType.MultiPart.FormData)
            val (text, url, image, type, conv) = if (isMultipart) {
                val up = call.receiveUpload(20L * 1024 * 1024)
                if (up.file != null && up.contentType?.startsWith("image/") != true) badRequest(M.ONLY_IMAGES)
                CaptureInput(up.fields["text"], up.fields["url"], up.file, up.contentType, up.fields["conversationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() })
            } else {
                val b = call.receiveBody<CaptureJson>()
                CaptureInput(b.text, b.url, null, null, b.conversationId)
            }
            if (text.isNullOrBlank() && url.isNullOrBlank() && image == null) badRequest(M.NOTHING_TO_SAVE)
            // If the "text" is just a link, treat it as a link
            val linkInText = text?.trim()?.takeIf { url == null && Regex("^https?://\\S+$").matches(it) }
            call.streamAgent(
                s.capture.capture(
                    user, if (linkInText != null) null else text, linkInText ?: url, image, type, conv,
                    call.clientLocation(), call.clientCapabilities(),
                ),
            )
        }
    }

    // ------------------------------------------------------------ Conversations
    get("/conversations") { call.respond(s.conversations.list(call.userId())) }
    get("/conversations/{id}/messages") {
        val id = call.uuidParam()
        s.conversations.get(call.userId(), id) ?: notFound(M.NF_CONVERSATION)
        call.respond(s.conversations.messages(call.userId(), id))
    }
    delete("/conversations/{id}") {
        if (!s.conversations.delete(call.userId(), call.uuidParam())) notFound(M.NF_CONVERSATION)
        call.respond(OkResponse())
    }

    // ------------------------------------------------------------ Today and briefing
    get("/today") {
        val user = s.users.byId(call.userId())!!
        val date = call.request.queryParameters["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        call.respond(s.briefing.today(user, date))
    }
    iaLimited {
        post("/briefing") {
            val user = s.users.byId(call.userId())!!
            call.respond(BriefingResponse(s.briefing.generate(user)))
        }
    }
}

private data class CaptureInput(val text: String?, val url: String?, val image: ByteArray?, val type: String?, val conversationId: UUID?)
