package app.forkcast.core.data.backup.folder

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
import app.forkcast.core.model.BackupSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** What the scheduled backup does with each outcome: notify at once, retry, or notify on the second failure. */
@RunWith(AndroidJUnit4::class)
class FolderBackupWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    // Midday, outside the default quiet hours.
    private val clock = Clock.fixed(Instant.parse("2026-10-26T16:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-fbw-${System.nanoTime()}.json") },
    )
    private val device = DevicePrefs(context).also { d -> d.update { DeviceState() } }
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** Answers [next] and counts failures in DevicePrefs the way the real repository does. */
    private inner class Scripted : FolderBackupRepository {
        var next: FolderProblem? = null
        override val status: Flow<FolderStatus> = emptyFlow()
        override val notice: Flow<BackupNotice?> = emptyFlow()
        override suspend fun dismissNotice(notice: BackupNotice) = Unit
        override suspend fun chooseFolder(tree: String): FolderProblem? = null
        override suspend fun forgetFolder() = Unit
        override suspend fun backupNow(): FolderProblem? = next.also { p ->
            device.update { if (p == null) it.copy(backupFailures = 0) else it.copy(backupFailures = it.backupFailures + 1) }
        }
        override suspend fun recordSaved(name: String, sizeBytes: Long?) = Unit
        override suspend fun updateSettings(transform: (BackupSettings) -> BackupSettings) = Unit
        override suspend fun setPassword(password: String) = Unit
        override suspend fun password(): String? = null
        override suspend fun changePassword(password: String) = ReencryptResult(0, emptyList())
        override suspend fun removePassword() = Unit
        override suspend fun cleanupPlan(): CleanupPlan = CleanupPlan.NothingToDelete(null)
        override suspend fun deleteOld(plan: CleanupPlan.Ready): CleanupResult = error("unused")
    }

    private val repo = Scripted()

    @Before fun channelsAndPermission() {
        // Every day, so the run is never skipped as an off day.
        kotlinx.coroutines.runBlocking { settings.update { it.copy(backup = it.backup.copy(days = (1..7).toSet())) } }
        Channel.createAll(context)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun run(attempt: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder<FolderBackupWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    FolderBackupWorker(appContext, workerParameters, repo, device, FolderProblemNotifier(appContext, Notifier(appContext), settings, clock), settings, clock)
            })
            .build()
            .doWork()

    private fun shown() = shadowOf(nm).getNotification(FolderProblemNotifier.NOTIFICATION_ID)

    @Test fun aLostFolderNotifiesAtOnceAndASuccessClearsIt() = runTest {
        repo.next = FolderProblem.FOLDER_LOST
        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(shown()).isNotNull()
        repo.next = null
        run()
        assertThat(shown()).isNull()
    }

    @Test fun writeFailuresRetryAndNotifyOnTheSecondInARow() = runTest {
        repo.next = FolderProblem.WRITE_FAILED
        assertThat(run()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(shown()).isNull()
        assertThat(run(attempt = 1)).isEqualTo(ListenableWorker.Result.retry())
        assertThat(shown()).isNotNull()
        // Out of retries: wait for the next scheduled run.
        assertThat(run(attempt = FolderBackupWorker.MAX_RETRIES)).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun noFolderIsQuiet() = runTest {
        repo.next = FolderProblem.NO_FOLDER
        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(shown()).isNull()
    }
}
