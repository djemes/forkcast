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
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.domain.BackupRetention
import app.forkcast.core.domain.NotificationRules
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

/**
 * The automatic backup to the chosen folder (owner, 2026-10-29): a daily job around the chosen
 * time that backs up only on the chosen days.
 */
@HiltWorker
class FolderBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: FolderBackupRepository,
    private val device: DevicePrefs,
    private val problems: FolderProblemNotifier,
    private val settings: SettingsRepository,
    private val clock: java.time.Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.settings.first().backup
        if (!BackupRetention.isChosenDay(ZonedDateTime.now(clock), s.days, s.minute)) return Result.success()
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
    private val secrets: SecretStore,
    private val clock: java.time.Clock,
) {
    /** Keeps the schedule in step with the settings (first pass keeps an existing schedule). */
    fun start(scope: CoroutineScope) {
        // The no-backup reminder counts from the first run on this phone.
        if (device.state.value.firstSeenAt == null) device.update { it.copy(firstSeenAt = clock.millis()) }
        scope.launch {
            var first = true
            combine(settings.settings, device.state, secrets.present) { s, d, present ->
                Plan(s.backup.auto && d.backupFolder != null && SecretName.BACKUP_PASSWORD in present, s.backup.days, s.backup.minute)
            }
                .distinctUntilChanged()
                .collect { plan ->
                    schedule(plan, replace = !first)
                    first = false
                }
        }
    }

    /** What the schedule depends on (retention is read at run time); [on] needs a folder and the backup password. */
    private data class Plan(val on: Boolean, val days: Set<Int>, val minute: Int)

    private fun schedule(plan: Plan, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        val delay = BackupRetention.delayUntilNext(ZonedDateTime.now(clock), plan.days, plan.minute)
        if (!plan.on || delay == null) {
            wm.cancelUniqueWork(NAME)
            return
        }
        // First run at the next chosen day and time, then daily around that time; off days do nothing.
        val request = PeriodicWorkRequestBuilder<FolderBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val NAME = "folder_backup"
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
