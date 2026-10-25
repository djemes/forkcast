package app.forkcast.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import com.google.common.truth.Truth.assertWithMessage
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * REVIEW-2 S12: a key saved in the app appears in no file the app writes for the user: the data
 * export, both CSVs, a backup file (every entry inside it) and a settings export (where keys
 * travel only encrypted). The crash report has its own test (app module, CrashReportsTest).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class NoSecretAnywhereTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = MemoryDataStore(AppSettings())
    private val repo = RoomBackupRepository(context, db, settings, clock, "1.6.0", CsvExporter(db, clock))
    private val secrets = SecretStore(File(context.filesDir, "secrets-test/store.bin"), { PlainAead }, Dispatchers.Unconfined)

    private val aiKey = "sk-FAKE-ai-key-for-the-no-secret-test-1234"
    private val usdaKey = "FAKEUSDAKEY0000000000000000000000000000"

    @After fun tearDown() {
        db.close()
        File(context.filesDir, "secrets-test").deleteRecursively()
    }

    @Test fun savedKeysAppearInNoExportedFile() = runTest {
        db.backupDao().upsertAiProfiles(listOf(AiProfileEntity("p1", "My AI", ProviderId.OPENAI_COMPAT, "m", "https://api.openai.com", TierLabel.PAID, 0, true, false, null, 1, 1)))
        secrets.setAiKey("p1", aiKey)
        secrets.set(SecretName.USDA_API_KEY, usdaKey)

        val files = mutableMapOf(
            "export.json" to repo.exportJson(),
            "food-log.csv" to repo.foodLogCsv(),
            "weights.csv" to repo.weightsCsv(),
        )
        val backup = ByteArrayOutputStream().also { BackupFile(repo, PhotoArchive(context, PhotoStore(context)), "1.6.0", clock).write(it, includePhotos = true, password = null) }
        ZipInputStream(backup.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files["backup:${it.name}"] = zip.readBytes().decodeToString() }
        }
        val transfer = SettingsBackupRepository(db, settings, secrets, clock, "1.6.0").apply { iterations = 1_000 }
        files["settings.forkcast-settings"] = transfer.export("a long passphrase here".toCharArray())

        // The backup's manifest and data were both read.
        assertWithMessage("backup entries").that(files.keys.count { it.startsWith("backup:") }).isAtLeast(2)
        for ((name, text) in files) {
            for (key in listOf(aiKey, usdaKey)) assertWithMessage(name).that(text).doesNotContain(key)
        }
    }

    /** The secret store's own encryption is Keystore-backed in the app; a pass-through is enough here. */
    private object PlainAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.reversedArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.reversedArray()
    }
}
