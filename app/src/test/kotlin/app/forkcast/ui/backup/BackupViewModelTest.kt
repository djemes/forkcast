package app.forkcast.ui.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.PhotoArchive
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.fakes.FakeBackupRepository
import app.forkcast.fakes.FakeDocumentIo
import app.forkcast.fakes.FakeFolderBackupRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
// The JDK's own AES-GCM for password-protected backups (see core:data SettingsBackupTest).
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val backups = FakeBackupRepository()
    private val documents = FakeDocumentIo()
    private val folder = FakeFolderBackupRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:05:00Z"), ZoneId.of("America/New_York"))
    private val uri: Uri = Uri.parse("content://test/file")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val photos = PhotoStore(context)
    private val archive = PhotoArchive(context, photos)
    private val file = BackupFile(backups, archive, "1.5.0", clock)
    private fun vm() = BackupViewModel(backups, documents, archive, file, folder, clock).also { current = it }
    private var current: BackupViewModel? = null

    /** Like advanceUntilIdle, also waiting out BackupFile's and PhotoArchive's work on Dispatchers.IO. */
    private fun TestScope.settle() {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            advanceUntilIdle()
            if (current?.state?.value?.busy != true) return
            check(System.currentTimeMillis() < deadline) { "still busy" }
            Thread.sleep(5)
        }
    }

    @Test fun fileNamesUseLocalTime() {
        val vm = vm()
        assertThat(vm.suggestedName(ExportKind.BACKUP)).isEqualTo("forkcast-backup-2026-10-18.forkcast")
        assertThat(vm.suggestedName(ExportKind.FOOD_LOG_CSV)).isEqualTo("forkcast-20261018-1205-food-log.csv")
        assertThat(vm.suggestedName(ExportKind.WEIGHTS_CSV)).isEqualTo("forkcast-20261018-1205-weights.csv")
    }

    @Test fun saveAsksOptionsThenThePickerThenWritesAndRecordsIt() = runTest(dispatcher) {
        val vm = vm()
        vm.onEvent(BackupEvent.AskSave)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.SaveOptions)
        vm.onEvent(BackupEvent.SaveChosen(photos = false, password = null))
        assertThat(vm.state.value.dialog).isNull()
        assertThat(vm.state.value.pickBackupFile).isTrue()
        val target = Uri.parse("content://test/forkcast-backup-2026-10-18.forkcast")
        vm.onEvent(BackupEvent.SaveTo(target))
        settle()
        assertThat(vm.state.value.pickBackupFile).isFalse()
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.Exported(ExportKind.BACKUP))
        val bytes = documents.writtenBytes.getValue(target)
        assertThat(folder.saved).containsExactly("forkcast-backup-2026-10-18.forkcast" to bytes.size.toLong())
        // It opens again as a backup.
        documents.fileBytes[uri] = bytes
        backups.parseAs("{json}", "good")
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false, photos = 0))
    }

    @Test fun aCanceledPickerSavesNothing() = runTest(dispatcher) {
        val vm = vm()
        vm.onEvent(BackupEvent.SaveChosen(photos = true, password = null))
        vm.onEvent(BackupEvent.SaveTo(null))
        settle()
        assertThat(documents.writtenBytes).isEmpty()
        assertThat(vm.state.value.pickBackupFile).isFalse()
    }

    @Test fun aProtectedBackupAsksForItsPassword() = runTest(dispatcher) {
        photos.root.deleteRecursively()
        File(photos.root, "a1/0.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val vm = vm()
        vm.onEvent(BackupEvent.SaveChosen(photos = true, password = "a long backup password"))
        vm.onEvent(BackupEvent.SaveTo(uri))
        settle()
        documents.fileBytes[uri] = documents.writtenBytes.getValue(uri)
        backups.parseAs("{json}", "good")

        vm.onEvent(BackupEvent.Open(uri))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Password(wrong = false))
        vm.onEvent(BackupEvent.Unlock("not the password"))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Password(wrong = true))
        vm.onEvent(BackupEvent.Unlock("a long backup password"))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false, photos = 1))
    }

    @Test fun csvExportsWriteAndReport() = runTest(dispatcher) {
        val vm = vm()
        vm.onEvent(BackupEvent.ExportCsv(ExportKind.FOOD_LOG_CSV, uri))
        settle()
        assertThat(documents.written[uri]).isEqualTo("food,csv")
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.Exported(ExportKind.FOOD_LOG_CSV))

        documents.failWrites = true
        vm.onEvent(BackupEvent.ExportCsv(ExportKind.WEIGHTS_CSV, uri))
        settle()
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.ExportFailed)
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test fun openShowsPreviewThenMerges() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false))
        assertThat(backups.imports).isEmpty()

        vm.onEvent(BackupEvent.Merge)
        settle()
        assertThat(backups.imports).containsExactly(ImportMode.MERGE)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Done(ImportSummary(ImportMode.MERGE, 3, 1, 2)))
    }

    @Test fun replaceNeedsConfirmationAndOffersUndo() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        settle()
        assertThat(vm.state.value.hasSafetyBackup).isFalse()

        vm.onEvent(BackupEvent.Open(uri))
        settle()
        vm.onEvent(BackupEvent.AskReplace)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ConfirmReplace)
        assertThat(backups.imports).isEmpty()

        vm.onEvent(BackupEvent.Replace)
        settle()
        assertThat(backups.imports).containsExactly(ImportMode.REPLACE)
        assertThat(vm.state.value.hasSafetyBackup).isTrue()

        vm.onEvent(BackupEvent.DismissDialog)
        vm.onEvent(BackupEvent.OpenSafetyBackup)
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = true))
    }

    @Test fun cancelDropsThePendingFile() = runTest(dispatcher) {
        documents.files[uri] = "good"
        val vm = vm()
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        vm.onEvent(BackupEvent.DismissDialog)
        vm.onEvent(BackupEvent.Merge)
        settle()
        assertThat(backups.imports).isEmpty()
        assertThat(vm.state.value.dialog).isNull()
    }

    @Test fun rejectionsAndFailuresBecomeDialogsAndChangeNothing() = runTest(dispatcher) {
        val vm = vm()
        documents.files[uri] = "newer"
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.Rejected(RejectReason.NEWER_VERSION))

        // A damaged zip.
        documents.fileBytes[uri] = byteArrayOf(0x50, 0x4b, 3, 4, 9, 9, 9)
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ReadFailed)
        documents.fileBytes.clear()

        vm.onEvent(BackupEvent.Open(Uri.parse("content://test/missing")))
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ReadFailed)

        documents.files[uri] = "good"
        backups.failImport = true
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        vm.onEvent(BackupEvent.Merge)
        settle()
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.ImportFailed)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(backups.imports).isEmpty()
    }
}
