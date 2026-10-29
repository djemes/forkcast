package app.forkcast.core.data.backup.folder

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupSecrets
import app.forkcast.core.data.backup.Kdf
import app.forkcast.core.model.RetentionPreset
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
    private val backupFile = BackupFile(exports, archive, BackupSecrets(secrets), "1.5.0", clock).apply { kdf = Kdf(8 * 1024, 1, 1) }
    private val repo = DefaultFolderBackupRepository(folder, backupFile, exports, archive, device, settings, secrets, clock)

    /** A folder and a backup password: what every automatic backup needs. */
    private suspend fun chosen() {
        assertThat(repo.chooseFolder(DirFolder.TREE)).isNull()
        repo.setPassword(PASSWORD)
    }

    private suspend fun encryptedBackup(password: String): ByteArray =
        java.io.ByteArrayOutputStream().also { backupFile.write(it, password.toCharArray()) }.toByteArray()

    private fun names() = folder.list(DirFolder.TREE).map { it.name }

    @Test fun backupWritesAForkcastFileAndRemembersIt() = runTest {
        chosen()
        assertThat(repo.backupNow()).isNull()
        val files = folder.list(DirFolder.TREE)
        assertThat(files.map { it.name }).containsExactly(NEW)
        val status = repo.status.first()
        assertThat(status.folderName).isEqualTo("Backups")
        assertThat(status.last?.name).isEqualTo(NEW)
        assertThat(status.last?.place).isEqualTo("Backups")
        assertThat(status.last?.sizeBytes).isEqualTo(files.single().sizeBytes)
        // Always encrypted with the backup password.
        assertThat(folder.openInput(files.single().uri).use { backupFile.open(it, null) }).isEqualTo(OpenedBackupFile.NeedsPassword)
        val opened = folder.openInput(files.single().uri).use { backupFile.open(it, PASSWORD.toCharArray()) } as OpenedBackupFile.Ok
        assertThat(opened.json).isEqualTo(exports.json)
    }

    @Test fun simpleKeepsFiveAndNeverTouchesOtherFiles() = runTest {
        chosen()
        repeat(10) { folder.put("forkcast-backup-2026-10-${"%02d".format(it + 1)}.forkcast", modified = it + 1L) }
        folder.put("holiday.jpg", modified = 0)
        folder.put("forkcast-20261019-0300.json.gz", modified = 0)
        repo.updateSettings { it.copy(retention = RetentionPreset.SIMPLE) }
        assertThat(repo.backupNow()).isNull()
        assertThat(names()).containsExactly(
            NEW,
            "forkcast-backup-2026-10-10.forkcast",
            "forkcast-backup-2026-10-09.forkcast",
            "forkcast-backup-2026-10-08.forkcast",
            "forkcast-backup-2026-10-07.forkcast",
            "holiday.jpg",
            "forkcast-20261019-0300.json.gz",
        )
    }

    @Test fun balancedKeepsSevenAndOnePerMonthForSixMonths() = runTest {
        chosen()
        // Weekly backups from January, named the 1.8.2 way; the file times say nothing.
        var day = java.time.LocalDate.of(2026, 1, 5)
        while (day.isBefore(java.time.LocalDate.of(2026, 10, 26))) {
            folder.put("${day}_0300_forkcast-backup.forkcast", modified = 1)
            day = day.plusWeeks(1)
        }
        assertThat(repo.backupNow()).isNull()
        val kept = names()
        assertThat(kept).hasSize(11)
        assertThat(kept).contains("2026-08-31_0300_forkcast-backup.forkcast")
        assertThat(kept).contains("2026-05-25_0300_forkcast-backup.forkcast")
        assertThat(kept).doesNotContain("2026-04-27_0300_forkcast-backup.forkcast")
    }

    @Test fun nothingIsPrunedWhenTheNewBackupDoesntReadBack() = runTest {
        chosen()
        repeat(10) { folder.put("forkcast-backup-2026-10-${"%02d".format(it + 1)}.forkcast", modified = it + 1L) }
        repo.updateSettings { it.copy(retention = RetentionPreset.SIMPLE) }
        exports.json = "not an export"
        assertThat(repo.backupNow()).isNull()
        assertThat(names()).hasSize(11)
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

    @Test fun noBackupWithoutAPassword() = runTest {
        assertThat(repo.chooseFolder(DirFolder.TREE)).isNull()
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.PASSWORD_MISSING)
        assertThat(names()).isEmpty()
        repo.setPassword(PASSWORD)
        assertThat(repo.status.first().hasPassword).isTrue()
        assertThat(repo.backupNow()).isNull()
        // The cleanup check opens it with the saved password.
        folder.put("forkcast-backup-2026-10-01.forkcast", modified = 1)
        assertThat(repo.cleanupPlan()).isInstanceOf(CleanupPlan.Ready::class.java)
    }

    @Test fun changingThePasswordReencryptsTheFolderAndNamesWhatCouldntBe() = runTest {
        chosen()
        assertThat(repo.backupNow()).isNull()
        folder.put("2026-10-19_0300_forkcast-backup.forkcast", modified = 1, bytes = encryptedBackup(PASSWORD))
        folder.put("2026-10-12_0300_forkcast-backup.forkcast", modified = 1, bytes = encryptedBackup("someone else's password"))
        folder.put("2026-10-05_0300_forkcast-backup_unencrypted.forkcast", modified = 1)
        folder.put("notes.txt", modified = 1)
        val result = repo.changePassword("a brand new password")
        assertThat(result.updated).isEqualTo(2)
        assertThat(result.failed).containsExactly("2026-10-12_0300_forkcast-backup.forkcast")
        assertThat(repo.password()).isEqualTo("a brand new password")
        // Same names; the two updated open with the new password only.
        assertThat(names()).containsExactly(
            NEW,
            "2026-10-19_0300_forkcast-backup.forkcast",
            "2026-10-12_0300_forkcast-backup.forkcast",
            "2026-10-05_0300_forkcast-backup_unencrypted.forkcast",
            "notes.txt",
        )
        for (name in listOf(NEW, "2026-10-19_0300_forkcast-backup.forkcast")) {
            assertThat(folder.openInput(name).use { backupFile.open(it, PASSWORD.toCharArray()) }).isEqualTo(OpenedBackupFile.WrongPassword)
            assertThat(folder.openInput(name).use { backupFile.open(it, "a brand new password".toCharArray()) }).isInstanceOf(OpenedBackupFile.Ok::class.java)
        }
    }

    @Test fun removingThePasswordTurnsAutomaticBackupOff() = runTest {
        chosen()
        repo.updateSettings { it.copy(auto = true) }
        repo.removePassword()
        assertThat(repo.password()).isNull()
        assertThat(repo.status.first().settings.auto).isFalse()
        assertThat(repo.backupNow()).isEqualTo(FolderProblem.PASSWORD_MISSING)
    }

    @Test fun cleanupChecksTheNewestThenDeletesOnlyThePlannedBackups() = runTest {
        chosen()
        repeat(3) { folder.put("forkcast-backup-2026-10-0${it + 1}.forkcast", modified = it + 1L) }
        assertThat(repo.backupNow()).isNull()
        val plan = repo.cleanupPlan() as CleanupPlan.Ready
        assertThat(plan.keep.name).isEqualTo(NEW)
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
        assertThat(names()).containsExactly(NEW, "notes.txt")
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
        repo.recordSaved(NEW, 1234)
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
            // Like document providers: a taken name gets " (1)".
            val f = generateSequence(0) { it + 1 }
                .map { n -> File(dir, if (n == 0) name else name.replace(".forkcast", " ($n).forkcast")) }
                .first { !it.exists() }
            val shown = f.name
            f.writeBytes(ByteArray(0))
            // Newer than anything put() made.
            f.setLastModified(Instant.parse("2026-10-26T07:00:00Z").toEpochMilli() + clockTick++)
            return FolderFile(shown, shown, null, null)
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
        override fun rename(file: String, name: String): String? = name.takeIf { File(dir, file).renameTo(File(dir, name)) }

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

    private companion object {
        const val PASSWORD = "a long backup password"
        /** What [clock]'s backup is called. */
        const val NEW = "2026-10-26_0300_forkcast-backup.forkcast"
    }

    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = encrypt(ciphertext, associatedData)
    }
}
