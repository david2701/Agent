package app.kairo.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kairo.android.graph
import app.kairo.android.widget.KairoWidget
import java.util.concurrent.TimeUnit

/** Trae del servidor los recordatorios y lugares, y reprograma alarmas y geovallas locales. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (g.session.current() == null) return Result.success()
        return try {
            val sync = g.api.deviceSync()
            g.alarms.apply(sync.reminders)
            g.geofences.apply(sync.reminders, sync.places)
            runCatching {
                val today = g.api.today()
                g.prefs.saveTodayCache(g.api.json.encodeToString(app.kairo.core.model.TodayData.serializer(), today))
                KairoWidget.refresh(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "kairo-sync"
        private const val ONCE = "kairo-sync-now"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context, delaySeconds: Long = 0) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
