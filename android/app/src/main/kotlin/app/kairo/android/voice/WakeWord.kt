package app.kairo.android.voice

import app.kairo.core.voice.AudioSpec
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/** Offline wake word models: one small Vosk model per language (Apache 2.0, ~40 MB each). */
enum class WakeLanguage(val dirName: String, val modelUrl: String) {
    Spanish("vosk-es", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
    English("vosk-en-us", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip");

    companion object {
        /** Spanish UI -> the Spanish model; anything else -> the English one. */
        fun forLocale(locale: Locale): WakeLanguage = if (locale.language == "es") Spanish else English
    }
}

/**
 * Downloads and locates the small Vosk wake word model for the app language, on demand.
 * Everything runs on the phone: audio does not leave the device until the wake phrase is detected.
 * A model that was already downloaded for another language keeps working until the right one is available
 * (the matcher accepts both "Hey Kairo" and "Oye Kairo" with whichever model is loaded).
 */
class WakeModelManager(private val context: Context) {
    sealed interface Status {
        data object Missing : Status
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private fun language(): WakeLanguage = WakeLanguage.forLocale(context.resources.configuration.locales[0])
    private fun dir(lang: WakeLanguage) = File(context.filesDir, lang.dirName)
    private fun isReady(lang: WakeLanguage): Boolean = File(dir(lang), "am/final.mdl").exists() || File(dir(lang), "final.mdl").exists()

    private val _status = MutableStateFlow<Status>(if (isReady()) Status.Ready else Status.Missing)
    val status: StateFlow<Status> = _status

    /** There is a usable model: the one for the app language or, failing that, another downloaded one. */
    fun isReady(): Boolean = WakeLanguage.entries.any { isReady(it) }

    /** Folder of the model to load: the app language's if downloaded, otherwise any downloaded one. */
    fun path(): String {
        val lang = language()
        val chosen = if (isReady(lang)) lang else WakeLanguage.entries.firstOrNull { isReady(it) } ?: lang
        return dir(chosen).absolutePath
    }

    /** Downloads the model for the app language if it is missing. */
    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        val lang = language()
        if (isReady(lang)) { _status.value = Status.Ready; return@withContext true }
        runCatching {
            _status.value = Status.Downloading(0f)
            val tmp = File(context.cacheDir, "vosk.zip")
            val conn = (URL(lang.modelUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000 }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        _status.value = Status.Downloading((read.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
            val staging = File(context.filesDir, "${lang.dirName}.tmp").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // The zip has a root folder (vosk-model-small-.../); strip it
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(staging, rel)
                    if (!f.canonicalPath.startsWith(staging.canonicalPath)) continue // zip-slip
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            tmp.delete()
            dir(lang).deleteRecursively()
            staging.renameTo(dir(lang))
            check(isReady(lang)) { "Incomplete model" }
            _status.value = Status.Ready
            true
        }.getOrElse {
            _status.value = Status.Failed(it.message ?: "Could not download the model")
            false
        }
    }

    fun delete() {
        WakeLanguage.entries.forEach { dir(it).deleteRecursively() }
        _status.value = Status.Missing
    }
}

/**
 * Pure text matching for the wake phrase, shared by the detector and the unit tests.
 * Both "Hey Kairo" (English) and "Oye Kairo" (Spanish) are accepted whichever model is loaded.
 * "Kairo" is not in the models' vocabularies but "cairo" is and sounds the same.
 */
object WakeMatcher {
    private val GREETINGS = listOf("oye", "hey", "ey", "ok")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** The assistant name as the models spell it ("Hey Kairo" -> "cairo"). */
    fun nameOf(phrase: String): String = normalize(phrase).split(' ').last().replace("k", "c")

    /** Phrases for the closed grammar: every greeting in both languages followed by the name. */
    fun variants(phrase: String): List<String> {
        val name = nameOf(phrase)
        return GREETINGS.map { "$it $name" }
    }

    /**
     * True if the recognizer output has a greeting right before the name, or is just the name:
     * with a closed grammar a model may drop a greeting that is missing from its vocabulary
     * (for example "oye" in the English model) and recognize only "cairo".
     */
    fun matches(text: String, phrase: String): Boolean {
        val name = nameOf(phrase)
        val tokens = normalize(text).replace("k", "c").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val idx = tokens.indexOf(name)
        if (idx < 0) return false
        if (idx > 0 && tokens[idx - 1] in GREETINGS) return true
        return tokens.size == 1
    }
}

/**
 * Wake phrase detector with a closed grammar: Vosk can only recognize the wake phrases or "[unk]",
 * which keeps it light and with few false positives.
 */
class WakeWordDetector(modelPath: String, private val phrase: String) : AutoCloseable {
    private val model = Model(modelPath)
    private val recognizer = Recognizer(
        model,
        AudioSpec.SAMPLE_RATE.toFloat(),
        (WakeMatcher.variants(phrase).map { "\"$it\"" } + "\"[unk]\"").joinToString(",", "[", "]"),
    )

    /** Returns true when the phrase was heard. */
    fun accept(frame: ShortArray, len: Int): Boolean {
        val final = recognizer.acceptWaveForm(frame, len)
        val text = if (final) JSONObject(recognizer.result).optString("text") else JSONObject(recognizer.partialResult).optString("partial")
        if (text.isBlank()) return false
        val hit = WakeMatcher.matches(text, phrase)
        if (hit || final) recognizer.reset()
        return hit
    }

    fun reset() = recognizer.reset()

    override fun close() {
        recognizer.close()
        model.close()
    }
}
