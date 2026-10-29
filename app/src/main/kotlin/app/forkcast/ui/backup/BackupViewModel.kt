package app.forkcast.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.BackupSecrets
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.OpenedBackupFile
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.PhotoArchive
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.backup.folder.FolderBackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class ExportKind(val extension: String, val mimeType: String) {
    /** The `.forkcast` backup file (roadmap 2.0, task 1.4). */
    BACKUP("forkcast", BackupFile.MIME_TYPE),
    FOOD_LOG_CSV("csv", "text/csv"),
    WEIGHTS_CSV("csv", "text/csv"),
}

sealed interface BackupDialog {
    /** "Back up without encryption": what's inside, before the unlock and the file picker. */
    data object UnencryptedWarning : BackupDialog
    /** An encrypted backup was restored on a phone with no backup password: keep the one typed? */
    data object KeepPassword : BackupDialog
    /** What's in the picked file, before anything changes. [photos] = meal photos it carries. */
    data class Preview(val preview: BackupPreview, val isSafetyBackup: Boolean, val photos: Int = 0) : BackupDialog
    /** The picked backup is password-protected; [wrong] after a wrong try. */
    data class Password(val wrong: Boolean) : BackupDialog
    data object ConfirmReplace : BackupDialog
    data class Done(val summary: ImportSummary) : BackupDialog
    data class Rejected(val reason: RejectReason) : BackupDialog
    data object ReadFailed : BackupDialog
    data object ImportFailed : BackupDialog
}

sealed interface BackupNotice {
    data class Exported(val kind: ExportKind) : BackupNotice
    data object ExportFailed : BackupNotice
    /** Back up now wrote to the folder, or why it couldn't. */
    data class Folder(val problem: FolderProblem?) : BackupNotice
}

/** How the next file from the picker is written. */
enum class SaveMode { ENCRYPTED, UNENCRYPTED }

data class BackupUiState(
    val busy: Boolean = false,
    val hasSafetyBackup: Boolean = false,
    val dialog: BackupDialog? = null,
    val notice: BackupNotice? = null,
    /** The screen opens the file picker for a backup, named for this mode. */
    val pickBackupFile: SaveMode? = null,
    /** A backup written for sharing: the screen opens Android's share sheet with it. */
    val share: Uri? = null,
    /** After the summary closes, ask [BackupDialog.KeepPassword]. */
    val offerKeepPassword: Boolean = false,
)

sealed interface BackupEvent {
    /** Encrypted, to the folder if one is chosen, else where the picker says. */
    data object BackUpNow : BackupEvent
    /** Encrypted, then Android's share sheet. */
    data object BackUpAndShare : BackupEvent
    data object Shared : BackupEvent
    data object AskUnencrypted : BackupEvent
    /** After the warning and the phone's unlock: the picker opens for the unencrypted copy. */
    data object UnencryptedConfirmed : BackupEvent
    /** Keeps the password a restored backup was opened with as this phone's backup password. */
    data object KeepPassword : BackupEvent
    /** The picker returned where to save (null = canceled). */
    data class SaveTo(val uri: Uri?) : BackupEvent
    data class ExportCsv(val kind: ExportKind, val uri: Uri) : BackupEvent
    data class Open(val uri: Uri) : BackupEvent
    data class Unlock(val password: String) : BackupEvent
    data object OpenSafetyBackup : BackupEvent
    data object Merge : BackupEvent
    data object AskReplace : BackupEvent
    data object Replace : BackupEvent
    data object DismissDialog : BackupEvent
    data object NoticeShown : BackupEvent
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupRepository,
    private val documents: DocumentIo,
    private val archive: PhotoArchive,
    private val file: BackupFile,
    private val folder: FolderBackupRepository,
    private val secrets: BackupSecrets,
    private val shares: BackupShares,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** The parsed file waiting on Merge/Replace; kept out of UiState because it can be large. */
    private var pending: ForkcastExport? = null
    /** The pending file was a zip whose photos are staged. */
    private var hasStagedPhotos = false
    /** The protected file waiting for its password. */
    private var lockedUri: Uri? = null
    /** The keys inside the file waiting on Merge/Replace. */
    private var pendingSecrets: Map<String, String> = emptyMap()
    /** The password the pending file opened with; offered as this phone's if it has none. Never in UiState. */
    private var openedWith: String? = null

