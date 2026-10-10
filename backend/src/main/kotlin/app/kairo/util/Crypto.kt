package app.kairo.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM encryption for secrets stored in the database (Google tokens, MCP headers). */
class Crypto(key: ByteArray) {
    private val secretKey = SecretKeySpec(key, "AES")
    private val macKey = SecretKeySpec(sha256Bytes(key + "kairo-hmac".toByteArray()), "HmacSHA256")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:" + b64(iv) + ":" + b64(out)
    }

    fun decrypt(token: String): String {
        val parts = token.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Invalid secret format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, unb64(parts[1])))
        return String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
    }

    fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray()))
    }

    fun verify(data: String, signature: String): Boolean =
        MessageDigest.isEqual(sign(data).toByteArray(), signature.toByteArray())

    companion object {
        private val random = SecureRandom()

        fun randomToken(bytes: Int = 32): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        fun sha256(s: String): String = sha256Bytes(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun sha256Bytes(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)
    }
}
