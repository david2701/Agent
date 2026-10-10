package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.IntArrayParam
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.intList
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.services.NoticeText
import app.kairo.services.Notifier
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.localized
import app.kairo.util.say
import app.kairo.util.humanDate
import app.kairo.util.humanDateTime
import app.kairo.util.zoneOf
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class EventPatch(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val allDay: Boolean? = null,
    val rrule: String? = null,
    val clearRrule: Boolean = false,
    val reminderMinutes: List<Int>? = null,
    val color: String? = null,
    val shared: Boolean? = null,
)

/** Data of an event read from Google Calendar. */
data class GoogleEventData(
    val googleId: String,
    val calendarId: String,
    val etag: String?,
    val recurringId: String?,
    val title: String,
    val description: String,
    val location: String,
    val startAt: Instant,
    val endAt: Instant,
    val allDay: Boolean,
    val timezone: String,
    val cancelled: Boolean,
)

class EventsRepo(private val db: Db, private val activity: ActivityRepo, private val notifier: Notifier) {

    /** Events in a range (the user's own and the family's), expanding the recurring ones created in Kairo. */
    suspend fun range(userId: UUID, from: Instant, to: Instant): List<Event> {
        val rows = db.read {
            query(
                """select * from events where ${visibleTo()} and deleted_at is null
                   and ((rrule is null and start_at < ? and end_at > ?) or (rrule is not null and start_at < ?))
                   order by start_at""",
                userId, userId, to, from, to,
            ) { map(it) }
        }
        val out = ArrayList<Event>()
        for (e in rows) {
            if (e.rrule == null) {
                out += e
            } else {
                val dur = Duration.between(e.startAt, e.endAt)
                val starts = Recurrence.between(e.rrule, e.startAt, zoneOf(e.timezone), from.minus(dur), to)
                starts.forEach { s -> out += e.copy(startAt = s, endAt = s.plus(dur)) }
            }
        }
        return out.sortedBy { it.startAt }
    }

    suspend fun get(userId: UUID, id: UUID): Event? = db.read { getTx(userId, id) }

    /** Alerts of events whose alert time falls in [from, to). */
    suspend fun alerts(userId: UUID, from: Instant, to: Instant): List<EventAlert> =
        range(userId, from, to.plusSeconds(8L * 86400))
            .filter { !it.allDay }
            .flatMap { e -> e.reminderMinutes.map { m -> EventAlert(e.id, e.title, e.startAt, e.startAt.minusSeconds(m * 60L), m, e.location, e.shared) } }
            .filter { !it.alertAt.isBefore(from) && it.alertAt.isBefore(to) }
            .sortedBy { it.alertAt }

    private fun Tx.getTx(userId: UUID, id: UUID): Event? =
        one("select * from events where ${visibleTo()} and id = ? and deleted_at is null", userId, userId, id) { map(it) }

