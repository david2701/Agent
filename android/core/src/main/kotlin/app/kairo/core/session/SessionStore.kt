package app.kairo.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kairo.core.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionStore by preferencesDataStore(name = "kairo_session")

data class Session(val serverUrl: String, val user: User, val deviceId: String)

/**
 * Device session. The refresh token is encrypted with an AES key from the Android Keystore
 * (it never leaves secure hardware); the access token lives only in memory.
 */
class SessionStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile var accessToken: String? = null

    val session: Flow<Session?> = context.sessionStore.data.map { p -> p.toSession() }

    suspend fun current(): Session? = context.sessionStore.data.first().toSession()

    suspend fun serverUrl(): String? = context.sessionStore.data.first()[SERVER]

    suspend fun refreshToken(): String? = context.sessionStore.data.first()[REFRESH]?.let { runCatching { decrypt(it) }.getOrNull() }

    suspend fun save(serverUrl: String, user: User, deviceId: String, refreshToken: String, access: String) {
        accessToken = access
        context.sessionStore.edit {
            it[SERVER] = serverUrl
            it[USER] = json.encodeToString(User.serializer(), user)
            it[DEVICE] = deviceId
            it[REFRESH] = encrypt(refreshToken)
        }
    }

    suspend fun updateTokens(refreshToken: String, access: String, user: User? = null) {
        accessToken = access
        context.sessionStore.edit {
            it[REFRESH] = encrypt(refreshToken)
            if (user != null) it[USER] = json.encodeToString(User.serializer(), user)
        }
    }

    /** UI tests only (Robolectric has no AndroidKeyStore): stores a session without a real refresh token. */
    @androidx.annotation.VisibleForTesting
    suspend fun saveForTest(serverUrl: String, user: User, deviceId: String, access: String) {
        accessToken = access
        context.sessionStore.edit {
            it[SERVER] = serverUrl
            it[USER] = json.encodeToString(User.serializer(), user)
            it[DEVICE] = deviceId
            it[REFRESH] = "test"
        }
    }

    suspend fun rememberServer(url: String) = context.sessionStore.edit { it[SERVER] = url }

    suspend fun clear() {
        accessToken = null
        context.sessionStore.edit {
            it.remove(USER)
            it.remove(DEVICE)
            it.remove(REFRESH)
        }
    }

    private fun Preferences.toSession(): Session? {
        val server = this[SERVER] ?: return null
        val user = this[USER]?.let { runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull() } ?: return null
        val device = this[DEVICE] ?: return null
        if (this[REFRESH] == null) return null
        return Session(server, user, device)
    }

    // --- Keystore ---

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(token: String): String {
        val (iv, data) = token.split(':').let { it[0] to it[1] }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    companion object {
        private const val ALIAS = "kairo_session_key"
        private val SERVER = stringPreferencesKey("server_url")
        private val USER = stringPreferencesKey("user")
        private val DEVICE = stringPreferencesKey("device_id")
        private val REFRESH = stringPreferencesKey("refresh_token")
    }
}
