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
import app.kairo.android.sync.NoticeAlert

object Channels {
    const val REMINDERS = "reminders"
    const val TIMERS = "timers"
    const val ASSISTANT = "assistant"
    const val LISTENING = "listening"
    const val BRIEFING = "briefing"
    const val RECORDING = "recording"
    const val FAMILY = "family"
    const val CONNECTION = "connection"
}

object Notifications {
    const val ID_LISTENING = 1001
    const val ID_RECORDING = 1002
    const val ID_ASSISTANT = 1003
    const val ID_BRIEFING = 1004
    const val ID_REARM = 1005
    const val ID_CONNECTED = 1006
    const val ID_FAMILY_SUMMARY = 1007
    private const val GROUP_FAMILY = "app.kairo.android.FAMILY"

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
                NotificationChannel(Channels.FAMILY, "Familia", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mensajes de tu familia, tareas que te asignan y cambios en lo compartido"
                    enableVibration(true)
                },
                NotificationChannel(Channels.CONNECTION, "Conexión directa", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Indicador permanente mientras Kairo mantiene la conexión con tu servidor"
                    setShowBadge(false)
                },
            ),
        )
    }

    /** Abre la app en [destination]; con [noticeId], además marca ese aviso de la familia como leído. */
    fun openApp(context: Context, destination: String? = null, noticeId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, (destination + (noticeId ?: "")).hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
                noticeId?.let { putExtra(MainActivity.EXTRA_NOTICE, it) }
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

    /** Aviso de la familia; si ya hay otros en la bandeja, se agrupan bajo un resumen. */
    fun showFamilyNotice(context: Context, n: NoticeAlert) {
        val builder = NotificationCompat.Builder(context, Channels.FAMILY)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(n.title)
            .setCategory(if (n.kind == "message") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_FAMILY)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, n.destination, n.id))
        if (n.body.isNotBlank()) builder.setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        show(context, n.notificationId, builder.build())

        val nm = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { nm.activeNotifications.filter { it.notification.group == GROUP_FAMILY && it.id != ID_FAMILY_SUMMARY } }.getOrDefault(emptyList())
        if (active.size >= 2) {
            val titles = active.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
            show(context, ID_FAMILY_SUMMARY, familySummary(context, active.size, titles))
        }
    }

    /**
     * Resumen de varios avisos de la familia: encabeza el grupo en la bandeja o, con
     * [groupSummary] en false, reemplaza a muchos avisos que trae de golpe la sincronización.
     */
    fun familySummary(context: Context, count: Int, titles: List<String>, groupSummary: Boolean = true): Notification {
        val style = NotificationCompat.InboxStyle().setSummaryText("Familia")
        titles.take(5).forEach { style.addLine(it) }
        val builder = NotificationCompat.Builder(context, Channels.FAMILY)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle("$count avisos de la familia")
            .setContentText(titles.take(3).joinToString(", "))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "family"))
        if (groupSummary) builder.setGroup(GROUP_FAMILY).setGroupSummary(true)
        return builder.build()
    }
}
