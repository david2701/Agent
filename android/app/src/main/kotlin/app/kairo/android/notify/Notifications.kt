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
                NotificationChannel(Channels.REMINDERS, context.getString(R.string.ch_reminders_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_reminders_desc)
                    enableVibration(true)
                    setSound(alarmSound, alarmAttrs)
                },
                NotificationChannel(Channels.TIMERS, context.getString(R.string.ch_timers_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_timers_desc)
                    enableVibration(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                },
                NotificationChannel(Channels.ASSISTANT, context.getString(R.string.ch_assistant_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.ch_assistant_desc)
                    setSound(null, null)
                },
                NotificationChannel(Channels.LISTENING, context.getString(R.string.ch_listening_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_listening_desc, context.getString(R.string.wake_phrase))
                    setShowBadge(false)
                },
                NotificationChannel(Channels.BRIEFING, context.getString(R.string.ch_briefing_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.ch_briefing_desc)
                },
                NotificationChannel(Channels.RECORDING, context.getString(R.string.ch_recording_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_recording_desc)
                    setShowBadge(false)
                },
                NotificationChannel(Channels.FAMILY, context.getString(R.string.ch_family_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_family_desc)
                    enableVibration(true)
                },
                NotificationChannel(Channels.CONNECTION, context.getString(R.string.ch_connection_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_connection_desc)
                    setShowBadge(false)
                },
            ),
        )
    }

    /** Opens the app at [destination]; with [noticeId], it also marks that family notice as read. */
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
            .setContentText(subtitle ?: context.getString(R.string.notif_reminder_default))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(fireAtMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "reminders"))
            .addAction(0, context.getString(R.string.home_reminder_done), action(ReminderActionReceiver.ACTION_DONE, 1))
            .addAction(0, context.getString(R.string.notif_snooze_10), action(ReminderActionReceiver.ACTION_SNOOZE, 2))
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

    /** Family notice; if there are others in the tray, they are grouped under a summary. */
    fun showFamilyNotice(context: Context, n: NoticeAlert) {
        val builder = NotificationCompat.Builder(context, Channels.FAMILY)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(n.title.ifBlank { context.getString(R.string.family_notice_default_title) })
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
     * Summary of several family notices: it heads the group in the tray or, with
     * [groupSummary] false, replaces many notices that sync brings at once.
     */
    fun familySummary(context: Context, count: Int, titles: List<String>, groupSummary: Boolean = true): Notification {
        val style = NotificationCompat.InboxStyle().setSummaryText(context.getString(R.string.notif_family_summary))
        titles.take(5).forEach { style.addLine(it) }
        val builder = NotificationCompat.Builder(context, Channels.FAMILY)
            .setSmallIcon(R.drawable.ic_stat_kairo)
            .setColor(0xFFF0A23B.toInt())
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_family_count, count, count))
            .setContentText(titles.take(3).joinToString(", "))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "family"))
        if (groupSummary) builder.setGroup(GROUP_FAMILY).setGroupSummary(true)
        return builder.build()
    }
}
