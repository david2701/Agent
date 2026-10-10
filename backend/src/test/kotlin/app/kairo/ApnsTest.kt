package app.kairo

import app.kairo.integrations.ApnsJwt
import app.kairo.integrations.ApnsPayload
import app.kairo.integrations.ApnsService
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApnsTest {
    private val reminder = ServerEvent(EventTypes.REMINDER_FIRE, buildJsonObject {
        put("id", "737cb3a7-c903-4339-ab6c-e07e06ff8e81")
        put("text", "Llevar el carnet de salud")
        put("fireAt", "2026-10-13T19:00:00Z")
        put("recurring", false)
    })

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `payload con alerta, aps y el mismo evento del WebSocket`() {
        val p = obj(ApnsPayload.build(reminder, "Kairo", "Llevar el carnet de salud"))
        val aps = p["aps"]!!.jsonObject
        val alert = aps["alert"]!!.jsonObject
        assertEquals("Kairo", alert["title"]!!.jsonPrimitive.content)
        assertEquals("Llevar el carnet de salud", alert["body"]!!.jsonPrimitive.content)
        assertEquals("default", aps["sound"]!!.jsonPrimitive.content)
        assertEquals("reminder", aps["thread-id"]!!.jsonPrimitive.content)
        assertEquals("time-sensitive", aps["interruption-level"]!!.jsonPrimitive.content)
        assertEquals("kairo.reminder", aps["category"]!!.jsonPrimitive.content)
        assertEquals(1, aps["content-available"]!!.jsonPrimitive.int)
        val kairo = p["kairo"]!!.jsonObject
        assertEquals(EventTypes.REMINDER_FIRE, kairo["type"]!!.jsonPrimitive.content)
        assertEquals(reminder.data, kairo["data"]!!.jsonObject)
        assertEquals("737cb3a7-c903-4339-ab6c-e07e06ff8e81", p["entityId"]!!.jsonPrimitive.content)
        assertEquals("reminders", p["destination"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nivel de interrupcion e hilo por tipo de evento`() {
        val alert = ServerEvent(EventTypes.EVENT_ALERT, buildJsonObject { put("id", "e1"); put("startAt", "2026-10-10T18:00:00Z"); put("minutes", 15) })
        val notice = ServerEvent(EventTypes.FAMILY_NOTICE, buildJsonObject { put("id", "n1"); put("entityType", "list"); put("entityId", "l1") })
        val brief = ServerEvent(EventTypes.BRIEFING_READY, buildJsonObject { put("text", "Hoy tienes dos citas"); put("date", "2026-10-10") })
        assertEquals("time-sensitive", ApnsPayload.interruptionLevel(alert))
        assertEquals("active", ApnsPayload.interruptionLevel(notice))
        assertEquals("active", ApnsPayload.interruptionLevel(brief))
        assertEquals("event", ApnsPayload.threadId(alert))
        assertEquals("family", ApnsPayload.threadId(notice))
        val n = obj(ApnsPayload.build(notice, "David compartió una lista", "Supermercado"))
        assertEquals("lists", n["destination"]!!.jsonPrimitive.content)
        assertEquals("l1", n["entityId"]!!.jsonPrimitive.content)
        assertNull(n["aps"]!!.jsonObject["category"])
    }

    @Test
    fun `collapse id coincide con la clave de deduplicacion de la app`() {
        val fireAt = Instant.parse("2026-10-13T19:00:00Z").epochSecond
        assertEquals("rem:737cb3a7-c903-4339-ab6c-e07e06ff8e81:$fireAt", ApnsPayload.collapseId(reminder))
        val alert = ServerEvent(EventTypes.EVENT_ALERT, buildJsonObject {
            put("id", "638a350c-ba3d-4e42-bbc6-34e42f833d63"); put("startAt", "2026-10-10T18:00:00Z"); put("minutes", 15)
        })
        assertEquals("ev:638a350c-ba3d-4e42-bbc6-34e42f833d63:${Instant.parse("2026-10-10T18:00:00Z").epochSecond}:15", ApnsPayload.collapseId(alert))
        assertEquals("brief:2026-10-10", ApnsPayload.collapseId(ServerEvent(EventTypes.BRIEFING_READY, buildJsonObject { put("date", "2026-10-10") })))
        assertEquals("notice:n1", ApnsPayload.collapseId(ServerEvent(EventTypes.FAMILY_NOTICE, buildJsonObject { put("id", "n1") })))
        assertNull(ApnsPayload.collapseId(ServerEvent(EventTypes.BRIEFING_READY)))
        assertNull(ApnsPayload.collapseId(ServerEvent(EventTypes.FAMILY_NOTICE, buildJsonObject { put("id", "x".repeat(80)) })))
    }

    @Test
    fun `payload nunca pasa de 4 KB y recorta el texto largo`() {
        val long = "Buenos días ☀️ Hoy tienes 📅 cita odontológica, ñandú y café. ".repeat(300) // ~18 KB en UTF-8
        val brief = ServerEvent(EventTypes.BRIEFING_READY, buildJsonObject { put("text", long); put("date", "2026-10-10") })
        val json = ApnsPayload.build(brief, "Tu día", long)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= ApnsPayload.MAX_BYTES, "ocupa ${json.toByteArray().size} bytes")
        assertTrue(json.toByteArray(Charsets.UTF_8).size > ApnsPayload.MAX_BYTES - 600, "no recorta de más")
        val p = obj(json)
        val body = p["aps"]!!.jsonObject["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content
        assertTrue(body.endsWith("…") && body.length < long.length)
        assertEquals(body, String(body.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "no corta un emoji a la mitad")
        val data = p["kairo"]!!.jsonObject["data"]!!.jsonObject
        assertEquals("2026-10-10", data["date"]!!.jsonPrimitive.content) // los campos cortos quedan intactos
        assertTrue(data["text"]!!.jsonPrimitive.content.endsWith("…"))
        assertEquals("briefing", p["aps"]!!.jsonObject["thread-id"]!!.jsonPrimitive.content)
        assertEquals("kairo.briefing", p["aps"]!!.jsonObject["category"]!!.jsonPrimitive.content)

        // Un aviso corto no se toca
        val short = ApnsPayload.build(reminder, "Kairo", "Llevar el carnet de salud")
        assertFalse(short.contains("…"))
        // Con un límite menor también cabe (y sigue siendo JSON válido)
        assertTrue(ApnsPayload.build(brief, "Tu día", long, maxBytes = 1024).toByteArray().size <= 1024)
        val tiny = obj(ApnsPayload.build(brief, "Tu día", long, maxBytes = 400))
        assertEquals(EventTypes.BRIEFING_READY, tiny["kairo"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jwt de proveedor ES256 con kid, iss e iat`() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        // Como el .p8 de Apple, pegado en una sola línea con \n literales (APNS_PRIVATE_KEY)
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        val key = ApnsJwt.parsePrivateKey(pem.replace("\n", "\\n"))
        assertEquals((kp.private as ECPrivateKey).s, key.s)
        assertEquals((kp.private as ECPrivateKey).s, ApnsJwt.parsePrivateKey(pem).s)

        var now = Instant.parse("2026-10-10T12:00:00Z")
        val signer = ApnsJwt("ABC123DEFG", "TEAM123456", key) { now }
        val token = signer.token()
        val decoded = JWT.require(Algorithm.ECDSA256(kp.public as ECPublicKey, null)).withIssuer("TEAM123456").build().verify(token)
        assertEquals("ES256", decoded.algorithm)
        assertEquals("ABC123DEFG", decoded.keyId)
        assertEquals("TEAM123456", decoded.issuer)
        assertEquals(now, decoded.issuedAtAsInstant)
        assertEquals(64, Base64.getUrlDecoder().decode(decoded.signature).size) // firma JOSE (R||S), no DER

        // Se reutiliza ~50 minutos y luego se renueva
        now = now.plus(Duration.ofMinutes(49))
        assertEquals(token, signer.token())
        now = now.plus(Duration.ofMinutes(2))
        val renewed = signer.token()
        assertNotEquals(token, renewed)
        assertEquals(now, JWT.decode(renewed).issuedAtAsInstant)
    }

    @Test
    fun `renovacion forzada respeta el minimo de 20 minutos`() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var now = Instant.parse("2026-10-10T12:00:00Z")
        val signer = ApnsJwt("K", "T", kp.private as ECPrivateKey) { now }
        val first = signer.token()
        now = now.plus(Duration.ofMinutes(5))
        assertFalse(signer.invalidate())
        assertEquals(first, signer.token())
        now = now.plus(Duration.ofMinutes(20))
        assertTrue(signer.invalidate())
        assertNotEquals(first, signer.token())
    }

    @Test
    fun `claves invalidas se rechazan con un mensaje claro`() {
        assertFailsWith<IllegalArgumentException> { ApnsJwt.parsePrivateKey("no es una clave") }
        assertFailsWith<IllegalArgumentException> { ApnsJwt.parsePrivateKey("-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----") }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<IllegalArgumentException> { ApnsJwt.parsePrivateKey(Base64.getEncoder().encodeToString(rsa.private.encoded)) }
    }

    @Test
    fun `tokens de dispositivo y entornos`() {
        val hex = "a1".repeat(32)
        assertEquals(hex, ApnsService.normalizeToken(hex))
        assertEquals(hex, ApnsService.normalizeToken(" <" + hex.uppercase().chunked(8).joinToString(" ") + "> "))
        assertEquals("ab".repeat(100), ApnsService.normalizeToken("ab".repeat(100)))
        assertNull(ApnsService.normalizeToken("ab".repeat(31)))   // 62 caracteres
        assertNull(ApnsService.normalizeToken("a".repeat(201)))
        assertNull(ApnsService.normalizeToken("z".repeat(64)))
        assertNull(ApnsService.normalizeToken(hex + "/../x"))
        assertEquals("sandbox", ApnsService.normalizeEnvironment("sandbox"))
        assertEquals("sandbox", ApnsService.normalizeEnvironment("development"))
        assertEquals("production", ApnsService.normalizeEnvironment("Production"))
        assertNull(ApnsService.normalizeEnvironment("staging"))
    }
}
