package app.kairo

import app.kairo.domain.SettingsService
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
    fun `modelos validados contra la lista de Nan`() {
        assertNull(check(current.copy(chatModel = "kimi-k3"), "chatModel"))
        assertNotNull(check(current.copy(chatModel = "gpt-ultra-caro"), "chatModel"))
        assertNotNull(check(current.copy(sttModel = "whisper-xxl"), "sttModel"))
        // El valor actual o el de fábrica se aceptan aunque Nan no los liste
        assertNull(check(current, "chatModel", "fallbackModel", "visionModel", "imageModel", "sttModel"))
        assertNull(SettingsService.validate(current, current.copy(imageModel = "viejo"), setOf("imageModel"), catalog))
        // Sólo se valida lo que cambia
        assertNull(SettingsService.validate(current.copy(chatModel = "otro"), current.copy(chatModel = "otro"), setOf("chatModel"), catalog))
    }

    @Test
    fun `sin lista de modelos se valida el formato`() {
        assertNull(check(current.copy(chatModel = "org/modelo-1.5:latest"), "chatModel", models = null))
        assertNotNull(check(current.copy(chatModel = "a".repeat(101)), "chatModel", models = null))
        assertNotNull(check(current.copy(visionModel = "modelo con espacios"), "visionModel", models = null))
        assertNotNull(check(current.copy(imageModel = "x\nINFO falso"), "imageModel", models = null))
        assertNotNull(check(current.copy(fallbackModel = ""), "fallbackModel", models = null))
    }

    @Test
    fun `voz y tema de ntfy`() {
        assertNull(check(current.copy(voice = "em_alex"), "voice"))
        assertNull(check(current.copy(voice = "af_heart"), "voice"))
        assertNotNull(check(current.copy(voice = "../../etc"), "voice"))
        assertNull(check(current.copy(ntfyTopic = "kairo-sofia_2"), "ntfyTopic"))
        assertNull(check(current.copy(ntfyTopic = null), "ntfyTopic"))
        assertNotNull(check(current.copy(ntfyTopic = "tema/otro"), "ntfyTopic"))
        assertNotNull(check(current.copy(ntfyTopic = "a".repeat(65)), "ntfyTopic"))
    }

    @Test
    fun `hosts del ntfy propio`() {
        assertEquals(setOf("ntfy.casa.com", "ntfy"), PushService.ntfyHosts("https://NTFY.casa.com", "http://ntfy:80", null, ""))
        assertEquals(emptySet(), PushService.ntfyHosts(null, "no es url"))
    }

    @Test
    fun `un suscriptor que no lee no frena a quien publica`() = runBlocking {
        val bus = EventBus()
        val user = UUID.randomUUID()
        // Se suscribe y se queda bloqueado sin leer (como un WebSocket que el cliente no consume)
        val stuck = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            bus.flow(user).collect { awaitCancellation() }
        }
        withTimeout(5_000) {
            repeat(10_000) { bus.publish(user, ServerEvent("entity.changed")) }
        }
        // Un suscriptor nuevo sigue recibiendo lo que se publica después
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals("reminder.fire", bus.flow(user).first { it.type == "reminder.fire" }.type)
        }
        bus.publish(user, ServerEvent("reminder.fire"))
        withTimeout(5_000) { next.join() }
        stuck.cancelAndJoin()
    }
}
