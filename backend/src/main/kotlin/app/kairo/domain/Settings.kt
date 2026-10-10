package app.kairo.domain

import app.kairo.util.KairoJson
import app.kairo.util.InvalidInput
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.Say
import app.kairo.util.say
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * User preferences. Every field has a default value. `voice` and `wakeWord` defaults depend on the language:
 * [SettingsService] fills them in (English here, Spanish when `language` is `es`) when the user never set them.
 */
@Serializable
data class UserSettings(
    val chatModel: String = "deepseek-v4-flash",
    val fallbackModel: String = "qwen3.8-flash",
    val visionModel: String = "gemma4",
    val imageModel: String = "flux-2-klein",
    val fastMode: Boolean = true,
    val voice: String = SettingsService.DEFAULT_VOICE_EN,
    val voiceSpeed: Double = 1.0,
    val sttModel: String = "whisper",
    /** `en`, `es` or `auto` (mirror the user's language and let Whisper detect it). */
    val language: String = Lang.DEFAULT.code,
    val wakeWord: String = SettingsService.DEFAULT_WAKE_WORD_EN,
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

/**
 * @param defaultLang language used for server text when the setting is `auto` (and when nothing is stored).
 */
class SettingsService(private val users: UsersRepo, val defaultLang: Lang = Lang.DEFAULT) {
    /** Latest models read from Nan (GET /models) and when. */
    @Volatile private var modelCache: Pair<Long, Set<String>>? = null

    suspend fun get(userId: UUID): UserSettings = decode(users.settings(userId))

    /** Language for text the server writes for [userId] (`auto` falls back to [acceptLanguage], then the server default). */
    suspend fun lang(userId: UUID, acceptLanguage: String? = null): Lang = langOf(get(userId), acceptLanguage)

    fun langOf(s: UserSettings, acceptLanguage: String? = null): Lang = Lang.resolve(s.language, acceptLanguage, defaultLang)

    /** Decodes the stored JSON, filling in the language-dependent defaults for what the user never set. */
    private fun decode(raw: JsonObject): UserSettings {
        val base = runCatching { KairoJson.decodeFromJsonElement<UserSettings>(raw) }.getOrElse { UserSettings() }
        val lang = Lang.fromCode(base.language) ?: defaultLang
        return base.copy(
            voice = if ("voice" in raw) base.voice else defaultVoice(lang),
            wakeWord = if ("wakeWord" in raw) base.wakeWord else defaultWakeWord(lang),
        )
    }

    /**
     * Applies only known keys; ignores the rest. Validates the models against [availableModels] (Nan's list)
     * when it can be read; otherwise at least their format. Throws [InvalidInput] if something is not valid.
     */
    suspend fun patch(userId: UUID, patch: JsonObject, availableModels: (suspend () -> List<String>)? = null): UserSettings {
        val known = UserSettings.serializer().descriptor.elementNames.toSet()
        val filtered: Map<String, JsonElement> = patch.filterKeys { it in known && it != "lastBriefingDate" }
        val stored = users.settings(userId)
        val current = decode(stored)
        val merged = JsonObject(stored + filtered)
        // Decoding validates the types (a wrong type throws a SerializationException, which is an IllegalArgumentException)
        KairoJson.decodeFromJsonElement<UserSettings>(merged)
        val parsed = decode(merged)
        val needsCatalog = filtered.keys.any { it in MODEL_KEYS && !unchangedOrDefault(it, parsed, current) }
        val catalog = if (needsCatalog && availableModels != null) modelCatalog(availableModels) else null
        validate(parsed, current, filtered.keys, catalog)?.let { throw InvalidInput(it.msg, it.args) }
        users.mergeSettings(userId, filtered.mapValues { (_, v) -> v })
        return parsed
    }

    /** Nan's model list, cached for 10 minutes; null if it could not be read (then only the format is validated). */
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
        const val DEFAULT_VOICE_EN = "af_heart"
        const val DEFAULT_VOICE_ES = "ef_dora"
        const val DEFAULT_WAKE_WORD_EN = "hey kairo"
        const val DEFAULT_WAKE_WORD_ES = "oye kairo"

        fun defaultVoice(lang: Lang): String = if (lang == Lang.ES) DEFAULT_VOICE_ES else DEFAULT_VOICE_EN
        fun defaultWakeWord(lang: Lang): String = if (lang == Lang.ES) DEFAULT_WAKE_WORD_ES else DEFAULT_WAKE_WORD_EN

        /** Kokoro voices the app offers, by language. */
        private class Voice(val id: String, val name: String, val lang: Lang, val description: M)

        private val CATALOG = listOf(
            Voice(DEFAULT_VOICE_EN, "Heart", Lang.EN, M.VOICE_FEMALE),
            Voice("am_adam", "Adam", Lang.EN, M.VOICE_MALE),
            Voice("bf_emma", "Emma", Lang.EN, M.VOICE_FEMALE_BRITISH),
            Voice(DEFAULT_VOICE_ES, "Dora", Lang.ES, M.VOICE_FEMALE),
            Voice("em_alex", "Alex", Lang.ES, M.VOICE_MALE),
            Voice("em_santa", "Santa", Lang.ES, M.VOICE_MALE_DEEP),
        )

        /** Voice ids that are known to exist, any language. */
        val VOICE_IDS: List<String> = CATALOG.map { it.id }

        /** The voices for the picker: the ones in [lang] first, then the rest; descriptions in [lang]. */
        fun voices(lang: Lang): List<VoiceOption> =
            CATALOG.sortedBy { if (it.lang == lang) 0 else 1 }.map { VoiceOption(it.id, it.name, it.description.t(lang)) }

        /** Settings that choose a Nan model (each one costs differently). */
        val MODEL_KEYS = setOf("chatModel", "fallbackModel", "visionModel", "imageModel", "sttModel")

        private val DEFAULTS = UserSettings()
        private val MODEL_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,99}$")
        private val VOICE_RE = Regex("^[a-z]{1,3}_[a-z0-9_]{1,40}$")
        /** Topic names that ntfy accepts. */
        private val NTFY_TOPIC_RE = Regex("^[-_A-Za-z0-9]{1,64}$")

        private fun model(s: UserSettings, key: String): String = when (key) {
            "chatModel" -> s.chatModel
            "fallbackModel" -> s.fallbackModel
            "visionModel" -> s.visionModel
            "imageModel" -> s.imageModel
            "sttModel" -> s.sttModel
            else -> error("Not a model setting: $key")
        }

        /** The value did not change or is the factory one: accepted even if it is not in Nan's list. */
        private fun unchangedOrDefault(key: String, next: UserSettings, current: UserSettings): Boolean =
            model(next, key) == model(current, key) || model(next, key) == model(DEFAULTS, key)

        /**
         * First error among the settings that change ([changed]), or null if everything is valid.
         * [catalog]: models available in Nan, or null if the list could not be read (only the format is validated).
         */
        fun validate(next: UserSettings, current: UserSettings, changed: Set<String>, catalog: Set<String>?): Say? {
            for (key in changed.filter { it in MODEL_KEYS }.sorted()) {
                if (unchangedOrDefault(key, next, current)) continue
                val value = model(next, key)
                if (!MODEL_RE.matches(value)) return say(M.SET_BAD_MODEL_NAME, key)
                if (catalog != null && value !in catalog) return say(M.SET_MODEL_UNAVAILABLE, value)
            }
            if ("voice" in changed && next.voice != current.voice && next.voice !in VOICE_IDS && !VOICE_RE.matches(next.voice)) {
                return say(M.SET_BAD_VOICE)
            }
            if ("language" in changed && next.language !in Lang.SETTING_VALUES) return say(M.SET_BAD_LANGUAGE)
            if ("ntfyTopic" in changed) {
                val topic = next.ntfyTopic?.trim()
                if (!topic.isNullOrEmpty() && !NTFY_TOPIC_RE.matches(topic)) return say(M.SET_BAD_NTFY_TOPIC)
            }
            return null
        }
    }
}

@Serializable
data class VoiceOption(val id: String, val name: String, val description: String)
