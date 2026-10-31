package app.forkcast.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import app.forkcast.core.model.AgreementAcceptance
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
    /** The folder automatic backups go to (a document-tree uri), null = none chosen here. */
    val backupFolder: String? = null,
    /** Its name as shown by its app, saved when it was chosen. */
    val backupFolderName: String? = null,
    /** The last successful backup, automatic or saved with the file picker. */
    val lastBackup: LastBackup? = null,
    /** Why the last automatic backup failed (a short code), null after a success. */
    val lastBackupError: String? = null,
    /** Automatic backups that failed in a row; the second one notifies. */
    val backupFailures: Int = 0,
    /** The no-backup reminder on Today was dismissed at this time (epoch ms); it comes back after another 14 days. */
    val backupReminderDismissedAt: Long? = null,
    /** When Forkcast first ran on this phone: the reminder counts from here before any backup. */
    val firstSeenAt: Long? = null,
    /** The release tag the update check last notified about, so each version notifies once. */
    val notifiedUpdate: String? = null,
    /** The Terms of Use and Disclaimer accepted on this phone, null = not yet (roadmap 2.0, task 4.1). */
    val agreement: AgreementAcceptance? = null,
    /** AI recipients ([app.forkcast.core.model.AiRecipient]) allowed to receive meals, with when (epoch ms). */
    val aiConsents: Map<String, Long> = emptyMap(),
    /** The major version the Ko-fi card in Settings was hidden at, null = shown ([app.forkcast.core.domain.SupportCard]). */
    val supportCardHiddenAtMajor: Int? = null,
    /** When the Ko-fi card was hidden (epoch ms), null = shown. */
    val supportCardHiddenAt: Long? = null,
)

/** Where and when the last backup was written. [place] is the folder's name, null for a picked file. */
data class LastBackup(val at: Long, val name: String, val place: String?, val sizeBytes: Long?)

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
            putOrRemove(BACKUP_FOLDER, next.backupFolder)
            putOrRemove(BACKUP_FOLDER_NAME, next.backupFolderName)
            putOrRemove(LAST_AT, next.lastBackup?.at)
            putOrRemove(LAST_NAME, next.lastBackup?.name)
            putOrRemove(LAST_PLACE, next.lastBackup?.place)
            putOrRemove(LAST_SIZE, next.lastBackup?.sizeBytes)
            putOrRemove(LAST_ERROR, next.lastBackupError)
            putInt(FAILURES, next.backupFailures)
            putOrRemove(REMINDER_DISMISSED, next.backupReminderDismissedAt)
            putOrRemove(FIRST_SEEN, next.firstSeenAt)
            putOrRemove(NOTIFIED_UPDATE, next.notifiedUpdate)
            putOrRemove(AGREEMENT_VERSION, next.agreement?.version?.toLong())
            putOrRemove(AGREEMENT_AT, next.agreement?.acceptedAt)
            putOrRemove(AGREEMENT_APP, next.agreement?.appVersion)
            putStringSet(AI_CONSENTS, next.aiConsents.map { (recipient, at) -> "$at$SEP$recipient" }.toSet())
            putOrRemove(SUPPORT_CARD_HIDDEN, next.supportCardHiddenAtMajor?.toLong())
            putOrRemove(SUPPORT_CARD_HIDDEN_AT, next.supportCardHiddenAt)
        }
        _state.value = next
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value != null) putString(key, value) else remove(key)
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value != null) putLong(key, value) else remove(key)
    }

    private fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    private fun read() = DeviceState(
        dismissedMissingKeys = prefs.getStringSet(DISMISSED, null)?.toSet().orEmpty(),
        backupFolder = prefs.getString(BACKUP_FOLDER, null),
        backupFolderName = prefs.getString(BACKUP_FOLDER_NAME, null),
        lastBackup = long(LAST_AT)?.let { at ->
            LastBackup(at, prefs.getString(LAST_NAME, null).orEmpty(), prefs.getString(LAST_PLACE, null), long(LAST_SIZE))
        },
        lastBackupError = prefs.getString(LAST_ERROR, null),
        backupFailures = prefs.getInt(FAILURES, 0),
        backupReminderDismissedAt = long(REMINDER_DISMISSED),
        firstSeenAt = long(FIRST_SEEN),
        notifiedUpdate = prefs.getString(NOTIFIED_UPDATE, null),
        agreement = long(AGREEMENT_VERSION)?.let { v ->
            AgreementAcceptance(v.toInt(), long(AGREEMENT_AT) ?: 0, prefs.getString(AGREEMENT_APP, null).orEmpty())
        },
        aiConsents = prefs.getStringSet(AI_CONSENTS, null).orEmpty().mapNotNull { entry ->
            val at = entry.substringBefore(SEP).toLongOrNull() ?: return@mapNotNull null
            entry.substringAfter(SEP) to at
        }.toMap(),
        supportCardHiddenAtMajor = long(SUPPORT_CARD_HIDDEN)?.toInt(),
        supportCardHiddenAt = long(SUPPORT_CARD_HIDDEN_AT),
    )

    companion object {
        /** Excluded from Android backup in data_extraction_rules.xml and backup_rules.xml. */
        const val FILE = "device"
        private const val DISMISSED = "dismissedMissingKeys"
        private const val BACKUP_FOLDER = "backupFolder"
        private const val BACKUP_FOLDER_NAME = "backupFolderName"
        private const val LAST_AT = "lastBackupAt"
        private const val LAST_NAME = "lastBackupName"
        private const val LAST_PLACE = "lastBackupPlace"
        private const val LAST_SIZE = "lastBackupSize"
        private const val LAST_ERROR = "lastBackupError"
        private const val FAILURES = "backupFailures"
        private const val REMINDER_DISMISSED = "backupReminderDismissedAt"
        private const val FIRST_SEEN = "firstSeenAt"
        private const val NOTIFIED_UPDATE = "notifiedUpdate"
        private const val AGREEMENT_VERSION = "agreementVersion"
        private const val AGREEMENT_AT = "agreementAcceptedAt"
        private const val AGREEMENT_APP = "agreementAppVersion"
        private const val AI_CONSENTS = "aiConsents"
        private const val SUPPORT_CARD_HIDDEN = "supportCardHiddenAtMajor"
        private const val SUPPORT_CARD_HIDDEN_AT = "supportCardHiddenAt"
        /** Between the time and the recipient in [AI_CONSENTS] entries; recipients never contain it. */
        private const val SEP = "|"
    }
}
