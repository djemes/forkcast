package app.forkcast.core.data.backup.folder

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.OpenedBackupFile
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.PhotoArchive
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.data.settings.DeviceState
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// The JDK's own AES-GCM for password-protected backups (see SettingsBackupTest).
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class FolderBackupRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-26T07:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-folder-${System.nanoTime()}.json") },
    )
    private val device = DevicePrefs(context).also { d -> d.update { DeviceState() } }
    private val secrets = SecretStore(File(context.filesDir, "secrets-${System.nanoTime()}/store.bin"), { XorAead() }, Dispatchers.Unconfined)
    private val folder = DirFolder(File(context.cacheDir, "folder-${System.nanoTime()}").apply { mkdirs() })
    private val archive = PhotoArchive(context, PhotoStore(context))
    private val exports = FakeExports()
    private val backupFile = BackupFile(exports, archive, "1.5.0", clock)
    private val repo = DefaultFolderBackupRepository(folder, backupFile, exports, archive, device, settings, secrets, clock)

    private suspend fun chosen() = assertThat(repo.chooseFolder(DirFolder.TREE)).isNull()

    @Test fun backupWritesAForkcastFileAndRemembersIt() = runTest {
        chosen()
        assertThat(repo.backupNow()).isNull()
        val files = folder.list(DirFolder.TREE)
        assertThat(files.map { it.name }).containsExactly("forkcast-backup-2026-10-26-0300.forkcast")
        val status = repo.status.first()
        assertThat(status.folderName).isEqualTo("Backups")
        assertThat(status.last?.name).isEqualTo("forkcast-backup-2026-10-26-0300.forkcast")
        assertThat(status.last?.place).isEqualTo("Backups")
        assertThat(status.last?.sizeBytes).isEqualTo(files.single().sizeBytes)
        val opened = folder.openInput(files.single().uri).use { backupFile.open(it, null) } as OpenedBackupFile.Ok
        assertThat(opened.json).isEqualTo(exports.json)
    }

    @Test fun keepsTheNewestAndNeverTouchesOtherFiles() = runTest {
        chosen()
        repeat(10) { folder.put("forkcast-backup-2026-10-${"%02d".format(it + 1)}.forkcast", modified = it + 1L) }
        folder.put("holiday.jpg", modified = 0)
        folder.put("forkcast-20261019-0300.json.gz", modified = 0)
        repo.updateSettings { it.copy(keep = 4) }
        assertThat(repo.backupNow()).isNull()
        val names = folder.list(DirFolder.TREE).map { it.name }
        assertThat(names).containsExactly(
            "forkcast-backup-2026-10-26-0300.forkcast",
            "forkcast-backup-2026-10-10.forkcast",
            "forkcast-backup-2026-10-09.forkcast",
            "forkcast-backup-2026-10-08.forkcast",
            "holiday.jpg",
            "forkcast-20261019-0300.json.gz",
        )
    }

    @Test fun aLostFolderIsReportedAndCounted() = runTest {
        chosen()
        folder.revoked = true
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.FOLDER_LOST)
        assertThat(repo.status.first().lastProblem).isEqualTo(FolderProblem.FOLDER_LOST)
        assertThat(device.state.value.backupFailures).isEqualTo(1)
        folder.revoked = false
        assertThat(repo.backupNow()).isNull()
        assertThat(repo.status.first().lastProblem).isNull()
        assertThat(device.state.value.backupFailures).isEqualTo(0)
    }

    @Test fun aFailedWriteLeavesNoHalfBackup() = runTest {
        chosen()
        folder.failWrites = true
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.WRITE_FAILED)
        assertThat(folder.list(DirFolder.TREE)).isEmpty()
        assertThat(repo.status.first().last).isNull()
    }

    @Test fun noFolderNoBackup() = runTest {
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.NO_FOLDER)
    }

    @Test fun passwordBackupsUseTheSavedPassword() = runTest {
        chosen()
        repo.updateSettings { it.copy(password = true) }
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.PASSWORD_MISSING)
        repo.setPassword("a long backup password")
        assertThat(repo.status.first().hasPassword).isTrue()
        assertThat(repo.backupNow()).isNull()
        val f = folder.list(DirFolder.TREE).single()
        assertThat(folder.openInput(f.uri).use { backupFile.open(it, null) }).isEqualTo(OpenedBackupFile.NeedsPassword)
        assertThat(folder.openInput(f.uri).use { backupFile.open(it, "a long backup password".toCharArray()) }).isInstanceOf(OpenedBackupFile.Ok::class.java)
        // The cleanup check opens it with the saved password.
        folder.put("forkcast-backup-2026-10-01.forkcast", modified = 1)
        assertThat(repo.cleanupPlan()).isInstanceOf(CleanupPlan.Ready::class.java)
        assertThat(secrets.get(SecretName.BACKUP_PASSWORD)).isEqualTo("a long backup password")
    }

    @Test fun cleanupChecksTheNewestThenDeletesOnlyThePlannedBackups() = runTest {
        chosen()
        repeat(3) { folder.put("forkcast-backup-2026-10-0${it + 1}.forkcast", modified = it + 1L) }
        assertThat(repo.backupNow()).isNull()
        val plan = repo.cleanupPlan() as CleanupPlan.Ready
        assertThat(plan.keep.name).isEqualTo("forkcast-backup-2026-10-26-0300.forkcast")
        assertThat(plan.delete.map { it.name }).containsExactly(
            "forkcast-backup-2026-10-03.forkcast",
            "forkcast-backup-2026-10-02.forkcast",
            "forkcast-backup-2026-10-01.forkcast",
        ).inOrder()
        // One of them disappears before the user confirms; another file arrives.
        folder.remove("forkcast-backup-2026-10-02.forkcast")
        folder.put("notes.txt", modified = 9)
        val result = repo.deleteOld(plan)
        assertThat(result.deleted).isEqualTo(2)
        assertThat(result.failed).isEqualTo(0)
        assertThat(folder.list(DirFolder.TREE).map { it.name }).containsExactly("forkcast-backup-2026-10-26-0300.forkcast", "notes.txt")
    }

    @Test fun cleanupRefusesWhenTheNewestCantBeRestored() = runTest {
        chosen()
        folder.put("forkcast-backup-2026-10-01.forkcast", modified = 1)
        folder.put("forkcast-backup-2026-10-02.forkcast", modified = 2, bytes = "not a backup".toByteArray())
        val plan = repo.cleanupPlan() as CleanupPlan.LatestNotRestorable
        assertThat(plan.reason).isEqualTo(NotRestorable.UNREADABLE)
        // An older export format inside.
        exports.json = """{"app":"Forkcast","schemaVersion":6}"""
        assertThat(repo.backupNow()).isNull()
        assertThat((repo.cleanupPlan() as CleanupPlan.LatestNotRestorable).reason).isEqualTo(NotRestorable.OLD_FORMAT)
        assertThat(folder.list(DirFolder.TREE)).hasSize(3)
    }

    @Test fun choosingAnotherFolderReleasesTheOldOne() = runTest {
        chosen()
        assertThat(repo.chooseFolder("content://other/tree/2")).isNull()
        assertThat(folder.released).containsExactly(DirFolder.TREE)
        repo.forgetFolder()
        assertThat(folder.released).containsExactly(DirFolder.TREE, "content://other/tree/2")
        assertThat(repo.status.first().chosen).isFalse()
    }

    @Test fun aBackupSavedWithThePickerCountsAsTheLast() = runTest {
        repo.recordSaved("forkcast-backup-2026-10-26-0300.forkcast", 1234)
        val last = repo.status.first().last!!
        assertThat(last.at).isEqualTo(clock.millis())
        assertThat(last.place).isNull()
        assertThat(last.sizeBytes).isEqualTo(1234)
    }

    @Test fun reminderAfterFourteenDaysWithoutABackup() = runTest {
        val day = 24L * 3600 * 1000
        device.update { it.copy(firstSeenAt = clock.millis() - 15 * day) }
        assertThat(repo.notice.first()).isEqualTo(BackupNotice.REMINDER)
        repo.dismissNotice(BackupNotice.REMINDER)
        assertThat(repo.notice.first()).isNull()
        device.update { it.copy(backupReminderDismissedAt = null, lastBackup = app.forkcast.core.data.settings.LastBackup(clock.millis() - 3 * day, "b", null, null)) }
        assertThat(repo.notice.first()).isNull()
        repo.updateSettings { it.copy(reminder = false) }
        assertThat(repo.notice.first()).isNull()
    }

    /** A folder on disk standing in for a document tree; uris are file names. */
    private class DirFolder(private val dir: File) : BackupFolder {
        var revoked = false
        var failWrites = false
        val released = mutableListOf<String>()
        private var clockTick = 1_000L

        fun put(name: String, modified: Long, bytes: ByteArray? = null) {
            val f = File(dir, name)
            f.writeBytes(bytes ?: validBackup)
            f.setLastModified(modified * 1000)
        }

        fun remove(name: String) = File(dir, name).delete()

        override fun keep(tree: String) = Unit
        override fun release(tree: String) { released += tree }
        override fun reachable(tree: String) = !revoked
        override fun displayName(tree: String) = "Backups"
        override fun list(tree: String): List<FolderFile> {
            if (revoked) throw FileNotFoundException()
            return dir.listFiles().orEmpty().sortedBy { it.name }.map { FolderFile(it.name, it.name, it.lastModified(), it.length()) }
        }
        override fun create(tree: String, name: String, mimeType: String): FolderFile {
            if (revoked) throw SecurityException()
            val f = File(dir, name)
            f.writeBytes(ByteArray(0))
            // Newer than anything put() made.
            f.setLastModified(Instant.parse("2026-10-26T07:00:00Z").toEpochMilli() + clockTick++)
            return FolderFile(name, name, null, null)
        }
        override fun openOutput(file: String): OutputStream {
            val out = File(dir, file).outputStream()
            if (!failWrites) return out
            return object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun close() = out.close()
            }
        }
        override fun openInput(file: String): InputStream = File(dir, file).inputStream()
        override fun delete(file: String) = File(dir, file).delete()

        companion object {
            const val TREE = "content://folder/tree/1"
            /** A current-format backup, as BackupFile writes it (zip with forkcast.json). */
            val validBackup: ByteArray by lazy {
                val out = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("forkcast.json"))
                    zip.write("""{"app":"Forkcast","schemaVersion":${ForkcastExport.SCHEMA_VERSION}}""".toByteArray())
                    zip.closeEntry()
                }
                out.toByteArray()
            }
        }
    }

    private class FakeExports : BackupRepository {
        var json = """{"app":"Forkcast","schemaVersion":${ForkcastExport.SCHEMA_VERSION}}"""
        override suspend fun exportJson() = json
        override suspend fun foodLogCsv() = ""
        override suspend fun weightsCsv() = ""
        /** Reads only the version: older than the oldest import is too old, no version is unreadable. */
        override fun parse(text: String): ParsedBackup {
            val version = Regex("\"schemaVersion\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
                ?: return ParsedBackup.Rejected(RejectReason.UNREADABLE)
            if (version < ForkcastExport.OLDEST_IMPORTED) return ParsedBackup.Rejected(RejectReason.OLD_VERSION)
            val export = ForkcastExport(schemaVersion = version, appVersion = "1.5.0", exportedAt = "2026-10-26T07:00:00Z", timeZone = "UTC")
            return ParsedBackup.Ok(export, app.forkcast.core.data.backup.preview(export))
        }
        override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary = throw UnsupportedOperationException()
        override suspend fun readSafetyBackup(): String? = null
    }

    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = encrypt(ciphertext, associatedData)
    }
}
