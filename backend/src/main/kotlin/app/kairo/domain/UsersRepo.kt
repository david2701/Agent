package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Jsonb
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.util.KairoJson
import app.kairo.util.Lang
import app.kairo.util.M
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class UserWithHash(val user: User, val passwordHash: String)

/** Session of a device as seen from its refresh token. */
class DeviceSession(val id: UUID, val userId: UUID, val lastSeenAt: Instant, val refreshedAt: Instant?)

class UsersRepo(private val db: Db) {

    suspend fun count(): Int = db.read { one("select count(*) as n from users") { it.getInt("n") } ?: 0 }

    suspend fun all(): List<User> = db.read { query("select * from users order by created_at") { map(it) } }

    suspend fun byEmail(email: String): UserWithHash? = db.read {
        one("select * from users where lower(email) = lower(?)", email.trim()) { UserWithHash(map(it), it.str("password_hash")) }
    }

    suspend fun byId(id: UUID): User? = db.read { one("select * from users where id = ?", id) { map(it) } }

    /** Several operations (from this repository and others) in a single transaction. */
    suspend fun <T> transaction(block: Tx.() -> T): T = db.tx(block)

    /**
     * Creates a user. Without [householdId] its own household is created too and the user becomes its administrator.
     * With a [language] the account starts with that `language` setting (and the household gets a name in it).
     */
    suspend fun create(
        email: String, name: String, passwordHash: String, timezone: String,
        householdId: UUID? = null, role: String = "admin", language: Lang? = null,
    ): User = db.tx { create(this, email, name, passwordHash, timezone, householdId, role, language) }

    fun create(
        tx: Tx, email: String, name: String, passwordHash: String, timezone: String,
        householdId: UUID? = null, role: String = "admin", language: Lang? = null,
    ): User = with(tx) {
        val clean = cleanDisplayName(name) ?: email.trim().substringBefore('@').take(MAX_DISPLAY_NAME)
        val hh = householdId
            ?: one("insert into households(name) values (?) returning id", M.HOUSEHOLD_DEFAULT_NAME.t(language ?: Lang.DEFAULT, clean)) { it.uuid("id") }!!
        val user = one(
            "insert into users(email, display_name, password_hash, timezone, household_id, role) values (?, ?, ?, ?, ?, ?) returning *",
            email.trim().lowercase(), clean, passwordHash, timezone, hh, if (householdId == null) "admin" else role,
        ) { map(it) }!!
        if (language != null) {
            exec(
                "insert into user_settings(user_id, data) values (?, ?) on conflict (user_id) do nothing",
                user.id, Jsonb(JsonObject(mapOf("language" to JsonPrimitive(language.code))).toString()),
            )
        }
        user
    }

    suspend fun emailTaken(email: String): Boolean = db.read { emailTaken(this, email) }

    fun emailTaken(tx: Tx, email: String): Boolean =
        tx.one("select 1 from users where lower(email) = lower(?)", email.trim()) { true } ?: false

    fun byId(tx: Tx, id: UUID): User? = tx.one("select * from users where id = ?", id) { map(it) }

    /** [displayName] is cleaned (no line breaks or control characters, 60 at most); empty = no change. */
    suspend fun update(id: UUID, displayName: String?, timezone: String?): User? = db.tx {
        one(
            "update users set display_name = coalesce(?, display_name), timezone = coalesce(?, timezone) where id = ? returning *",
            displayName?.let { cleanDisplayName(it) }, timezone, id,
        ) { map(it) }
    }

    suspend fun setPassword(id: UUID, hash: String) = db.tx { setPassword(this, id, hash) }

    fun setPassword(tx: Tx, id: UUID, hash: String) = tx.exec("update users set password_hash = ? where id = ?", hash, id)

    // --- Settings (free-form jsonb, with defaults in Settings.kt) ---

    suspend fun settings(userId: UUID): JsonObject = db.read {
        one("select data from user_settings where user_id = ?", userId) { KairoJson.parseToJsonElement(it.str("data")).jsonObject }
    } ?: JsonObject(emptyMap())

    suspend fun mergeSettings(userId: UUID, patch: Map<String, JsonElement>): JsonObject {
        val merged = JsonObject(settings(userId) + patch)
        db.tx {
            exec(
                "insert into user_settings(user_id, data) values (?, ?) on conflict (user_id) do update set data = excluded.data",
                userId, Jsonb(merged.toString()),
            )
        }
        return merged
    }

    // --- Devices / sessions ---

    suspend fun createDevice(userId: UUID, name: String, platform: String, refreshHash: String): UUID =
        db.tx { createDevice(this, userId, name, platform, refreshHash) }

    fun createDevice(tx: Tx, userId: UUID, name: String, platform: String, refreshHash: String): UUID = tx.one(
        "insert into devices(user_id, name, platform, refresh_token_hash) values (?, ?, ?, ?) returning id",
        userId, name.take(80), platform.take(20), refreshHash,
    ) { it.uuid("id") }!!

