package app.kairo.integrations

import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.realtime.ServerEvent
import app.kairo.util.ApiJson
import app.kairo.util.Crypto
import app.kairo.util.InvalidInput
import app.kairo.util.M
import app.kairo.util.SafeUrl
import app.kairo.util.bypassProxy
import app.kairo.util.logSafe
import app.kairo.util.proxyFromEnv
import app.kairo.util.requireInput
import app.kairo.util.readCapped
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push message encryption (RFC 8291, `aes128gcm` of RFC 8188) with P-256 keys.
 * It is what the app's UnifiedPush connector decrypts; no intermediary (ntfy, etc.) can read the content.
 */
object WebPushCrypto {
    private val random = SecureRandom()
    private const val RECORD_SIZE = 4096

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Uncompressed public point: 0x04 || X || Y (65 bytes). */
    fun encodePublic(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)

    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Invalid P-256 public key" }
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
    }

    fun encodePrivate(key: ECPrivateKey): ByteArray = fixed(key.s)

    fun decodePrivate(d: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), curve)) as ECPrivateKey

    /**
     * Encrypts [plaintext] for the user agent with public key [uaPublic] (65 bytes) and secret [authSecret] (16 bytes).
     * [serverKeys] and [salt] are only fixed in tests; in real use they are random for every message.
     */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        serverKeys: KeyPair = generateKeyPair(),
        salt: ByteArray = ByteArray(16).also { random.nextBytes(it) },
    ): ByteArray {
        require(plaintext.size + 1 + 16 <= RECORD_SIZE) { "Message too large for Web Push" }
        val asPublic = encodePublic(serverKeys.public as ECPublicKey)
        val ecdh = KeyAgreement.getInstance("ECDH").apply {
            init(serverKeys.private)
            doPhase(decodePublic(uaPublic), true)
        }.generateSecret()
        val (cek, nonce) = deriveKeys(ecdh, authSecret, uaPublic, asPublic, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val ciphertext = cipher.doFinal(plaintext + byteArrayOf(0x02))
        val header = salt + int32(RECORD_SIZE) + byteArrayOf(asPublic.size.toByte()) + asPublic
        return header + ciphertext
    }

    /** Decrypts an `aes128gcm` body (used by the test; in production the phone decrypts). */
    fun decrypt(body: ByteArray, uaPrivate: ECPrivateKey, uaPublic: ByteArray, authSecret: ByteArray): ByteArray {
        val salt = body.copyOfRange(0, 16)
        val idLen = body[20].toInt() and 0xff
        val asPublic = body.copyOfRange(21, 21 + idLen)
        val ciphertext = body.copyOfRange(21 + idLen, body.size)
        val ecdh = KeyAgreement.getInstance("ECDH").apply {
            init(uaPrivate)
            doPhase(decodePublic(asPublic), true)
        }.generateSecret()
        val (cek, nonce) = deriveKeys(ecdh, authSecret, uaPublic, asPublic, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val padded = cipher.doFinal(ciphertext)
        val end = padded.indexOfLast { it != 0.toByte() }
        require(end >= 0 && padded[end] == 0x02.toByte()) { "Invalid padding" }
        return padded.copyOfRange(0, end)
    }

    private fun deriveKeys(ecdh: ByteArray, auth: ByteArray, uaPublic: ByteArray, asPublic: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic
        val prkKey = hmac(auth, ecdh)
        val ikm = hmac(prkKey, keyInfo + byteArrayOf(1)).copyOf(32)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        return cek to nonce
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Unsigned integer in 32 big-endian bytes. */
    private fun fixed(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))
}

/**
 * Own push without Firebase: sends encrypted Web Push to the UnifiedPush endpoints the phones register
 * (for example a self-hosted ntfy). It signs with VAPID (RFC 8292) using a key that is generated once
 * and stored encrypted in the database.
 *
 * The endpoint is chosen by the phone, so it is validated against SSRF ([SafeUrl]) when it is registered and before every send:
 * public hosts only, except the own ntfy (host of NTFY_PUBLIC_URL or NTFY_URL, which may be on the local network)
 * or with KAIRO_PUSH_ALLOW_PRIVATE=true (any private-network host; never loopback or link-local).
 */
class PushService(private val db: Db, private val crypto: Crypto, private val cfg: AppConfig, env: Map<String, String> = System.getenv()) {
    private val log = LoggerFactory.getLogger(PushService::class.java)
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        followRedirects = false
        expectSuccess = false
    }
    /** No proxy: only for the own ntfy when it is on the local network or in NO_PROXY. */
    private val direct = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        followRedirects = false
        expectSuccess = false
    }
    /** ntfy hosts the operator configured. */
    private val ntfyHosts: Set<String> = ntfyHosts(env["NTFY_PUBLIC_URL"], cfg.ntfyUrl)
    private val endpointPolicy = SafeUrl.Policy(
        allowPrivate = env["KAIRO_PUSH_ALLOW_PRIVATE"]?.trim()?.toBooleanStrictOrNull() ?: false,
        trustedHosts = ntfyHosts,
    )
    private val keyLock = Mutex()
    @Volatile private var vapid: Pair<KeyPair, String>? = null

    /** VAPID public key (base64url, 87 characters) the app hands to its distributor when registering. */
    suspend fun vapidPublicKey(): String = keys().second

    private suspend fun keys(): Pair<KeyPair, String> = vapid ?: keyLock.withLock {
        vapid ?: run {
            val pair = keysFromDb() ?: run {
                val kp = WebPushCrypto.generateKeyPair()
                val value = buildJsonObject {
                    put("pub", WebPushCrypto.b64u(WebPushCrypto.encodePublic(kp.public as ECPublicKey)))
                    put("d", WebPushCrypto.b64u(WebPushCrypto.encodePrivate(kp.private as ECPrivateKey)))
                }.toString()
                db.tx { exec("insert into server_secrets(name, value) values ('vapid', ?) on conflict (name) do nothing", crypto.encrypt(value)) }
                // If another instance created it at the same time, the one that got stored wins
                keysFromDb() ?: kp
            }
            (pair to WebPushCrypto.b64u(WebPushCrypto.encodePublic(pair.public as ECPublicKey))).also { vapid = it }
        }
    }

    private suspend fun keysFromDb(): KeyPair? {
        val stored = db.read { one("select value from server_secrets where name = 'vapid'") { it.str("value") } } ?: return null
        val o = Json.parseToJsonElement(crypto.decrypt(stored)).jsonObject
        return KeyPair(
            WebPushCrypto.decodePublic(WebPushCrypto.unb64u(o["pub"]!!.jsonPrimitive.content)),
            WebPushCrypto.decodePrivate(WebPushCrypto.unb64u(o["d"]!!.jsonPrimitive.content)),
        )
    }

    /**
     * Checks an endpoint: https (or http with KAIRO_PUSH_ALLOW_HTTP) and an allowed destination.
     * Returns the parsed URL; throws an [InvalidInput] (message for the user) if it is not usable.
     */
    private suspend fun checkEndpoint(endpoint: String): Url {
        val checked = try {
            SafeUrl.check(endpoint, endpointPolicy)
        } catch (e: SafeUrl.UnsafeUrlException) {
            throw InvalidInput(if (e.blockedAddress) M.PUSH_ENDPOINT_BLOCKED else M.PUSH_ENDPOINT_NOT_HTTPS)
        }
        val scheme = checked.url.protocol.name
        requireInput(scheme == "https" || (cfg.pushAllowHttp && scheme == "http"), M.PUSH_ENDPOINT_NOT_HTTPS)
        return checked.url
    }

    suspend fun register(userId: UUID, deviceId: UUID?, endpoint: String, p256dh: String, auth: String) {
        checkEndpoint(endpoint)
        val pub = runCatching { WebPushCrypto.unb64u(p256dh) }.getOrNull()
        requireInput(pub != null && runCatching { WebPushCrypto.decodePublic(pub) }.isSuccess, M.PUSH_BAD_KEY)
        val sec = runCatching { WebPushCrypto.unb64u(auth) }.getOrNull()
        requireInput(sec != null && sec.size == 16, M.PUSH_BAD_SECRET)
        db.tx {
            if (deviceId != null) exec("delete from push_endpoints where device_id = ? and endpoint <> ?", deviceId, endpoint)
            // An endpoint of another account is not reassigned: this way nobody can take someone else's push away or receive theirs
            val n = exec(
                """insert into push_endpoints(user_id, device_id, endpoint, p256dh, auth) values (?, ?, ?, ?, ?)
                   on conflict (endpoint) do update set device_id = excluded.device_id,
                   p256dh = excluded.p256dh, auth = excluded.auth, failures = 0
                   where push_endpoints.user_id = excluded.user_id""",
                userId, deviceId, endpoint, p256dh, auth,
            )
            requireInput(n > 0, M.PUSH_ENDPOINT_TAKEN)
        }
    }

    suspend fun unregister(userId: UUID, deviceId: UUID?) = db.tx {
        if (deviceId != null) exec("delete from push_endpoints where user_id = ? and device_id = ?", userId, deviceId)
    }

    suspend fun registeredDevices(userId: UUID): Int =
        db.read { one("select count(*) as n from push_endpoints where user_id = ?", userId) { it.getInt("n") } } ?: 0

    /** Sends the event to all the user's phones with registered push. Returns how many accepted it. */
    suspend fun send(userId: UUID, event: ServerEvent, urgency: String = "high", ttlSeconds: Int = 24 * 3600): Int {
        val targets = db.read {
            query("select id, endpoint, p256dh, auth from push_endpoints where user_id = ?", userId) {
                PushTarget(it.uuid("id"), it.str("endpoint"), it.str("p256dh"), it.str("auth"))
            }
        }
        if (targets.isEmpty()) return 0
        val payload = ApiJson.encodeToString(ServerEvent.serializer(), event).toByteArray()
        val (kp, pubB64) = keys()
        var ok = 0
        for (t in targets) {
            val result = runCatching {
                // Validated again on every send: DNS may have changed since registration
                val url = checkEndpoint(t.endpoint)
                val body = WebPushCrypto.encrypt(payload, WebPushCrypto.unb64u(t.p256dh), WebPushCrypto.unb64u(t.auth))
                val client = if (useDirect(url.host)) direct else http
                client.preparePost(url) {
                    header("TTL", ttlSeconds.toString())
                    header("Urgency", urgency)
                    header("Content-Encoding", "aes128gcm")
                    header("Content-Type", "application/octet-stream")
                    header("Authorization", "vapid t=${vapidJwt(t.endpoint, kp)}, k=$pubB64")
                    setBody(body)
                }.execute { resp ->
                    // Only the code matters; at most MAX_RESPONSE_BYTES of the body are read and discarded
                    resp.bodyAsChannel().readCapped(MAX_RESPONSE_BYTES)
                    resp.status.value
                }
            }
            val status = result.getOrNull()
            when {
                status != null && status in 200..299 -> {
                    ok++
                    db.tx { exec("update push_endpoints set last_ok_at = now(), failures = 0 where id = ?", t.id) }
                }
                status == 404 || status == 410 -> db.tx { exec("delete from push_endpoints where id = ?", t.id) }
                else -> {
                    log.warn("Push to {} failed: {}", logSafe(runCatching { Url(t.endpoint).host }.getOrDefault("?"), 120), logSafe(status ?: result.exceptionOrNull()?.message))
                    db.tx { exec("update push_endpoints set failures = failures + 1 where id = ?", t.id) }
                    db.tx { exec("delete from push_endpoints where id = ? and failures > 50", t.id) }
                }
            }
        }
        return ok
    }

    private fun vapidJwt(endpoint: String, kp: KeyPair): String {
        val u = Url(endpoint)
        val audience = URLBuilder(protocol = u.protocol, host = u.host, port = u.port).buildString().trimEnd('/')
        val subject = if (cfg.publicUrl.startsWith("https://")) cfg.publicUrl else "mailto:kairo@localhost"
        return JWT.create()
            .withAudience(audience)
            .withSubject(subject)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(12 * 3600)))
            .sign(Algorithm.ECDSA256(kp.public as ECPublicKey, kp.private as ECPrivateKey))
    }

    /** No proxy only for the own ntfy (and only if it is local or in NO_PROXY); everything else goes through the proxy if there is one. */
    private fun useDirect(host: String): Boolean = SafeUrl.normalizeHost(host) in ntfyHosts && bypassProxy(host)

    private data class PushTarget(val id: UUID, val endpoint: String, val p256dh: String, val auth: String)

    companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024

        /** Hosts of the configured ntfy URLs (NTFY_PUBLIC_URL for the phones, NTFY_URL for the server). */
        fun ntfyHosts(vararg urls: String?): Set<String> = urls.filterNotNull()
            .mapNotNull { raw ->
                // java.net.URI and not Ktor's Url: Ktor completes a text that is not a URL with "localhost"
                runCatching { java.net.URI(raw.trim()) }.getOrNull()
                    ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") }
                    ?.host?.let(SafeUrl::normalizeHost)
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
