package app.kairo.android

import app.kairo.android.sync.ServerEvents
import app.kairo.android.ui.components.initialsOf
import app.kairo.android.ui.components.sharedLabel
import app.kairo.core.model.DeviceSync
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.Household
import app.kairo.core.model.Member
import app.kairo.core.net.KairoApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El mismo `{type, data}` llega por WebSocket y por push: se decodifica igual en los dos caminos. */
class PushDispatchTest {
    // Misma configuración que KairoApi.json (el backend envía nulos explícitos)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `family notice de push se convierte en aviso con destino`() {
        val payload = """
            {"type":"family.notice","data":{"id":"e2cb03d7-7057-4fe6-8598-53e964df43f4","kind":"assigned",
             "title":"Valentina te asignó una tarea","body":"Renovar el seguro del carro · lunes 19 de octubre",
             "actorId":"507f2f64-4e03-4648-9ae9-e35730e0992d","entityType":"task","entityId":"c36f6bc0-e38a-4b97-988e-e81d77ca7226",
             "createdAt":"2026-10-10T13:58:50.442768Z","readAt":null}}
        """.trimIndent()
        val ev = ServerEvents.decode(json, payload.toByteArray())
        assertNotNull(ev)
        val notice = ServerEvents.notice(json, ev!!)
        assertNotNull(notice)
        val alert = ServerEvents.alert(notice!!)
        assertEquals("e2cb03d7-7057-4fe6-8598-53e964df43f4", alert.id)
        assertEquals("Valentina te asignó una tarea", alert.title)
        assertEquals("Renovar el seguro del carro · lunes 19 de octubre", alert.body)
        assertEquals("tasks", alert.destination)
        assertEquals("assigned", alert.kind)
    }

    @Test
    fun `prueba de push sin entidad abre Familia`() {
        val payload = """{"type":"family.notice","data":{"id":"7d1","kind":"test","title":"Push de Kairo funcionando","body":"Este aviso llegó sin Firebase.","createdAt":"2026-10-10T14:00:00Z"}}"""
        val alert = ServerEvents.decode(json, payload)?.let { ServerEvents.notice(json, it) }?.let(ServerEvents::alert)
        assertNotNull(alert)
        assertEquals("family", alert!!.destination)
        assertEquals("Push de Kairo funcionando", alert.title)
    }

    @Test
    fun `otros eventos y basura no son avisos`() {
        val fire = ServerEvents.decode(json, """{"type":"reminder.fire","data":{"id":"r1","text":"Pagar EPM","fireAt":"2026-10-11T14:00:00Z","recurring":false,"createdBy":null}}""")
        assertEquals("reminder.fire", fire?.type)
        assertNull(ServerEvents.notice(json, fire!!))
        // Contenido que el conector no pudo descifrar
        assertNull(ServerEvents.decode(json, byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x42)))
        assertNull(ServerEvents.decode(json, """{"data":{}}"""))
        // family.notice sin id: se ignora
        val noId = ServerEvents.decode(json, """{"type":"family.notice","data":{"title":"x"}}""")!!
        assertNull(ServerEvents.notice(json, noId))
    }

    @Test
    fun `destinos de cada tipo de entidad`() {
        assertEquals("notes", ServerEvents.destinationFor("note"))
        assertEquals("calendar", ServerEvents.destinationFor("event"))
        assertEquals("lists", ServerEvents.destinationFor("list"))
        assertEquals("reminders", ServerEvents.destinationFor("reminder"))
        assertEquals("family", ServerEvents.destinationFor("family"))
        assertEquals("family", ServerEvents.destinationFor(null))
    }

    @Test
    fun `sync device trae avisos y estado del push`() {
        val body = """
            {"serverTime":"2026-10-10T14:13:58Z","reminders":[],"places":[],
             "eventAlerts":[{"eventId":"e1","title":"Almuerzo familiar","startAt":"2026-10-10T18:00:00Z","alertAt":"2026-10-10T17:45:00Z","minutes":15,"location":"Envigado","shared":true}],
             "briefingEnabled":true,"briefingTime":"07:00",
             "notices":[{"id":"n1","kind":"message","title":"Valentina","body":"Hoy llego tarde","actorId":"u2","entityType":null,"entityId":null,"createdAt":"2026-10-10T13:58:50Z","readAt":null}],
             "pushRegistered":false}
        """.trimIndent()
        val sync = json.decodeFromString(DeviceSync.serializer(), body)
        assertEquals(1, sync.notices.size)
        assertEquals("family", ServerEvents.alert(sync.notices.first()).destination)
        assertTrue(sync.eventAlerts.first().shared)
        assertEquals(false, sync.pushRegistered)
    }

    @Test
    fun `codigos de familia sin importar mayusculas ni guion`() {
        assertEquals("ABCD-2345", KairoApi.normalizeCode("abcd2345"))
        assertEquals("ABCD-2345", KairoApi.normalizeCode(" abcd - 2345 "))
        assertEquals("ABCD-2345", KairoApi.normalizeCode("ABCD 2345"))
        assertEquals("ABC", KairoApi.normalizeCode("a-b-c"))
    }

    @Test
    fun `etiquetas de lo compartido`() {
        val family = FamilyInfo(
            Household("h", "Nuestra familia", "2026-10-10T00:00:00Z"),
            listOf(Member("u1", "David", "d@x", "admin", ""), Member("u2", "Valentina Ríos", "v@x", "member", "")),
            me = "u1", isAdmin = true,
        )
        assertNull(sharedLabel(false, "u2", family))
        assertEquals("Familiar", sharedLabel(true, "u1", family))
        assertEquals("de Valentina", sharedLabel(true, "u2", family))
        assertEquals("VR", initialsOf("Valentina Ríos"))
        assertEquals("D", initialsOf("David"))
    }
}
