package app.kairo.auth

import app.kairo.config.AppConfig
import app.kairo.domain.User
import app.kairo.domain.UsersRepo
import app.kairo.util.Crypto
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Date
import java.util.UUID

class AuthException(message: String) : RuntimeException(message)

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val deviceId: UUID)

/**
 * Autenticación de un único propietario (preparada para varios usuarios).
 * - Contraseñas con bcrypt (coste 12).
 * - Access token JWT de 1 hora con el id del dispositivo.
 * - Refresh token opaco por dispositivo, guardado como hash SHA-256 y rotado en cada uso.
 */
class AuthService(private val cfg: AppConfig, private val users: UsersRepo) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val algorithm = Algorithm.HMAC256(cfg.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    private val dummyHash by lazy { hash(Crypto.randomToken(16)) }

    fun hash(password: String): String = BCrypt.withDefaults().hashToString(12, password.toCharArray())

    fun check(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    /** Crea la cuenta del propietario en el primer arranque a partir de KAIRO_OWNER_EMAIL/PASSWORD. */
    suspend fun bootstrapOwner() {
        if (users.count() > 0) return
        val email = cfg.ownerEmail
        val password = cfg.ownerPassword
        if (email == null || password == null) {
            log.warn("No hay usuarios. Define KAIRO_OWNER_EMAIL y KAIRO_OWNER_PASSWORD para crear la cuenta del propietario.")
            return
        }
        require(password.length >= 10) { "KAIRO_OWNER_PASSWORD debe tener al menos 10 caracteres" }
        users.create(email, cfg.ownerName, hash(password), cfg.defaultTimezone)
        log.info("Cuenta del propietario creada para {}", email)
    }

    suspend fun login(email: String, password: String, deviceName: String, platform: String): Pair<User, TokenPair> {
        val found = users.byEmail(email)
        // Comparación con hash ficticio para no revelar si el correo existe por tiempos de respuesta.
        val ok = if (found != null) check(password, found.passwordHash) else { check(password, dummyHash); false }
        if (!ok || found == null) throw AuthException("Correo o contraseña incorrectos")
        val refresh = Crypto.randomToken(48)
        val deviceId = users.createDevice(found.user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
        return found.user to TokenPair(access(found.user.id, deviceId), refresh, ACCESS_TTL, deviceId)
    }

    suspend fun refresh(refreshToken: String): Pair<User, TokenPair> {
        val (deviceId, userId) = users.deviceByRefresh(Crypto.sha256(refreshToken)) ?: throw AuthException("Sesión expirada")
        val user = users.byId(userId) ?: throw AuthException("Sesión expirada")
        val next = Crypto.randomToken(48)
        users.rotateRefresh(deviceId, Crypto.sha256(next))
        return user to TokenPair(access(userId, deviceId), next, ACCESS_TTL, deviceId)
    }

    suspend fun changePassword(userId: UUID, current: String, new: String) {
        val user = users.byId(userId) ?: throw AuthException("Usuario no encontrado")
        val withHash = users.byEmail(user.email) ?: throw AuthException("Usuario no encontrado")
        if (!check(current, withHash.passwordHash)) throw AuthException("La contraseña actual no es correcta")
        if (new.length < 10) throw AuthException("La nueva contraseña debe tener al menos 10 caracteres")
        users.setPassword(userId, hash(new))
    }

    fun access(userId: UUID, deviceId: UUID): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject(userId.toString())
        .withClaim("did", deviceId.toString())
        .withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(ACCESS_TTL)))
        .sign(algorithm)

    companion object {
        const val ISSUER = "kairo"
        const val AUDIENCE = "kairo-clients"
        const val ACCESS_TTL = 3600L
    }
}
