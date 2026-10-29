package app.forkcast.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import com.google.common.truth.Truth.assertWithMessage
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
 * REVIEW-2 S12, as changed by the owner on 2026-10-29: a saved key appears in no file the app
 * writes except inside a backup's `secrets.json` (where backups carry everything), never in the
 * data export, the CSVs or a backup's other entries; an encrypted backup shows it nowhere; and the
 * backup password and GitHub token are never in a backup. The crash report has its own test
 * (app module, CrashReportsTest).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class NoSecretAnywhereTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = MemoryDataStore(AppSettings())
    private val repo = RoomBackupRepository(context, db, settings, clock, "1.6.0", CsvExporter(db, clock))
    private val secrets = testSecretStore(context, "secrets-test")

    private val aiKey = "sk-FAKE-ai-key-for-the-no-secret-test-1234"
    private val usdaKey = "FAKEUSDAKEY0000000000000000000000000000"
    private val backupPassword = "the-backup-password-itself-0000"
    private val githubToken = "github_pat_FAKE0000000000000000"

    @After fun tearDown() {
        db.close()
        File(context.filesDir, "secrets-test").deleteRecursively()
    }

    @Test fun savedKeysAppearOnlyInsideBackups() = runTest {
        db.backupDao().upsertAiProfiles(listOf(AiProfileEntity("p1", "My AI", ProviderId.OPENAI_COMPAT, "m", "https://api.openai.com", TierLabel.PAID, 0, true, false, null, 1, 1)))
        secrets.setAiKey("p1", aiKey)
        secrets.set(SecretName.USDA_API_KEY, usdaKey)
        secrets.set(SecretName.BACKUP_PASSWORD, backupPassword)
        secrets.set(SecretName.GITHUB_TOKEN, githubToken)

        val files = mutableMapOf(
            "export.json" to repo.exportJson(),
            "food-log.csv" to repo.foodLogCsv(),
            "weights.csv" to repo.weightsCsv(),
        )
        val file = BackupFile(repo, PhotoArchive(context, PhotoStore(context)), BackupSecrets(secrets), "1.6.0", clock)
        val plain = ByteArrayOutputStream().also { file.write(it, password = null) }
        var secretsEntry: String? = null
        ZipInputStream(plain.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach {
                val text = zip.readBytes().decodeToString()
                if (it.name == PhotoArchive.SECRETS_NAME) secretsEntry = text else files["backup:${it.name}"] = text
            }
        }
        val encrypted = ByteArrayOutputStream().also { file.write(it, password = backupPassword.toCharArray()) }
        files["encrypted backup"] = encrypted.toByteArray().decodeToString()

        // The backup's manifest and data were both read, and the keys are in its secrets entry only.
        assertWithMessage("backup entries").that(files.keys.count { it.startsWith("backup:") }).isAtLeast(2)
        assertWithMessage("secrets.json").that(secretsEntry).contains(aiKey)
        assertWithMessage("secrets.json").that(secretsEntry).contains(usdaKey)
        assertWithMessage("secrets.json").that(secretsEntry).doesNotContain(backupPassword)
        assertWithMessage("secrets.json").that(secretsEntry).doesNotContain(githubToken)
        for ((name, text) in files) {
            for (key in listOf(aiKey, usdaKey, backupPassword, githubToken)) assertWithMessage(name).that(text).doesNotContain(key)
        }
    }
}
