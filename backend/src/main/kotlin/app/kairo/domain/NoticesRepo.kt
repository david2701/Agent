@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.UuidArray
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.util.InstantSerializer
import app.kairo.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Notice for a member: something the family shared, an assigned task, a message. */
@Serializable
data class Notice(
    val id: UUID,
    val kind: String,
    val title: String,
    val body: String,
    val actorId: UUID?,
    val entityType: String?,
    val entityId: UUID?,
    val createdAt: Instant,
    val readAt: Instant?,
)

object NoticeKinds {
    /** Someone shared something new with the family. */
    const val SHARED = "shared"
    /** Something shared that affects you changed (an event moved or cancelled, a task completed). */
    const val UPDATED = "updated"
    /** You were assigned a task. */
    const val ASSIGNED = "assigned"
    /** Another member set a reminder for you. */
    const val REMINDER = "reminder"
    /** Direct message from the family ("tell everyone dinner is ready"). */
    const val MESSAGE = "message"
}

class NoticesRepo(private val db: Db) {

    suspend fun insert(userId: UUID, actorId: UUID?, kind: String, title: String, body: String, entityType: String?, entityId: UUID?): Notice = db.tx {
        one(
            """insert into notices(user_id, actor_id, kind, title, body, entity_type, entity_id)
               values (?, ?, ?, ?, ?, ?, ?) returning *""",
            userId, actorId, kind, title.take(200), body.take(1000), entityType, entityId,
        ) { map(it) }!!
    }

    suspend fun list(userId: UUID, limit: Int = 50, unreadOnly: Boolean = false): List<Notice> = db.read {
        query(
            "select * from notices where user_id = ? and (not ? or read_at is null) order by created_at desc limit ?",
            userId, unreadOnly, limit.coerceIn(1, 200),
        ) { map(it) }
    }

    /** Unread from the last hours, so the phone shows them on sync if the push never arrived. */
    suspend fun recentUnread(userId: UUID, since: Instant): List<Notice> = db.read {
        query(
            "select * from notices where user_id = ? and read_at is null and created_at > ? order by created_at limit 50",
            userId, since,
        ) { map(it) }
    }

    suspend fun unreadCount(userId: UUID): Int =
        db.read { one("select count(*) as n from notices where user_id = ? and read_at is null", userId) { it.getInt("n") } } ?: 0

    suspend fun markRead(userId: UUID, ids: List<UUID>?): Int = db.tx {
        if (ids == null) exec("update notices set read_at = now() where user_id = ? and read_at is null", userId)
        else exec("update notices set read_at = now() where user_id = ? and read_at is null and id = any(?)", userId, UuidArray(ids))
    }

    /** Cleanup: old notices are no longer useful. */
    suspend fun prune() = db.tx { exec("delete from notices where created_at < now() - interval '60 days'") }

    private fun map(rs: ResultSet) = Notice(
        id = rs.uuid("id"),
        kind = rs.str("kind"),
        title = rs.str("title"),
        body = rs.str("body"),
        actorId = rs.uuidOrNull("actor_id"),
        entityType = rs.strOrNull("entity_type"),
        entityId = rs.uuidOrNull("entity_id"),
        createdAt = rs.instant("created_at"),
        readAt = rs.instantOrNull("read_at"),
    )
}
