package app.forkcast.core.data.conversion

import android.content.Context
import androidx.datastore.dataStoreFile
import app.forkcast.core.data.backup.BackupJson
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.RoomBackupRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversion release, 1.9.1 (roadmap 2.0, task 6.0): leaves this phone's files exactly as 2.0
 * reads them, so 2.0 ships with no converters. Each step does nothing once it has run, so it runs
 * at every start. Removed in 2.0, with LegacySettings and the other 1.x converters.
 */
@Singleton
class ConversionRelease @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    private val device: DevicePrefs,
) {
    /** Safety backups and this phone's own state; after start-up, off the main thread. */
    suspend fun run() = withContext(Dispatchers.IO) {
        // A Ko-fi card hidden in 1.8.6 has no time, so it shows (SupportCard); the state now says so.
        // Writing DevicePrefs also drops the keys of Drive backup (1.0–1.4) and the camera tips (1.3–1.4).
        device.update { if (it.supportCardHiddenAt == null) it.copy(supportCardHiddenAtMajor = null) else it }
        convertSafetyBackups()
    }

    /** The copies kept before a Replace, rewritten in the current export format (2.0 reads only that). */
    private fun convertSafetyBackups() {
        val files = File(context.filesDir, RoomBackupRepository.SAFETY_DIR).listFiles { f -> f.name.endsWith(".json") } ?: return
        files.forEach { file ->
            val export = runCatching { (backups.parse(file.readText()) as? ParsedBackup.Ok)?.export }.getOrNull()
            if (export != null && export.schemaVersion != ForkcastExport.SCHEMA_VERSION) runCatching {
                writeAtomically(file, BackupJson.encodeToString(ForkcastExport.serializer(), export.copy(schemaVersion = ForkcastExport.SCHEMA_VERSION)))
            }
        }
    }

    companion object {
        /**
         * Before anything opens the settings (Application.onCreate, ahead of injection): rewrites
         * settings.json in the current shape, without the keys older versions left in it. DataStore
         * writes only when a value changes, so it would never drop them itself.
         */
        fun prepareSettings(context: Context) {
            val file = context.dataStoreFile(AppSettingsSerializer.FILE_NAME)
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
            // A file that doesn't read is left to DataStore's corruption handler.
            val tidy = runCatching { AppSettingsSerializer.encode(AppSettingsSerializer.decode(text)) }.getOrNull() ?: return
            if (tidy != text) runCatching { writeAtomically(file, tidy) }
        }

        private fun writeAtomically(file: File, text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
