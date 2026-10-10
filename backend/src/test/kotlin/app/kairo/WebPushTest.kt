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

    // Example vector from RFC 8291, section 5
    private val plaintext = "When I grow up, I want to be a watermelon"
    private val asPrivate = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    private val asPublic = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val uaPrivate = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
    private val salt = "DGv6ra1nlYgDCS1FRnbzlw"
    private val expected = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    @Test
    fun `aes128gcm encryption matches the RFC 8291 example`() {
        val server = KeyPair(WebPushCrypto.decodePublic(unb64u(asPublic)), WebPushCrypto.decodePrivate(unb64u(asPrivate)))
        val body = WebPushCrypto.encrypt(plaintext.toByteArray(), unb64u(uaPublic), unb64u(auth), server, unb64u(salt))
        assertEquals(expected, b64u(body))
    }

    @Test
    fun `the user agent decrypts the RFC 8291 example`() {
        val out = WebPushCrypto.decrypt(unb64u(expected), WebPushCrypto.decodePrivate(unb64u(uaPrivate)), unb64u(uaPublic), unb64u(auth))
        assertEquals(plaintext, String(out))
    }

    @Test
    fun `round trip with random keys`() {
        val ua = WebPushCrypto.generateKeyPair()
        val uaPub = WebPushCrypto.encodePublic(ua.public as ECPublicKey)
        val secret = ByteArray(16) { it.toByte() }
        val msg = """{"type":"family.notice","data":{"title":"Sam added a family event","body":"Mia's checkup · Thu 10:00 AM"}}"""
        val body = WebPushCrypto.encrypt(msg.toByteArray(), uaPub, secret)
        val back = WebPushCrypto.decrypt(body, ua.private as java.security.interfaces.ECPrivateKey, uaPub, secret)
        assertEquals(msg, String(back))
        // VAPID public key in the format UnifiedPush expects: 65 bytes in base64url = 87 characters
        assertEquals(87, b64u(uaPub).length)
    }

    @Test
    fun `invite codes tolerate dashes, spaces and lowercase`() {
        assertEquals("ABCD2345", FamilyRepo.normalizeCode("abcd-2345"))
        assertEquals("ABCD2345", FamilyRepo.normalizeCode(" ABCD 2345 "))
        assertTrue(FamilyRepo.normalizeCode("ab-cd").all { it.isUpperCase() })
    }
}
