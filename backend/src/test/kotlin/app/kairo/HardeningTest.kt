package app.kairo

import app.kairo.domain.SettingsService
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.domain.UserSettings
import app.kairo.integrations.PushService
import app.kairo.realtime.EventBus
import app.kairo.realtime.ServerEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HardeningTest {

    private val current = UserSettings()
    private val catalog = setOf("deepseek-v4-flash", "qwen3.8-flash", "gemma4", "kimi-k3", "whisper-large")

    private fun check(next: UserSettings, vararg keys: String, models: Set<String>? = catalog) =
        SettingsService.validate(next, current, keys.toSet(), models)

    @Test
    fun `models are validated against Nan's list`() {
        assertNull(check(current.copy(chatModel = "kimi-k3"), "chatModel"))
        assertNotNull(check(current.copy(chatModel = "gpt-ultra-caro"), "chatModel"))
        assertNotNull(check(current.copy(sttModel = "whisper-xxl"), "sttModel"))
        // The current or the factory value is accepted even if Nan does not list it
        assertNull(check(current, "chatModel", "fallbackModel", "visionModel", "imageModel", "sttModel"))
        assertNull(SettingsService.validate(current, current.copy(imageModel = "old"), setOf("imageModel"), catalog))
        // Only what changes is validated
        assertNull(SettingsService.validate(current.copy(chatModel = "other"), current.copy(chatModel = "other"), setOf("chatModel"), catalog))
    }

    @Test
    fun `without a model list only the format is validated`() {
        assertNull(check(current.copy(chatModel = "org/model-1.5:latest"), "chatModel", models = null))
        assertNotNull(check(current.copy(chatModel = "a".repeat(101)), "chatModel", models = null))
        assertNotNull(check(current.copy(visionModel = "model with spaces"), "visionModel", models = null))
        assertNotNull(check(current.copy(imageModel = "x\nINFO fake"), "imageModel", models = null))
        assertNotNull(check(current.copy(fallbackModel = ""), "fallbackModel", models = null))
    }

    @Test
    fun `voice and ntfy topic`() {
        assertNull(check(current.copy(voice = "em_alex"), "voice"))
        assertNull(check(current.copy(voice = "af_heart"), "voice"))
        assertNotNull(check(current.copy(voice = "../../etc"), "voice"))
        assertNull(check(current.copy(ntfyTopic = "kairo-sofia_2"), "ntfyTopic"))
        assertNull(check(current.copy(ntfyTopic = null), "ntfyTopic"))
        assertNotNull(check(current.copy(ntfyTopic = "topic/other"), "ntfyTopic"))
        assertNotNull(check(current.copy(ntfyTopic = "a".repeat(65)), "ntfyTopic"))
    }

    @Test
    fun `language setting accepts en, es and auto only`() {
        assertEquals("en", current.language)
        listOf("en", "es", "auto").forEach { assertNull(check(current.copy(language = it), "language"), it) }
        listOf("fr", "EN", "es-CO", "", "english").forEach {
            val error = check(current.copy(language = it), "language")
            assertNotNull(error, "should reject '$it'")
            assertEquals(M.SET_BAD_LANGUAGE, error.msg)
        }
        // Only validated when it changes
        assertNull(check(current.copy(language = "fr"), "voice"))
    }

    @Test
    fun `voice and wake word defaults follow the language`() {
        assertEquals("af_heart", SettingsService.defaultVoice(Lang.EN))
        assertEquals("ef_dora", SettingsService.defaultVoice(Lang.ES))
        assertEquals("hey kairo", SettingsService.defaultWakeWord(Lang.EN))
        assertEquals("oye kairo", SettingsService.defaultWakeWord(Lang.ES))
        assertEquals("af_heart", UserSettings().voice)
        assertEquals("hey kairo", UserSettings().wakeWord)
        // The picker lists the voices of the language first, with the description in that language
        assertEquals("Heart", SettingsService.voices(Lang.EN).first().name)
        assertEquals("female", SettingsService.voices(Lang.EN).first().description)
        assertEquals("Dora", SettingsService.voices(Lang.ES).first().name)
        assertEquals("femenina", SettingsService.voices(Lang.ES).first().description)
        assertEquals(SettingsService.voices(Lang.EN).map { it.id }.toSet(), SettingsService.voices(Lang.ES).map { it.id }.toSet())
        assertNull(check(current.copy(voice = "ef_dora"), "voice"))
        assertNull(check(current.copy(voice = "am_adam"), "voice"))
    }

    @Test
    fun `own ntfy hosts`() {
        assertEquals(setOf("ntfy.home.example", "ntfy"), PushService.ntfyHosts("https://NTFY.home.example", "http://ntfy:80", null, ""))
        assertEquals(emptySet(), PushService.ntfyHosts(null, "not a url"))
    }

    @Test
    fun `a subscriber that does not read does not slow down the publisher`() = runBlocking {
        val bus = EventBus()
        val user = UUID.randomUUID()
        // It subscribes and stays blocked without reading (like a WebSocket the client does not consume)
        val stuck = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            bus.flow(user).collect { awaitCancellation() }
        }
        withTimeout(5_000) {
            repeat(10_000) { bus.publish(user, ServerEvent("entity.changed")) }
        }
        // A new subscriber keeps receiving what is published afterwards
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals("reminder.fire", bus.flow(user).first { it.type == "reminder.fire" }.type)
        }
        bus.publish(user, ServerEvent("reminder.fire"))
        withTimeout(5_000) { next.join() }
        stuck.cancelAndJoin()
    }
}
