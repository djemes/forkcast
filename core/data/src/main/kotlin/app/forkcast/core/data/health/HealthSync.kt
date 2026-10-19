package app.forkcast.core.data.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        health.sync(SyncReason.BACKGROUND)
        runCatching { maintenance.updateIfDue() }
        return Result.success()
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
                    val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS).build()
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

    private companion object {
        const val PERIODIC = "health-sync"
        const val WRITE_DEBOUNCE_MS = 2_000L
    }
}
