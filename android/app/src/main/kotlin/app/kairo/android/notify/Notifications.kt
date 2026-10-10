package app.kairo.android.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import app.kairo.android.MainActivity
import app.kairo.android.R
import app.kairo.android.assist.AssistActivity
import app.kairo.android.reminders.ReminderActionReceiver

object Channels {
    const val REMINDERS = "reminders"
    const val TIMERS = "timers"
    const val ASSISTANT = "assistant"
    const val LISTENING = "listening"
    const val BRIEFING = "briefing"
    const val RECORDING = "recording"
}

object Notifications {
    const val ID_LISTENING = 1001
    const val ID_RECORDING = 1002
    const val ID_ASSISTANT = 1003
    const val ID_BRIEFING = 1004
    const val ID_REARM = 1005

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(Channels.REMINDERS, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos de tus recordatorios"
                    enableVibration(true)
                    setSound(alarmSound, alarmAttrs)
                },
                NotificationChannel(Channels.TIMERS, "Temporizadores y alarmas", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Temporizadores y alarmas que pides por voz"
                    enableVibration(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                },
                NotificationChannel(Channels.ASSISTANT, "Respuestas de Kairo", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Lo que Kairo respondió cuando hablaste con la pantalla apagada"
                    setSound(null, null)
                },
                NotificationChannel(Channels.LISTENING, "Escucha continua", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Indicador permanente mientras Kairo espera \"Oye Kairo\""
                    setShowBadge(false)
                },
                NotificationChannel(Channels.BRIEFING, "Resumen del día", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tu resumen de la mañana"
                },
                NotificationChannel(Channels.RECORDING, "Grabaciones", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Grabación de reuniones en curso"
                    setShowBadge(false)
                },
            ),
        )
    }

    fun openApp(context: Context, destination: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, destination.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun talkIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 77,
            Intent(context, AssistActivity::class.java).setAction(AssistActivity.ACTION_TALK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun reminder(context: Context, id: String, text: String, fireAtMillis: Long, subtitle: String? = null): Notification {
        fun action(kind: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
            context, (id + kind).hashCode() + req,
            Intent(context, ReminderActionReceiver::class.java).setAction(kind).putExtra(ReminderActionReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(text)
            .setContentText(subtitle ?: "Recordatorio")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(fireAtMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "reminders"))
            .addAction(0, "Hecho", action(ReminderActionReceiver.ACTION_DONE, 1))
            .addAction(0, "10 min más", action(ReminderActionReceiver.ACTION_SNOOZE, 2))
            .build()
    }

    fun show(context: Context, id: Int, n: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    fun simple(context: Context, channel: String, title: String, text: String, destination: String? = null): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, destination))
            .build()

    fun reminderNotificationId(id: String) = id.hashCode()
}
