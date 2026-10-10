package app.kairo.domain

import app.kairo.util.KairoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

/** Preferencias del usuario. Todos los campos tienen valor por defecto. */
@Serializable
data class UserSettings(
    val chatModel: String = "deepseek-v4-flash",
    val fallbackModel: String = "qwen3.8-flash",
    val visionModel: String = "gemma4",
    val imageModel: String = "flux-2-klein",
    val fastMode: Boolean = true,
    val voice: String = "ef_dora",
    val voiceSpeed: Double = 1.0,
    val sttModel: String = "whisper",
    val language: String = "es",
    val wakeWord: String = "oye kairo",
    val briefingEnabled: Boolean = true,
    val briefingTime: String = "07:00",
    val lastBriefingDate: String? = null,
    val homeCity: String? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val ntfyTopic: String? = null,
    val googleCalendarId: String = "primary",
    val defaultEventReminderMinutes: Int = 15,
    val keepRecordingAudio: Boolean = false,
)

class SettingsService(private val users: UsersRepo) {

    suspend fun get(userId: UUID): UserSettings {
        val raw = users.settings(userId)
        return runCatching { KairoJson.decodeFromJsonElement<UserSettings>(raw) }.getOrElse { UserSettings() }
    }

    /** Aplica sólo claves conocidas; ignora el resto. */
    suspend fun patch(userId: UUID, patch: JsonObject): UserSettings {
        val known = UserSettings.serializer().descriptor.elementNames.toSet()
        val filtered: Map<String, JsonElement> = patch.filterKeys { it in known && it != "lastBriefingDate" }
        val merged = JsonObject(users.settings(userId) + filtered)
        val parsed = KairoJson.decodeFromJsonElement<UserSettings>(merged) // valida tipos
        users.mergeSettings(userId, filtered.mapValues { (_, v) -> v })
        return parsed
    }

    suspend fun setInternal(userId: UUID, key: String, value: JsonElement?) {
        users.mergeSettings(userId, mapOf(key to (value ?: JsonNull)))
    }

    companion object {
        /** Voces en español de Kokoro. */
        val VOICES = listOf(
            VoiceOption("ef_dora", "Dora", "femenina"),
            VoiceOption("em_alex", "Alex", "masculina"),
            VoiceOption("em_santa", "Santa", "masculina grave"),
        )
    }
}

@Serializable
data class VoiceOption(val id: String, val name: String, val description: String)
