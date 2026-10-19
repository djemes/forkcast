package app.forkcast.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.RejectReason
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
    JSON("json", "application/json"),
    FOOD_LOG_CSV("csv", "text/csv"),
    WEIGHTS_CSV("csv", "text/csv"),
}

sealed interface BackupDialog {
    /** What's in the picked file, before anything changes. */
    data class Preview(val preview: BackupPreview, val isSafetyBackup: Boolean) : BackupDialog
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
)

sealed interface BackupEvent {
    data class Export(val kind: ExportKind, val uri: Uri) : BackupEvent
    data class Open(val uri: Uri) : BackupEvent
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
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** The parsed file waiting on Merge/Replace; kept out of UiState because it can be large. */
    private var pending: ForkcastExport? = null

    init {
        refreshSafetyBackup()
    }

    /** forkcast-YYYYMMDD-HHMM.json (SPEC §4.11); CSVs get a suffix. */
    fun suggestedName(kind: ExportKind): String {
        val stamp = NAME_STAMP.format(clock.instant().atZone(clock.zone))
        val suffix = when (kind) {
            ExportKind.JSON -> ""
            ExportKind.FOOD_LOG_CSV -> "-food-log"
            ExportKind.WEIGHTS_CSV -> "-weights"
        }
        return "forkcast-$stamp$suffix.${kind.extension}"
    }

    fun onEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.Export -> export(event.kind, event.uri)
            is BackupEvent.Open -> work { show(documents.readText(event.uri), isSafetyBackup = false) }
            BackupEvent.OpenSafetyBackup -> work { backups.readSafetyBackup()?.let { show(it, isSafetyBackup = true) } }
            BackupEvent.Merge -> runImport(ImportMode.MERGE)
            BackupEvent.AskReplace -> _state.update { it.copy(dialog = BackupDialog.ConfirmReplace) }
            BackupEvent.Replace -> runImport(ImportMode.REPLACE)
            BackupEvent.DismissDialog -> {
                pending = null
                _state.update { it.copy(dialog = null) }
            }
            BackupEvent.NoticeShown -> _state.update { it.copy(notice = null) }
        }
    }

    private fun export(kind: ExportKind, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val notice = try {
            val text = when (kind) {
                ExportKind.JSON -> backups.exportJson()
                ExportKind.FOOD_LOG_CSV -> backups.foodLogCsv()
                ExportKind.WEIGHTS_CSV -> backups.weightsCsv()
            }
            documents.writeText(uri, text)
            BackupNotice.Exported(kind)
        } catch (e: IOException) {
            BackupNotice.ExportFailed
        } catch (e: SecurityException) {
            BackupNotice.ExportFailed
        }
        _state.update { it.copy(busy = false, notice = notice) }
    }

    private fun show(text: String, isSafetyBackup: Boolean) {
        val dialog = when (val parsed = backups.parse(text)) {
            is ParsedBackup.Ok -> {
                pending = parsed.export
                BackupDialog.Preview(parsed.preview, isSafetyBackup)
            }
            is ParsedBackup.Rejected -> BackupDialog.Rejected(parsed.reason)
        }
        _state.update { it.copy(dialog = dialog) }
    }

    private fun runImport(mode: ImportMode) {
        val export = pending ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            val dialog = try {
                BackupDialog.Done(backups.import(export, mode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // Room/IO failures roll back; tell the user nothing changed.
                BackupDialog.ImportFailed
            }
            pending = null
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
