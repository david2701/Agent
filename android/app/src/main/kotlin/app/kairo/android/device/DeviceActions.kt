package app.kairo.android.device

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.kairo.android.R
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Acciones que el agente pide ejecutar en el teléfono (temporizadores y alarmas).
 * Se implementan con AlarmManager + notificaciones propias para que funcionen también
 * desde el servicio en segundo plano (Android bloquea abrir el reloj desde el fondo).
 */
class DeviceActions(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun handle(action: JsonObject) {
        when ((action["type"] as? JsonPrimitive)?.content) {
            "timer" -> {
                val secs = action["seconds"]?.jsonPrimitive?.intOrNull ?: return
                val label = action["label"]?.jsonPrimitive?.content ?: "Temporizador"
                startTimer(secs, label)
            }
            "alarm" -> {
                val h = action["hour"]?.jsonPrimitive?.intOrNull ?: return
                val m = action["minute"]?.jsonPrimitive?.intOrNull ?: 0
                val label = action["label"]?.jsonPrimitive?.content ?: "Alarma"
                val days = (action["days"])?.let { runCatching { it.jsonArray.map { d -> d.jsonPrimitive.content } }.getOrNull() } ?: emptyList()
                setAlarm(h, m, label, days)
            }
        }
    }

    fun startTimer(seconds: Int, label: String) {
        val end = System.currentTimeMillis() + seconds * 1000L
        val id = (end / 1000).toInt()
        schedule(id, end, "Temporizador: $label", "Se cumplió el tiempo")
        // Notificación con cuenta regresiva en vivo
        val n = NotificationCompat.Builder(context, Channels.TIMERS)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(label)
            .setContentText("Temporizador en curso")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(end)
            .setOngoing(true)
            .setSilent(true)
            .setTimeoutAfter(seconds * 1000L)
            .setContentIntent(Notifications.openApp(context))
            .addAction(0, "Cancelar", cancelIntent(id))
            .build()
        Notifications.show(context, TIMER_BASE + id, n)
    }

    fun setAlarm(hour: Int, minute: Int, label: String, days: List<String>) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val allowed = days.mapNotNull { DAY[it.lowercase().take(3)] }.toSet()
        var date = LocalDate.now(zone)
        var at = LocalDateTime.of(date, LocalTime.of(hour, minute))
        var guard = 0
        while ((at <= now || (allowed.isNotEmpty() && at.dayOfWeek !in allowed)) && guard++ < 8) {
            date = date.plusDays(1)
            at = LocalDateTime.of(date, LocalTime.of(hour, minute))
        }
        val millis = at.atZone(zone).toInstant().toEpochMilli()
        schedule((millis / 1000).toInt(), millis, label, "Alarma de las %02d:%02d".format(hour, minute))
    }

    private fun schedule(id: Int, atMillis: Long, title: String, text: String) {
        val pi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, TimerReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (alarms.canScheduleExactAlarms()) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, Notifications.openApp(context)), pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancelIntent(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, -id,
        Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_CANCEL).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val TIMER_BASE = 50_000
        private val DAY = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
            "lun" to DayOfWeek.MONDAY, "mar" to DayOfWeek.TUESDAY, "mie" to DayOfWeek.WEDNESDAY, "jue" to DayOfWeek.THURSDAY,
            "vie" to DayOfWeek.FRIDAY, "sab" to DayOfWeek.SATURDAY, "dom" to DayOfWeek.SUNDAY,
        )
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        if (intent.action == ACTION_CANCEL) {
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, TimerReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            pi?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
            Notifications.cancel(context, DeviceActions.TIMER_BASE + id)
            return
        }
        Notifications.cancel(context, DeviceActions.TIMER_BASE + id)
        val n = NotificationCompat.Builder(context, Channels.TIMERS)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(intent.getStringExtra("title") ?: "Kairo")
            .setContentText(intent.getStringExtra("text") ?: "")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(Notifications.openApp(context))
            .build()
        Notifications.show(context, DeviceActions.TIMER_BASE + id + 1, n)
    }

    companion object {
        const val ACTION_CANCEL = "app.kairo.android.CANCEL_TIMER"
    }
}
