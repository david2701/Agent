package app.kairo.domain

import app.kairo.util.KairoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Últimos modelos leídos de Nan (GET /models) y cuándo. */
    @Volatile private var modelCache: Pair<Long, Set<String>>? = null

    suspend fun get(userId: UUID): UserSettings {
        val raw = users.settings(userId)
        return runCatching { KairoJson.decodeFromJsonElement<UserSettings>(raw) }.getOrElse { UserSettings() }
    }

    /**
     * Aplica sólo claves conocidas; ignora el resto. Valida los modelos contra [availableModels] (la lista de Nan)
     * cuando se puede leer; si no, al menos su formato. Lanza IllegalArgumentException si algo no es válido.
     */
    suspend fun patch(userId: UUID, patch: JsonObject, availableModels: (suspend () -> List<String>)? = null): UserSettings {
        val known = UserSettings.serializer().descriptor.elementNames.toSet()
        val filtered: Map<String, JsonElement> = patch.filterKeys { it in known && it != "lastBriefingDate" }
        val stored = users.settings(userId)
        val current = runCatching { KairoJson.decodeFromJsonElement<UserSettings>(stored) }.getOrElse { UserSettings() }
        val merged = JsonObject(stored + filtered)
        val parsed = KairoJson.decodeFromJsonElement<UserSettings>(merged) // valida tipos
        val needsCatalog = filtered.keys.any { it in MODEL_KEYS && !unchangedOrDefault(it, parsed, current) }
        val catalog = if (needsCatalog && availableModels != null) modelCatalog(availableModels) else null
        validate(parsed, current, filtered.keys, catalog)?.let { throw IllegalArgumentException(it) }
        users.mergeSettings(userId, filtered.mapValues { (_, v) -> v })
        return parsed
    }

    /** Lista de modelos de Nan con caché de 10 minutos; null si no se pudo leer (entonces sólo se valida el formato). */
    private suspend fun modelCatalog(fetch: suspend () -> List<String>): Set<String>? {
        modelCache?.let { (at, models) -> if (System.currentTimeMillis() - at < 10 * 60_000) return models }
        val models = try {
            withTimeoutOrNull(5_000) { fetch() }?.filter { it.isNotBlank() }?.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (models.isNullOrEmpty()) return null
        modelCache = System.currentTimeMillis() to models
        return models
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

        /** Ajustes que eligen un modelo de Nan (cada uno cuesta distinto). */
        val MODEL_KEYS = setOf("chatModel", "fallbackModel", "visionModel", "imageModel", "sttModel")

        private val DEFAULTS = UserSettings()
        private val MODEL_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,99}$")
        private val VOICE_RE = Regex("^[a-z]{1,3}_[a-z0-9_]{1,40}$")
        /** Nombres de tema que acepta ntfy. */
        private val NTFY_TOPIC_RE = Regex("^[-_A-Za-z0-9]{1,64}$")

        private fun model(s: UserSettings, key: String): String = when (key) {
            "chatModel" -> s.chatModel
            "fallbackModel" -> s.fallbackModel
            "visionModel" -> s.visionModel
            "imageModel" -> s.imageModel
            "sttModel" -> s.sttModel
            else -> error("No es un ajuste de modelo: $key")
        }

        /** El valor no cambia o es el de fábrica: se acepta aunque no esté en la lista de Nan. */
        private fun unchangedOrDefault(key: String, next: UserSettings, current: UserSettings): Boolean =
            model(next, key) == model(current, key) || model(next, key) == model(DEFAULTS, key)

        /**
         * Primer error de los ajustes que cambian ([changed]) o null si todo es válido.
         * [catalog]: modelos disponibles en Nan, o null si no se pudo leer la lista (sólo se valida el formato).
         */
        fun validate(next: UserSettings, current: UserSettings, changed: Set<String>, catalog: Set<String>?): String? {
            for (key in changed.filter { it in MODEL_KEYS }.sorted()) {
                if (unchangedOrDefault(key, next, current)) continue
                val value = model(next, key)
                if (!MODEL_RE.matches(value)) return "Nombre de modelo inválido en $key"
                if (catalog != null && value !in catalog) return "El modelo $value no está disponible"
            }
            if ("voice" in changed && next.voice != current.voice && next.voice !in VOICES.map { it.id } && !VOICE_RE.matches(next.voice)) {
                return "Voz inválida"
            }
            if ("ntfyTopic" in changed) {
                val topic = next.ntfyTopic?.trim()
                if (!topic.isNullOrEmpty() && !NTFY_TOPIC_RE.matches(topic)) return "Tema de ntfy inválido: usa letras, números, - o _ (máximo 64)"
            }
            return null
        }
    }
}

@Serializable
data class VoiceOption(val id: String, val name: String, val description: String)
