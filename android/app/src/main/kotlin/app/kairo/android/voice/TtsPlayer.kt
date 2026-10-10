package app.kairo.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Reproduce el audio de Kokoro (mp3) con foco de audio transitorio y nivel para el orbe. */
class TtsPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null

    suspend fun play(mp3: ByteArray, onLevel: (Float) -> Unit) {
        val file = File(context.cacheDir, "kairo-tts-${System.nanoTime()}.mp3")
        file.writeBytes(mp3)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        audio.requestAudioFocus(focus)
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(attrs)
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener {
                    attachVisualizer(mp.audioSessionId, onLevel)
                    it.start()
                }
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop() } }
                mp.prepareAsync()
            }
        } finally {
            release()
            onLevel(0f)
            audio.abandonAudioFocusRequest(focus)
            file.delete()
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        release()
    }

    private fun release() {
        runCatching { visualizer?.release() }
        visualizer = null
        runCatching { player?.release() }
        player = null
    }

    private fun attachVisualizer(session: Int, onLevel: (Float) -> Unit) {
        visualizer = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                        wave ?: return
                        var sum = 0.0
                        for (b in wave) { val x = (b.toInt() and 0xFF) - 128; sum += x * x }
                        onLevel((kotlin.math.sqrt(sum / wave.size) / 48.0).toFloat().coerceIn(0f, 1f))
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) = Unit
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        }.getOrNull()
    }

    companion object {
        val attrs: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
