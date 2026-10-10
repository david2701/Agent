package app.kairo.services

import app.kairo.domain.NotesRepo
import app.kairo.domain.Recording
import app.kairo.domain.RecordingsRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.TasksRepo
import app.kairo.domain.User
import app.kairo.nan.ChatMessage
import app.kairo.nan.ChatOptions
import app.kairo.nan.NanClient
import app.kairo.realtime.EventBus
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.KairoJson
import app.kairo.util.M
import app.kairo.util.invalid
import app.kairo.util.requireInput
import app.kairo.util.humanDate
import app.kairo.util.zoneOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Long recordings: the client uploads segments (about 5 min each) that are transcribed on arrival.
 * When it ends, a summary, key points and actions are generated and saved as a note.
 * The audio is not kept: only the transcript.
 */
class RecordingService(
    private val repo: RecordingsRepo,
    private val voice: VoiceService,
    private val notes: NotesRepo,
    private val tasks: TasksRepo,
    private val settings: SettingsService,
    private val nan: NanClient,
    private val bus: EventBus,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(RecordingService::class.java)

    suspend fun addSegment(user: User, id: UUID, seq: Int, durationS: Int, audio: ByteArray, fileName: String, contentType: String): Recording {
        val rec = repo.get(user.id, id) ?: invalid(M.NF_RECORDING)
        requireInput(rec.status == "recording", M.RECORDING_FINISHED)
        val t = voice.transcribe(user.id, audio, fileName, contentType)
        val text = if (voice.isNoise(t.text)) "" else t.text
        repo.addSegment(id, seq, durationS.takeIf { it > 0 } ?: t.durationSeconds?.toInt() ?: 0, text)
        notify(user.id, id)
        return repo.get(user.id, id)!!
    }

    fun finish(user: User, id: UUID, title: String?) {
        scope.launch {
            try {
                repo.setStatus(id, "processing")
                notify(user.id, id)
                val rec = repo.get(user.id, id) ?: return@launch
                if (rec.transcript.isBlank()) {
                    repo.setStatus(id, "failed", M.RECORDING_NO_SPEECH.t(settings.lang(user.id)))
                    notify(user.id, id)
                    return@launch
                }
                val s = settings.get(user.id)
                val lang = settings.langOf(s)
                val res = nan.chat(
                    listOf(
                        ChatMessage.system(
                            "You summarize recordings (meetings, classes, voice notes) in ${lang.englishName}. Return ONLY a JSON object with the keys: " +
                                "\"title\" (8 words at most), \"summary\" (one paragraph of 3-5 sentences), \"key_points\" (list of up to 8 sentences), " +
                                "\"action_items\" (list of concrete tasks phrased as actions, with the person responsible if mentioned; empty if none).",
                        ),
                        ChatMessage.user("Transcript:\n" + rec.transcript.take(60_000)),
                    ),
                    opts = ChatOptions(model = s.chatModel, maxTokens = 2000, jsonMode = true, reasoningEffort = "none"),
                )
                val raw = res.message.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val obj = runCatching { KairoJson.parseToJsonElement(raw).jsonObject }.getOrElse { buildJsonObject { put("summary", raw) } }
                fun list(k: String) = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                val finalTitle = title?.takeIf { it.isNotBlank() } ?: (obj["title"] as? JsonPrimitive)?.content ?: M.DEFAULT_RECORDING_TITLE.t(lang)
                val summary = (obj["summary"] as? JsonPrimitive)?.content ?: ""
                val keyPoints = list("key_points")
                val actions = list("action_items")
                val date = humanDate(Instant.now().atZone(zoneOf(user.timezone)).toLocalDate(), lang = lang)
                val content = buildString {
                    appendLine("**$date** · ${rec.durationS / 60} min")
                    appendLine()
                    appendLine(summary)
                    if (keyPoints.isNotEmpty()) {
                        appendLine(); appendLine("### ${M.RECORDING_KEY_POINTS.t(lang)}"); keyPoints.forEach { appendLine("- $it") }
                    }
                    if (actions.isNotEmpty()) {
                        appendLine(); appendLine("### ${M.RECORDING_ACTIONS.t(lang)}"); actions.forEach { appendLine("- [ ] $it") }
                    }
                    appendLine(); appendLine("### ${M.RECORDING_TRANSCRIPT.t(lang)}"); appendLine(rec.transcript)
                }
                val note = notes.create(user.id, finalTitle, content, listOf(M.RECORDING_NOTE_TAG.t(lang)), source = "recording")
                repo.setResult(id, finalTitle, summary, keyPoints, actions, note.id)
            } catch (e: Exception) {
                log.error("Failed to process recording", e)
                repo.setStatus(id, "failed", e.message?.take(200))
            }
            notify(user.id, id)
        }
    }

    suspend fun createTasks(user: User, id: UUID): Int {
        val rec = repo.get(user.id, id) ?: invalid(M.NF_RECORDING)
        val project = tasks.findOrCreateProject(user.id, rec.title.take(40))
        val lang = settings.lang(user.id)
        rec.actionItems.forEach { tasks.create(user.id, it, notes = M.RECORDING_TASK_NOTE.t(lang, rec.title), projectId = project.id, source = "recording") }
        return rec.actionItems.size
    }

    private suspend fun notify(userId: UUID, id: UUID) =
        bus.publish(userId, ServerEvent(EventTypes.RECORDING_UPDATED, buildJsonObject { put("id", id.toString()) }))
}
