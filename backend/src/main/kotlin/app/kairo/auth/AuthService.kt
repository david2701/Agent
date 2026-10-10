package app.kairo.auth

import app.kairo.config.AppConfig
import app.kairo.domain.FamilyRepo
import app.kairo.domain.User
import app.kairo.domain.UsersRepo
import app.kairo.util.Crypto
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import kotlinx.coroutines.sync.withLock
import java.sql.SQLException
import java.time.Instant
import java.util.Date
import java.util.UUID

class AuthException(message: String) : RuntimeException(message)

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val deviceId: UUID)

/** Resultado de usar un código de la familia. [createdBy]: quién generó el código (para avisar a la familia). */
data class JoinResult(val user: User, val tokens: TokenPair, val isNew: Boolean, val createdBy: UUID?)

/**
 * Autenticación de un único propietario (preparada para varios usuarios).
 * - Contraseñas con bcrypt (coste 12).
 * - Access token JWT de 1 hora con el id del dispositivo.
 * - Refresh token opaco por dispositivo, guardado como hash SHA-256 y rotado en cada uso.
 */
class AuthService(private val cfg: AppConfig, private val users: UsersRepo, private val family: FamilyRepo) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val algorithm = Algorithm.HMAC256(cfg.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    private val dummyHash by lazy { hash(Crypto.randomToken(16)) }

    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * ¿Sigue vigente la sesión de este dispositivo y es de [userId]? Se consulta en cada petición
     * (y en cada ping del WebSocket), con caché de 60 s que se limpia al revocar.
     */
    suspend fun deviceActive(deviceId: String, userId: String): Boolean = deviceStatus(deviceId, userId) ?: false

    /**
     * Como [deviceActive], pero null si no se pudo consultar la base (no se guarda en caché: un corte breve
     * no debe cerrar sesiones durante un minuto). El WebSocket sólo se cierra con un `false` seguro.
     */
    suspend fun deviceStatus(deviceId: String, userId: String): Boolean? {
        val key = "$userId/$deviceId"
        val now = System.currentTimeMillis()
        deviceCache[key]?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        val did = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: return false
        val uid = runCatching { UUID.fromString(userId) }.getOrNull() ?: return false
        val active = runCatching { users.isDeviceActive(did, uid) }.getOrElse { return null }
        deviceCache[key] = now to active
        return active
    }

    /** Tras revocar sesiones o quitar a alguien, para que deje de entrar de inmediato. */
    fun forgetSessions() = deviceCache.clear()

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
        AppConfig.ownerPasswordProblem(password)?.let { error(it) }
        users.create(email, cfg.ownerName, hash(password), cfg.defaultTimezone)
        log.info("Cuenta del propietario creada para {}", email)
    }

    /**
     * Fallos de inicio de sesión por correo: con [LOGIN_MAX_FAILURES] en [LOGIN_WINDOW_MS] el correo queda
     * bloqueado un rato, con el mismo error genérico (no revela si la cuenta existe ni si está bloqueada).
     * Complementa el límite por IP, que no frena a quien reparte los intentos entre muchas direcciones.
     */
    private val loginFailures = KeyedFailureCounter(LOGIN_MAX_FAILURES, LOGIN_WINDOW_MS)

    suspend fun login(email: String, password: String, deviceName: String, platform: String): Pair<User, TokenPair> {
        val key = email.trim().lowercase()
        val now = System.currentTimeMillis()
        if (loginFailures.blocked(key, now)) {
            check(password, dummyHash) // mismo tiempo de respuesta que un intento normal
            throw AuthException(BAD_LOGIN)
        }
        val found = users.byEmail(email)
        // Comparación con hash ficticio para no revelar si el correo existe por tiempos de respuesta.
        val ok = if (found != null) check(password, found.passwordHash) else { check(password, dummyHash); false }
        if (!ok || found == null) {
            if (loginFailures.record(key, now) == LOGIN_MAX_FAILURES) {
                log.warn("Demasiados intentos fallidos para {}: se bloquea el inicio de sesión de ese correo por 15 minutos", maskEmail(key))
            }
            throw AuthException(BAD_LOGIN)
        }
        loginFailures.reset(key)
        val refresh = Crypto.randomToken(48)
        val deviceId = users.createDevice(found.user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
        return found.user to TokenPair(access(found.user.id, deviceId), refresh, ACCESS_TTL, deviceId)
    }

    /**
     * Códigos de vinculación para dispositivos sin teclado (reloj): 6 dígitos, 5 minutos, un solo uso y
     * uno vigente por usuario (pedir otro reemplaza el anterior). Como 6 dígitos se adivinan con tiempo,
     * [PAIR_MAX_FAILURES] intentos fallidos en [PAIR_WINDOW_MS] (sumando todas las IP) anulan todos los
     * códigos vigentes: quien vincula un reloj sólo tiene que pedir otro.
     */
    private val pairCodes = java.util.concurrent.ConcurrentHashMap<String, Pair<UUID, Long>>()
    private val pairFailures = FailureWindow(PAIR_MAX_FAILURES, PAIR_WINDOW_MS)
    @Volatile private var lastPairWarning = 0L
    private val random = java.security.SecureRandom()

    fun createPairCode(userId: UUID): String {
        synchronized(pairCodes) {
            val now = System.currentTimeMillis()
            pairCodes.entries.removeIf { it.value.second < now || it.value.first == userId }
            // Con un código por usuario el espacio no se llena, pero nunca se reintenta sin límite
            repeat(50) {
                val code = "%06d".format(random.nextInt(1_000_000))
                if (pairCodes.putIfAbsent(code, userId to now + PAIR_TTL_MS) == null) return code
            }
        }
        throw AuthException("No se pudo generar un código. Intenta de nuevo en un momento.")
    }

    suspend fun completePairing(code: String, deviceName: String, platform: String): Pair<User, TokenPair> {
        val now = System.currentTimeMillis()
        val entry = pairCodes.remove(code.trim())?.takeIf { it.second >= now }
        if (entry == null) {
            pairFailed(now)
            throw AuthException("Código inválido o vencido")
        }
        val user = users.byId(entry.first) ?: throw AuthException("Usuario no encontrado")
        val refresh = Crypto.randomToken(48)
        val deviceId = users.createDevice(user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
        return user to TokenPair(access(user.id, deviceId), refresh, ACCESS_TTL, deviceId)
    }

    private fun pairFailed(now: Long) {
        if (pairFailures.record(now) < PAIR_MAX_FAILURES) return
        val dropped = synchronized(pairCodes) { pairCodes.size.also { pairCodes.clear() } }
        if (now - lastPairWarning > 60_000) {
            lastPairWarning = now
            log.warn("{} intentos fallidos de vinculación en 5 minutos: se anularon {} códigos vigentes (posible fuerza bruta)", PAIR_MAX_FAILURES, dropped)
        }
    }

    /** Para pruebas: cuántos códigos de vinculación hay vigentes. */
    internal fun livePairCodes(): Int = pairCodes.size

    /**
     * Rotaciones recientes: si dos peticiones llegan con el mismo refresh token (dos pestañas que
     * abren a la vez, una red que reintenta), la segunda recibe el mismo token nuevo en lugar de
     * cerrar la sesión. Sólo vale durante [REFRESH_GRACE_MS] y mientras ese token nuevo siga vigente.
     * Pasada la gracia, un token ya rotado que reaparece es sospechoso: se cierra la sesión de ese dispositivo.
     */
    private val recentRotations = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    suspend fun refresh(refreshToken: String): Pair<User, TokenPair> = refreshLock.withLock {
        val now = System.currentTimeMillis()
        recentRotations.entries.removeIf { it.value.second < now }
        val oldHash = Crypto.sha256(refreshToken)
        recentRotations[oldHash]?.first?.let { cached ->
            users.deviceByRefresh(Crypto.sha256(cached))?.let { d ->
                val user = users.byId(d.userId) ?: throw AuthException("Sesión expirada")
                return@withLock user to TokenPair(access(d.userId, d.id), cached, ACCESS_TTL, d.id)
            }
        }
        val device = users.deviceByRefresh(oldHash)
        if (device == null) {
            users.deviceByPreviousRefresh(oldHash)?.let { d ->
                if (SessionPolicy.reuseIsSuspicious(d.refreshedAt, Instant.ofEpochMilli(now), REFRESH_GRACE_MS)) {
                    users.revokeDevice(d.userId, d.id)
                    forgetSessions()
                    log.warn("Refresh token reutilizado tras la rotación (dispositivo {}): se cerró esa sesión", d.id)
                }
            }
            throw AuthException("Sesión expirada")
        }
        if (SessionPolicy.idleExpired(device.lastSeenAt, Instant.ofEpochMilli(now))) {
            users.revokeDevice(device.userId, device.id)
            forgetSessions()
            throw AuthException("Sesión expirada")
        }
        val next = Crypto.randomToken(48)
        users.rotateRefresh(device.id, Crypto.sha256(next))
        recentRotations[oldHash] = next to now + REFRESH_GRACE_MS
        val user = users.byId(device.userId) ?: throw AuthException("Sesión expirada")
        user to TokenPair(access(device.userId, device.id), next, ACCESS_TTL, device.id)
    }

    /**
     * Usa un código de la familia: con una invitación crea la cuenta del nuevo miembro; con un código de acceso
     * (que generó el administrador) define una contraseña nueva para un miembro existente y cierra sus otras sesiones.
     * El código se reclama de forma atómica en la misma transacción que crea la cuenta o cambia la contraseña:
     * no se puede usar dos veces, y si algo falla no queda gastado.
     */
    suspend fun join(
        code: String,
        email: String?,
        password: String,
        displayName: String?,
        deviceName: String,
        platform: String,
        timezone: String?,
    ): JoinResult {
        // Consulta previa barata: un código inválido no llega a bcrypt ni a la transacción
        val preview = family.findInvite(code) ?: throw AuthException(INVALID_CODE)
        if (password.length < 10) throw AuthException("La contraseña debe tener al menos 10 caracteres")
        val mail = email?.trim()?.lowercase().orEmpty()
        if (preview.targetUserId == null) {
            if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(mail)) throw AuthException("Escribe un correo válido")
            if (users.emailTaken(mail)) throw AuthException(EMAIL_TAKEN)
        }
        val passwordHash = hash(password)
        val refresh = Crypto.randomToken(48)
        val tz = timezone?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess } ?: cfg.defaultTimezone
        val result = try {
            users.transaction {
                val inv = family.claimInvite(this, code) ?: throw AuthException(INVALID_CODE)
                val (user, isNew) = if (inv.targetUserId != null) {
                    val target = users.byId(this, inv.targetUserId)?.takeIf { it.householdId == inv.householdId }
                        ?: throw AuthException("Ese miembro ya no existe")
                    users.setPassword(this, target.id, passwordHash)
                    users.revokeAllDevices(this, target.id)
                    target to false
                } else {
                    if (users.emailTaken(this, mail)) throw AuthException(EMAIL_TAKEN)
                    val name = UsersRepo.cleanDisplayName(displayName.orEmpty())
                        ?: UsersRepo.cleanDisplayName(inv.name) ?: mail.substringBefore('@')
                    users.create(this, mail, name, passwordHash, tz, householdId = inv.householdId, role = inv.role) to true
                }
                family.markUsedBy(this, inv.id, user.id)
                val deviceId = users.createDevice(this, user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
                JoinResult(user, TokenPair(access(user.id, deviceId), refresh, ACCESS_TTL, deviceId), isNew, inv.createdBy)
            }
        } catch (e: SQLException) {
            // Dos altas simultáneas con el mismo correo: la base rechaza la segunda (y se deshace todo)
            if (e.sqlState == "23505") throw AuthException(EMAIL_TAKEN)
            throw e
        }
        if (!result.isNew) forgetSessions()
        family.invalidate()
        return result
    }

    /** Cambia la contraseña y cierra las demás sesiones del usuario (la de [currentDeviceId] sigue abierta). */
    suspend fun changePassword(userId: UUID, currentDeviceId: UUID?, current: String, new: String) {
        val user = users.byId(userId) ?: throw AuthException("Usuario no encontrado")
        val withHash = users.byEmail(user.email) ?: throw AuthException("Usuario no encontrado")
        if (!check(current, withHash.passwordHash)) throw AuthException("La contraseña actual no es correcta")
        if (new.length < 10) throw AuthException("La nueva contraseña debe tener al menos 10 caracteres")
        users.setPassword(userId, hash(new))
        users.revokeOtherDevices(userId, currentDeviceId)
        forgetSessions()
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
        const val REFRESH_GRACE_MS = 60_000L

        const val PAIR_TTL_MS = 5 * 60_000L
        const val PAIR_MAX_FAILURES = 20
        const val PAIR_WINDOW_MS = 5 * 60_000L

        const val LOGIN_MAX_FAILURES = 10
        const val LOGIN_WINDOW_MS = 15 * 60_000L

        const val BAD_LOGIN = "Correo o contraseña incorrectos"
        private const val INVALID_CODE = "El código no es válido o ya venció. Pide uno nuevo a quien administra la familia."
        private const val EMAIL_TAKEN = "Ya hay una cuenta con ese correo"

        /** "ana@correo.com" -> "a***@correo.com", para los registros. */
        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            return if (at <= 0) "***" else email.take(1) + "***" + email.substring(at)
        }
    }
}
