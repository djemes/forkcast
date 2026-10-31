package app.forkcast.core.data.update

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.Notifier
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.data.settings.DeviceState
import app.forkcast.core.network.update.Release
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateCheckWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-update-${System.nanoTime()}.json") },
    )
    private val device = DevicePrefs(context).also { d -> d.update { DeviceState() } }
    private val nm = context.getSystemService(NotificationManager::class.java)

    private class Fake(var answer: UpdateCheck) : UpdateRepository {
        var betasAsked: Boolean? = null
        override suspend fun check(currentVersionName: String, includeBetas: Boolean): UpdateCheck = answer.also { betasAsked = includeBetas }
        override suspend fun download(release: Release, progress: (Long, Long) -> Unit): Download = error("never downloads")
    }

    private val release = Release("v1.6.0", null, null, "p", null)
    private val fake = Fake(UpdateCheck.Available(release))

    @Before fun channelsAndPermission() {
        Channel.createAll(context)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun run(): ListenableWorker.Result =
        TestListenableWorkerBuilder<UpdateCheckWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    UpdateCheckWorker(appContext, workerParameters, fake, settings, device, Notifier(appContext), "1.5.0")
            })
            .build()
            .doWork()

    @Test fun aNewVersionNotifiesOnce() = runTest {
        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(shadowOf(nm).allNotifications).hasSize(1)
        assertThat(shadowOf(nm).getNotification(UpdateCheckWorker.NOTIFICATION_ID)).isNotNull()
        assertThat(device.state.value.notifiedUpdate).isEqualTo("v1.6.0")
        nm.cancelAll()
        run()
        assertThat(shadowOf(nm).allNotifications).isEmpty()
        // The next version notifies again.
        fake.answer = UpdateCheck.Available(release.copy(tag = "v1.7.0"))
        run()
        assertThat(shadowOf(nm).allNotifications).hasSize(1)
    }

    @Test fun offWhenSwitchedOffAndQuietOnFailure() = runTest {
        settings.update { it.copy(updates = it.updates.copy(auto = false)) }
        run()
        assertThat(fake.betasAsked).isNull()
        settings.update { it.copy(updates = it.updates.copy(auto = true, beta = true)) }
        fake.answer = UpdateCheck.Failed(app.forkcast.core.network.update.UpdateError.Offline())
        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(fake.betasAsked).isTrue()
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }
}
