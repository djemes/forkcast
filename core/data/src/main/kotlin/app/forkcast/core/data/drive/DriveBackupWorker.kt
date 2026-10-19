package app.forkcast.core.data.drive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.forkcast.core.data.R
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.Notifier
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.domain.BackupRetention
import app.forkcast.core.domain.NotificationRules
import app.forkcast.core.model.BackupSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The nightly (or weekly) Drive backup (SPEC §4.11). */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drive: DriveBackupRepository,
    private val problems: DriveProblemNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val problem = drive.backupNow() ?: return Result.success()
        return when (problem) {
            // Passing trouble: WorkManager tries again later (exponential backoff from 30 min).
            DriveProblem.OFFLINE, DriveProblem.BUSY, DriveProblem.ERROR ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else { problems.notifyIfOverdue(problem); Result.success() }
            DriveProblem.NOT_CONNECTED -> Result.success()
            // Needs the user: say so now.
            DriveProblem.RECONNECT, DriveProblem.NOT_SET_UP, DriveProblem.QUOTA_FULL -> { problems.notify(problem); Result.success() }
        }
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}

/** Plans the backup work from the settings and whether Drive is connected on this phone. */
@Singleton
class DriveBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val device: DevicePrefs,
    private val clock: Clock,
) {
    /** Keeps the schedule in step with the settings (first pass keeps an existing schedule). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var first = true
            combine(settings.settings, device.state) { s, d -> s.backup to (d.driveAccount != null) }
                .distinctUntilChanged()
                .collect { (backup, connected) ->
                    schedule(backup, connected, replace = !first)
                    first = false
                }
        }
    }

    fun schedule(backup: BackupSettings, connected: Boolean, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!connected || !backup.auto) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (backup.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(backup.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(backup.frequency.days.toLong(), TimeUnit.DAYS)
            // First run at night (around 3 am), then every period from there.
            .setInitialDelay(BackupRetention.delayUntil(ZonedDateTime.now(clock)).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** "Back up now" while the app may leave the screen: a one-off run with the same worker. */
    fun runOnce() {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val NAME = "drive_backup"
    }
}

/** "Backup problems" notifications (channel BACKUP_FAILED), silent during quiet hours. */
@Singleton
class DriveProblemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val device: DevicePrefs,
    private val clock: Clock,
) {
    suspend fun notify(problem: DriveProblem) {
        val s = settings.settings.first()
        if (!s.notifications.backupFailedEnabled) return
        val text = context.getString(
            when (problem) {
                DriveProblem.RECONNECT -> R.string.notif_backup_reconnect
                DriveProblem.NOT_SET_UP -> R.string.notif_backup_not_set_up
                DriveProblem.QUOTA_FULL -> R.string.notif_backup_quota
                DriveProblem.OFFLINE -> R.string.notif_backup_offline
                else -> R.string.notif_backup_error
            },
        )
        val now = java.time.LocalTime.now(clock)
        val quiet = NotificationRules.inQuietHours(now.hour * 60 + now.minute, s.notifications.quietStartMinute, s.notifications.quietEndMinute)
        notifier.post(Channel.BACKUP_FAILED, NOTIFICATION_ID, context.getString(R.string.notif_backup_title), text, OPEN, silent = quiet)
    }

    /** Transient trouble only matters once backups are clearly behind (two periods without a success). */
    suspend fun notifyIfOverdue(problem: DriveProblem) {
        val period = settings.settings.first().backup.frequency.days
        val last = device.state.value.lastDriveBackupAt?.let(Instant::ofEpochMilli)
        if (BackupRetention.overdue(last, Instant.EPOCH, period, clock.instant())) notify(problem)
    }

    companion object {
        const val NOTIFICATION_ID = 7_001
        /** Opens Settings › Backup & data. */
        const val OPEN = "backup"
    }
}
