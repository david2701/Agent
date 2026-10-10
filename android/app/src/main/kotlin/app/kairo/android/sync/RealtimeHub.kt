package app.kairo.android.sync

import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket connection to the server. It stays alive while the app is in the foreground,
 * while the listening service or the direct connection runs (reference counted).
 * Events are processed by [ServerEventHandler], the same one push uses.
 */
class RealtimeHub(private val api: KairoApi, private val scope: CoroutineScope, private val handler: ServerEventHandler) {
    private val users = AtomicInteger(0)
    private var job: Job? = null

    /** Collection that changed on the server ("note", "task", "reminder"...). */
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