    init {
        refreshSafetyBackup()
    }

    /** `2026-10-29_0300_forkcast-backup[_unencrypted].forkcast`; CSVs `forkcast-YYYYMMDD-HHMM-….csv`. */
    fun suggestedName(kind: ExportKind, mode: SaveMode = SaveMode.ENCRYPTED): String {
        if (kind == ExportKind.BACKUP) return file.fileName(encrypted = mode == SaveMode.ENCRYPTED)
        val stamp = NAME_STAMP.format(clock.instant().atZone(clock.zone))
        val suffix = if (kind == ExportKind.FOOD_LOG_CSV) "-food-log" else "-weights"
        return "forkcast-$stamp$suffix.${kind.extension}"
    }

    fun onEvent(event: BackupEvent) {
        when (event) {
            BackupEvent.BackUpNow -> viewModelScope.launch {
                if (folder.status.first().chosen) {
                    _state.update { it.copy(busy = true) }
                    val problem = folder.backupNow()
                    _state.update { it.copy(busy = false, notice = BackupNotice.Folder(problem)) }
                } else {
                    _state.update { it.copy(pickBackupFile = SaveMode.ENCRYPTED) }
                }
            }
            BackupEvent.BackUpAndShare -> share()
            BackupEvent.Shared -> _state.update { it.copy(share = null) }
            BackupEvent.AskUnencrypted -> _state.update { it.copy(dialog = BackupDialog.UnencryptedWarning) }
            BackupEvent.UnencryptedConfirmed -> _state.update { it.copy(dialog = null, pickBackupFile = SaveMode.UNENCRYPTED) }
            BackupEvent.KeepPassword -> {
                val pw = openedWith
                openedWith = null
                _state.update { it.copy(dialog = null) }
                if (pw != null) viewModelScope.launch { folder.setPassword(pw) }
            }
            is BackupEvent.SaveTo -> {
                val mode = _state.value.pickBackupFile
                _state.update { it.copy(pickBackupFile = null) }
                if (event.uri != null && mode != null) save(event.uri, mode)
            }
            is BackupEvent.ExportCsv -> exportCsv(event.kind, event.uri)
            is BackupEvent.Open -> work {
                lockedUri = null
                read(event.uri, password = null)
            }
            is BackupEvent.Unlock -> {
                val uri = lockedUri ?: return
                work { read(uri, event.password) }
            }
            BackupEvent.OpenSafetyBackup -> work {
                hasStagedPhotos = false
                backups.readSafetyBackup()?.let { show(it, isSafetyBackup = true, photos = 0) }
            }
            BackupEvent.Merge -> runImport(ImportMode.MERGE)
            BackupEvent.AskReplace -> _state.update { it.copy(dialog = BackupDialog.ConfirmReplace) }
            BackupEvent.Replace -> runImport(ImportMode.REPLACE)
            BackupEvent.DismissDialog -> if (_state.value.offerKeepPassword && _state.value.dialog is BackupDialog.Done) {
                _state.update { it.copy(dialog = BackupDialog.KeepPassword, offerKeepPassword = false) }
            } else {
                if (hasStagedPhotos) viewModelScope.launch { archive.discardStaged() }
                hasStagedPhotos = false
                pending = null
                pendingSecrets = emptyMap()
                openedWith = null
                lockedUri = null
                _state.update { it.copy(dialog = null) }
            }
            BackupEvent.NoticeShown -> _state.update { it.copy(notice = null) }
        }
    }

    private suspend fun read(uri: Uri, password: String?) {
        val opened = documents.readStream(uri) { file.open(it, password?.toCharArray()) }
        when (opened) {
            OpenedBackupFile.NeedsPassword, OpenedBackupFile.WrongPassword -> {
                lockedUri = uri
                _state.update { it.copy(dialog = BackupDialog.Password(wrong = opened == OpenedBackupFile.WrongPassword)) }
            }
            is OpenedBackupFile.Ok -> {
                lockedUri = null
                hasStagedPhotos = opened.photos > 0
                pendingSecrets = opened.secrets
                openedWith = password?.takeIf { opened.encrypted }
                show(opened.json, isSafetyBackup = false, photos = opened.photos)
            }
        }
    }

