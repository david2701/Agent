package app.kairo.android.sync

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
 * Maneja los eventos del servidor, lleguen por WebSocket ([RealtimeHub]) o por push
 * ([app.kairo.android.push.KairoPushService]). El mismo aviso puede llegar por los dos caminos
 * y por la alarma local o la sincronización: [FiredLog] hace que se muestre una sola vez.
 */
class ServerEventHandler(private val context: Context, private val json: Json, private val family: FamilyStore) {
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Colección que cambió en el servidor ("note", "task", "family", "notice"...). */
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
                    val from = family.firstName(str("createdBy"))?.let { "De $it" }
                    Notifications.show(context, Notifications.reminderNotificationId(id), Notifications.reminder(context, id, str("text") ?: "Recordatorio", at, from))
                }
                _changes.tryEmit("reminder")
            }
            "event.alert" -> {
                val key = "ev:${str("id")}:${str("startAt")}:${str("minutes")}"
                val at = str("alertAt")?.toInstantOrNull()?.toEpochMilli() ?: System.currentTimeMillis()
                if (FiredLog(context).markIfNew(key, at)) {
                    val minutes = str("minutes")?.toIntOrNull() ?: 0
                    val sub = eventSubtitle(minutes, str("location"), str("shared") == "true")
                    Notifications.show(context, key.hashCode(), Notifications.simple(context, Channels.REMINDERS, str("title") ?: "Evento", sub, destination = "calendar"))
                }
            }
            "briefing.ready" -> {
                val text = str("text") ?: return
                Notifications.show(context, Notifications.ID_BRIEFING, Notifications.simple(context, Channels.BRIEFING, "Tu día", text, destination = "home"))
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
        /** "En 15 min · Envigado · Familiar" */
        fun eventSubtitle(minutes: Int, location: String?, shared: Boolean): String =
            listOfNotNull(
                if (minutes == 0) "Empieza ahora" else "En $minutes min",
                location?.takeIf { it.isNotBlank() },
                if (shared) "Familiar" else null,
            ).joinToString(" · ")
    }
}
