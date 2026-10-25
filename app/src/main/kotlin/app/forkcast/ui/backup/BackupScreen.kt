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
import app.forkcast.ui.help.HelpEntry
import app.forkcast.ui.help.HelpLink
import app.forkcast.ui.help.HelpLinks
import app.forkcast.ui.help.LocalHelp
import app.forkcast.ui.help.showError
import app.forkcast.ui.common.ToggleRow
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.settings.LastBackup
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
    auto: AutoBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoState by auto.state.collectAsStateWithLifecycle()
    val transferState by transfer.state.collectAsStateWithLifecycle()
    val onEvent = viewModel::onEvent

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.BACKUP.mimeType)) { uri ->
        onEvent(BackupEvent.SaveTo(uri))
    }
    LaunchedEffect(state.pickBackupFile) {
        if (state.pickBackupFile) saveBackup.launch(viewModel.suggestedName(ExportKind.BACKUP))
    }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        transfer.export(uri)
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> transfer.picked(uri) }
    LaunchedEffect(transferState.step) {
        if (transferState.step == SettingsTransferStep.PickExportFile) exportSettings.launch(transfer.suggestedName())
    }

    val exportFoodLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.FOOD_LOG_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.ExportCsv(ExportKind.FOOD_LOG_CSV, it)) }
    }
    val exportWeights = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.WEIGHTS_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.ExportCsv(ExportKind.WEIGHTS_CSV, it)) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onEvent(BackupEvent.Open(it)) }
    }

    BackupScreen(
        state = state,
        onEvent = onEvent,
        actions = BackupActions(
            exportFoodLog = { exportFoodLog.launch(viewModel.suggestedName(ExportKind.FOOD_LOG_CSV)) },
            exportWeights = { exportWeights.launch(viewModel.suggestedName(ExportKind.WEIGHTS_CSV)) },
            // .forkcast has no registered type; 1.x backups are .json or .zip, which some file managers label oddly.
            restore = { open.launch(arrayOf("*/*")) },
        ),
        onBack = onBack,
        last = autoState.status.last,
        reminder = autoState.status.settings.reminder,
        onReminder = auto::setReminder,
        autoSection = { AutoBackupSectionRoute(auto, autoState) },
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
    val exportFoodLog: () -> Unit = {},
    val exportWeights: () -> Unit = {},
    val restore: () -> Unit = {},
)

