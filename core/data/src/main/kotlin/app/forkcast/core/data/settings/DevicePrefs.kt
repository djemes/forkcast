package app.forkcast.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State that belongs to this phone only: never in data exports, settings exports or Android
 * backup (`device.xml` is excluded in the backup rules). A restored or new phone starts clean,
 * so for example it asks for missing API keys again even if the old phone was told "Not now".
 */
data class DeviceState(
    /** Missing-key prompts dismissed on this phone ("usda", "ai:<profileId>"). */
    val dismissedMissingKeys: Set<String> = emptySet(),
    /** Google account Drive backups go to (shown in Settings; null = not connected here). */
    val driveAccount: String? = null,
    /** Id of the "Forkcast Backups" folder in that Drive, once found or created. */
    val driveFolderId: String? = null,
    /** Last successful Drive backup (epoch ms). */
    val lastDriveBackupAt: Long? = null,
    /** Why the last attempt failed (a short code), null after a success. */
    val lastDriveError: String? = null,
)

@Singleton
class DevicePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    @Synchronized
    fun update(transform: (DeviceState) -> DeviceState) {
        val next = transform(_state.value)
        prefs.edit {
            putStringSet(DISMISSED, next.dismissedMissingKeys)
            putOrRemove(DRIVE_ACCOUNT, next.driveAccount)
            putOrRemove(DRIVE_FOLDER, next.driveFolderId)
            if (next.lastDriveBackupAt != null) putLong(LAST_BACKUP, next.lastDriveBackupAt) else remove(LAST_BACKUP)
            putOrRemove(LAST_ERROR, next.lastDriveError)
            // The camera tips' open/closed state (1.3–1.4) is gone; drop it from phones that have it.
            remove(CAMERA_WARNINGS)
        }
        _state.value = next
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value != null) putString(key, value) else remove(key)
    }

    private fun read() = DeviceState(
        dismissedMissingKeys = prefs.getStringSet(DISMISSED, null)?.toSet().orEmpty(),
        driveAccount = prefs.getString(DRIVE_ACCOUNT, null),
        driveFolderId = prefs.getString(DRIVE_FOLDER, null),
        lastDriveBackupAt = if (prefs.contains(LAST_BACKUP)) prefs.getLong(LAST_BACKUP, 0) else null,
        lastDriveError = prefs.getString(LAST_ERROR, null),
    )

    companion object {
        /** Excluded from Android backup in data_extraction_rules.xml and backup_rules.xml. */
        const val FILE = "device"
        private const val DISMISSED = "dismissedMissingKeys"
        private const val DRIVE_ACCOUNT = "driveAccount"
        private const val DRIVE_FOLDER = "driveFolderId"
        private const val LAST_BACKUP = "lastDriveBackupAt"
        private const val LAST_ERROR = "lastDriveError"
        private const val CAMERA_WARNINGS = "cameraWarningsOpen"
    }
}
