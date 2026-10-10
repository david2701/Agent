package app.kairo.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
 * Servicio en primer plano (tipo micrófono) que mantiene a Kairo atento a "Oye Kairo".
 * Android exige la notificación permanente y muestra el indicador verde del micrófono.
 * También mantiene la conexión en tiempo real para recibir avisos al instante.
 */
class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { graph.prefs.setWakeWord(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TALK -> {
                graph.voice.talk()
                return START_STICKY
            }
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_LISTENING, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+: no se puede iniciar el micrófono desde segundo plano
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kairo:listening").apply {
                setReferenceCounted(false)
                acquire()
            }
            graph.realtime.acquire()
            graph.voice.onTurnFinished = { transcript, reply ->
                val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!inForeground && reply != null) {
                    Notifications.show(
                        this, Notifications.ID_ASSISTANT,
                        Notifications.simple(this, Channels.ASSISTANT, transcript?.let { "\"$it\"" } ?: "Kairo", reply, destination = "chat"),
                    )
                }
            }
        }
        scope.launch {
            val phrase = runCatching { graph.api.settings().settings.wakeWord }.getOrDefault("oye kairo")
            graph.voice.startLoop(phrase)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        graph.voice.stopLoop()
        graph.voice.onTurnFinished = null
        graph.realtime.release()
        runCatching { wakeLock?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, Channels.LISTENING)
        .setSmallIcon(R.drawable.ic_stat_kairo)
        .setColor(0xFFF0A23B.toInt())
        .setContentTitle("Kairo está atento")
        .setContentText("Di \"Oye Kairo\" o toca Hablar")
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(Notifications.openApp(this))
        .addAction(0, "Hablar", PendingIntent.getService(this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_TALK), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Pausar", PendingIntent.getService(this, 2, Intent(this, ListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_STOP = "app.kairo.android.STOP_LISTENING"
        const val ACTION_TALK = "app.kairo.android.TALK_NOW"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ListeningService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }
    }
}
