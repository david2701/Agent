package app.kairo.android.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import app.kairo.android.data.AppPrefs
import app.kairo.android.data.FiredLog
import app.kairo.android.graph
import app.kairo.android.notify.Notifications
import app.kairo.android.sync.SyncWorker
import app.kairo.core.model.Place
import app.kairo.core.model.Reminder
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Programa los recordatorios como alarmas exactas locales: suenan aunque el teléfono
 * no tenga internet o el servidor esté caído. El servidor también los envía por WebSocket;
 * [FiredLog] evita la notificación duplicada.
 */
class AlarmScheduler(private val context: Context, private val prefs: AppPrefs) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    suspend fun apply(reminders: List<Reminder>, eventAlerts: List<app.kairo.core.model.EventAlert> = emptyList()) {
        val now = System.currentTimeMillis()
        val horizon = now + 35L * 86_400_000
        val wanted = reminders.filter { it.status == "scheduled" }
            .mapNotNull { r -> r.nextFireAt?.toInstantOrNull()?.toEpochMilli()?.let { r to it } }
            .filter { (_, at) -> at in (now - 60_000)..horizon }
            .sortedBy { it.second }
            .take(100)
        val events = eventAlerts.mapNotNull { a -> a.alertAt.toInstantOrNull()?.toEpochMilli()?.let { a to it } }
            .filter { (_, at) -> at in (now - 60_000)..horizon }.take(100)
        val wantedIds = wanted.map { it.first.id }.toSet() + events.map { eventKey(it.first) }
        prefs.scheduledAlarms().filter { it !in wantedIds }.forEach { cancel(it) }
        wanted.forEach { (r, at) -> schedule(r, at) }
        events.forEach { (a, at) -> scheduleEvent(a, at) }
        prefs.setScheduledAlarms(wantedIds)
    }

    private fun eventKey(a: app.kairo.core.model.EventAlert) = "ev:${a.eventId}:${a.startAt}:${a.minutes}"

    private fun scheduleEvent(a: app.kairo.core.model.EventAlert, at: Long) {
        val key = eventKey(a)
        val subtitle = (if (a.minutes == 0) "Empieza ahora" else "En ${a.minutes} min") + (a.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        val pi = PendingIntent.getBroadcast(
            context, key.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, key).putExtra(EXTRA_TEXT, a.title).putExtra(EXTRA_AT, at)
                .putExtra(EXTRA_KIND, "event").putExtra(EXTRA_SUBTITLE, subtitle),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val trigger = maxOf(at, System.currentTimeMillis() + 1_000)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
    }

    /** Alarma diaria del resumen de la mañana (hora configurada en el servidor). */
    fun scheduleBriefing(enabled: Boolean, time: String) {
        val pi = PendingIntent.getBroadcast(context, 9_001, Intent(context, BriefingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pi)
        if (!enabled) return
        val t = runCatching { java.time.LocalTime.parse(time) }.getOrDefault(java.time.LocalTime.of(7, 0))
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(t).atZone(zone)
        if (!next.isAfter(java.time.ZonedDateTime.now(zone))) next = next.plusDays(1)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }

    private fun intent(id: String, text: String = "", at: Long = 0) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(r: Reminder, at: Long) {
        val pi = intent(r.id, r.text, at)
        val trigger = maxOf(at, System.currentTimeMillis() + 1_000)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun cancel(id: String) = alarms.cancel(intent(id))

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AT = "at"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SUBTITLE = "subtitle"
    }
}

/** Avisa que el resumen del día está listo; "Escuchar" lo pide al servidor y lo lee en voz alta. */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (FiredLog(context).markIfNew("briefing", java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) {
            val listen = PendingIntent.getActivity(
                context, 9_002,
                Intent(context, app.kairo.android.assist.AssistActivity::class.java).setAction(app.kairo.android.assist.AssistActivity.ACTION_BRIEFING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = androidx.core.app.NotificationCompat.Builder(context, app.kairo.android.notify.Channels.BRIEFING)
                .setSmallIcon(app.kairo.android.R.drawable.ic_stat_kairo)
                .setColor(0xFFF0A23B.toInt())
                .setContentTitle("Tu resumen del día")
                .setContentText("Agenda, pendientes y clima de hoy")
                .setAutoCancel(true)
                .setContentIntent(listen)
                .addAction(0, "Escuchar", listen)
                .build()
            Notifications.show(context, Notifications.ID_BRIEFING, n)
        }
        SyncWorker.runNow(context) // reprograma la alarma de mañana
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT) ?: "Recordatorio"
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis())
        if (intent.getStringExtra(AlarmScheduler.EXTRA_KIND) == "event") {
            if (FiredLog(context).markIfNew(id, at)) {
                val sub = intent.getStringExtra(AlarmScheduler.EXTRA_SUBTITLE) ?: ""
                Notifications.show(context, id.hashCode(), Notifications.simple(context, app.kairo.android.notify.Channels.REMINDERS, text, sub, destination = "calendar"))
            }
            return
        }
        if (FiredLog(context).markIfNew(id, at)) {
            Notifications.show(context, Notifications.reminderNotificationId(id), Notifications.reminder(context, id, text, at))
        }
        // Reprograma la siguiente ocurrencia de los recurrentes
        SyncWorker.runNow(context, delaySeconds = 90)
    }
}

