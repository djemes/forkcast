package app.forkcast.ui.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.fakes.FakeBackupRepository
import app.forkcast.fakes.FakeDocumentIo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val backups = FakeBackupRepository()
    private val documents = FakeDocumentIo()
    private val drive = app.forkcast.fakes.FakeDriveBackupRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:05:00Z"), ZoneId.of("America/New_York"))
    private val uri: Uri = Uri.parse("content://test/file")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = BackupViewModel(backups, documents, app.forkcast.core.data.backup.PhotoArchive(context, app.forkcast.core.data.ai.PhotoStore(context)), drive, clock)

    @Test fun fileNamesUseLocalTime() {
        val vm = vm()
        assertThat(vm.suggestedName(ExportKind.JSON)).isEqualTo("forkcast-20261018-1205.json")
        assertThat(vm.suggestedName(ExportKind.FOOD_LOG_CSV)).isEqualTo("forkcast-20261018-1205-food-log.csv")
        assertThat(vm.suggestedName(ExportKind.WEIGHTS_CSV)).isEqualTo("forkcast-20261018-1205-weights.csv")
    }

    @Test fun exportWritesFileAndReportsIt() = runTest(dispatcher) {
        val vm = vm()
        vm.onEvent(BackupEvent.Export(ExportKind.JSON, uri))
        advanceUntilIdle()
        assertThat(documents.written[uri]).isEqualTo("{json}")
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.Exported(ExportKind.JSON))

        documents.failWrites = true
        vm.onEvent(BackupEvent.Export(ExportKind.WEIGHTS_CSV, uri))
        advanceUntilIdle()
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.ExportFailed)
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test fun openShowsPreviewThenMerges() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        vm.onEvent(BackupEvent.Open(uri))
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false))
        assertThat(backups.imports).isEmpty()

        vm.onEvent(BackupEvent.Merge)
        advanceUntilIdle()
        assertThat(backups.imports).containsExactly(ImportMode.MERGE)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Done(ImportSummary(ImportMode.MERGE, 3, 1, 2)))
    }

    @Test fun replaceNeedsConfirmationAndOffersUndo() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.state.value.hasSafetyBackup).isFalse()

        vm.onEvent(BackupEvent.Open(uri))
        advanceUntilIdle()
        vm.onEvent(BackupEvent.AskReplace)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ConfirmReplace)
        assertThat(backups.imports).isEmpty()

        vm.onEvent(BackupEvent.Replace)
        advanceUntilIdle()
        assertThat(backups.imports).containsExactly(ImportMode.REPLACE)
        assertThat(vm.state.value.hasSafetyBackup).isTrue()

        vm.onEvent(BackupEvent.DismissDialog)
        vm.onEvent(BackupEvent.OpenSafetyBackup)
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = true))
    }

    @Test fun cancelDropsThePendingFile() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        vm.onEvent(BackupEvent.Open(uri))
        advanceUntilIdle()
        vm.onEvent(BackupEvent.DismissDialog)
        vm.onEvent(BackupEvent.Merge)
        advanceUntilIdle()
        assertThat(backups.imports).isEmpty()
        assertThat(vm.state.value.dialog).isNull()
    }

    @Test fun rejectionsAndFailuresBecomeDialogs() = runTest(dispatcher) {
        val vm = vm()
        documents.files[uri] = "newer"
        vm.onEvent(BackupEvent.Open(uri))
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Rejected(RejectReason.NEWER_VERSION))

        vm.onEvent(BackupEvent.Open(Uri.parse("content://test/missing")))
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ReadFailed)

        documents.files[uri] = "good"
        backups.failImport = true
        vm.onEvent(BackupEvent.Open(uri))
        advanceUntilIdle()
        vm.onEvent(BackupEvent.Merge)
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ImportFailed)
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test fun driveBackupOpensTheSamePreview() = runTest(dispatcher) {
        drive.files["b1"] = "good"
        val vm = vm()
        vm.onEvent(BackupEvent.OpenDrive("b1"))
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false))

        drive.problem = app.forkcast.core.data.drive.DriveProblem.OFFLINE
        vm.onEvent(BackupEvent.DismissDialog)
        vm.onEvent(BackupEvent.OpenDrive("b1"))
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.DriveFailed(app.forkcast.core.data.drive.DriveProblem.OFFLINE))
    }
}
