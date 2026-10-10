package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.services.NoticeText
import app.kairo.services.Notifier
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.localized
import app.kairo.util.requireInput
import app.kairo.util.say
import app.kairo.util.humanDateTime
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

/**
 * Reminders. They are personal, but a member can set one for another ("remind Sofía to...").
 * Whoever set it sees it in their list and can change or cancel it; it rings on the phone of whoever receives it.
 */
class RemindersRepo(private val db: Db, private val activity: ActivityRepo, private val notifier: Notifier) {

    /** The place name only comes out if the place still exists and the reminder's owner can see it. */
    private val select = """select r.*, p.name as place_name from reminders r left join places p on p.id = r.place_id
        and p.deleted_at is null
        and (p.user_id = r.user_id or (p.shared and p.household_id = (select ru.household_id from users ru where ru.id = r.user_id)))"""

    /** The reminder is [userId]'s or they set it for someone else. Two parameters: userId, userId. */
    private val mine = "(r.user_id = ? or r.created_by = ?)"

    suspend fun list(userId: UUID, statuses: List<String>? = null, limit: Int = 300): List<Reminder> = db.read {
        val all = query(
            """$select where $mine and r.deleted_at is null
               order by case r.status when 'fired' then 0 when 'scheduled' then 1 else 2 end,
                        r.next_fire_at nulls last, r.updated_at desc limit ?""",
            userId, userId, limit,
        ) { map(it) }
        if (statuses == null) all else all.filter { it.status in statuses }
    }

    suspend fun get(userId: UUID, id: UUID): Reminder? = db.read { getTx(userId, id) }

