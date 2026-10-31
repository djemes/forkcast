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
import kotlinx.coroutines.withContext
import app.forkcast.ui.report.ReportCache
import javax.inject.Inject

@HiltAndroidApp
class ForkcastApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Lazy: building these pulls in the database, both HTTP clients, the AI defaults and the
    // Health Connect client. They're created on a background thread below, not before the first frame.
    @Inject lateinit var estimates: Lazy<AiEstimateRepository>
    @Inject lateinit var notifications: Lazy<NotificationStarter>
    @Inject lateinit var health: Lazy<HealthSyncStarter>
    @Inject lateinit var missingKeys: Lazy<app.forkcast.core.data.repository.MissingKeysRepository>
    @Inject lateinit var folderBackup: Lazy<app.forkcast.core.data.backup.folder.FolderBackupScheduler>
    @Inject lateinit var updateCheck: Lazy<app.forkcast.core.data.update.UpdateCheckScheduler>
    @Inject lateinit var widgets: Lazy<app.forkcast.widget.WidgetUpdater>
    @Inject lateinit var conversion: Lazy<app.forkcast.core.data.conversion.ConversionRelease>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The app's own language on Android 10–12 (AppLanguage).
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.forkcast.AppLanguage.wrap(newBase))

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // 1.9.1, the conversion release: settings in 2.0's shape before injection opens them (removed in 2.0).
        app.forkcast.core.data.conversion.ConversionRelease.prepareSettings(this)
        super.onCreate()
        CrashReports.install(this)
        if (BuildConfig.DEBUG) {
            // Flags disk/network work on the main thread during development (SPEC §9). Log only.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        Channel.createAll(this)
        appScope.launch {
            runCatching { conversion.get().run() }
            // A meal report left in the cache when the app was closed during its preview.
            withContext(Dispatchers.IO) { ReportCache.clear(this@ForkcastApplication) }
            notifications.get().start(appScope)
            health.get().start(appScope)
            missingKeys.get().start(appScope)
            folderBackup.get().start(appScope)
            // The daily update check exists only where the updater does.
            if (Distribution.current.hasUpdater) updateCheck.get().start(appScope)
            else app.forkcast.core.data.update.UpdateCheckScheduler.stop(this@ForkcastApplication)
            widgets.get().start(appScope)
            // Meal photos older than the retention setting (default 14 days) are deleted; entries stay (SPEC §4.6).
            runCatching { estimates.get().cleanUpPhotos() }
            // Estimates left pending with no live job (killed app, stuck in an older version) get queued or a Failed status.
            runCatching { estimates.get().recover() }
        }
    }
}