    suspend fun create(
        userId: UUID,
        title: String,
        startAt: Instant,
        endAt: Instant,
        allDay: Boolean,
        timezone: String,
        description: String = "",
        location: String = "",
        rrule: String? = null,
        reminderMinutes: List<Int> = listOf(15),
        color: String? = null,
        source: String,
        shared: Boolean = false,
    ): Event {
        val event = db.tx {
            val id = one(
                """insert into events(user_id, household_id, shared, title, description, location, start_at, end_at, all_day, rrule, timezone,
                   reminder_minutes, color, source, dirty) values (?, $HOUSEHOLD_OF, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'kairo', true) returning id""",
                userId, userId, shared, title.trim(), description, location, startAt, if (endAt.isAfter(startAt)) endAt else startAt.plusSeconds(3600),
                allDay, Recurrence.normalize(rrule), timezone, IntArrayParam(reminderMinutes), color,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.EVENT, event.id, say(M.ACT_EVENT, event.title), source, shared = event.shared)
        if (event.shared) {
            notifier.everyone(userId, NoticeKinds.SHARED, NoticeText(say(M.NOTICE_EVENT_ADDED, notifier.who(userId)), describe(event)), EntityTypes.EVENT, event.id)
        }
        return event
    }

    /** "Mia's checkup · Thursday, October 15 at 10:00 AM · Clinic", in the reader's language. */
    private fun describe(e: Event) = localized { lang ->
        val zone = zoneOf(e.timezone)
        val whenText = if (e.allDay) M.WHEN_ALL_DAY.t(lang, humanDate(e.startAt.atZone(zone).toLocalDate(), lang = lang)) else humanDateTime(e.startAt, zone, lang = lang)
        listOf(e.title, whenText, e.location).filter { it.isNotBlank() }.joinToString(" · ")
    }

    suspend fun update(userId: UUID, id: UUID, patch: EventPatch, source: String): Event? {
        val c = get(userId, id) ?: return null
        if (patch.shared != null && patch.shared != c.shared && c.ownerId != userId) {
            throw FamilyException(M.ONLY_OWNER_SHARE_EVENT)
        }
        val start = patch.startAt ?: c.startAt
        var end = patch.endAt ?: if (patch.startAt != null) start.plus(Duration.between(c.startAt, c.endAt)) else c.endAt
        if (!end.isAfter(start)) end = start.plusSeconds(3600)
        val event = db.tx {
            exec(
                """update events set title = ?, description = ?, location = ?, start_at = ?, end_at = ?, all_day = ?, rrule = ?,
                   reminder_minutes = ?, color = ?, shared = ?, dirty = true, updated_at = now() where ${visibleTo()} and id = ?""",
                patch.title?.trim() ?: c.title, patch.description ?: c.description, patch.location ?: c.location,
                start, end, patch.allDay ?: c.allDay,
                if (patch.clearRrule) null else Recurrence.normalize(patch.rrule) ?: c.rrule,
                IntArrayParam(patch.reminderMinutes ?: c.reminderMinutes), patch.color ?: c.color, patch.shared ?: c.shared,
                userId, userId, id,
            )
            getTx(userId, id)
        } ?: return null
        activity.record(userId, Kinds.UPDATED, EntityTypes.EVENT, id, say(M.ACT_EVENT_EDITED, event.title), source, shared = event.shared || c.shared)
        if (event.shared) {
            val who = notifier.who(userId)
            when {
                !c.shared -> notifier.everyone(userId, NoticeKinds.SHARED, NoticeText(say(M.NOTICE_EVENT_ADDED, who), describe(event)), EntityTypes.EVENT, id)
                event.startAt != c.startAt || event.allDay != c.allDay ->
                    notifier.everyone(userId, NoticeKinds.UPDATED, NoticeText(say(M.NOTICE_EVENT_CHANGED, who), describe(event)), EntityTypes.EVENT, id)
            }
        }
        return event
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Event? {
        val c = get(userId, id) ?: return null
        db.tx { exec("update events set deleted_at = now(), dirty = true, updated_at = now() where ${visibleTo()} and id = ?", userId, userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.EVENT, id, say(M.ACT_EVENT_DELETED, c.title), source, shared = c.shared)
        if (c.shared) {
            notifier.everyone(userId, NoticeKinds.UPDATED, NoticeText(say(M.NOTICE_EVENT_CANCELLED, notifier.who(userId)), describe(c)), EntityTypes.EVENT, id)
        }
        return c
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update events set deleted_at = null, dirty = true, updated_at = now() where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    // --- Google sync ---

    data class DirtyEvent(val event: Event, val deleted: Boolean, val googleCalendarId: String?, val googleEtag: String?)

    suspend fun dirty(userId: UUID): List<DirtyEvent> = db.read {
        query("select * from events where user_id = ? and dirty and source in ('kairo','google') order by updated_at limit 200", userId) {
            DirtyEvent(map(it), it.getObject("deleted_at") != null, it.strOrNull("google_calendar_id"), it.strOrNull("google_etag"))
        }
    }

    suspend fun markSynced(id: UUID, googleId: String?, calendarId: String?, etag: String?) = db.tx {
        exec(
            "update events set dirty = false, google_event_id = coalesce(?, google_event_id), google_calendar_id = coalesce(?, google_calendar_id), google_etag = coalesce(?, google_etag) where id = ?",
            googleId, calendarId, etag, id,
        )
    }

    suspend fun purge(id: UUID) = db.tx { exec("delete from events where id = ?", id) }

    /** Inserts or updates an event that came from Google. It does not overwrite pending local changes. */
    suspend fun upsertFromGoogle(userId: UUID, g: GoogleEventData): String = db.tx {
        val existing = one("select id, dirty from events where user_id = ? and google_event_id = ?", userId, g.googleId) {
            it.uuid("id") to it.getBoolean("dirty")
        }
        when {
            existing != null && existing.second -> "skipped"
            g.cancelled && existing != null -> {
                exec("update events set deleted_at = coalesce(deleted_at, now()), updated_at = now() where id = ?", existing.first); "deleted"
            }
            g.cancelled -> "ignored"
            existing != null -> {
                exec(
                    """update events set title = ?, description = ?, location = ?, start_at = ?, end_at = ?, all_day = ?, timezone = ?,
                       google_etag = ?, google_calendar_id = ?, google_recurring_id = ?, deleted_at = null, updated_at = now() where id = ?""",
                    g.title, g.description, g.location, g.startAt, g.endAt, g.allDay, g.timezone, g.etag, g.calendarId, g.recurringId, existing.first,
                ); "updated"
            }
            else -> {
                // household_id is mandatory since V2; what comes from Google is personal (shared = false)
                exec(
                    """insert into events(user_id, household_id, shared, title, description, location, start_at, end_at, all_day, timezone, source,
                       google_event_id, google_calendar_id, google_etag, google_recurring_id, dirty)
                       values (?, $HOUSEHOLD_OF, false, ?, ?, ?, ?, ?, ?, ?, 'google', ?, ?, ?, ?, false)""",
                    userId, userId, g.title, g.description, g.location, g.startAt, g.endAt, g.allDay, g.timezone,
                    g.googleId, g.calendarId, g.etag, g.recurringId,
                ); "created"
            }
        }
    }

    private fun map(rs: ResultSet) = Event(
        id = rs.uuid("id"),
        title = rs.str("title"),
        description = rs.str("description"),
        location = rs.str("location"),
        startAt = rs.instant("start_at"),
        endAt = rs.instant("end_at"),
        allDay = rs.getBoolean("all_day"),
        rrule = rs.strOrNull("rrule"),
        timezone = rs.str("timezone"),
        reminderMinutes = rs.intList("reminder_minutes"),
        color = rs.strOrNull("color"),
        source = rs.str("source"),
        googleEventId = rs.strOrNull("google_event_id"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        shared = rs.getBoolean("shared"),
        ownerId = rs.uuid("user_id"),
    )
}
