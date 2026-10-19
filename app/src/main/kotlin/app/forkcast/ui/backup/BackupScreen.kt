package app.forkcast.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.ui.settings.SettingsScaffold
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → Backup & data (SPEC §4.11). */
@Composable
fun BackupRoute(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
    transfer: SettingsTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transferState by transfer.state.collectAsStateWithLifecycle()
    val onEvent = viewModel::onEvent

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.ZIP.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.Export(ExportKind.ZIP, it)) }
    }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        transfer.export(uri)
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> transfer.picked(uri) }
    LaunchedEffect(transferState.step) {
        if (transferState.step == SettingsTransferStep.PickExportFile) exportSettings.launch(transfer.suggestedName())
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.JSON.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.Export(ExportKind.JSON, it)) }
    }
    val exportFoodLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.FOOD_LOG_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.Export(ExportKind.FOOD_LOG_CSV, it)) }
    }
    val exportWeights = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.WEIGHTS_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.Export(ExportKind.WEIGHTS_CSV, it)) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onEvent(BackupEvent.Open(it)) }
    }

    BackupScreen(
        state = state,
        onEvent = onEvent,
        actions = BackupActions(
            exportJson = { exportJson.launch(viewModel.suggestedName(ExportKind.JSON)) },
            exportZip = { exportZip.launch(viewModel.suggestedName(ExportKind.ZIP)) },
            exportFoodLog = { exportFoodLog.launch(viewModel.suggestedName(ExportKind.FOOD_LOG_CSV)) },
            exportWeights = { exportWeights.launch(viewModel.suggestedName(ExportKind.WEIGHTS_CSV)) },
            // Some file managers label .json as octet-stream or plain text.
            import = { open.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain")) },
        ),
        onBack = onBack,
        settingsSection = {
            SettingsTransferSection(
                busy = state.busy || transferState.busy,
                onExport = transfer::startExport,
                onImport = { importSettings.launch(arrayOf("application/octet-stream", "*/*")) },
            )
        },
        extraNotice = transferState.notice,
        onExtraNoticeShown = transfer::noticeShown,
    )
    transferState.step?.let { step ->
        SettingsTransferDialogs(step, transfer::setExportPassphrase, transfer::unlock, transfer::apply, transfer::dismiss)
    }
}

/** System file pickers, launched from the route so the screen stays previewable and testable. */
data class BackupActions(
    val exportJson: () -> Unit,
    val exportFoodLog: () -> Unit,
    val exportWeights: () -> Unit,
    val import: () -> Unit,
    /** Last, with a default, so existing positional callers keep working. */
    val exportZip: () -> Unit = {},
)

