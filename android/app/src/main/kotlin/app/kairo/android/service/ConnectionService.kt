package app.kairo.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.kairo.android.R
import app.kairo.android.graph
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Direct connection to your server": a light foreground service (`specialUse` type) for people who
 * do not install ntfy or use the wake word. It only keeps the WebSocket of [app.kairo.android.sync.RealtimeHub]
 * open so notices arrive instantly. The person turns it on in Settings; it never starts by itself.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { graph.prefs.setDirectConnection(false); stopSelf() }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_CONNECTED, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            graph.realtime.acquire()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (holding) graph.realtime.release()
        holding = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, Channels.CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_kairo)
        .setColor(0xFFF0A23B.toInt())
        .setContentTitle(getString(R.string.conn_title))
        .setContentText(getString(R.string.conn_text))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(Notifications.openApp(this, "settings"))
        .addAction(0, getString(R.string.conn_disable), PendingIntent.getService(this, 3, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_STOP = "app.kairo.android.STOP_CONNECTION"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
