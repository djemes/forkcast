package app.forkcast.core.data.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.health.SyncReason
import app.forkcast.core.domain.NotificationRules
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs due reminders, then schedules itself for the next one. Inexact on purpose (SPEC §4.8:
 * ±15 min is fine), so no exact-alarm permission is needed.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: NotificationEngine,
    private val scheduler: ReminderScheduler,
    private val health: HealthRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A scale reading that reached Health Connect skips the weigh-in reminder (SPEC §4.8).
        runCatching { health.sync(SyncReason.BACKGROUND) }
        // The chain is only this worker rescheduling itself: plan the next run even if this one
        // failed, or no reminder would fire until the app was opened again.
        val ran = try {
            runCatching { engine.runReminders() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        } finally {
            runCatching { scheduler.scheduleNext() }
        }
        return if (ran.isSuccess) Result.success() else Result.failure()
    }
}

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: NotificationEngine,
    private val clock: Clock,
) {
    /** (Re)plans the single reminder chain; call at startup and when notification settings change. */
    suspend fun scheduleNext() {
        val wm = WorkManager.getInstance(context)
        val now = LocalTime.now(clock).let { it.hour * 60 + it.minute }
        val minutes = NotificationRules.minutesUntilNext(now, engine.reminderMinutes())
        if (minutes == null) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            // A minute late rather than early, so "now >= reminder time" holds when it runs.
            .setInitialDelay(minutes.toLong() + 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val NAME = "reminders"
    }
}
