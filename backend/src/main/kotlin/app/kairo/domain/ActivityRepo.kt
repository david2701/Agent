package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.realtime.EventBus
import app.kairo.util.Lang
import app.kairo.util.Say
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

object Kinds {
    const val CREATED = "created"
    const val UPDATED = "updated"
    const val DELETED = "deleted"
    const val COMPLETED = "completed"
    const val FIRED = "fired"
    const val RESTORED = "restored"
}

/**
 * Log of everything that changes (whether the agent, the dashboard or the app did it).
 * It shows what Kairo did and allows undoing creations and deletions.
 * Summaries are stored in the language of whoever acted ([langOf]).
 */
class ActivityRepo(
    private val db: Db,
    private val bus: EventBus,
    private val family: FamilyRepo,
    private val langOf: suspend (UUID) -> Lang = { Lang.DEFAULT },
) {

    /** Records a change whose summary is rendered in the actor's language. */
    suspend fun record(
        userId: UUID,
        kind: String,
        entityType: String,
        entityId: UUID?,
        summary: Say,
        source: String,
        shared: Boolean = false,
        alsoTo: Collection<UUID> = emptyList(),
    ): Unit = record(userId, kind, entityType, entityId, summary.text(runCatching { langOf(userId) }.getOrDefault(Lang.DEFAULT)), source, shared, alsoTo)

    suspend fun record(
        userId: UUID,
        kind: String,
        entityType: String,
        entityId: UUID?,
        summary: String,
        source: String,
        /** If it is something shared, the change reaches the whole family live. */
        shared: Boolean = false,
        /** Other users who must refresh (e.g. the person a reminder was set for). */
        alsoTo: Collection<UUID> = emptyList(),
    ) {
        db.tx {
            exec(
                "insert into activity(user_id, kind, entity_type, entity_id, summary, source) values (?, ?, ?, ?, ?, ?)",
                userId, kind, entityType, entityId, summary.take(300), source,
            )
        }
        bus.entityChanged(audience(userId, shared, alsoTo), entityType, entityId, kind)
    }

    /** Tells the connected clients about a minor change that is not stored in the history. */
    suspend fun notify(userId: UUID, entityType: String, entityId: UUID?, kind: String, shared: Boolean = false) =
        bus.entityChanged(audience(userId, shared, emptyList()), entityType, entityId, kind)

    private suspend fun audience(userId: UUID, shared: Boolean, alsoTo: Collection<UUID>): Set<UUID> =
        ((if (shared) family.memberIds(userId) else listOf(userId)) + alsoTo).toSet()

    suspend fun list(userId: UUID, limit: Int = 50, before: Instant? = null): List<Activity> = db.read {
        query(
            """select * from activity where user_id = ? and (?::timestamptz is null or created_at < ?)
               order by created_at desc limit ?""",
            userId, before, before, limit.coerceIn(1, 200),
        ) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Activity? = db.read {
        one("select * from activity where user_id = ? and id = ?", userId, id) { map(it) }
    }

    suspend fun markUndone(id: UUID) = db.tx { exec("update activity set undone_at = now() where id = ?", id) }

    private fun map(rs: ResultSet): Activity {
        val kind = rs.str("kind")
        val type = rs.str("entity_type")
        val undone = rs.instantOrNull("undone_at")
        return Activity(
            id = rs.uuid("id"),
            kind = kind,
            entityType = type,
            entityId = rs.uuidOrNull("entity_id"),
            summary = rs.str("summary"),
            source = rs.str("source"),
            undoable = undone == null && (kind == Kinds.CREATED || kind == Kinds.DELETED) && type in UNDOABLE,
            undoneAt = undone,
            createdAt = rs.instant("created_at"),
        )
    }

    companion object {
        val UNDOABLE = setOf(
            EntityTypes.NOTE, EntityTypes.TASK, EntityTypes.REMINDER, EntityTypes.LIST,
            EntityTypes.EVENT, EntityTypes.MEMORY, EntityTypes.PLACE,
        )
    }
}
