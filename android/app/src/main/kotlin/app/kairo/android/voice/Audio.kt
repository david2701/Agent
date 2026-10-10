package app.kairo.android.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioSpec {
    const val SAMPLE_RATE = 16_000
    /** 32 ms por bloque. */
    const val FRAME = 512
}

fun rms(buf: ShortArray, len: Int): Double {
    if (len <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until len) {
        val v = buf[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / len)
}

/** Nivel 0..1 para la animación, en escala aproximadamente logarítmica. */
fun levelOf(rms: Double): Float {
    if (rms <= 30) return 0f
    val db = 20 * kotlin.math.log10(rms / 32768.0) // -60 dB .. 0 dB
    return ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
}

/** WAV PCM 16 bits mono. Whisper lo acepta directamente. */
fun pcmToWav(pcm: ShortArray, len: Int, sampleRate: Int = AudioSpec.SAMPLE_RATE): ByteArray {
    val dataBytes = len * 2
    val out = ByteArrayOutputStream(44 + dataBytes)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes)
    }
    out.write(header.array())
    val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until len) body.putShort(pcm[i])
    out.write(body.array())
    return out.toByteArray()
}

/**
 * Detección de fin de frase por energía con umbral adaptativo al ruido de fondo.
 * Termina tras ~0,9 s de silencio una vez que hubo voz, o a los 15 s.
 */
class EndOfSpeech(private var noiseFloor: Double = 220.0) {
    private var speechFrames = 0
    private var silenceFrames = 0
    private var totalFrames = 0
    var heardSpeech = false
        private set

    fun updateNoise(r: Double) {
        noiseFloor = noiseFloor * 0.97 + r * 0.03
    }

    fun reset() {
        speechFrames = 0; silenceFrames = 0; totalFrames = 0; heardSpeech = false
    }

    enum class Verdict { Continue, Done, NoSpeech }

    fun push(r: Double): Verdict {
        totalFrames++
        val threshold = maxOf(noiseFloor * 2.6, 420.0)
        if (r > threshold) {
            speechFrames++
            silenceFrames = 0
            if (speechFrames >= 3) heardSpeech = true
        } else {
            silenceFrames++
            if (!heardSpeech) speechFrames = 0
            if (!heardSpeech) updateNoise(r)
        }
        val framesPerSecond = AudioSpec.SAMPLE_RATE / AudioSpec.FRAME
        return when {
            heardSpeech && silenceFrames > framesPerSecond * 0.9 -> Verdict.Done
            totalFrames > framesPerSecond * 15 -> if (heardSpeech) Verdict.Done else Verdict.NoSpeech
            !heardSpeech && totalFrames > framesPerSecond * 6 -> Verdict.NoSpeech
            else -> Verdict.Continue
        }
    }
}

/** Búfer creciente de PCM. */
class PcmBuffer(initial: Int = AudioSpec.SAMPLE_RATE * 8) {
    var data = ShortArray(initial)
        private set
    var size = 0
        private set

    fun append(src: ShortArray, len: Int) {
        if (size + len > data.size) data = data.copyOf(maxOf(data.size * 2, size + len))
        System.arraycopy(src, 0, data, size, len)
        size += len
    }

    fun clear() { size = 0 }
}
