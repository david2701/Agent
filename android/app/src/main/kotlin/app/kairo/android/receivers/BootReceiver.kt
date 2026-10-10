package app.kairo.android.receivers

import app.kairo.android.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.kairo.android.graph
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import app.kairo.android.service.ConnectionService
import app.kairo.android.service.ListeningService
import app.kairo.android.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a reboot or app update: reschedules alarms, reopens the direct connection if it was
 * on and, if listening was on, turns it back on or prompts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncWorker.schedulePeriodic(context)
        SyncWorker.runNow(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val local = context.graph.prefs.current()
                // specialUse can start from boot (unlike the microphone)
                if (local.directConnection && context.graph.session.current() != null) ConnectionService.start(context)
                if (boot && local.wakeWordEnabled) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ListeningService.start(context)
                    } else {
                        // Android 14+ does not allow opening the microphone from boot: a tap turns it back on
                        val n = Notifications.simple(context, Channels.LISTENING, context.getString(R.string.boot_rearm_title, context.getString(R.string.wake_phrase)), context.getString(R.string.boot_rearm_text), destination = "rearm")
                        Notifications.show(context, Notifications.ID_REARM, n)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
