package app.kairo.android.sync

import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Conexión WebSocket con el servidor. Se mantiene viva mientras la app está en primer plano,
 * mientras corre el servicio de escucha o la conexión directa (contador de referencias).
 * Los eventos los procesa [ServerEventHandler], el mismo que usa el push.
 */
class RealtimeHub(private val api: KairoApi, private val scope: CoroutineScope, private val handler: ServerEventHandler) {
    private val users = AtomicInteger(0)
    private var job: Job? = null

    /** Colección que cambió en el servidor ("note", "task", "reminder"...). */
    val changes: SharedFlow<String> get() = handler.changes

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
        job = scope.launch { api.realtime().collect { handler.handle(it) } }
    }
}
