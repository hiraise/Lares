package app.lares.android.notifications

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lares.android.LaresApplication
import app.lares.android.R
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.calendar.CalendarReminderPlanner
import app.lares.client.data.tasks.TaskReminderPlanner
import app.lares.client.notifications.BackgroundSync
import app.lares.client.platform.AppStartup
import app.lares.client.sync.SyncEngine
import app.lares.contract.api.notifications.NotificationChannel
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * One sync cycle in the background (plan M0-09): periodic every 15 minutes, and expedited after a push tickle.
 * The engine is single-flight, so a run while the app syncs anyway just waits for that cycle.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = LaresApplication.koin
        // The process may have been started for this work: wait until the saved session is restored.
        koin.get<AppStartup>().ready.first { it }
        if (koin.get<SessionManager>().state.value !is SessionState.LoggedIn) return Result.success()
        // Failures are retried by the engine's own triggers and the next periodic run, not by WorkManager.
        koin.get<SyncEngine>().syncNow()
        // The process may not run the UI collector: keep task and calendar reminders in step with what the sync brought.
        koin.get<TaskReminderPlanner>().reconcile()
        koin.get<CalendarReminderPlanner>().reconcile()
        return Result.success()
    }

    /** Expedited work runs as a foreground service before Android 12; it needs a quiet notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification =
            NotificationCompat
                .Builder(applicationContext, NotificationChannel.SYSTEM.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.sync_in_progress))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    private companion object {
        const val FOREGROUND_ID = 1001
    }
}

/**
 * [BackgroundSync] on WorkManager; work needs a network connection. [syncEngine] also gets a direct request, so a
 * tickle arriving while a run is already in progress is not lost (unique work keeps the running one).
 */
class WorkManagerBackgroundSync(
    context: Context,
    private val syncEngine: () -> SyncEngine,
) : BackgroundSync {
    private val workManager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC)
        workManager.cancelUniqueWork(NOW)
    }

    override fun requestNow() {
        syncEngine().request()
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC = "lares-sync-periodic"
        const val NOW = "lares-sync-now"
    }
}
