package app.forkcast.ui.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupCrypto
import app.forkcast.core.data.backup.BackupSecrets
import app.forkcast.core.data.backup.Kdf
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
// The JDK's own AES-GCM for password-protected backups (see core:data BackupCryptoTest).
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
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
    private val store by lazy { app.forkcast.fakes.testSecretStore(tmp.root) }
    private val secrets by lazy { BackupSecrets(store) }
    private val file by lazy { BackupFile(backups, archive, secrets, "1.5.0", clock).apply { kdf = Kdf(8 * 1024, 1, 1) } }
    private fun vm() = BackupViewModel(backups, documents, archive, file, folder, secrets, BackupShares(context)).also { current = it }
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
        assertThat(vm.suggestedName()).isEqualTo("2026-10-18_1205_forkcast-backup.forkcast")
        assertThat(vm.suggestedName(SaveMode.UNENCRYPTED)).isEqualTo("2026-10-18_1205_forkcast-backup_unencrypted.forkcast")
    }

    @Test fun backUpNowWithoutAFolderAsksWhereThenWritesEncrypted() = runTest(dispatcher) {
        folder.setPassword(PASSWORD)
        val vm = vm()
        vm.onEvent(BackupEvent.BackUpNow)
        settle()
        assertThat(vm.state.value.pickBackupFile).isEqualTo(SaveMode.ENCRYPTED)
        val target = Uri.parse("content://test/2026-10-18_1205_forkcast-backup.forkcast")
        vm.onEvent(BackupEvent.SaveTo(target))
        settle()
        assertThat(vm.state.value.pickBackupFile).isNull()
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.Exported(ExportKind.BACKUP))
        val bytes = documents.writtenBytes.getValue(target)
        assertThat(BackupCrypto.isEncrypted(bytes)).isTrue()
        assertThat(folder.saved).containsExactly("2026-10-18_1205_forkcast-backup.forkcast" to bytes.size.toLong())
    }

    @Test fun backUpNowWithAFolderWritesThere() = runTest(dispatcher) {
        folder.setPassword(PASSWORD)
        folder.chooseFolder("content://tree/1")
        val vm = vm()
        vm.onEvent(BackupEvent.BackUpNow)
        settle()
        assertThat(folder.backups).isEqualTo(1)
        assertThat(vm.state.value.pickBackupFile).isNull()
        assertThat(vm.state.value.notice).isEqualTo(BackupNotice.Folder(null))
    }

    @Test fun theUnencryptedCopyWarnsFirstAndIsReadable() = runTest(dispatcher) {
        val vm = vm()
        vm.onEvent(BackupEvent.AskUnencrypted)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.UnencryptedWarning)
        // The screen asks for the phone's unlock, then confirms.
        vm.onEvent(BackupEvent.UnencryptedConfirmed)
        assertThat(vm.state.value.pickBackupFile).isEqualTo(SaveMode.UNENCRYPTED)
        vm.onEvent(BackupEvent.SaveTo(uri))
        settle()
        assertThat(BackupCrypto.isEncrypted(documents.writtenBytes.getValue(uri))).isFalse()
    }

    @Test fun aCanceledPickerSavesNothing() = runTest(dispatcher) {
        folder.setPassword(PASSWORD)
        val vm = vm()
        vm.onEvent(BackupEvent.BackUpNow)
        settle()
        vm.onEvent(BackupEvent.SaveTo(null))
        settle()
        assertThat(documents.writtenBytes).isEmpty()
        assertThat(vm.state.value.pickBackupFile).isNull()
    }

    @Test fun keysComeBackAndThePasswordCanBeKept() = runTest(dispatcher) {
        // Made on the "old phone": a key and the backup password.
        store.setAiKey("p1", "sk-old-phone-key")
        folder.setPassword(PASSWORD)
        val vm = vm()
        vm.onEvent(BackupEvent.BackUpNow)
        settle()
        vm.onEvent(BackupEvent.SaveTo(uri))
        settle()
        documents.fileBytes[uri] = documents.writtenBytes.getValue(uri)
        // The "new phone": no keys, no backup password.
        store.setAiKey("p1", null)
        folder.removePassword()
        backups.parseAs("{json}", "good")
        vm.onEvent(BackupEvent.Open(uri))
        settle()
        vm.onEvent(BackupEvent.Unlock(PASSWORD))
        settle()
        vm.onEvent(BackupEvent.Merge)
        settle()
        assertThat(vm.state.value.dialog).isInstanceOf(BackupDialog.Done::class.java)
        assertThat(store.aiKey("p1")).isEqualTo("sk-old-phone-key")
        vm.onEvent(BackupEvent.DismissDialog)
        assertThat(vm.state.value.dialog).isEqualTo(BackupDialog.KeepPassword)
        vm.onEvent(BackupEvent.KeepPassword)
        settle()
        assertThat(folder.password).isEqualTo(PASSWORD)
    }

    @Test fun aProtectedBackupAsksForItsPassword() = runTest(dispatcher) {
        photos.root.deleteRecursively()
        File(photos.root, "a1/0.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        folder.setPassword("a long backup password")
        val vm = vm()
        vm.onEvent(BackupEvent.BackUpNow)
        settle()
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

    private companion object {
        const val PASSWORD = "a long backup password"
    }
}
