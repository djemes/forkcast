package app.forkcast.core.data.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.forkcast.core.data.db.HealthDao
import app.forkcast.core.data.repository.MaintenanceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Hourly sync while connected; reads only when background reads are granted (SPEC §4.9). */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val health: HealthRepository,
    private val maintenance: MaintenanceRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // syncSoon(): just push the pending writes (a glass from the shortcut), no reads.
        if (inputData.getBoolean(KEY_WRITES_ONLY, false)) {
            health.sync(SyncReason.WRITES)
            return Result.success()
        }
        health.sync(SyncReason.BACKGROUND)
        runCatching { maintenance.updateIfDue() }
        return Result.success()
    }

    companion object {
        const val KEY_WRITES_ONLY = "writesOnly"
    }
}

/** Wires the Health Connect sync up for the app's lifetime (called once from the Application). */
@Singleton
class HealthSyncStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val dao: HealthDao,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // Periodic work exists only while connected.
        scope.launch {
            health.status.map { it.settings.connected }.distinctUntilChanged().collect { connected ->
                val wm = WorkManager.getInstance(context)
                if (connected) {
                    val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                        // Skipped on a low battery; the app syncs on open anyway.
                        .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .build()
                    wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    wm.cancelUniqueWork(PERIODIC)
                }
            }
        }
        // Log and water changes go out within a few seconds while the app is running.
        scope.launch {
            combine(dao.observePendingLogCount(), dao.observePendingWaterCount()) { a, b -> a + b }
                .filter { it > 0 }
                .debounce(WRITE_DEBOUNCE_MS)
                .collect { runCatching { health.sync(SyncReason.WRITES) } }
        }
    }

    /**
     * Sends pending writes within seconds even if the app process goes away right after, e.g. a
     * glass added from the "+1 glass" shortcut. A no-op sync when Health Connect isn't connected.
     */
    fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInitialDelay(WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf(HealthSyncWorker.KEY_WRITES_ONLY to true))
            .build()
        // Append, not replace: replacing could cancel a run in the middle of a Health Connect write.
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "health-sync"
        const val SOON = "health-sync-soon"
        const val WRITE_DEBOUNCE_MS = 2_000L
    }
}