@Composable
fun BackupScreen(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    actions: BackupActions,
    onBack: () -> Unit,
    settingsSection: @Composable () -> Unit = {},
    extraNotice: Int? = null,
    onExtraNoticeShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(extraNotice) {
        val notice = extraNotice ?: return@LaunchedEffect
        onExtraNoticeShown()
        snackbar.showSnackbar(resources.getString(notice))
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        onEvent(BackupEvent.NoticeShown)
        snackbar.showSnackbar(resources.getString(notice.message()))
    }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(title = stringResource(R.string.settings_backup_data), onBack = onBack) {
            val working = stringResource(R.string.backup_working)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = working })
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                SectionTitle(R.string.backup_export_title)
                Text(stringResource(R.string.backup_export_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.exportJson, enabled = !state.busy) { Text(stringResource(R.string.backup_export_json)) }
                OutlinedButton(onClick = actions.exportZip, enabled = !state.busy) { Text(stringResource(R.string.backup_export_zip)) }

                SectionTitle(R.string.backup_csv_title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.exportFoodLog, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_food_log)) }
                    OutlinedButton(onClick = actions.exportWeights, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_weights)) }
                }

                SectionTitle(R.string.backup_import_title, Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.backup_import_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.import, enabled = !state.busy) { Text(stringResource(R.string.backup_import)) }

                Column(Modifier.padding(top = 16.dp)) { settingsSection() }

                if (state.hasSafetyBackup) {
                    Text(
                        stringResource(R.string.backup_restore_safety_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = { onEvent(BackupEvent.OpenSafetyBackup) }, enabled = !state.busy) {
                        Text(stringResource(R.string.backup_restore_safety))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.dialog?.let { BackupDialogs(it, onEvent) }
}

@Composable
private fun SectionTitle(title: Int, modifier: Modifier = Modifier) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

@Composable
private fun BackupDialogs(dialog: BackupDialog, onEvent: (BackupEvent) -> Unit) {
    val dismiss = { onEvent(BackupEvent.DismissDialog) }
    when (dialog) {
        is BackupDialog.Preview -> AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(stringResource(if (dialog.isSafetyBackup) R.string.backup_preview_safety_title else R.string.backup_preview_title))
            },
            text = { PreviewText(dialog.preview) },
            confirmButton = { TextButton(onClick = { onEvent(BackupEvent.Merge) }) { Text(stringResource(R.string.backup_merge)) } },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onEvent(BackupEvent.AskReplace) }) { Text(stringResource(R.string.backup_replace_ellipsis)) }
            },
        )
        BackupDialog.ConfirmReplace -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(BackupEvent.Replace) }) {
                    Text(stringResource(R.string.backup_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is BackupDialog.Done -> MessageDialog(
            title = stringResource(R.string.backup_done_title),
            body = with(dialog.summary) {
                if (mode == ImportMode.MERGE) {
                    stringResource(R.string.backup_done_merge, added, updated, unchanged)
                } else {
                    pluralStringResource(R.plurals.backup_done_replace, added, added)
                }
            },
            onDismiss = dismiss,
        )
        is BackupDialog.Rejected -> MessageDialog(
            title = stringResource(R.string.backup_rejected_title),
            body = stringResource(
                when (dialog.reason) {
                    RejectReason.NEWER_VERSION -> R.string.backup_rejected_newer
                    RejectReason.NOT_FORKCAST -> R.string.backup_rejected_other
                    RejectReason.UNREADABLE -> R.string.backup_rejected_unreadable
                },
            ),
            onDismiss = dismiss,
        )
        BackupDialog.ReadFailed -> MessageDialog(stringResource(R.string.backup_rejected_title), stringResource(R.string.backup_read_failed), dismiss)
        BackupDialog.ImportFailed -> MessageDialog(stringResource(R.string.backup_rejected_title), stringResource(R.string.backup_import_failed), dismiss)
    }
}

@Composable
private fun PreviewText(preview: BackupPreview) {
    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val dateTimeFormat = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        preview.exportedAt?.let {
            Text(stringResource(R.string.backup_preview_made, dateTimeFormat.format(it.atZone(ZoneId.systemDefault())), preview.appVersion))
        }
        with(preview.counts) {
            Text(stringResource(R.string.backup_preview_log, logEntries))
            Text(stringResource(R.string.backup_preview_foods, foods))
            Text(stringResource(R.string.backup_preview_saved_meals, savedMeals))
            Text(stringResource(R.string.backup_preview_weights, weights))
            Text(stringResource(R.string.backup_preview_water, water))
            if (aiEstimates > 0) Text(stringResource(R.string.backup_preview_ai_estimates, aiEstimates))
            if (aiProfiles > 0) Text(stringResource(R.string.backup_preview_ai_profiles, aiProfiles))
            if (!hasProfile) Text(stringResource(R.string.backup_preview_no_profile))
        }
        if (preview.firstDate != null && preview.lastDate != null) {
            Text(stringResource(R.string.backup_preview_dates, dateFormat.format(preview.firstDate), dateFormat.format(preview.lastDate)))
        }
        Text(stringResource(R.string.backup_preview_choices), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MessageDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

private fun BackupNotice.message(): Int = when (this) {
    is BackupNotice.Exported -> when (kind) {
        ExportKind.JSON -> R.string.backup_saved_json
        ExportKind.ZIP -> R.string.backup_saved_zip
        ExportKind.FOOD_LOG_CSV -> R.string.backup_saved_food_log
        ExportKind.WEIGHTS_CSV -> R.string.backup_saved_weights
    }
    BackupNotice.ExportFailed -> R.string.backup_save_failed
}
