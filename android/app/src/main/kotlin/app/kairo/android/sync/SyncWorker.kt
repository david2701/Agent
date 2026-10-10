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
import app.kairo.android.data.FiredLog
import app.kairo.android.graph
import app.kairo.android.notify.Notifications
import app.kairo.android.widget.KairoWidget
import app.kairo.core.model.Notice
import java.util.concurrent.TimeUnit

/**
 * Fetches reminders and places from the server and reschedules local alarms and geofences.
 * It also shows the family notices that did not arrive by push or WebSocket.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (g.session.current() == null) return Result.success()
        return try {
            val sync = g.api.deviceSync()
            g.alarms.apply(sync.reminders, sync.eventAlerts, from = g.family::firstName)
            g.alarms.scheduleBriefing(sync.briefingEnabled, sync.briefingTime)
            g.geofences.apply(sync.reminders, sync.places)
            showNotices(sync.notices)
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

    /** Unread notices this phone has not shown yet (same log as push and WebSocket). */
    private fun showNotices(notices: List<Notice>) {
        val log = FiredLog(applicationContext)
        val fresh = notices.filter { it.readAt == null }.sortedBy { it.createdAt }.filter { log.markNoticeIfNew(it.id) }
        if (fresh.size > MAX_SINGLE_NOTICES) {
            // Many at once (e.g. the first start): a single summary instead of filling the tray
            Notifications.show(applicationContext, Notifications.ID_FAMILY_SUMMARY, Notifications.familySummary(applicationContext, fresh.size, fresh.reversed().map { it.title }, groupSummary = false))
        } else {
            fresh.forEach { Notifications.showFamilyNotice(applicationContext, ServerEvents.alert(it)) }
        }
    }

    companion object {
        private const val MAX_SINGLE_NOTICES = 4
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