    /** Encrypted with the backup password, or (after the warning and the unlock) readable. */
    private fun save(uri: Uri, mode: SaveMode) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        var size: Long? = null
        val notice = try {
            val password = if (mode == SaveMode.ENCRYPTED) folder.password() ?: throw IOException("no backup password") else null
            documents.writeStream(uri) { out -> size = file.write(out, password?.toCharArray()) }
            folder.recordSaved(documents.displayName(uri) ?: file.fileName(mode == SaveMode.ENCRYPTED), size)
            BackupNotice.Exported(ExportKind.BACKUP)
        } catch (e: IOException) {
            BackupNotice.ExportFailed
        } catch (e: SecurityException) {
            BackupNotice.ExportFailed
        }
        _state.update { it.copy(busy = false, notice = notice) }
    }

    /** An encrypted backup in the app's share folder, handed to the share sheet. */
    private fun share() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val uri = try {
            val password = folder.password() ?: throw IOException("no backup password")
            shares.write(file.fileName()) { out -> file.write(out, password.toCharArray()) }
        } catch (e: IOException) {
            null
        }
        if (uri != null) folder.recordSaved(file.fileName(), null)
        _state.update { it.copy(busy = false, share = uri, notice = if (uri == null) BackupNotice.ExportFailed else it.notice) }
    }

    private fun exportCsv(kind: ExportKind, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val notice = try {
            documents.writeText(uri, if (kind == ExportKind.FOOD_LOG_CSV) backups.foodLogCsv() else backups.weightsCsv())
            BackupNotice.Exported(kind)
        } catch (e: IOException) {
            BackupNotice.ExportFailed
        } catch (e: SecurityException) {
            BackupNotice.ExportFailed
        }
        _state.update { it.copy(busy = false, notice = notice) }
    }

    private suspend fun show(text: String, isSafetyBackup: Boolean, photos: Int) {
        val dialog = when (val parsed = backups.parse(text)) {
            is ParsedBackup.Ok -> {
                pending = parsed.export
                BackupDialog.Preview(parsed.preview, isSafetyBackup, photos)
            }
            is ParsedBackup.Rejected -> {
                if (hasStagedPhotos) archive.discardStaged()
                hasStagedPhotos = false
                BackupDialog.Rejected(parsed.reason)
            }
        }
        _state.update { it.copy(dialog = dialog) }
    }

    private fun runImport(mode: ImportMode) {
        val export = pending ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            val dialog = try {
                BackupDialog.Done(backups.import(export, mode)).also {
                    if (hasStagedPhotos) archive.commitStaged()
                    // The keys too (owner, 2026-10-29: restore everything).
                    secrets.restore(pendingSecrets, mode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // Room/IO failures roll back; tell the user nothing changed.
                BackupDialog.ImportFailed
            }
            pending = null
            pendingSecrets = emptyMap()
            hasStagedPhotos = false
            // A phone without a backup password can keep the one this backup opened with.
            val offerPassword = dialog is BackupDialog.Done && openedWith != null && folder.password() == null
            if (!offerPassword) openedWith = null
            _state.update { it.copy(busy = false, dialog = dialog, offerKeepPassword = offerPassword) }
            refreshSafetyBackup()
        }
    }

    /** Reads a file off the main thread and maps read errors to a dialog. */
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: IOException) {
            _state.update { it.copy(dialog = BackupDialog.ReadFailed) }
        } catch (e: SecurityException) {
            _state.update { it.copy(dialog = BackupDialog.ReadFailed) }
        }
        _state.update { it.copy(busy = false) }
    }

    private fun refreshSafetyBackup() = viewModelScope.launch {
        val has = runCatching { backups.readSafetyBackup() != null }.getOrDefault(false)
        _state.update { it.copy(hasSafetyBackup = has) }
    }

    private companion object {
        val NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
