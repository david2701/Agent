package app.kairo.integrations

import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.realtime.ServerEvent
import app.kairo.util.ApiJson
import app.kairo.util.Crypto
import app.kairo.util.bypassProxy
import app.kairo.util.proxyFromEnv
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
 * Cifrado de mensajes Web Push (RFC 8291, `aes128gcm` de RFC 8188) con claves P-256.
 * Es lo que descifra el conector UnifiedPush de la app; ningún intermediario (ntfy, etc.) puede leer el contenido.
 */
object WebPushCrypto {
    private val random = SecureRandom()
    private const val RECORD_SIZE = 4096

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Punto público sin comprimir: 0x04 || X || Y (65 bytes). */
    fun encodePublic(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)

    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Clave pública P-256 inválida" }
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
    }

    fun encodePrivate(key: ECPrivateKey): ByteArray = fixed(key.s)

    fun decodePrivate(d: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), curve)) as ECPrivateKey

    /**
     * Cifra [plaintext] para el agente de usuario con clave pública [uaPublic] (65 bytes) y secreto [authSecret] (16 bytes).
     * [serverKeys] y [salt] sólo se fijan en pruebas; en uso real son aleatorios en cada mensaje.
     */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        serverKeys: KeyPair = generateKeyPair(),
        salt: ByteArray = ByteArray(16).also { random.nextBytes(it) },
    ): ByteArray {
        require(plaintext.size + 1 + 16 <= RECORD_SIZE) { "Mensaje demasiado grande para Web Push" }
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

    /** Descifra un cuerpo `aes128gcm` (lo usa la prueba; en producción descifra el teléfono). */
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
        require(end >= 0 && padded[end] == 0x02.toByte()) { "Relleno inválido" }
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

    /** Entero sin signo en 32 bytes big-endian. */
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
 * Push propio sin Firebase: envía Web Push cifrado a los endpoints UnifiedPush que registran los teléfonos
 * (por ejemplo un ntfy autoalojado). Firma con VAPID (RFC 8292) usando una clave que se genera una vez
 * y se guarda cifrada en la base de datos.
 */
class PushService(private val db: Db, private val crypto: Crypto, private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(PushService::class.java)
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }
    private val direct = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }
    private val keyLock = Mutex()
    @Volatile private var vapid: Pair<KeyPair, String>? = null

    /** Clave pública VAPID (base64url, 87 caracteres) que la app pasa al registrarse en su distribuidor. */
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
                // Si otra instancia la creó al mismo tiempo, gana la que quedó guardada
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

    suspend fun register(userId: UUID, deviceId: UUID?, endpoint: String, p256dh: String, auth: String) {
        val url = runCatching { Url(endpoint) }.getOrNull()
        val schemeOk = url?.protocol?.name == "https" || (cfg.pushAllowHttp && url?.protocol?.name == "http")
        require(url != null && schemeOk && url.host.isNotBlank()) { "El endpoint de push debe ser una URL https" }
        val pub = runCatching { WebPushCrypto.unb64u(p256dh) }.getOrNull()
        require(pub != null && runCatching { WebPushCrypto.decodePublic(pub) }.isSuccess) { "Clave pública de push inválida" }
        val sec = runCatching { WebPushCrypto.unb64u(auth) }.getOrNull()
        require(sec != null && sec.size == 16) { "Secreto de push inválido" }
        db.tx {
            if (deviceId != null) exec("delete from push_endpoints where device_id = ? and endpoint <> ?", deviceId, endpoint)
            exec(
                """insert into push_endpoints(user_id, device_id, endpoint, p256dh, auth) values (?, ?, ?, ?, ?)
                   on conflict (endpoint) do update set user_id = excluded.user_id, device_id = excluded.device_id,
                   p256dh = excluded.p256dh, auth = excluded.auth, failures = 0""",
                userId, deviceId, endpoint, p256dh, auth,
            )
        }
    }

    suspend fun unregister(userId: UUID, deviceId: UUID?) = db.tx {
        if (deviceId != null) exec("delete from push_endpoints where user_id = ? and device_id = ?", userId, deviceId)
    }

    suspend fun registeredDevices(userId: UUID): Int =
        db.read { one("select count(*) as n from push_endpoints where user_id = ?", userId) { it.getInt("n") } } ?: 0

    /** Envía el evento a todos los teléfonos del usuario con push registrado. Devuelve cuántos lo aceptaron. */
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
                val body = WebPushCrypto.encrypt(payload, WebPushCrypto.unb64u(t.p256dh), WebPushCrypto.unb64u(t.auth))
                val client = if (bypassProxy(Url(t.endpoint).host)) direct else http
                client.post(t.endpoint) {
                    header("TTL", ttlSeconds.toString())
                    header("Urgency", urgency)
                    header("Content-Encoding", "aes128gcm")
                    header("Content-Type", "application/octet-stream")
                    header("Authorization", "vapid t=${vapidJwt(t.endpoint, kp)}, k=$pubB64")
                    setBody(body)
                }.status.value
            }
            val status = result.getOrNull()
            when {
                status != null && status in 200..299 -> {
                    ok++
                    db.tx { exec("update push_endpoints set last_ok_at = now(), failures = 0 where id = ?", t.id) }
                }
                status == 404 || status == 410 -> db.tx { exec("delete from push_endpoints where id = ?", t.id) }
                else -> {
                    log.warn("Push a {} falló: {}", Url(t.endpoint).host, status ?: result.exceptionOrNull()?.message)
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

    private data class PushTarget(val id: UUID, val endpoint: String, val p256dh: String, val auth: String)
}
