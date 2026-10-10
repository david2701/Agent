@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.util.Crypto
import app.kairo.util.InstantSerializer
import app.kairo.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Household(val id: UUID, val name: String, val createdAt: Instant)

@Serializable
data class Member(
    val id: UUID,
    val displayName: String,
    val email: String,
    /** admin | member */
    val role: String,
    val joinedAt: Instant,
)

@Serializable
data class FamilyInfo(val household: Household, val members: List<Member>, val me: UUID, val isAdmin: Boolean)

@Serializable
data class Invitation(
    val id: UUID,
    /** invite (nuevo miembro) | reset (nuevo acceso para un miembro existente) */
    val kind: String,
    val name: String,
    val role: String,
    val targetUserId: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** Lo que ve quien abre un código antes de unirse. */
@Serializable
data class InvitePreview(val kind: String, val householdName: String, val name: String, val email: String? = null)

data class InviteRecord(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val role: String,
    val targetUserId: UUID?,
    val expiresAt: Instant,
)

/** Error de una operación familiar; [status] 403 cuando hace falta ser administrador. */
class FamilyException(message: String, val status: Int = 400) : RuntimeException(message)

/**
 * Familia: un hogar con varios perfiles. Cada perfil tiene lo suyo (privado) y comparte lo que marca
 * como familiar (eventos, listas, tareas, notas y lugares). El administrador invita, quita miembros y
 * genera códigos de acceso nuevos, pero no ve lo personal de nadie.
 */
class FamilyRepo(private val db: Db) {
    private val random = SecureRandom()
    private val membersCache = ConcurrentHashMap<UUID, Pair<Long, List<UUID>>>()

    suspend fun householdId(userId: UUID): UUID =
        db.read { one("select household_id from users where id = ?", userId) { it.uuid("household_id") } }
            ?: throw FamilyException("Usuario no encontrado")

    suspend fun household(userId: UUID): Household = db.read {
        one("select h.* from households h join users u on u.household_id = h.id where u.id = ?", userId) {
            Household(it.uuid("id"), it.str("name"), it.instant("created_at"))
        }
    } ?: throw FamilyException("Usuario no encontrado")

    suspend fun members(userId: UUID): List<Member> = db.read {
        query(
            """select m.* from users m where m.household_id = $HOUSEHOLD_OF
               order by case m.role when 'admin' then 0 else 1 end, m.created_at""",
            userId,
        ) { mapMember(it) }
    }

    /** Ids de todos los miembros del hogar del usuario (incluido él). Caché corta: se consulta en cada cambio compartido. */
    suspend fun memberIds(userId: UUID): List<UUID> {
        val now = System.currentTimeMillis()
        membersCache[userId]?.takeIf { now - it.first < 30_000 }?.let { return it.second }
        val ids = db.read { query("select id from users where household_id = $HOUSEHOLD_OF", userId) { it.uuid("id") } }
        membersCache[userId] = now to ids
        return ids
    }

    fun invalidate() = membersCache.clear()

    suspend fun info(userId: UUID): FamilyInfo {
        val members = members(userId)
        return FamilyInfo(household(userId), members, userId, members.firstOrNull { it.id == userId }?.role == "admin")
    }

    suspend fun isAdmin(userId: UUID): Boolean =
        db.read { one("select role from users where id = ?", userId) { it.str("role") } } == "admin"

    suspend fun rename(userId: UUID, name: String): Household {
        requireAdmin(userId)
        val clean = name.trim().take(60).ifEmpty { throw FamilyException("El nombre no puede estar vacío") }
        db.tx { exec("update households set name = ? where id = $HOUSEHOLD_OF", clean, userId) }
        return household(userId)
    }

    // --- Invitaciones y códigos de acceso ---

    /**
     * Crea un código de un solo uso: para un miembro nuevo ([targetUserId] nulo, 7 días) o para que un
     * miembro existente defina una contraseña nueva (24 horas). Devuelve la invitación y el código en claro.
     */
    suspend fun createInvite(actorId: UUID, name: String, role: String, targetUserId: UUID?): Pair<Invitation, String> {
        requireAdmin(actorId)
        val hh = householdId(actorId)
        if (targetUserId != null) {
            val ok = db.read { one("select 1 from users where id = ? and household_id = ?", targetUserId, hh) { true } } ?: false
            if (!ok) throw FamilyException("Ese miembro no es de tu familia")
        }
        val code = newCode()
        val ttl = if (targetUserId == null) 7L * 86400 else 86400L
        val inv = db.tx {
            if (targetUserId != null) {
                // Un solo código vigente por miembro
                exec("delete from invitations where target_user_id = ? and used_at is null", targetUserId)
            }
            one(
                """insert into invitations(household_id, code_hash, name, role, target_user_id, created_by, expires_at)
                   values (?, ?, ?, ?, ?, ?, ?) returning *""",
                hh, Crypto.sha256(normalizeCode(code)), name.trim().take(60), if (role == "admin") "admin" else "member",
                targetUserId, actorId, Instant.now().plusSeconds(ttl),
            ) { mapInvite(it) }!!
        }
        return inv to code
    }

    suspend fun invites(userId: UUID): List<Invitation> {
        requireAdmin(userId)
        return db.read {
            query(
                """select * from invitations where household_id = $HOUSEHOLD_OF
                   and (used_at is null and expires_at > now() or used_at > now() - interval '7 days')
                   order by created_at desc limit 50""",
                userId,
            ) { mapInvite(it) }
        }
    }

    suspend fun revokeInvite(userId: UUID, id: UUID): Boolean {
        requireAdmin(userId)
        return db.tx { exec("delete from invitations where id = ? and household_id = $HOUSEHOLD_OF and used_at is null", id, userId) } > 0
    }

    /** Invitación vigente para un código, o null. */
    suspend fun findInvite(code: String): InviteRecord? = db.read {
        one(
            "select * from invitations where code_hash = ? and used_at is null and expires_at > now()",
            Crypto.sha256(normalizeCode(code)),
        ) {
            InviteRecord(it.uuid("id"), it.uuid("household_id"), it.str("name"), it.str("role"), it.uuidOrNull("target_user_id"), it.instant("expires_at"))
        }
    }

    suspend fun preview(code: String): InvitePreview? {
        val inv = findInvite(code) ?: return null
        return db.read {
            val hhName = one("select name from households where id = ?", inv.householdId) { it.str("name") } ?: ""
            val target = inv.targetUserId?.let { t -> one("select display_name, email from users where id = ?", t) { it.str("display_name") to it.str("email") } }
            InvitePreview(
                kind = if (inv.targetUserId == null) "invite" else "reset",
                householdName = hhName,
                name = target?.first ?: inv.name,
                email = target?.second,
            )
        }
    }

    suspend fun markUsed(inviteId: UUID, usedBy: UUID) = db.tx {
        exec("update invitations set used_at = now(), used_by = ? where id = ?", usedBy, inviteId)
    }

    // --- Miembros ---

    suspend fun setRole(actorId: UUID, memberId: UUID, role: String): Member {
        requireAdmin(actorId)
        val r = if (role == "admin") "admin" else "member"
        val hh = householdId(actorId)
        db.tx {
            if (r == "member") {
                val admins = one("select count(*) as n from users where household_id = ? and role = 'admin' and id <> ?", hh, memberId) { it.getInt("n") } ?: 0
                if (admins == 0) throw FamilyException("La familia necesita al menos un administrador")
            }
            if (exec("update users set role = ? where id = ? and household_id = ?", r, memberId, hh) == 0) throw FamilyException("Ese miembro no es de tu familia")
        }
        invalidate()
        return members(actorId).first { it.id == memberId }
    }

    /**
     * Quita a un miembro y borra su cuenta y sus datos personales. Lo que había compartido con la familia
     * (listas, eventos, tareas, notas, lugares) pasa al administrador que lo quita, para que nadie lo pierda.
     */
    suspend fun removeMember(actorId: UUID, memberId: UUID) {
        requireAdmin(actorId)
        if (actorId == memberId) throw FamilyException("No puedes quitarte a ti mismo")
        val hh = householdId(actorId)
        db.tx {
            one("select 1 from users where id = ? and household_id = ?", memberId, hh) { true } ?: throw FamilyException("Ese miembro no es de tu familia")
            for (table in listOf("notes", "tasks", "lists", "places")) {
                exec("update $table set user_id = ? where user_id = ? and shared", actorId, memberId)
            }
            exec("update events set user_id = ?, google_event_id = null, google_calendar_id = null, google_etag = null where user_id = ? and shared", actorId, memberId)
            exec("delete from users where id = ?", memberId)
        }
        invalidate()
    }

    private suspend fun requireAdmin(userId: UUID) {
        if (!isAdmin(userId)) throw FamilyException("Solo un administrador de la familia puede hacer esto", 403)
    }

    private fun newCode(): String {
        val sb = StringBuilder()
        repeat(8) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.substring(0, 4) + "-" + sb.substring(4)
    }

    private fun mapMember(rs: ResultSet) = Member(rs.uuid("id"), rs.str("display_name"), rs.str("email"), rs.str("role"), rs.instant("created_at"))

    private fun mapInvite(rs: ResultSet): Invitation {
        val target = rs.uuidOrNull("target_user_id")
        return Invitation(
            id = rs.uuid("id"),
            kind = if (target == null) "invite" else "reset",
            name = rs.str("name"),
            role = rs.str("role"),
            targetUserId = target,
            createdAt = rs.instant("created_at"),
            expiresAt = rs.instant("expires_at"),
            usedAt = rs.instantOrNull("used_at"),
        )
    }

    companion object {
        /** Sin caracteres ambiguos (0/O, 1/I/L): 31 símbolos x 8 ≈ 40 bits, con límite de intentos en el endpoint. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun normalizeCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }
    }
}
