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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class UserWithHash(val user: User, val passwordHash: String)

/** Sesión de un dispositivo vista desde su refresh token. */
class DeviceSession(val id: UUID, val userId: UUID, val lastSeenAt: Instant, val refreshedAt: Instant?)

class UsersRepo(private val db: Db) {

    suspend fun count(): Int = db.read { one("select count(*) as n from users") { it.getInt("n") } ?: 0 }

    suspend fun all(): List<User> = db.read { query("select * from users order by created_at") { map(it) } }

    suspend fun byEmail(email: String): UserWithHash? = db.read {
        one("select * from users where lower(email) = lower(?)", email.trim()) { UserWithHash(map(it), it.str("password_hash")) }
    }

    suspend fun byId(id: UUID): User? = db.read { one("select * from users where id = ?", id) { map(it) } }

    /** Varias operaciones (de este repositorio y de otros) en una sola transacción. */
    suspend fun <T> transaction(block: Tx.() -> T): T = db.tx(block)

    /** Crea un usuario. Sin [householdId] se crea también su propio hogar y queda como administrador. */
    suspend fun create(email: String, name: String, passwordHash: String, timezone: String, householdId: UUID? = null, role: String = "admin"): User =
        db.tx { create(this, email, name, passwordHash, timezone, householdId, role) }

    fun create(tx: Tx, email: String, name: String, passwordHash: String, timezone: String, householdId: UUID? = null, role: String = "admin"): User = with(tx) {
        val clean = cleanDisplayName(name) ?: email.trim().substringBefore('@').take(MAX_DISPLAY_NAME)
        val hh = householdId ?: one("insert into households(name) values (?) returning id", "Familia de $clean") { it.uuid("id") }!!
        one(
            "insert into users(email, display_name, password_hash, timezone, household_id, role) values (?, ?, ?, ?, ?, ?) returning *",
            email.trim().lowercase(), clean, passwordHash, timezone, hh, if (householdId == null) "admin" else role,
        ) { map(it) }!!
    }

    suspend fun emailTaken(email: String): Boolean = db.read { emailTaken(this, email) }

    fun emailTaken(tx: Tx, email: String): Boolean =
        tx.one("select 1 from users where lower(email) = lower(?)", email.trim()) { true } ?: false

    fun byId(tx: Tx, id: UUID): User? = tx.one("select * from users where id = ?", id) { map(it) }

    /** [displayName] se limpia (sin saltos de línea ni caracteres de control, 60 como mucho); vacío = sin cambios. */
    suspend fun update(id: UUID, displayName: String?, timezone: String?): User? = db.tx {
        one(
            "update users set display_name = coalesce(?, display_name), timezone = coalesce(?, timezone) where id = ? returning *",
            displayName?.let { cleanDisplayName(it) }, timezone, id,
        ) { map(it) }
    }

    suspend fun setPassword(id: UUID, hash: String) = db.tx { setPassword(this, id, hash) }

    fun setPassword(tx: Tx, id: UUID, hash: String) = tx.exec("update users set password_hash = ? where id = ?", hash, id)

    // --- Settings (jsonb libre, con valores por defecto en Settings.kt) ---

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

    // --- Dispositivos / sesiones ---

    suspend fun createDevice(userId: UUID, name: String, platform: String, refreshHash: String): UUID =
        db.tx { createDevice(this, userId, name, platform, refreshHash) }

    fun createDevice(tx: Tx, userId: UUID, name: String, platform: String, refreshHash: String): UUID = tx.one(
        "insert into devices(user_id, name, platform, refresh_token_hash) values (?, ?, ?, ?) returning id",
        userId, name.take(80), platform.take(20), refreshHash,
    ) { it.uuid("id") }!!

    suspend fun deviceByRefresh(hash: String): DeviceSession? = db.read {
        one("select * from devices where refresh_token_hash = ? and revoked_at is null", hash) { mapSession(it) }
    }

    /** Dispositivo vigente cuyo refresh token *anterior* es [hash] (un token ya rotado que alguien vuelve a usar). */
    suspend fun deviceByPreviousRefresh(hash: String): DeviceSession? = db.read {
        one("select * from devices where prev_refresh_token_hash = ? and revoked_at is null", hash) { mapSession(it) }
    }

    /** Rota el refresh token y recuerda el anterior para detectar si alguien lo reutiliza. */
    suspend fun rotateRefresh(deviceId: UUID, newHash: String) = db.tx {
        exec(
            """update devices set prev_refresh_token_hash = refresh_token_hash, refresh_token_hash = ?,
               refreshed_at = now(), last_seen_at = now() where id = ?""",
            newHash, deviceId,
        )
    }

    suspend fun touchDevice(deviceId: UUID) = db.tx { exec("update devices set last_seen_at = now() where id = ?", deviceId) }

    /** ¿El dispositivo sigue vigente *y* es de [userId]? (un JWT con el `did` de otra persona no vale). */
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

    /** Cierra todas las sesiones de un usuario (tras definir una contraseña nueva con un código de acceso). */
    suspend fun revokeAllDevices(userId: UUID) = db.tx { revokeAllDevices(this, userId) }

    fun revokeAllDevices(tx: Tx, userId: UUID) = with(tx) {
        exec("delete from push_endpoints where user_id = ?", userId)
        exec("delete from apns_tokens where user_id = ?", userId)
        exec("update devices set revoked_at = now() where user_id = ? and revoked_at is null", userId)
    }

    /** Cierra las demás sesiones del usuario y deja abierta [keepDeviceId] (tras cambiar la contraseña). */
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
         * Nombre visible seguro: va dentro de los prompts del asistente de los demás miembros, así que no
         * puede traer saltos de línea ni caracteres de control o de formato (que fingirían otras instrucciones).
         * Junta los espacios, recorta a 60 caracteres y devuelve null si queda vacío.
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
            // Recorta por puntos de código para no partir un emoji a la mitad
            val count = clean.codePointCount(0, clean.length)
            return if (count <= MAX_DISPLAY_NAME) clean else clean.substring(0, clean.offsetByCodePoints(0, MAX_DISPLAY_NAME)).trimEnd()
        }
    }
}
