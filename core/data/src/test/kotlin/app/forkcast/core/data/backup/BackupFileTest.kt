package app.forkcast.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// As in SettingsBackupTest: the JDK's own AES-GCM, not Robolectric's Conscrypt.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class BackupFileTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val photos = PhotoStore(context)
    private val archive = PhotoArchive(context, photos)
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("America/New_York"))
    private val file = BackupFile(FixedExport, archive, "1.5.0", clock)
    private val password = "a long backup password".toCharArray()

    @Before fun photosOnThisPhone() {
        photos.root.deleteRecursively()
        File(photos.root, "a1/0.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private suspend fun backup(includePhotos: Boolean, pw: CharArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val written = file.write(out, includePhotos, pw)
        assertThat(written).isEqualTo(out.size().toLong())
        return out.toByteArray()
    }

    @Test fun plainBackupCarriesManifestJsonAndPhotos() = runTest {
        val opened = file.open(backup(includePhotos = true).inputStream(), null) as OpenedBackupFile.Ok
        assertThat(opened.json).isEqualTo(FixedExport.JSON)
        assertThat(opened.photos).isEqualTo(1)
        assertThat(opened.encrypted).isFalse()
        assertThat(opened.manifest).isEqualTo(
            BackupManifest(appVersion = "1.5.0", schemaVersion = ForkcastExport.SCHEMA_VERSION, createdAt = "2026-10-24T10:00:00Z", photos = true),
        )
    }

    @Test fun withoutPhotos() = runTest {
        val opened = file.open(backup(includePhotos = false).inputStream(), null) as OpenedBackupFile.Ok
        assertThat(opened.photos).isEqualTo(0)
        assertThat(opened.manifest?.photos).isFalse()
    }

    @Test fun passwordProtectedBackupNeedsTheRightPassword() = runTest {
        val bytes = backup(includePhotos = true, pw = password)
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
        assertThat(file.fileName()).isEqualTo("forkcast-backup-2026-10-24.forkcast")
        assertThat(file.fileName(LocalDate.of(2027, 1, 5))).isEqualTo("forkcast-backup-2027-01-05.forkcast")
        assertThat(BackupFile.isBackupName("forkcast-backup-2026-10-24 (1).forkcast")).isTrue()
        assertThat(BackupFile.isBackupName("forkcast-20261024-0300.json")).isFalse()
        assertThat(BackupFile.isBackupName("holiday.jpg")).isFalse()
    }

    private object FixedExport : BackupRepository {
        const val JSON = """{"app":"Forkcast","schemaVersion":7}"""
        override suspend fun exportJson() = JSON
        override suspend fun foodLogCsv(): String = error("unused")
        override suspend fun weightsCsv(): String = error("unused")
        override fun parse(text: String): ParsedBackup = error("unused")
        override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary = error("unused")
        override suspend fun readSafetyBackup(): String? = error("unused")
    }
}