    suspend fun deviceByRefresh(hash: String): DeviceSession? = db.read {
        one("select * from devices where refresh_token_hash = ? and revoked_at is null", hash) { mapSession(it) }
    }

    /** Active device whose *previous* refresh token is [hash] (an already rotated token that someone uses again). */
    suspend fun deviceByPreviousRefresh(hash: String): DeviceSession? = db.read {
        one("select * from devices where prev_refresh_token_hash = ? and revoked_at is null", hash) { mapSession(it) }
    }

    /** Rotates the refresh token and remembers the previous one to detect whether someone reuses it. */
    suspend fun rotateRefresh(deviceId: UUID, newHash: String) = db.tx {
        exec(
            """update devices set prev_refresh_token_hash = refresh_token_hash, refresh_token_hash = ?,
               refreshed_at = now(), last_seen_at = now() where id = ?""",
            newHash, deviceId,
        )
    }

    suspend fun touchDevice(deviceId: UUID) = db.tx { exec("update devices set last_seen_at = now() where id = ?", deviceId) }

    /** Is the device still active *and* does it belong to [userId]? (a JWT with someone else's `did` is not valid). */
    suspend fun isDeviceActive(deviceId: UUID, userId: UUID): Boolean = db.read {
        one("select 1 from devices where id = ? and user_id = ? and revoked_at is null", deviceId, userId) { true } ?: false
    }

    suspend fun devices(userId: UUID): List<Device> = db.read {
        query("select * from devices where user_id = ? and revoked_at is null order by last_seen_at desc", userId) {
            Device(it.uuid("id"), it.str("name"), it.str("platform"), it.instant("created_at"), it.instant("last_seen_at"))
        }
    }

    suspend fun revokeDevice(userId: UUID, deviceId: UUID): Boolean = db.tx {
        exec("delete from push_endpoints where device_id = ? and user_id = ?", deviceId, userId)
        exec("delete from apns_tokens where device_id = ? and user_id = ?", deviceId, userId)
        exec("update devices set revoked_at = now() where user_id = ? and id = ?", userId, deviceId) > 0
    }

    /** Closes all of a user's sessions (after setting a new password with an access code). */
    suspend fun revokeAllDevices(userId: UUID) = db.tx { revokeAllDevices(this, userId) }

    fun revokeAllDevices(tx: Tx, userId: UUID) = with(tx) {
        exec("delete from push_endpoints where user_id = ?", userId)
        exec("delete from apns_tokens where user_id = ?", userId)
        exec("update devices set revoked_at = now() where user_id = ? and revoked_at is null", userId)
    }

    /** Closes the user's other sessions and keeps [keepDeviceId] open (after changing the password). */
    suspend fun revokeOtherDevices(userId: UUID, keepDeviceId: UUID?): Int = db.tx {
        if (keepDeviceId == null) return@tx revokeAllDevices(this, userId)
        exec("delete from push_endpoints where user_id = ? and (device_id is null or device_id <> ?)", userId, keepDeviceId)
        exec("delete from apns_tokens where user_id = ? and (device_id is null or device_id <> ?)", userId, keepDeviceId)
        exec("update devices set revoked_at = now() where user_id = ? and id <> ? and revoked_at is null", userId, keepDeviceId)
    }

    private fun map(rs: ResultSet) = User(
        rs.uuid("id"), rs.str("email"), rs.str("display_name"), rs.str("timezone"), rs.instant("created_at"),
        householdId = rs.uuid("household_id"), role = rs.str("role"), color = rs.strOrNull("color"),
    )

    private fun mapSession(rs: ResultSet) =
        DeviceSession(rs.uuid("id"), rs.uuid("user_id"), rs.instant("last_seen_at"), rs.instantOrNull("refreshed_at"))

    companion object {
        const val MAX_DISPLAY_NAME = 60

        /**
         * Safe display name: it goes inside the assistant prompts of the other members, so it cannot carry
         * line breaks or control or formatting characters (which would fake other instructions).
         * It collapses spaces, trims to 60 characters and returns null if nothing is left.
         */
        fun cleanDisplayName(raw: String): String? {
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                val type = Character.getType(cp)
                val bad = Character.isISOControl(cp) || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() || type == Character.SURROGATE.toInt()
                if (bad || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                } else {
                    sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            val clean = sb.toString().trim()
            if (clean.isEmpty()) return null
            // Trim by code points so an emoji is not split in half
            val count = clean.codePointCount(0, clean.length)
            return if (count <= MAX_DISPLAY_NAME) clean else clean.substring(0, clean.offsetByCodePoints(0, MAX_DISPLAY_NAME)).trimEnd()
        }
    }
}
