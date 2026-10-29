package app.forkcast.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import app.forkcast.core.data.secrets.SecretName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// As in SettingsBackupTest: the JDK's own AES-GCM, not Robolectric's Conscrypt.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class BackupFileTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val photos = PhotoStore(context)
    private val archive = PhotoArchive(context, photos)
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("America/New_York"))
    private val store = testSecretStore(context, "secrets-file-test")
    private val file = BackupFile(FixedExport, archive, BackupSecrets(store), "1.5.0", clock)
    private val password = "a long backup password".toCharArray()

    @After fun tearDown() {
        File(context.filesDir, "secrets-file-test").deleteRecursively()
    }

    @Before fun photosOnThisPhone() {
        photos.root.deleteRecursively()
        File(photos.root, "a1/0.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private suspend fun backup(pw: CharArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val written = file.write(out, pw)
        assertThat(written).isEqualTo(out.size().toLong())
        return out.toByteArray()
    }

    @Test fun plainBackupCarriesManifestJsonAndPhotos() = runTest {
        store.setAiKey("p1", "sk-test-key")
        store.set(SecretName.BACKUP_PASSWORD, "never inside")
        val opened = file.open(backup().inputStream(), null) as OpenedBackupFile.Ok
        assertThat(opened.json).isEqualTo(FixedExport.JSON)
        assertThat(opened.photos).isEqualTo(1)
        assertThat(opened.encrypted).isFalse()
        // Keys travel with the backup (owner, 2026-10-29), never the backup password itself.
        assertThat(opened.secrets).containsExactly("ai:p1", "sk-test-key")
        assertThat(opened.manifest).isEqualTo(
            BackupManifest(appVersion = "1.5.0", schemaVersion = ForkcastExport.SCHEMA_VERSION, createdAt = "2026-10-24T10:00:00Z", photos = true, keys = 1),
        )
    }

    @Test fun passwordProtectedBackupNeedsTheRightPassword() = runTest {
        val bytes = backup(pw = password)
        assertThat(BackupCrypto.isEncrypted(bytes)).isTrue()
        assertThat(file.open(bytes.inputStream(), null)).isEqualTo(OpenedBackupFile.NeedsPassword)
        assertThat(file.open(bytes.inputStream(), "not it at all".toCharArray())).isEqualTo(OpenedBackupFile.WrongPassword)
        val opened = file.open(bytes.inputStream(), password) as OpenedBackupFile.Ok
        assertThat(opened.json).isEqualTo(FixedExport.JSON)
        assertThat(opened.photos).isEqualTo(1)
        assertThat(opened.encrypted).isTrue()
    }

    @Test fun olderFormatsStillOpen() = runTest {
        // 1.0–1.4 "Include photos" zip: no manifest.
        val zip = ByteArrayOutputStream().also { archive.write(it, FixedExport.JSON) }.toByteArray()
        val fromZip = file.open(zip.inputStream(), null) as OpenedBackupFile.Ok
        assertThat(fromZip.manifest).isNull()
        assertThat(fromZip.photos).isEqualTo(1)
        // A plain JSON export.
        val fromJson = file.open(FixedExport.JSON.byteInputStream(), null) as OpenedBackupFile.Ok
        assertThat(fromJson.json).isEqualTo(FixedExport.JSON)
        assertThat(fromJson.photos).isEqualTo(0)
    }

    @Test fun names() {
        assertThat(file.fileName()).isEqualTo("2026-10-24_0600_forkcast-backup.forkcast")
        assertThat(file.fileName(encrypted = false)).isEqualTo("2026-10-24_0600_forkcast-backup_unencrypted.forkcast")
        assertThat(BackupFile.isBackupName("2026-10-24_0600_forkcast-backup (1).forkcast")).isTrue()
        assertThat(BackupFile.isUnencryptedName("2026-10-24_0600_forkcast-backup_unencrypted.forkcast")).isTrue()
        assertThat(BackupFile.isUnencryptedName("2026-10-24_0600_forkcast-backup.forkcast")).isFalse()
        // 1.5 to 1.8.1 names are still Forkcast's.
        assertThat(BackupFile.isBackupName("forkcast-backup-2026-10-24.forkcast")).isTrue()
        assertThat(BackupFile.isBackupName("forkcast-backup-2026-10-24-0300.forkcast")).isTrue()
        assertThat(BackupFile.isBackupName("forkcast-backup-2026-10-24 (1).forkcast")).isTrue()
        val zone = ZoneId.of("America/New_York")
        assertThat(BackupFile.createdAt("2026-10-24_0600_forkcast-backup.forkcast", zone)).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"))
        assertThat(BackupFile.createdAt("forkcast-backup-2026-10-24-0300.forkcast", zone)).isEqualTo(Instant.parse("2026-10-24T07:00:00Z"))
        assertThat(BackupFile.createdAt("forkcast-backup-2026-10-24.forkcast", zone)).isEqualTo(Instant.parse("2026-10-24T04:00:00Z"))
        assertThat(BackupFile.isBackupName("forkcast-20261024-0300.json")).isFalse()
        assertThat(BackupFile.isBackupName("holiday.jpg")).isFalse()
    }

    private object FixedExport : BackupRepository {
        const val JSON = """{"app":"Forkcast","schemaVersion":7}"""
        override suspend fun exportJson() = JSON
        override fun parse(text: String): ParsedBackup = error("unused")
        override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary = error("unused")
        override suspend fun readSafetyBackup(): String? = error("unused")
    }
}
