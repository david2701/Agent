package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.util.zoneOf
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class ReminderPatch(
    val text: String? = null,
    val dueAt: Instant? = null,
    val rrule: String? = null,
    val clearRrule: Boolean = false,
    val placeId: UUID? = null,
    val placeTrigger: String? = null,
    val clearPlace: Boolean = false,
)

class RemindersRepo(private val db: Db, private val activity: ActivityRepo) {

    private val select = """select r.*, p.name as place_name from reminders r left join places p on p.id = r.place_id"""

    suspend fun list(userId: UUID, statuses: List<String>? = null, limit: Int = 300): List<Reminder> = db.read {
        val all = query(
            """$select where r.user_id = ? and r.deleted_at is null
               order by case r.status when 'fired' then 0 when 'scheduled' then 1 else 2 end,
                        r.next_fire_at nulls last, r.updated_at desc limit ?""",
            userId, limit,
        ) { map(it) }
        if (statuses == null) all else all.filter { it.status in statuses }
    }

    suspend fun get(userId: UUID, id: UUID): Reminder? = db.read { getTx(userId, id) }

    private fun Tx.getTx(userId: UUID, id: UUID): Reminder? =
        one("$select where r.user_id = ? and r.id = ? and r.deleted_at is null", userId, id) { map(it) }

    suspend fun create(
        userId: UUID,
        text: String,
        dueAt: Instant?,
        rrule: String?,
        timezone: String,
        placeId: UUID? = null,
        placeTrigger: String? = null,
        taskId: UUID? = null,
        source: String,
    ): Reminder {
        require(dueAt != null || placeId != null) { "El recordatorio necesita una fecha u un lugar" }
        val rule = Recurrence.normalize(rrule)
        val next = computeNext(dueAt, rule, timezone, Instant.now().minusSeconds(60))
        val reminder = db.tx {
            val id = one(
                """insert into reminders(user_id, text, due_at, rrule, timezone, place_id, place_trigger, next_fire_at, task_id, source)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""",
                userId, text.trim(), dueAt, rule, timezone, placeId, placeId?.let { placeTrigger ?: "arrive" }, next, taskId, source,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.REMINDER, reminder.id, "Recordatorio: ${reminder.text}", source)
        return reminder
    }

    suspend fun update(userId: UUID, id: UUID, patch: ReminderPatch, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val rule = if (patch.clearRrule) null else Recurrence.normalize(patch.rrule) ?: current.rrule
        val due = patch.dueAt ?: current.dueAt
        val placeId = if (patch.clearPlace) null else patch.placeId ?: current.placeId
        val scheduleChanged = patch.dueAt != null || patch.rrule != null || patch.clearRrule
        val next = if (scheduleChanged || current.status == "scheduled") computeNext(due, rule, current.timezone, Instant.now().minusSeconds(60)) else current.nextFireAt
        val status = if (scheduleChanged && (next != null || placeId != null)) "scheduled" else current.status
        val reminder = db.tx {
            exec(
                """update reminders set text = ?, due_at = ?, rrule = ?, place_id = ?, place_trigger = ?, next_fire_at = ?,
                   status = ?, updated_at = now() where user_id = ? and id = ?""",
                patch.text?.trim() ?: current.text, due, rule, placeId,
                if (placeId == null) null else patch.placeTrigger ?: current.placeTrigger ?: "arrive",
                next, status, userId, id,
            )
            getTx(userId, id)
        } ?: return null
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, "Recordatorio editado: ${reminder.text}", source)
        return reminder
    }

