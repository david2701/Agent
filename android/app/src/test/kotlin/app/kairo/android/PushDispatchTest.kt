package app.kairo.android

import app.kairo.android.sync.ServerEvents
import app.kairo.android.ui.components.initialsOf
import app.kairo.android.ui.components.SharedBy
import app.kairo.android.ui.components.sharedBy
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

/** The same `{type, data}` arrives over WebSocket and push: it is decoded the same way on both paths. */
class PushDispatchTest {
    // Same configuration as KairoApi.json (the backend sends explicit nulls)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `push family notice becomes an alert with a destination`() {
        val payload = """
            {"type":"family.notice","data":{"id":"e2cb03d7-7057-4fe6-8598-53e964df43f4","kind":"assigned",
             "title":"Sam assigned you a task","body":"Renew the car insurance \u00B7 Monday, October 19",
             "actorId":"507f2f64-4e03-4648-9ae9-e35730e0992d","entityType":"task","entityId":"c36f6bc0-e38a-4b97-988e-e81d77ca7226",
             "createdAt":"2026-10-10T13:58:50.442768Z","readAt":null}}
        """.trimIndent()
        val ev = ServerEvents.decode(json, payload.toByteArray())
        assertNotNull(ev)
        val notice = ServerEvents.notice(json, ev!!)
        assertNotNull(notice)
        val alert = ServerEvents.alert(notice!!)
        assertEquals("e2cb03d7-7057-4fe6-8598-53e964df43f4", alert.id)
        assertEquals("Sam assigned you a task", alert.title)
        assertEquals("Renew the car insurance \u00B7 Monday, October 19", alert.body)
        assertEquals("tasks", alert.destination)
        assertEquals("assigned", alert.kind)
    }

    @Test
    fun `push test without an entity opens Family`() {
        val payload = """{"type":"family.notice","data":{"id":"7d1","kind":"test","title":"Kairo push is working","body":"This notice arrived without Firebase.","createdAt":"2026-10-10T14:00:00Z"}}"""
        val alert = ServerEvents.decode(json, payload)?.let { ServerEvents.notice(json, it) }?.let(ServerEvents::alert)
        assertNotNull(alert)
        assertEquals("family", alert!!.destination)
        assertEquals("Kairo push is working", alert.title)
    }

    @Test
    fun `other events and garbage are not notices`() {
        val fire = ServerEvents.decode(json, """{"type":"reminder.fire","data":{"id":"r1","text":"Pay the electricity bill","fireAt":"2026-10-11T14:00:00Z","recurring":false,"createdBy":null}}""")
        assertEquals("reminder.fire", fire?.type)
        assertNull(ServerEvents.notice(json, fire!!))
        // Content the connector could not decrypt
        assertNull(ServerEvents.decode(json, byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x42)))
        assertNull(ServerEvents.decode(json, """{"data":{}}"""))
        // family.notice without an id is ignored
        val noId = ServerEvents.decode(json, """{"type":"family.notice","data":{"title":"x"}}""")!!
        assertNull(ServerEvents.notice(json, noId))
    }

    @Test
    fun `destination of each entity type`() {
        assertEquals("notes", ServerEvents.destinationFor("note"))
        assertEquals("calendar", ServerEvents.destinationFor("event"))
        assertEquals("lists", ServerEvents.destinationFor("list"))
        assertEquals("reminders", ServerEvents.destinationFor("reminder"))
        assertEquals("family", ServerEvents.destinationFor("family"))
        assertEquals("family", ServerEvents.destinationFor(null))
    }

    @Test
    fun `device sync brings notices and push state`() {
        val body = """
            {"serverTime":"2026-10-10T14:13:58Z","reminders":[],"places":[],
             "eventAlerts":[{"eventId":"e1","title":"Family lunch","startAt":"2026-10-10T18:00:00Z","alertAt":"2026-10-10T17:45:00Z","minutes":15,"location":"Downtown","shared":true}],
             "briefingEnabled":true,"briefingTime":"07:00",
             "notices":[{"id":"n1","kind":"message","title":"Sam","body":"I will be late today","actorId":"u2","entityType":null,"entityId":null,"createdAt":"2026-10-10T13:58:50Z","readAt":null}],
             "pushRegistered":false}
        """.trimIndent()
        val sync = json.decodeFromString(DeviceSync.serializer(), body)
        assertEquals(1, sync.notices.size)
        assertEquals("family", ServerEvents.alert(sync.notices.first()).destination)
        assertTrue(sync.eventAlerts.first().shared)
        assertEquals(false, sync.pushRegistered)
    }

    @Test
    fun `family codes ignore case and dashes`() {
        assertEquals("ABCD-2345", KairoApi.normalizeCode("abcd2345"))
        assertEquals("ABCD-2345", KairoApi.normalizeCode(" abcd - 2345 "))
        assertEquals("ABCD-2345", KairoApi.normalizeCode("ABCD 2345"))
        assertEquals("ABC", KairoApi.normalizeCode("a-b-c"))
    }

    @Test
    fun `labels of shared items`() {
        val family = FamilyInfo(
            Household("h", "Our family", "2026-10-10T00:00:00Z"),
            listOf(Member("u1", "Alex", "d@x", "admin", ""), Member("u2", "Sam Rios", "v@x", "member", "")),
            me = "u1", isAdmin = true,
        )
        assertNull(sharedBy(false, "u2", family))
        assertEquals(SharedBy.Family, sharedBy(true, "u1", family))
        assertEquals(SharedBy.Member("Sam"), sharedBy(true, "u2", family))
        assertEquals("SR", initialsOf("Sam Rios"))
        assertEquals("A", initialsOf("Alex"))
    }
}
