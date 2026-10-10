package app.kairo

import app.kairo.domain.FamilyRepo
import app.kairo.integrations.WebPushCrypto
import app.kairo.integrations.WebPushCrypto.b64u
import app.kairo.integrations.WebPushCrypto.unb64u
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPushTest {

    // Vector de ejemplo de RFC 8291, sección 5
    private val plaintext = "When I grow up, I want to be a watermelon"
    private val asPrivate = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    private val asPublic = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val uaPrivate = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
    private val salt = "DGv6ra1nlYgDCS1FRnbzlw"
    private val expected = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    @Test
    fun `cifrado aes128gcm coincide con el ejemplo de RFC 8291`() {
        val server = KeyPair(WebPushCrypto.decodePublic(unb64u(asPublic)), WebPushCrypto.decodePrivate(unb64u(asPrivate)))
        val body = WebPushCrypto.encrypt(plaintext.toByteArray(), unb64u(uaPublic), unb64u(auth), server, unb64u(salt))
        assertEquals(expected, b64u(body))
    }

    @Test
    fun `el agente de usuario descifra el ejemplo de RFC 8291`() {
        val out = WebPushCrypto.decrypt(unb64u(expected), WebPushCrypto.decodePrivate(unb64u(uaPrivate)), unb64u(uaPublic), unb64u(auth))
        assertEquals(plaintext, String(out))
    }

    @Test
    fun `ida y vuelta con claves aleatorias`() {
        val ua = WebPushCrypto.generateKeyPair()
        val uaPub = WebPushCrypto.encodePublic(ua.public as ECPublicKey)
        val secret = ByteArray(16) { it.toByte() }
        val msg = """{"type":"family.notice","data":{"title":"Valentina agregó un evento familiar","body":"Cita médica de Sofía · jue 10:00"}}"""
        val body = WebPushCrypto.encrypt(msg.toByteArray(), uaPub, secret)
        val back = WebPushCrypto.decrypt(body, ua.private as java.security.interfaces.ECPrivateKey, uaPub, secret)
        assertEquals(msg, String(back))
        // Clave pública VAPID en el formato que espera UnifiedPush: 65 bytes en base64url = 87 caracteres
        assertEquals(87, b64u(uaPub).length)
    }

    @Test
    fun `codigos de invitacion toleran guiones, espacios y minusculas`() {
        assertEquals("ABCD2345", FamilyRepo.normalizeCode("abcd-2345"))
        assertEquals("ABCD2345", FamilyRepo.normalizeCode(" ABCD 2345 "))
        assertTrue(FamilyRepo.normalizeCode("ab-cd").all { it.isUpperCase() })
    }
}