    /** Marca como hecho. Si es recurrente, sigue programado para la próxima vez. */
    suspend fun complete(userId: UUID, id: UUID, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val reminder = db.tx {
            if (current.rrule != null && current.status != "cancelled") {
                val next = computeNext(current.dueAt, current.rrule, current.timezone, maxOf(Instant.now(), current.nextFireAt ?: Instant.now()))
                exec(
                    "update reminders set status = ?, next_fire_at = ?, updated_at = now() where id = ?",
                    if (next != null) "scheduled" else "done", next, id,
                )
            } else {
                exec("update reminders set status = 'done', next_fire_at = null, updated_at = now() where id = ?", id)
            }
            getTx(userId, id)
        }
        activity.record(userId, Kinds.COMPLETED, EntityTypes.REMINDER, id, "Recordatorio hecho: ${current.text}", source)
        return reminder
    }

    suspend fun snooze(userId: UUID, id: UUID, minutes: Int, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val at = Instant.now().plusSeconds(minutes.coerceIn(1, 7 * 24 * 60) * 60L)
        val reminder = db.tx {
            exec("update reminders set status = 'scheduled', next_fire_at = ?, updated_at = now() where id = ?", at, id)
            getTx(userId, id)
        }
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, "Pospuesto $minutes min: ${current.text}", source)
        return reminder
    }

    suspend fun cancel(userId: UUID, id: UUID, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val reminder = db.tx {
            exec("update reminders set status = 'cancelled', next_fire_at = null, updated_at = now() where id = ?", id)
            getTx(userId, id)
        }
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, "Recordatorio cancelado: ${current.text}", source)
        return reminder
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        db.tx { exec("update reminders set deleted_at = now() where user_id = ? and id = ?", userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.REMINDER, id, "Recordatorio eliminado: ${current.text}", source)
        return current
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update reminders set deleted_at = null where user_id = ? and id = ?", userId, id) } > 0

    /** Recordatorios vencidos para el planificador. Bloquea filas para que una sola instancia los dispare. */
    suspend fun claimDue(now: Instant, handle: suspend (Reminder, UUID) -> Unit) {
        val due = db.tx {
            val rows = query(
                """$select where r.status = 'scheduled' and r.deleted_at is null and r.next_fire_at <= ?
                   order by r.next_fire_at limit 50 for update of r skip locked""",
                now,
            ) { map(it) to it.uuid("user_id") }
            rows.forEach { (r, _) ->
                val next = r.rrule?.let { computeNext(r.dueAt, it, r.timezone, maxOf(now, r.nextFireAt ?: now)) }
                exec(
                    "update reminders set status = ?, next_fire_at = ?, last_fired_at = ?, updated_at = now() where id = ?",
                    if (next != null) "scheduled" else "fired", next, now, r.id,
                )
            }
            rows
        }
        due.forEach { (r, userId) -> handle(r, userId) }
    }

    /** Recordatorios que el teléfono debe programar como alarmas locales o geovallas. */
    suspend fun forDevice(userId: UUID): List<Reminder> = db.read {
        query(
            "$select where r.user_id = ? and r.deleted_at is null and r.status = 'scheduled' order by r.next_fire_at nulls last limit 500",
            userId,
        ) { map(it) }
    }

    private fun computeNext(dueAt: Instant?, rrule: String?, tz: String, after: Instant): Instant? {
        if (dueAt == null) return null
        if (rrule == null) return dueAt
        if (dueAt.isAfter(after)) return dueAt
        return Recurrence.nextAfter(rrule, dueAt, zoneOf(tz), after)
    }

    private fun map(rs: ResultSet) = Reminder(
        id = rs.uuid("id"),
        text = rs.str("text"),
        dueAt = rs.instantOrNull("due_at"),
        rrule = rs.strOrNull("rrule"),
        timezone = rs.str("timezone"),
        placeId = rs.uuidOrNull("place_id"),
        placeName = rs.strOrNull("place_name"),
        placeTrigger = rs.strOrNull("place_trigger"),
        status = rs.str("status"),
        nextFireAt = rs.instantOrNull("next_fire_at"),
        lastFiredAt = rs.instantOrNull("last_fired_at"),
        taskId = rs.uuidOrNull("task_id"),
        source = rs.str("source"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
    )
}
