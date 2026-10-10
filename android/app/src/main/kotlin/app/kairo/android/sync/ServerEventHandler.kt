package app.kairo.android.sync

import app.kairo.android.R
import android.content.Context
import app.kairo.android.data.FiredLog
import app.kairo.android.family.FamilyStore
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import app.kairo.core.model.ServerEvent
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles server events, whether they arrive over WebSocket ([RealtimeHub]) or push
 * ([app.kairo.android.push.KairoPushService]). The same notice can arrive both ways
 * and through the local alarm or sync: [FiredLog] makes it show only once.
 */
class ServerEventHandler(private val context: Context, private val json: Json, private val family: FamilyStore) {
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Collection that changed on the server ("note", "task", "family", "notice"...). */
    val changes: SharedFlow<String> = _changes

    fun handle(ev: ServerEvent) {
        fun str(k: String) = (ev.data[k] as? JsonPrimitive)?.content
        when (ev.type) {
            "entity.changed" -> {
                val entity = str("entity") ?: return
                _changes.tryEmit(entity)
                if (entity == "reminder" || entity == "place") SyncWorker.runNow(context, delaySeconds = 2)
            }
            "reminder.fire" -> {
                val id = str("id") ?: return
                val at = str("fireAt")?.toInstantOrNull()?.toEpochMilli() ?: System.currentTimeMillis()
                if (FiredLog(context).markIfNew(id, at)) {
                    val from = family.firstName(str("createdBy"))?.let { context.getString(R.string.rem_from, it) }
                    Notifications.show(context, Notifications.reminderNotificationId(id), Notifications.reminder(context, id, str("text") ?: context.getString(R.string.notif_reminder_default), at, from))
                }
                _changes.tryEmit("reminder")
            }
            "event.alert" -> {
                val key = "ev:${str("id")}:${str("startAt")}:${str("minutes")}"
                val at = str("alertAt")?.toInstantOrNull()?.toEpochMilli() ?: System.currentTimeMillis()
                if (FiredLog(context).markIfNew(key, at)) {
                    val minutes = str("minutes")?.toIntOrNull() ?: 0
                    val sub = eventSubtitle(context, minutes, str("location"), str("shared") == "true")
                    Notifications.show(context, key.hashCode(), Notifications.simple(context, Channels.REMINDERS, str("title") ?: context.getString(R.string.event_default_title), sub, destination = "calendar"))
                }
            }
            "briefing.ready" -> {
                val text = str("text") ?: return
                Notifications.show(context, Notifications.ID_BRIEFING, Notifications.simple(context, Channels.BRIEFING, context.getString(R.string.home_your_day), text, destination = "home"))
            }
            ServerEvents.FAMILY_NOTICE -> {
                val notice = ServerEvents.notice(json, ev) ?: return
                if (FiredLog(context).markNoticeIfNew(notice.id)) Notifications.showFamilyNotice(context, ServerEvents.alert(notice))
                _changes.tryEmit("notice")
            }
            "recording.updated" -> _changes.tryEmit("recording")
        }
    }

    companion object {
        /** "In 15 min - Downtown - Family" */
        fun eventSubtitle(context: Context, minutes: Int, location: String?, shared: Boolean): String =
            listOfNotNull(
                if (minutes == 0) context.getString(R.string.event_starts_now) else context.getString(R.string.event_in_minutes, minutes),
                location?.takeIf { it.isNotBlank() },
                if (shared) context.getString(R.string.shared_family) else null,
            ).joinToString(" \u00B7 ")
    }
}
