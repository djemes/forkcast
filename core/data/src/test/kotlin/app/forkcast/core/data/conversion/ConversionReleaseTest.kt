package app.forkcast.core.data.conversion

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.backup.BackupJson
import app.forkcast.core.data.backup.MemoryDataStore
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.RoomBackupRepository
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.BackupSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** 1.9.1, the conversion release (roadmap 2.0, 6.0). */
@RunWith(AndroidJUnit4::class)
class ConversionReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-30T16:00:00Z"), ZoneId.of("America/New_York"))
    private val backups = RoomBackupRepository(context, db, MemoryDataStore(AppSettings()), clock, "1.9.1")
    private val settingsFile = context.dataStoreFile(AppSettingsSerializer.FILE_NAME)
    private val safetyDir = File(context.filesDir, RoomBackupRepository.SAFETY_DIR)

    @After fun tearDown() {
        db.close()
        settingsFile.delete()
        safetyDir.deleteRecursively()
        context.getSharedPreferences(DevicePrefs.FILE, 0).edit().clear().commit()
    }

    @Test fun settingsFileIsRewrittenInTheCurrentShape() {
        settingsFile.parentFile!!.mkdirs()
        settingsFile.writeText(
            """{"appearance":{"themeMode":"DARK"},"backup":{"auto":true,"frequency":"DAILY","weekday":1,"days":null,""" +
                """"wifiOnly":true,"minute":120},"photoRetention14Applied":true}""",
        )
        ConversionRelease.prepareSettings(context)

        val text = settingsFile.readText()
        assertThat(text).doesNotContain("frequency")
        assertThat(text).doesNotContain("weekday")
        assertThat(text).doesNotContain("wifiOnly")
        assertThat(text).doesNotContain("photoRetention14Applied")
        val settings = AppSettingsSerializer.decode(text)
        assertThat(settings.backup).isEqualTo(BackupSettings(days = (1..7).toSet(), minute = 120))
        assertThat(settings.appearance.themeMode.name).isEqualTo("DARK")
        // Once done, nothing changes.
        ConversionRelease.prepareSettings(context)
        assertThat(settingsFile.readText()).isEqualTo(text)
    }

    @Test fun unreadableOrMissingSettingsAreLeftAlone() {
        ConversionRelease.prepareSettings(context)
        assertThat(settingsFile.exists()).isFalse()
        settingsFile.parentFile!!.mkdirs()
        settingsFile.writeText("not json")
        ConversionRelease.prepareSettings(context)
        assertThat(settingsFile.readText()).isEqualTo("not json")
    }

    @Test fun safetyBackupsAreRewrittenAsTheCurrentExport() = runTest {
        safetyDir.mkdirs()
        val old = File(safetyDir, "forkcast-before-replace-20261020-101010.json")
        old.writeText(
            """{"app":"Forkcast","schemaVersion":8,"appVersion":"1.9.0","exportedAt":"2026-10-20T14:10:10Z","timeZone":"UTC",""" +
                """"settings":{"backup":{"frequency":"WEEKLY","weekday":5}}}""",
        )
        val broken = File(safetyDir, "forkcast-before-replace-20261019-101010.json").apply { writeText("{") }
        val device = DevicePrefs(context)

        ConversionRelease(context, backups, device).run()

        val root = BackupJson.parseToJsonElement(old.readText()).jsonObject
        assertThat(root.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("9")
        val parsed = backups.parse(old.readText()) as ParsedBackup.Ok
        assertThat(parsed.export.settings!!.backup.days).containsExactly(5)
        assertThat(parsed.export.appVersion).isEqualTo("1.9.0")
        assertThat(broken.readText()).isEqualTo("{")
    }

    @Test fun deviceStateLosesOldKeysAndTheUntimedCardHide() = runTest {
        context.getSharedPreferences(DevicePrefs.FILE, 0).edit()
            .putString("driveAccount", "someone").putBoolean("cameraWarningsOpen", true).putLong("supportCardHiddenAtMajor", 1)
            .putString("backupFolderName", "Backups").commit()
        val device = DevicePrefs(context)

        ConversionRelease(context, backups, device).run()

        val prefs = context.getSharedPreferences(DevicePrefs.FILE, 0)
        assertThat(prefs.contains("driveAccount")).isFalse()
        assertThat(prefs.contains("cameraWarningsOpen")).isFalse()
        assertThat(prefs.contains("supportCardHiddenAtMajor")).isFalse()
        assertThat(device.state.value.backupFolderName).isEqualTo("Backups")
    }

    @Test fun aCardHiddenWithATimeStaysHidden() = runTest {
        val device = DevicePrefs(context)
        device.update { it.copy(supportCardHiddenAtMajor = 1, supportCardHiddenAt = 123L) }

        ConversionRelease(context, backups, device).run()

        assertThat(device.state.value.supportCardHiddenAtMajor).isEqualTo(1)
        assertThat(device.state.value.supportCardHiddenAt).isEqualTo(123L)
    }
}
