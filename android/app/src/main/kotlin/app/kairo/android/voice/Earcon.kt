package app.kairo.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Short, soft synthesized sounds (no files): start and end of listening. */
object Earcon {
    private const val RATE = 44_100

    fun start() = play(listOf(660.0 to 70, 990.0 to 90))
    fun end() = play(listOf(880.0 to 60, 587.0 to 80))
    fun error() = play(listOf(392.0 to 120))

    private fun play(notes: List<Pair<Double, Int>>) {
        runCatching {
            val samples = notes.sumOf { it.second } * RATE / 1000
            val pcm = ShortArray(samples)
            var offset = 0
            for ((freq, ms) in notes) {
                val n = ms * RATE / 1000
                for (i in 0 until n) {
                    val env = sin(PI * i / n) // soft attack and decay
                    pcm[offset + i] = (sin(2 * PI * freq * i / RATE) * env * 0.22 * Short.MAX_VALUE).toInt().toShort()
                }
                offset += n
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { t?.release() }
                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            })
            track.play()
        }
    }
}
