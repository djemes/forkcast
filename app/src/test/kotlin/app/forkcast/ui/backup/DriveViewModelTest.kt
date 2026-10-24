package app.forkcast.ui.backup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.drive.CleanupPlan
import app.forkcast.core.data.drive.CleanupResult
import app.forkcast.core.data.drive.DriveAccess
import app.forkcast.core.data.drive.DriveAuth
import app.forkcast.core.data.drive.DriveBackup
import app.forkcast.core.data.drive.DriveProblem
import app.forkcast.core.data.drive.NotRestorable
import app.forkcast.fakes.FakeDriveBackupRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** "Delete old backups" (owner, 2026-10-24): check, confirm, delete; or back up first. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DriveViewModelTest {

    private val drive = FakeDriveBackupRepository()
    private val auth = object : DriveAuth {
        override suspend fun authorize(): DriveAccess = DriveAccess.Granted("t")
        override fun fromConsent(data: Intent?): DriveAccess = DriveAccess.Granted("t")
        override suspend fun forget(token: String) = Unit
        override suspend fun revoke(email: String) = Unit
    }
    private val keep = DriveBackup("k", "forkcast-20261024-0300.json.gz", Instant.parse("2026-10-24T07:00:00Z"), 1)
    private val old = (1..3).map { DriveBackup("o$it", "forkcast-2026102$it-0300.json.gz", Instant.parse("2026-10-2${it}T07:00:00Z"), 1) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun checkThenConfirmDeletesThePlannedOnes() = runTest {
        val vm = DriveViewModel(drive, auth)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        drive.plan = CleanupPlan.Ready(keep, old)
        vm.checkCleanup()
        assertThat(vm.state.value.cleanup).isEqualTo(CleanupPlan.Ready(keep, old))
        // Checking alone deletes nothing.
        assertThat(drive.deleted).isEmpty()
        vm.confirmCleanup()
        assertThat(drive.deleted).containsExactly("o1", "o2", "o3")
        assertThat(vm.state.value.cleanup).isNull()
        assertThat(vm.state.value.cleanedUp).isEqualTo(CleanupResult(3, 0, keep))
    }

    @Test fun newestNotRestorableBacksUpThenChecksAgain() = runTest {
        val vm = DriveViewModel(drive, auth)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        drive.plan = CleanupPlan.LatestNotRestorable(keep, NotRestorable.OLD_FORMAT)
        vm.checkCleanup()
        // Nothing can be confirmed from this state.
        vm.confirmCleanup()
        assertThat(drive.deleted).isEmpty()
        drive.plan = CleanupPlan.Ready(keep, old)
        vm.backupThenCheckCleanup()
        assertThat(drive.backups).isEqualTo(1)
        assertThat(vm.state.value.cleanup).isEqualTo(CleanupPlan.Ready(keep, old))
    }

    @Test fun aDriveProblemShowsInsteadOfADialog() = runTest {
        val vm = DriveViewModel(drive, auth)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        drive.problem = DriveProblem.OFFLINE
        vm.checkCleanup()
        assertThat(vm.state.value.cleanup).isNull()
        assertThat(vm.state.value.problem).isEqualTo(DriveProblem.OFFLINE)
    }
}
