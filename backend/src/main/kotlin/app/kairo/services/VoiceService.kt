package app.kairo.services

import app.kairo.agent.Speech
import app.kairo.domain.SettingsService
import app.kairo.nan.NanClient
import app.kairo.nan.Transcription
import app.kairo.util.Crypto
import app.kairo.util.M
import app.kairo.util.requireInput
import java.util.Collections
import java.util.UUID

/** Voice: Whisper (speech to text) and Kokoro (text to speech), with a small cache of synthesized audio. */
class VoiceService(private val nan: NanClient, private val settings: SettingsService) {

    private val cache: MutableMap<String, ByteArray> = Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 60
    })

    suspend fun transcribe(userId: UUID, audio: ByteArray, fileName: String, contentType: String): Transcription {
        requireInput(audio.size > 1000, M.AUDIO_EMPTY)
        requireInput(audio.size <= 25 * 1024 * 1024, M.AUDIO_TOO_LARGE)
        val s = settings.get(userId)
        // The language hint follows the user's setting; `auto` lets Whisper detect it
        return nan.transcribe(audio, fileName, contentType, s.language.takeIf { it != "auto" }, s.sttModel)
    }

    suspend fun speak(userId: UUID, text: String, voice: String? = null, speed: Double? = null): ByteArray {
        val s = settings.get(userId)
        val clean = Speech.clean(text).take(2000)
        requireInput(clean.isNotBlank(), M.NOTHING_TO_READ)
        val v = voice ?: s.voice
        val sp = (speed ?: s.voiceSpeed).coerceIn(0.6, 1.6)
        val key = Crypto.sha256("$v|$sp|$clean")
        cache[key]?.let { return it }
        val audio = nan.speech(clean, v, speed = sp)
        cache[key] = audio
        return audio
    }

    /** Filters the typical transcriptions of silence that Whisper hallucinates (in Spanish and in English). */
    fun isNoise(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', '!', '?', ' ')
        return t.isEmpty() || t in NOISE || t.length < 2
    }

    companion object {
        private val NOISE = setOf(
            // Spanish
            "gracias", "gracias por ver el video", "subtítulos realizados por la comunidad de amara.org",
            "subtitulado por la comunidad de amara.org", "música", "aplausos", "suscríbete",
            // English
            "thank you", "thanks", "you", "thanks for watching", "thank you for watching", "thank you so much for watching",
            "please subscribe", "subscribe", "like and subscribe", "subtitles by the amara.org community",
            "music", "applause", "bye", "bye bye", "okay", "uh", "um", "hmm",
        )
    }
}
