package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Jsonb
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.util.KairoJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import java.util.UUID

class UserWithHash(val user: User, val passwordHash: String)

class UsersRepo(private val db: Db) {

    suspend fun count(): Int = db.read { one("select count(*) as n from users") { it.getInt("n") } ?: 0 }

    suspend fun all(): List<User> = db.read { query("select * from users order by created_at") { map(it) } }

    suspend fun byEmail(email: String): UserWithHash? = db.read {
        one("select * from users where lower(email) = lower(?)", email.trim()) { UserWithHash(map(it), it.str("password_hash")) }
    }

    suspend fun byId(id: UUID): User? = db.read { one("select * from users where id = ?", id) { map(it) } }

    /** Crea un usuario. Sin [householdId] se crea también su propio hogar y queda como administrador. */
    suspend fun create(email: String, name: String, passwordHash: String, timezone: String, householdId: UUID? = null, role: String = "admin"): User = db.tx {
        val hh = householdId ?: one("insert into households(name) values (?) returning id", "Familia de ${name.trim()}") { it.uuid("id") }!!
        one(
            "insert into users(email, display_name, password_hash, timezone, household_id, role) values (?, ?, ?, ?, ?, ?) returning *",
            email.trim().lowercase(), name.trim(), passwordHash, timezone, hh, if (householdId == null) "admin" else role,
        ) { map(it) }!!
    }

    suspend fun emailTaken(email: String): Boolean =
        db.read { one("select 1 from users where lower(email) = lower(?)", email.trim()) { true } } ?: false

    suspend fun update(id: UUID, displayName: String?, timezone: String?): User? = db.tx {
        one(
            "update users set display_name = coalesce(?, display_name), timezone = coalesce(?, timezone) where id = ? returning *",
            displayName?.trim(), timezone, id,
        ) { map(it) }
    }

    suspend fun setPassword(id: UUID, hash: String) = db.tx { exec("update users set password_hash = ? where id = ?", hash, id) }

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

    suspend fun createDevice(userId: UUID, name: String, platform: String, refreshHash: String): UUID = db.tx {
        one(
            "insert into devices(user_id, name, platform, refresh_token_hash) values (?, ?, ?, ?) returning id",
            userId, name.take(80), platform.take(20), refreshHash,
        ) { it.uuid("id") }!!
    }

    suspend fun deviceByRefresh(hash: String): Pair<UUID, UUID>? = db.read {
        one("select id, user_id from devices where refresh_token_hash = ? and revoked_at is null", hash) { it.uuid("id") to it.uuid("user_id") }
    }

    suspend fun rotateRefresh(deviceId: UUID, newHash: String) = db.tx {
        exec("update devices set refresh_token_hash = ?, last_seen_at = now() where id = ?", newHash, deviceId)
    }

    suspend fun touchDevice(deviceId: UUID) = db.tx { exec("update devices set last_seen_at = now() where id = ?", deviceId) }

    suspend fun isDeviceActive(deviceId: UUID): Boolean = db.read {
        one("select 1 from devices where id = ? and revoked_at is null", deviceId) { true } ?: false
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
    suspend fun revokeAllDevices(userId: UUID) = db.tx {
        exec("delete from push_endpoints where user_id = ?", userId)
        exec("delete from apns_tokens where user_id = ?", userId)
        exec("update devices set revoked_at = now() where user_id = ? and revoked_at is null", userId)
    }

    private fun map(rs: ResultSet) = User(
        rs.uuid("id"), rs.str("email"), rs.str("display_name"), rs.str("timezone"), rs.instant("created_at"),
        householdId = rs.uuid("household_id"), role = rs.str("role"), color = rs.strOrNull("color"),
    )
}