@Composable
fun BackupScreen(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    actions: BackupActions,
    onBack: () -> Unit,
    last: LastBackup? = null,
    reminder: Boolean = true,
    onReminder: (Boolean) -> Unit = {},
    /** Automatic backup to a chosen folder. */
    autoSection: @Composable () -> Unit = {},
    settingsSection: @Composable () -> Unit = {},
    extraNotice: Int? = null,
    onExtraNoticeShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(extraNotice) {
        val notice = extraNotice ?: return@LaunchedEffect
        onExtraNoticeShown()
        // Settings transfer: only "Couldn't save the file" is a problem.
        if (notice == R.string.settings_failed) snackbar.showError(resources.getString(notice), resources.getString(R.string.help_link)) { openHelp(HelpEntry.BACKUP_SAVE_FAILED) }
        else snackbar.showSnackbar(resources.getString(notice))
    }
    val openHelp = LocalHelp.current
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        onEvent(BackupEvent.NoticeShown)
        val message = resources.getString(notice.message())
        if (notice == BackupNotice.ExportFailed) snackbar.showError(message, resources.getString(R.string.help_link)) { openHelp(HelpEntry.BACKUP_SAVE_FAILED) }
        else snackbar.showSnackbar(message)
    }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(title = stringResource(R.string.settings_backup_data), onBack = onBack) {
            val working = stringResource(R.string.backup_working)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = working })
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                SectionTitle(R.string.backup_title)
                Text(stringResource(R.string.backup_body), style = MaterialTheme.typography.bodyMedium)
                LastBackupLine(last)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEvent(BackupEvent.AskSave) }, enabled = !state.busy) { Text(stringResource(R.string.backup_save)) }
                    OutlinedButton(onClick = actions.restore, enabled = !state.busy) { Text(stringResource(R.string.backup_restore)) }
                }
                ToggleRow(stringResource(R.string.backup_reminder), stringResource(R.string.backup_reminder_detail), reminder, onReminder)

                Column(Modifier.padding(top = 16.dp)) { autoSection() }

                SectionTitle(R.string.backup_csv_title, Modifier.padding(top = 16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.exportFoodLog, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_food_log)) }
                    OutlinedButton(onClick = actions.exportWeights, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_weights)) }
                }

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
        BackupDialog.SaveOptions -> SaveOptionsDialog(onContinue = { photos, password -> onEvent(BackupEvent.SaveChosen(photos, password)) }, onDismiss = dismiss)
        is BackupDialog.Password -> UnlockBackupDialog(dialog.wrong, onUnlock = { onEvent(BackupEvent.Unlock(it)) }, onDismiss = dismiss)
        is BackupDialog.Preview -> AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(stringResource(if (dialog.isSafetyBackup) R.string.backup_preview_safety_title else R.string.backup_preview_title))
            },
            text = { PreviewText(dialog.preview, dialog.photos) },
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
                    RejectReason.OLD_VERSION -> R.string.backup_rejected_older
                    RejectReason.NOT_FORKCAST -> R.string.backup_rejected_other
                    RejectReason.UNREADABLE -> R.string.backup_rejected_unreadable
                },
            ),
            onDismiss = dismiss,
            help = HelpLinks.restore(dialog.reason),
        )
        BackupDialog.ReadFailed ->
            MessageDialog(stringResource(R.string.backup_rejected_title), stringResource(R.string.backup_read_failed), dismiss, HelpEntry.BACKUP_RESTORE_FAILED)
        BackupDialog.ImportFailed ->
            MessageDialog(stringResource(R.string.backup_rejected_title), stringResource(R.string.backup_import_failed), dismiss, HelpEntry.BACKUP_RESTORE_FAILED)
    }
}

@Composable
private fun PreviewText(preview: BackupPreview, photos: Int) {
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
            if (recipes > 0) Text(stringResource(R.string.backup_preview_recipes, recipes))
            if (!hasProfile) Text(stringResource(R.string.backup_preview_no_profile))
        }
        if (preview.aiServers.isNotEmpty()) AiServersNote(preview.aiServers)
        Text(if (photos > 0) stringResource(R.string.backup_preview_photos, photos) else stringResource(R.string.backup_preview_no_photos))
        if (preview.firstDate != null && preview.lastDate != null) {
            Text(stringResource(R.string.backup_preview_dates, dateFormat.format(preview.firstDate), dateFormat.format(preview.lastDate)))
        }
        Text(stringResource(R.string.backup_preview_choices), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Servers named by the file's AI profiles (REVIEW-2 S4): they arrive turned off. */
@Composable
internal fun AiServersNote(servers: List<String>) {
    Text(
        stringResource(R.string.import_ai_servers, servers.joinToString(", ")),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** [help]: the entry a refusal links to; its "Help" closes the dialog on the way. */
@Composable
private fun MessageDialog(title: String, body: String, onDismiss: () -> Unit, help: HelpEntry? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = if (help != null) ({ HelpLink(help, onOpen = onDismiss) }) else null,
    )
}

private fun BackupNotice.message(): Int = when (this) {
    is BackupNotice.Exported -> when (kind) {
        ExportKind.BACKUP -> R.string.backup_saved
        ExportKind.FOOD_LOG_CSV -> R.string.backup_saved_food_log
        ExportKind.WEIGHTS_CSV -> R.string.backup_saved_weights
    }
    BackupNotice.ExportFailed -> R.string.backup_save_failed
}
