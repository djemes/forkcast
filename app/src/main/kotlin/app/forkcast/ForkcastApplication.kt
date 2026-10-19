package app.forkcast

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.health.HealthSyncStarter
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.NotificationStarter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ForkcastApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var estimates: AiEstimateRepository
    @Inject lateinit var notifications: NotificationStarter
    @Inject lateinit var health: HealthSyncStarter

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
        // Meal photos older than the retention setting (default 90 days) are deleted (SPEC §4.6).
        appScope.launch { runCatching { estimates.cleanUpPhotos() } }
        notifications.start(appScope)
        health.start(appScope)
    }
}
