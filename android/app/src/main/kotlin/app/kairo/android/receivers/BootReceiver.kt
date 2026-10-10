package app.kairo.android.receivers

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
 * Tras reiniciar o actualizar la app: reprograma alarmas, vuelve a abrir la conexión directa si
 * estaba activa y, si la escucha estaba activa, la reactiva o avisa.
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
                // specialUse puede arrancar desde el arranque del sistema (a diferencia del micrófono)
                if (local.directConnection && context.graph.session.current() != null) ConnectionService.start(context)
                if (boot && local.wakeWordEnabled) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ListeningService.start(context)
                    } else {
                        // Android 14+ no permite abrir el micrófono desde el arranque: un toque lo reactiva
                        val n = Notifications.simple(context, Channels.LISTENING, "Reactiva \"Oye Kairo\"", "Toca para que Kairo vuelva a escuchar después del reinicio.", destination = "rearm")
                        Notifications.show(context, Notifications.ID_REARM, n)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
