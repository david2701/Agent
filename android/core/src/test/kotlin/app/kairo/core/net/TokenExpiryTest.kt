package app.kairo.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TokenExpiryTest {
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".signature"
    }

    @Test
    fun `valid token is not renewed`() {
        assertFalse(KairoApi.expiresSoon(jwt("""{"sub":"u","exp":2000}"""), nowSeconds = 1000))
    }

    @Test
    fun `expiring or expired token is renewed`() {
        assertTrue(KairoApi.expiresSoon(jwt("""{"sub":"u","exp":1030}"""), nowSeconds = 1000))
        assertTrue(KairoApi.expiresSoon(jwt("""{"sub":"u","exp":900}"""), nowSeconds = 1000))
    }

    @Test
    fun `unreadable token is renewed`() {
        assertTrue(KairoApi.expiresSoon("not-a-jwt"))
        assertTrue(KairoApi.expiresSoon(jwt("""{"sub":"u"}""")))
    }
}
