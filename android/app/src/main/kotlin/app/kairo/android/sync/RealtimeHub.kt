package app.kairo.android.sync

import android.content.Context
import app.kairo.android.data.FiredLog
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import app.kairo.core.model.ServerEvent
import app.kairo.core.net.KairoApi
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Conexión WebSocket con el servidor. Se mantiene viva mientras la app está en primer plano
 * o mientras corre el servicio de escucha (contador de referencias).
 */
class RealtimeHub(private val context: Context, private val api: KairoApi, private val scope: CoroutineScope) {
    private val users = AtomicInteger(0)
    private var job: Job? = null
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Colección que cambió en el servidor ("note", "task", "reminder"...). */
    val changes: SharedFlow<String> = _changes

    @Synchronized
    fun acquire() {
        if (users.incrementAndGet() == 1) start()
    }

    @Synchronized
    fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        job?.cancel()
        job = scope.launch { api.realtime().collect { handle(it) } }
    }

    private fun handle(ev: ServerEvent) {
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
                    Notifications.show(context, Notifications.reminderNotificationId(id), Notifications.reminder(context, id, str("text") ?: "Recordatorio", at))
                }
                _changes.tryEmit("reminder")
            }
            "event.alert" -> {
                val key = "ev:${str("id")}:${str("startAt")}:${str("minutes")}"
                val at = str("alertAt")?.toInstantOrNull()?.toEpochMilli() ?: System.currentTimeMillis()
                if (FiredLog(context).markIfNew(key, at)) {
                    val minutes = str("minutes")?.toIntOrNull() ?: 0
                    val sub = (if (minutes == 0) "Empieza ahora" else "En $minutes min") + (str("location")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    Notifications.show(context, key.hashCode(), Notifications.simple(context, Channels.REMINDERS, str("title") ?: "Evento", sub, destination = "calendar"))
                }
            }
            "briefing.ready" -> {
                val text = str("text") ?: return
                Notifications.show(context, Notifications.ID_BRIEFING, Notifications.simple(context, Channels.BRIEFING, "Tu día", text, destination = "home"))
            }
            "recording.updated" -> _changes.tryEmit("recording")
        }
    }
}
