package app.forkcast.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.BackupRepository
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
    /** "Save a backup file": photos and password, before the file picker opens. */
    data object SaveOptions : BackupDialog
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
}

data class BackupUiState(
    val busy: Boolean = false,
    val hasSafetyBackup: Boolean = false,
    val dialog: BackupDialog? = null,
    val notice: BackupNotice? = null,
    /** Options chosen: the screen opens the file picker for the backup now. */
    val pickBackupFile: Boolean = false,
)

sealed interface BackupEvent {
    data object AskSave : BackupEvent
    /** From the save options; the file picker opens next. */
    data class SaveChosen(val photos: Boolean, val password: String?) : BackupEvent
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
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** The parsed file waiting on Merge/Replace; kept out of UiState because it can be large. */
    private var pending: ForkcastExport? = null
    /** The pending file was a zip whose photos are staged. */
    private var hasStagedPhotos = false
    /** Save options while the file picker is open; the password never goes into UiState. */
    private var saveOptions: Pair<Boolean, String?>? = null
    /** The protected file waiting for its password. */
    private var lockedUri: Uri? = null

    init {
        refreshSafetyBackup()
    }

    /** `forkcast-backup-YYYY-MM-DD.forkcast`; CSVs `forkcast-YYYYMMDD-HHMM-….csv`. */
    fun suggestedName(kind: ExportKind): String {
        if (kind == ExportKind.BACKUP) return file.fileName()
        val stamp = NAME_STAMP.format(clock.instant().atZone(clock.zone))
        val suffix = if (kind == ExportKind.FOOD_LOG_CSV) "-food-log" else "-weights"
        return "forkcast-$stamp$suffix.${kind.extension}"
    }

    fun onEvent(event: BackupEvent) {
        when (event) {
            BackupEvent.AskSave -> _state.update { it.copy(dialog = BackupDialog.SaveOptions) }
            is BackupEvent.SaveChosen -> {
                saveOptions = event.photos to event.password?.takeIf { it.isNotEmpty() }
                _state.update { it.copy(dialog = null, pickBackupFile = true) }
            }
            is BackupEvent.SaveTo -> {
                _state.update { it.copy(pickBackupFile = false) }
                val options = saveOptions
                saveOptions = null
                if (event.uri != null && options != null) save(event.uri, options.first, options.second)
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
            BackupEvent.DismissDialog -> {
                if (hasStagedPhotos) viewModelScope.launch { archive.discardStaged() }
                hasStagedPhotos = false
                pending = null
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
                show(opened.json, isSafetyBackup = false, photos = opened.photos)
            }
        }
    }

    private fun save(uri: Uri, photos: Boolean, password: String?) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        var size: Long? = null
        val notice = try {
            documents.writeStream(uri) { out -> size = file.write(out, photos, password?.toCharArray()) }
            folder.recordSaved(documents.displayName(uri) ?: file.fileName(), size)
            BackupNotice.Exported(ExportKind.BACKUP)
        } catch (e: IOException) {
            BackupNotice.ExportFailed
        } catch (e: SecurityException) {
            BackupNotice.ExportFailed
        }
        _state.update { it.copy(busy = false, notice = notice) }
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
                BackupDialog.Done(backups.import(export, mode)).also { if (hasStagedPhotos) archive.commitStaged() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // Room/IO failures roll back; tell the user nothing changed.
                BackupDialog.ImportFailed
            }
            pending = null
            hasStagedPhotos = false
            _state.update { it.copy(busy = false, dialog = dialog) }
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
