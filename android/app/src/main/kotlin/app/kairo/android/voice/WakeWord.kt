package app.kairo.android.voice

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
import java.util.zip.ZipInputStream

/**
 * Modelo de voz pequeño en español de Vosk (Apache 2.0, ~39 MB), descargado la primera vez.
 * Corre 100% en el teléfono: el audio no sale del dispositivo hasta que se detecta "Oye Kairo".
 */
class WakeModelManager(private val context: Context) {
    sealed interface Status {
        data object Missing : Status
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val dir = File(context.filesDir, "vosk-es")
    private val _status = MutableStateFlow<Status>(if (isReady()) Status.Ready else Status.Missing)
    val status: StateFlow<Status> = _status

    fun isReady(): Boolean = File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()

    fun path(): String = dir.absolutePath

    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) { _status.value = Status.Ready; return@withContext true }
        runCatching {
            _status.value = Status.Downloading(0f)
            val tmp = File(context.cacheDir, "vosk.zip")
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000 }
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
            val staging = File(context.filesDir, "vosk-es.tmp").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // El zip trae una carpeta raíz (vosk-model-small-es-0.42/); la quitamos
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(staging, rel)
                    if (!f.canonicalPath.startsWith(staging.canonicalPath)) continue // zip-slip
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            tmp.delete()
            dir.deleteRecursively()
            staging.renameTo(dir)
            check(isReady()) { "Modelo incompleto" }
            _status.value = Status.Ready
            true
        }.getOrElse {
            _status.value = Status.Failed(it.message ?: "No se pudo descargar el modelo")
            false
        }
    }

    fun delete() {
        dir.deleteRecursively()
        _status.value = Status.Missing
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    }
}

/**
 * Detector de la frase de activación con una gramática cerrada: Vosk solo puede reconocer
 * las frases de activación o "[unk]", lo que lo hace liviano y con pocos falsos positivos.
 * "Kairo" no existe en el vocabulario del modelo, pero "cairo" sí y suena igual.
 */
class WakeWordDetector(modelPath: String, phrase: String) : AutoCloseable {
    private val model = Model(modelPath)
    private val phrases = variants(phrase)
    private val recognizer = Recognizer(
        model,
        AudioSpec.SAMPLE_RATE.toFloat(),
        (phrases.map { "\"$it\"" } + "\"[unk]\"").joinToString(",", "[", "]"),
    )

    /** Devuelve true cuando escuchó la frase. */
    fun accept(frame: ShortArray, len: Int): Boolean {
        val final = recognizer.acceptWaveForm(frame, len)
        val text = if (final) JSONObject(recognizer.result).optString("text") else JSONObject(recognizer.partialResult).optString("partial")
        if (text.isBlank()) return false
        val hit = phrases.any { text.contains(it) }
        if (hit || final) recognizer.reset()
        return hit
    }

    fun reset() = recognizer.reset()

    override fun close() {
        recognizer.close()
        model.close()
    }

    companion object {
        fun variants(phrase: String): List<String> {
            val p = Normalizer.normalize(phrase.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            val name = p.split(' ').last().replace("k", "c")
            return listOf("oye $name", "hey $name", "ey $name", "ok $name").distinct()
        }
    }
}
