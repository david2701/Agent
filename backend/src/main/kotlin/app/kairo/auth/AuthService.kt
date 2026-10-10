package app.kairo.auth

import app.kairo.config.AppConfig
import app.kairo.domain.FamilyRepo
import app.kairo.domain.User
import app.kairo.domain.UsersRepo
import app.kairo.util.Crypto
import app.kairo.util.Lang
import app.kairo.util.Localizable
import app.kairo.util.M
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

/** An authentication failure; [text] renders the message in the reader's language ([message] is English). */
class AuthException(val msg: M, val args: List<Any?> = emptyList()) :
    RuntimeException(msg.t(Lang.EN, *args.toTypedArray())), Localizable {
    override fun text(lang: Lang): String = msg.t(lang, *args.toTypedArray())
}

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val deviceId: UUID)

/** Result of using a family code. [createdBy]: who generated the code (to notify the family). */
data class JoinResult(val user: User, val tokens: TokenPair, val isNew: Boolean, val createdBy: UUID?)

/**
 * Authentication for a single owner (ready for several users).
 * - Passwords with bcrypt (cost 12).
 * - 1-hour JWT access token carrying the device id.
 * - Opaque refresh token per device, stored as a SHA-256 hash and rotated on every use.
 */
class AuthService(private val cfg: AppConfig, private val users: UsersRepo, private val family: FamilyRepo) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val algorithm = Algorithm.HMAC256(cfg.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    private val dummyHash by lazy { hash(Crypto.randomToken(16)) }

    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * Is this device's session still valid and does it belong to [userId]? Checked on every request
     * (and on every WebSocket ping), with a 60 s cache that is cleared on revoke.
     */
    suspend fun deviceActive(deviceId: String, userId: String): Boolean = deviceStatus(deviceId, userId) ?: false

    /**
     * Like [deviceActive], but null if the database could not be queried (not cached: a brief outage
     * must not close sessions for a minute). The WebSocket is only closed on a definite `false`.
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

    /** After revoking sessions or removing someone, so they stop getting in immediately. */
    fun forgetSessions() = deviceCache.clear()

    fun hash(password: String): String = BCrypt.withDefaults().hashToString(12, password.toCharArray())

    fun check(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    /** Creates the owner account on first start from KAIRO_OWNER_EMAIL/PASSWORD (language: KAIRO_DEFAULT_LANGUAGE). */
    suspend fun bootstrapOwner() {
        if (users.count() > 0) return
        val email = cfg.ownerEmail
        val password = cfg.ownerPassword
        if (email == null || password == null) {
            log.warn("There are no users. Set KAIRO_OWNER_EMAIL and KAIRO_OWNER_PASSWORD to create the owner account.")
            return
        }
        require(password.length >= 10) { "KAIRO_OWNER_PASSWORD must be at least 10 characters long" }
        AppConfig.ownerPasswordProblem(password)?.let { error(it) }
        users.create(email, cfg.ownerName, hash(password), cfg.defaultTimezone, language = cfg.defaultLanguage)
        log.info("Owner account created for {}", email)
    }

    /**
     * Login failures per email: with [LOGIN_MAX_FAILURES] within [LOGIN_WINDOW_MS] the email is blocked for a
     * while, with the same generic error (it does not reveal whether the account exists or is blocked).
     * Complements the per-IP limit, which does not stop someone who spreads attempts over many addresses.
     */
    private val loginFailures = KeyedFailureCounter(LOGIN_MAX_FAILURES, LOGIN_WINDOW_MS)

    suspend fun login(email: String, password: String, deviceName: String, platform: String): Pair<User, TokenPair> {
        val key = email.trim().lowercase()
        val now = System.currentTimeMillis()
        if (loginFailures.blocked(key, now)) {
            check(password, dummyHash) // same response time as a normal attempt
            throw AuthException(M.BAD_LOGIN)
        }
        val found = users.byEmail(email)
        // Compare against a dummy hash so response times do not reveal whether the email exists.
        val ok = if (found != null) check(password, found.passwordHash) else { check(password, dummyHash); false }
        if (!ok || found == null) {
            if (loginFailures.record(key, now) == LOGIN_MAX_FAILURES) {
                log.warn("Too many failed attempts for {}: logins for that email are blocked for 15 minutes", maskEmail(key))
            }
            throw AuthException(M.BAD_LOGIN)
        }
        loginFailures.reset(key)
        val refresh = Crypto.randomToken(48)
        val deviceId = users.createDevice(found.user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
        return found.user to TokenPair(access(found.user.id, deviceId), refresh, ACCESS_TTL, deviceId)
    }

    /**
     * Pairing codes for devices without a keyboard (watch): 6 digits, 5 minutes, single use and
     * one valid per user (asking for another replaces the previous one). Since 6 digits can be guessed given
     * enough time, [PAIR_MAX_FAILURES] failed attempts within [PAIR_WINDOW_MS] (summing all IPs) void every
     * valid code: whoever is pairing a watch just has to ask for another.
     */
    private val pairCodes = java.util.concurrent.ConcurrentHashMap<String, Pair<UUID, Long>>()
    private val pairFailures = FailureWindow(PAIR_MAX_FAILURES, PAIR_WINDOW_MS)
    @Volatile private var lastPairWarning = 0L
    private val random = java.security.SecureRandom()

    fun createPairCode(userId: UUID): String {
        synchronized(pairCodes) {
            val now = System.currentTimeMillis()
            pairCodes.entries.removeIf { it.value.second < now || it.value.first == userId }
            // With one code per user the space never fills up, but retries are never unbounded
            repeat(50) {
                val code = "%06d".format(random.nextInt(1_000_000))
                if (pairCodes.putIfAbsent(code, userId to now + PAIR_TTL_MS) == null) return code
            }
        }
        throw AuthException(M.PAIR_CODE_FAILED)
    }

    suspend fun completePairing(code: String, deviceName: String, platform: String): Pair<User, TokenPair> {
        val now = System.currentTimeMillis()
        val entry = pairCodes.remove(code.trim())?.takeIf { it.second >= now }
        if (entry == null) {
            pairFailed(now)
            throw AuthException(M.PAIR_CODE_INVALID)
        }
        val user = users.byId(entry.first) ?: throw AuthException(M.NF_USER)
        val refresh = Crypto.randomToken(48)
        val deviceId = users.createDevice(user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
        return user to TokenPair(access(user.id, deviceId), refresh, ACCESS_TTL, deviceId)
    }

    private fun pairFailed(now: Long) {
        if (pairFailures.record(now) < PAIR_MAX_FAILURES) return
        val dropped = synchronized(pairCodes) { pairCodes.size.also { pairCodes.clear() } }
        if (now - lastPairWarning > 60_000) {
            lastPairWarning = now
            log.warn("{} failed pairing attempts in 5 minutes: {} valid codes were voided (possible brute force)", PAIR_MAX_FAILURES, dropped)
        }
    }

    /** For tests: how many pairing codes are currently valid. */
    internal fun livePairCodes(): Int = pairCodes.size

    /**
     * Recent rotations: if two requests arrive with the same refresh token (two tabs opening at the same
     * time, a network that retries), the second gets the same new token instead of closing the session.
     * It only applies during [REFRESH_GRACE_MS] and while that new token is still valid.
     * After the grace period, an already rotated token that shows up again is suspicious: that device's session is closed.
     */
    private val recentRotations = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    suspend fun refresh(refreshToken: String): Pair<User, TokenPair> = refreshLock.withLock {
        val now = System.currentTimeMillis()
        recentRotations.entries.removeIf { it.value.second < now }
        val oldHash = Crypto.sha256(refreshToken)
        recentRotations[oldHash]?.first?.let { cached ->
            users.deviceByRefresh(Crypto.sha256(cached))?.let { d ->
                val user = users.byId(d.userId) ?: throw AuthException(M.SESSION_EXPIRED)
                return@withLock user to TokenPair(access(d.userId, d.id), cached, ACCESS_TTL, d.id)
            }
        }
        val device = users.deviceByRefresh(oldHash)
        if (device == null) {
            users.deviceByPreviousRefresh(oldHash)?.let { d ->
                if (SessionPolicy.reuseIsSuspicious(d.refreshedAt, Instant.ofEpochMilli(now), REFRESH_GRACE_MS)) {
                    users.revokeDevice(d.userId, d.id)
                    forgetSessions()
                    log.warn("Refresh token reused after rotation (device {}): that session was closed", d.id)
                }
            }
            throw AuthException(M.SESSION_EXPIRED)
        }
        if (SessionPolicy.idleExpired(device.lastSeenAt, Instant.ofEpochMilli(now))) {
            users.revokeDevice(device.userId, device.id)
            forgetSessions()
            throw AuthException(M.SESSION_EXPIRED)
        }
        val next = Crypto.randomToken(48)
        users.rotateRefresh(device.id, Crypto.sha256(next))
        recentRotations[oldHash] = next to now + REFRESH_GRACE_MS
        val user = users.byId(device.userId) ?: throw AuthException(M.SESSION_EXPIRED)
        user to TokenPair(access(device.userId, device.id), next, ACCESS_TTL, device.id)
    }

    /**
     * Uses a family code: with an invitation it creates the new member's account; with an access code
     * (generated by the administrator) it sets a new password for an existing member and closes their other sessions.
     * The code is claimed atomically in the same transaction that creates the account or changes the password:
     * it cannot be used twice, and if anything fails it is not spent.
     * [lang] is the language of the request: a new account starts with it as its `language` setting.
     */
    suspend fun join(
        code: String,
        email: String?,
        password: String,
        displayName: String?,
        deviceName: String,
        platform: String,
        timezone: String?,
        lang: Lang = Lang.DEFAULT,
    ): JoinResult {
        // Cheap pre-check: an invalid code never reaches bcrypt or the transaction
        val preview = family.findInvite(code) ?: throw AuthException(M.INVALID_CODE)
        if (password.length < 10) throw AuthException(M.PASSWORD_MIN_10)
        val mail = email?.trim()?.lowercase().orEmpty()
        if (preview.targetUserId == null) {
            if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(mail)) throw AuthException(M.ENTER_VALID_EMAIL)
            if (users.emailTaken(mail)) throw AuthException(M.EMAIL_TAKEN)
        }
        val passwordHash = hash(password)
        val refresh = Crypto.randomToken(48)
        val tz = timezone?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess } ?: cfg.defaultTimezone
        val result = try {
            users.transaction {
                val inv = family.claimInvite(this, code) ?: throw AuthException(M.INVALID_CODE)
                val (user, isNew) = if (inv.targetUserId != null) {
                    val target = users.byId(this, inv.targetUserId)?.takeIf { it.householdId == inv.householdId }
                        ?: throw AuthException(M.MEMBER_GONE)
                    users.setPassword(this, target.id, passwordHash)
                    users.revokeAllDevices(this, target.id)
                    target to false
                } else {
                    if (users.emailTaken(this, mail)) throw AuthException(M.EMAIL_TAKEN)
                    val name = UsersRepo.cleanDisplayName(displayName.orEmpty())
                        ?: UsersRepo.cleanDisplayName(inv.name) ?: mail.substringBefore('@')
                    users.create(this, mail, name, passwordHash, tz, householdId = inv.householdId, role = inv.role, language = lang) to true
                }
                family.markUsedBy(this, inv.id, user.id)
                val deviceId = users.createDevice(this, user.id, deviceName.ifBlank { platform }, platform, Crypto.sha256(refresh))
                JoinResult(user, TokenPair(access(user.id, deviceId), refresh, ACCESS_TTL, deviceId), isNew, inv.createdBy)
            }
        } catch (e: SQLException) {
            // Two simultaneous sign-ups with the same email: the database rejects the second (and everything rolls back)
            if (e.sqlState == "23505") throw AuthException(M.EMAIL_TAKEN)
            throw e
        }
        if (!result.isNew) forgetSessions()
        family.invalidate()
        return result
    }

    /** Changes the password and closes the user's other sessions (the one of [currentDeviceId] stays open). */
    suspend fun changePassword(userId: UUID, currentDeviceId: UUID?, current: String, new: String) {
        val user = users.byId(userId) ?: throw AuthException(M.NF_USER)
        val withHash = users.byEmail(user.email) ?: throw AuthException(M.NF_USER)
        if (!check(current, withHash.passwordHash)) throw AuthException(M.CURRENT_PASSWORD_WRONG)
        if (new.length < 10) throw AuthException(M.NEW_PASSWORD_MIN_10)
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

        /** "ana@example.com" -> "a***@example.com", for the logs. */
        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            return if (at <= 0) "***" else email.take(1) + "***" + email.substring(at)
        }
    }
}
