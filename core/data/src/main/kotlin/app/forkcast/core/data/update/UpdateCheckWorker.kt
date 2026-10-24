package app.forkcast.core.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.forkcast.core.data.R
import app.forkcast.core.data.backup.APP_VERSION
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.Notifier
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.settings.DevicePrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * "Check automatically" (roadmap 2.0, task 1.5): at most once a day, asks GitHub for a newer
 * version and posts one quiet notification per new version. Never downloads anything by itself.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
    private val device: DevicePrefs,
    private val notifier: Notifier,
    @Named(APP_VERSION) private val version: String,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.settings.first().updates
        if (!s.auto) return Result.success()
        // Any failure (offline, rate limit, private repository) just waits for tomorrow's check.
        val check = updates.check(version, includeBetas = s.beta) as? UpdateCheck.Available ?: return Result.success()
        val tag = check.release.tag
        if (device.state.value.notifiedUpdate == tag) return Result.success()
        val shown = tag.removePrefix("v")
        notifier.post(
            Channel.UPDATES,
            NOTIFICATION_ID,
            applicationContext.getString(R.string.notif_update_title, shown),
            applicationContext.getString(R.string.notif_update_body),
            OPEN,
        )
        device.update { it.copy(notifiedUpdate = tag) }
        return Result.success()
    }

    companion object {
        const val NOTIFICATION_ID = 7_101
        /** Opens Settings › Updates. */
        const val OPEN = "updates"
    }
}

/** Keeps the daily check in step with "Check automatically"; only the GitHub build starts it. */
@Singleton
class UpdateCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings.map { it.updates.auto }.distinctUntilChanged().collect { on -> if (on) schedule() else stop(context) }
        }
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Not right at start: the first check waits a few hours.
            .setInitialDelay(FIRST_DELAY_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val NAME = "update_check"
        private const val FIRST_DELAY_HOURS = 3L

        /** No daily check (switched off, or a build without the updater). */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
