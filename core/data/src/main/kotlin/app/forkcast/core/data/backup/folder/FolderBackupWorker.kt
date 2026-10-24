package app.forkcast.core.data.backup.folder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
import app.forkcast.core.model.BackupFrequency
import app.forkcast.core.model.BackupSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The weekly (or daily) backup to the chosen folder (roadmap 2.0, task 1.4). */
@HiltWorker
class FolderBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: FolderBackupRepository,
    private val device: DevicePrefs,
    private val problems: FolderProblemNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val problem = backups.backupNow() ?: return Result.success().also { problems.clear() }
        return when (problem) {
            FolderProblem.NO_FOLDER -> Result.success()
            // Needs the user: say so now.
            FolderProblem.FOLDER_LOST, FolderProblem.PASSWORD_MISSING -> { problems.notify(problem); Result.success() }
            // Passing trouble: try again later (backoff from 30 min); the second failure in a row notifies.
            FolderProblem.WRITE_FAILED -> {
                if (device.state.value.backupFailures >= NOTIFY_AFTER) problems.notify(problem)
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 3
        const val NOTIFY_AFTER = 2
    }
}

/** Plans the backup work from the settings and whether a folder is chosen on this phone. */
@Singleton
class FolderBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val device: DevicePrefs,
    private val clock: java.time.Clock,
) {
    /** Keeps the schedule in step with the settings (first pass keeps an existing schedule). */
    fun start(scope: CoroutineScope) {
        // The no-backup reminder counts from the first run on this phone.
        if (device.state.value.firstSeenAt == null) device.update { it.copy(firstSeenAt = clock.millis()) }
        // The 1.0–1.4 Drive backup's schedule goes with Drive.
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_DRIVE)
        scope.launch {
            var first = true
            combine(settings.settings, device.state) { s, d -> s.backup.schedule() to (d.backupFolder != null) }
                .distinctUntilChanged()
                .collect { (backup, chosen) ->
                    schedule(backup, chosen, replace = !first)
                    first = false
                }
        }
    }

    /** Only what changes the schedule (keep, photos and password are read at run time). */
    private fun BackupSettings.schedule() = BackupSettings(auto = auto, frequency = frequency, weekday = weekday)

    fun schedule(backup: BackupSettings, chosen: Boolean, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!chosen || !backup.auto) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val now = ZonedDateTime.now(clock)
        // First run at night (around 3 am; weekly: on the chosen weekday, Monday by default), then every period from there.
        val delay = if (backup.frequency == BackupFrequency.WEEKLY) BackupRetention.delayUntilWeekday(now, backup.weekday) else BackupRetention.delayUntil(now)
        val request = PeriodicWorkRequestBuilder<FolderBackupWorker>(backup.frequency.days.toLong(), TimeUnit.DAYS)
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val NAME = "folder_backup"
        private const val LEGACY_DRIVE = "drive_backup"
    }
}

/** "Backup problems" notifications (channel BACKUP_FAILED), silent during quiet hours. */
@Singleton
class FolderProblemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val clock: java.time.Clock,
) {
    suspend fun notify(problem: FolderProblem) {
        val s = settings.settings.first()
        if (!s.notifications.backupFailedEnabled) return
        val text = context.getString(
            when (problem) {
                FolderProblem.FOLDER_LOST, FolderProblem.NO_FOLDER -> R.string.notif_backup_folder_lost
                FolderProblem.PASSWORD_MISSING -> R.string.notif_backup_password_missing
                FolderProblem.WRITE_FAILED -> R.string.notif_backup_write_failed
            },
        )
        val now = java.time.LocalTime.now(clock)
        val quiet = NotificationRules.inQuietHours(now.hour * 60 + now.minute, s.notifications.quietStartMinute, s.notifications.quietEndMinute)
        notifier.post(Channel.BACKUP_FAILED, NOTIFICATION_ID, context.getString(R.string.notif_backup_title), text, OPEN, silent = quiet)
    }

    /** A backup worked: an old problem notification is out of date. */
    fun clear() = notifier.cancel(NOTIFICATION_ID)

    companion object {
        const val NOTIFICATION_ID = 7_001
        /** Opens Settings › Backup & data. */
        const val OPEN = "backup"
    }
}
