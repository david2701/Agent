package app.kairo.services

import app.kairo.agent.Speech
import app.kairo.domain.SettingsService
import app.kairo.nan.NanClient
import app.kairo.nan.Transcription
import app.kairo.util.Crypto
import java.util.Collections
import java.util.UUID

/** Voz: Whisper (voz a texto) y Kokoro (texto a voz), con caché pequeña de audio sintetizado. */
class VoiceService(private val nan: NanClient, private val settings: SettingsService) {

    private val cache: MutableMap<String, ByteArray> = Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 60
    })

    suspend fun transcribe(userId: UUID, audio: ByteArray, fileName: String, contentType: String): Transcription {
        require(audio.size > 1000) { "El audio está vacío" }
        require(audio.size <= 25 * 1024 * 1024) { "El audio supera 25 MB" }
        val s = settings.get(userId)
        return nan.transcribe(audio, fileName, contentType, s.language.takeIf { it != "auto" }, s.sttModel)
    }

    suspend fun speak(userId: UUID, text: String, voice: String? = null, speed: Double? = null): ByteArray {
        val s = settings.get(userId)
        val clean = Speech.clean(text).take(2000)
        require(clean.isNotBlank()) { "No hay texto para leer" }
        val v = voice ?: s.voice
        val sp = (speed ?: s.voiceSpeed).coerceIn(0.6, 1.6)
        val key = Crypto.sha256("$v|$sp|$clean")
        cache[key]?.let { return it }
        val audio = nan.speech(clean, v, speed = sp)
        cache[key] = audio
        return audio
    }

    /** Filtra transcripciones típicas de silencio que Whisper alucina. */
    fun isNoise(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', '!', '?', ' ')
        return t.isEmpty() || t in NOISE || t.length < 2
    }

    companion object {
        private val NOISE = setOf(
            "gracias", "gracias por ver el video", "subtítulos realizados por la comunidad de amara.org",
            "subtitulado por la comunidad de amara.org", "música", "aplausos", "suscríbete", "thank you", "you",
        )
    }
}