/** Botones "Hecho" y "10 min más" de la notificación. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        Notifications.cancel(context, Notifications.reminderNotificationId(id))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val api = context.graph.api
                when (intent.action) {
                    ACTION_DONE -> api.reminderDone(id)
                    ACTION_SNOOZE -> api.reminderSnooze(id, 10)
                }
            } catch (_: Exception) {
                // Sin conexión: se reintentará al sincronizar; el aviso local ya se descartó
            } finally {
                SyncWorker.runNow(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val ACTION_DONE = "app.kairo.android.REMINDER_DONE"
        const val ACTION_SNOOZE = "app.kairo.android.REMINDER_SNOOZE"
    }
}

/**
 * Recordatorios por lugar con alertas de proximidad del sistema (sin Google Play Services).
 * Requiere ubicación precisa y "permitir todo el tiempo".
 */
class GeofenceManager(private val context: Context, private val prefs: AppPrefs) {
    private val lm = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun apply(reminders: List<Reminder>, places: List<Place>) {
        val byId = places.associateBy { it.id }
        val wanted = reminders.filter { it.status == "scheduled" && it.placeId != null && byId.containsKey(it.placeId) }
        val wantedIds = wanted.map { it.id }.toSet()
        prefs.geofences().filter { it !in wantedIds }.forEach { lm.removeProximityAlert(intent(it, "", "", "arrive")) }
        if (!hasPermission()) {
            prefs.setGeofences(emptySet())
            return
        }
        wanted.forEach { r ->
            val p = byId.getValue(r.placeId!!)
            lm.addProximityAlert(p.latitude, p.longitude, p.radiusM.toFloat(), -1, intent(r.id, r.text, p.name, r.placeTrigger ?: "arrive"))
        }
        prefs.setGeofences(wantedIds)
    }

    private fun intent(id: String, text: String, place: String, trigger: String) = PendingIntent.getBroadcast(
        context, ("geo$id").hashCode(),
        Intent(context, ProximityReceiver::class.java).putExtra("id", id).putExtra("text", text).putExtra("place", place).putExtra("trigger", trigger),
        // Mutable: el sistema añade KEY_PROXIMITY_ENTERING
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val trigger = intent.getStringExtra("trigger") ?: "arrive"
        if ((trigger == "arrive") != entering) return
        val id = intent.getStringExtra("id") ?: return
        val text = intent.getStringExtra("text") ?: "Recordatorio"
        val place = intent.getStringExtra("place") ?: ""
        val now = System.currentTimeMillis()
        if (FiredLog(context).markIfNew(id, now - now % (30 * 60_000))) {
            val subtitle = if (entering) "Llegaste a $place" else "Saliste de $place"
            Notifications.show(context, Notifications.reminderNotificationId(id), Notifications.reminder(context, id, text, now, subtitle))
        }
    }
}
