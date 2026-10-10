package app.kairo.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import app.kairo.android.notify.Notifications
import app.kairo.android.sync.SyncWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

open class KairoApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        startBackground()
    }

    /** Background work (WorkManager and realtime). UI tests skip it. */
    protected open fun startBackground() {
        WorkManager.initialize(this, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build())
        SyncWorker.schedulePeriodic(this)

        // Realtime while the app is visible
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.realtime.acquire()
                SyncWorker.runNow(this@KairoApplication)
            }
            override fun onStop(owner: LifecycleOwner) = graph.realtime.release()
        })

        // Own push: on opening the app and after sign-in, renews the registration if a distributor is chosen
        graph.scope.launch {
            graph.session.session.map { it?.user?.id }.distinctUntilChanged().filterNotNull().collect { graph.push.registerIfAcknowledged() }
        }
    }
}