    private fun Tx.getTx(userId: UUID, id: UUID): Reminder? =
        one("$select where $mine and r.id = ? and r.deleted_at is null", userId, userId, id) { map(it) }

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
        /** Another member who sets it for [userId]. */
        createdBy: UUID? = null,
    ): Reminder {
        requireInput(dueAt != null || placeId != null, M.REMINDER_NEEDS_DATE_OR_PLACE)
        val rule = Recurrence.normalize(rrule)
        val next = computeNext(dueAt, rule, timezone, Instant.now().minusSeconds(60))
        val by = createdBy?.takeIf { it != userId }
        placeId?.let { requirePlaceVisible(userId, it) }
        val reminder = db.tx {
            val id = one(
                """insert into reminders(user_id, text, due_at, rrule, timezone, place_id, place_trigger, next_fire_at, task_id, source, created_by)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""",
                userId, text.trim(), dueAt, rule, timezone, placeId, placeId?.let { placeTrigger ?: "arrive" }, next, taskId, source, by,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        if (by == null) {
            activity.record(userId, Kinds.CREATED, EntityTypes.REMINDER, reminder.id, say(M.ACT_REMINDER, reminder.text), source)
        } else {
            // It stays in the activity of whoever set it (so they can undo it) and refreshes on both sides
            activity.record(by, Kinds.CREATED, EntityTypes.REMINDER, reminder.id, say(M.ACT_REMINDER_FOR, notifier.who(userId), reminder.text), source, alsoTo = listOf(userId))
            val body = localized { lang ->
                val whenText = reminder.nextFireAt?.let { humanDateTime(it, zoneOf(timezone), lang = lang) }
                    ?: reminder.placeName?.let { M.WHEN_AT_PLACE_ARRIVE.t(lang, it) }
                listOfNotNull(reminder.text, whenText).joinToString(" · ")
            }
            notifier.family(by, listOf(userId), NoticeKinds.REMINDER, NoticeText(say(M.NOTICE_REMINDER_SET, notifier.who(by)), body), EntityTypes.REMINDER, reminder.id)
        }
        return reminder
    }

    /** Besides the owner, refreshes whoever set it (or the other way around). */
    private fun others(userId: UUID, r: Reminder): List<UUID> = listOfNotNull(r.ownerId, r.createdBy).filter { it != userId }

    suspend fun update(userId: UUID, id: UUID, patch: ReminderPatch, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        // Same as on create: the place has to be available to whoever receives the reminder
        if (!patch.clearPlace && patch.placeId != null && patch.placeId != current.placeId) requirePlaceVisible(current.ownerId ?: userId, patch.placeId)
        val rule = if (patch.clearRrule) null else Recurrence.normalize(patch.rrule) ?: current.rrule
        val due = patch.dueAt ?: current.dueAt
        val placeId = if (patch.clearPlace) null else patch.placeId ?: current.placeId
        val scheduleChanged = patch.dueAt != null || patch.rrule != null || patch.clearRrule
        val next = if (scheduleChanged || current.status == "scheduled") computeNext(due, rule, current.timezone, Instant.now().minusSeconds(60)) else current.nextFireAt
        val status = if (scheduleChanged && (next != null || placeId != null)) "scheduled" else current.status
        val reminder = db.tx {
            exec(
                """update reminders set text = ?, due_at = ?, rrule = ?, place_id = ?, place_trigger = ?, next_fire_at = ?,
                   status = ?, updated_at = now() where id = ?""",
                patch.text?.trim() ?: current.text, due, rule, placeId,
                if (placeId == null) null else patch.placeTrigger ?: current.placeTrigger ?: "arrive",
                next, status, id,
            )
            getTx(userId, id)
        } ?: return null
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, say(M.ACT_REMINDER_EDITED, reminder.text), source, alsoTo = others(userId, reminder))
        return reminder
    }

    /** Marks as done. If it is recurring, it stays scheduled for the next time. */
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
        activity.record(userId, Kinds.COMPLETED, EntityTypes.REMINDER, id, say(M.ACT_REMINDER_DONE, current.text), source, alsoTo = others(userId, current))
        return reminder
    }

    suspend fun snooze(userId: UUID, id: UUID, minutes: Int, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val at = Instant.now().plusSeconds(minutes.coerceIn(1, 7 * 24 * 60) * 60L)
        val reminder = db.tx {
            exec("update reminders set status = 'scheduled', next_fire_at = ?, updated_at = now() where id = ?", at, id)
            getTx(userId, id)
        }
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, say(M.ACT_REMINDER_SNOOZED, minutes, current.text), source, alsoTo = others(userId, current))
        return reminder
    }

    suspend fun cancel(userId: UUID, id: UUID, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        val reminder = db.tx {
            exec("update reminders set status = 'cancelled', next_fire_at = null, updated_at = now() where id = ?", id)
            getTx(userId, id)
        }
        activity.record(userId, Kinds.UPDATED, EntityTypes.REMINDER, id, say(M.ACT_REMINDER_CANCELLED, current.text), source, alsoTo = others(userId, current))
        return reminder
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Reminder? {
        val current = get(userId, id) ?: return null
        db.tx { exec("update reminders set deleted_at = now() where id = ?", id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.REMINDER, id, say(M.ACT_REMINDER_DELETED, current.text), source, alsoTo = others(userId, current))
        return current
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update reminders r set deleted_at = null where $mine and r.id = ?", userId, userId, id) } > 0

    /** Due reminders for the scheduler. It locks rows so a single instance fires them. */
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

    /** Reminders the phone must schedule as local alarms or geofences. */
    suspend fun forDevice(userId: UUID): List<Reminder> = db.read {
        query(
            "$select where r.user_id = ? and r.deleted_at is null and r.status = 'scheduled' order by r.next_fire_at nulls last limit 500",
            userId,
        ) { map(it) }
    }

    /** The place exists, is not deleted and [ownerId] can see it (their own or shared in their family). */
    private suspend fun requirePlaceVisible(ownerId: UUID, placeId: UUID) {
        val ok = db.read {
            one("select 1 as ok from places where id = ? and deleted_at is null and ${visibleTo()}", placeId, ownerId, ownerId) { true }
        } ?: false
        requireInput(ok, M.PLACE_NOT_AVAILABLE)
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
        ownerId = rs.uuid("user_id"),
        createdBy = rs.uuidOrNull("created_by"),
    )
}
