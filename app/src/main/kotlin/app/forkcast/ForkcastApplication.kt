package app.forkcast

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.health.HealthSyncStarter
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.NotificationStarter
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ForkcastApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Lazy: building these pulls in the database, both HTTP clients, the AI defaults and the
    // Health Connect client. They're created on a background thread below, not before the first frame.
    @Inject lateinit var estimates: Lazy<AiEstimateRepository>
    @Inject lateinit var notifications: Lazy<NotificationStarter>
    @Inject lateinit var health: Lazy<HealthSyncStarter>
    @Inject lateinit var settings: Lazy<app.forkcast.core.data.repository.SettingsRepository>
    @Inject lateinit var missingKeys: Lazy<app.forkcast.core.data.repository.MissingKeysRepository>
    @Inject lateinit var driveBackup: Lazy<app.forkcast.core.data.drive.DriveBackupScheduler>
    @Inject lateinit var widgets: Lazy<app.forkcast.widget.WidgetUpdater>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Flags disk/network work on the main thread during development (SPEC §9). Log only.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        Channel.createAll(this)
        appScope.launch {
            notifications.get().start(appScope)
            health.get().start(appScope)
            missingKeys.get().start(appScope)
            driveBackup.get().start(appScope)
            widgets.get().start(appScope)
            // Settings saved with the old 90-day default move to the new 14 days, once (owner, 2026-10-19).
            runCatching {
                settings.get().updateAi { ai ->
                    if (ai.photoRetention14Applied) ai
                    else ai.copy(photoRetentionDays = if (ai.photoRetentionDays == 90) 14 else ai.photoRetentionDays, photoRetention14Applied = true)
                }
            }
            // Meal photos older than the retention setting (default 14 days) are deleted; entries stay (SPEC §4.6).
            runCatching { estimates.get().cleanUpPhotos() }
        }
    }
}
