package app.forkcast.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import app.forkcast.ui.common.DeviceAuth
import app.forkcast.ui.common.NoScreenLockDialog
import kotlinx.coroutines.launch
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
import app.forkcast.ui.common.AdvancedSection
import app.forkcast.ui.common.ToggleRow
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.settings.LastBackup
import app.forkcast.ui.settings.SettingsScaffold
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → Backup & data (SPEC §4.11; owner, 2026-10-29). */
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
    val context = LocalContext.current

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.BACKUP.mimeType)) { uri ->
        onEvent(BackupEvent.SaveTo(uri))
    }
    LaunchedEffect(state.pickBackupFile) {
        state.pickBackupFile?.let { saveBackup.launch(viewModel.suggestedName(ExportKind.BACKUP, it)) }
    }
    val shareTitle = stringResource(R.string.backup_share_title)
    LaunchedEffect(state.share) {
        val uri = state.share ?: return@LaunchedEffect
        onEvent(BackupEvent.Shared)
        val send = Intent(Intent.ACTION_SEND)
            .setType(ExportKind.BACKUP.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, shareTitle))
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> transfer.picked(uri) }

    val exportFoodLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.FOOD_LOG_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.ExportCsv(ExportKind.FOOD_LOG_CSV, it)) }
    }
    val exportWeights = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.WEIGHTS_CSV.mimeType)) { uri ->
        uri?.let { onEvent(BackupEvent.ExportCsv(ExportKind.WEIGHTS_CSV, it)) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onEvent(BackupEvent.Open(it)) }
    }

    // The unencrypted copy asks for the phone's unlock after its warning (owner, 2026-10-29).
    var noLock by rememberSaveable { mutableStateOf<Int?>(null) }
    val unlockTitle = stringResource(R.string.bpw_unlock_title)
    val unencryptedSubtitle = stringResource(R.string.backup_unencrypted_unlock)
    noLock?.let { NoScreenLockDialog(onDismiss = { noLock = null }, body = stringResource(it)) }
    val confirmUnencrypted = {
        if (!DeviceAuth.hasScreenLock(context)) {
            onEvent(BackupEvent.DismissDialog)
            noLock = R.string.backup_unencrypted_no_lock
        } else {
            DeviceAuth.authenticate(
                context,
                unlockTitle,
                unencryptedSubtitle,
                onSuccess = { onEvent(BackupEvent.UnencryptedConfirmed) },
                onFailure = { onEvent(BackupEvent.DismissDialog) },
            )
        }
    }

    var askPassword by rememberSaveable { mutableStateOf<PasswordAsk?>(null) }
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
        hasPassword = autoState.status.hasPassword,
        last = autoState.status.last,
        exampleName = viewModel.suggestedName(ExportKind.BACKUP),
        reminder = autoState.status.settings.reminder,
        onReminder = auto::setReminder,
        passwordSection = { snackbar ->
            BackupPasswordSectionRoute(auto, autoState, ask = askPassword, onAsk = { askPassword = it }, snackbar = snackbar)
        },
        autoSection = { AutoBackupSectionRoute(auto, autoState, needPassword = { askPassword = PasswordAsk.NEW_THEN_AUTO }) },
        reportSection = { snackbar -> app.forkcast.ui.report.MealReportSectionRoute(snackbar) },
        settingsSection = {
            SettingsTransferSection(
                busy = state.busy || transferState.busy,
                onImport = { importSettings.launch(arrayOf("application/octet-stream", "*/*")) },
            )
        },
        extraNotice = transferState.notice,
        onExtraNoticeShown = transfer::noticeShown,
        onUnencryptedContinue = confirmUnencrypted,
    )
    transferState.step?.let { step ->
        SettingsTransferDialogs(step, transfer::unlock, transfer::apply, transfer::dismiss)
    }
}

/** Which password dialog is open: a first one (and then turn automatic backup on), or a change. */
enum class PasswordAsk { NEW, NEW_THEN_AUTO, CHANGE }

/** System file pickers, launched from the route so the screen stays previewable and testable. */
data class BackupActions(
    val exportFoodLog: () -> Unit = {},
    val exportWeights: () -> Unit = {},
    val restore: () -> Unit = {},
)

/**
 * Backup password first (every backup needs it), then Back up now / Back up and share (greyed out
 * without a password: a tap says why), automatic backup, the red unencrypted copy, restore.
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    actions: BackupActions,
    onBack: () -> Unit,
    hasPassword: Boolean = false,
    last: LastBackup? = null,
    /** A file name as backups get it, for the naming note. */
    exampleName: String = "",
    reminder: Boolean = true,
    onReminder: (Boolean) -> Unit = {},
    passwordSection: @Composable (SnackbarHostState) -> Unit = {},
    /** Automatic backup to a chosen folder. */
    autoSection: @Composable () -> Unit = {},
    /** The meal report for a health professional. */
    reportSection: @Composable (SnackbarHostState) -> Unit = {},
    settingsSection: @Composable () -> Unit = {},
    extraNotice: Int? = null,
    onExtraNoticeShown: () -> Unit = {},
    onUnencryptedContinue: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val openHelp = LocalHelp.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(extraNotice) {
        val notice = extraNotice ?: return@LaunchedEffect
        onExtraNoticeShown()
        // Settings transfer: only "Couldn't save the file" is a problem.
        if (notice == R.string.settings_failed) snackbar.showError(resources.getString(notice), resources.getString(R.string.help_link)) { openHelp(HelpEntry.BACKUP_SAVE_FAILED) }
        else snackbar.showSnackbar(resources.getString(notice))
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        onEvent(BackupEvent.NoticeShown)
        val message = resources.getString(notice.message())
        if (notice == BackupNotice.ExportFailed) snackbar.showError(message, resources.getString(R.string.help_link)) { openHelp(HelpEntry.BACKUP_SAVE_FAILED) }
        else snackbar.showSnackbar(message)
    }
    val needsPassword = stringResource(R.string.backup_needs_password)
    // Greyed out but still tappable without a password, so the tap can say why (owner, 2026-10-29).
    val encrypted: (BackupEvent) -> Unit = { event ->
        if (hasPassword) onEvent(event) else scope.launch { snackbar.showSnackbar(needsPassword) }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(title = stringResource(R.string.settings_backup_data), onBack = onBack) {
            val working = stringResource(R.string.backup_working)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = working })
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                passwordSection(snackbar)

                SectionTitle(R.string.backup_title, Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.backup_contents), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeedsPasswordButton(R.string.backup_now, hasPassword, enabled = !state.busy, filled = true) { encrypted(BackupEvent.BackUpNow) }
                    NeedsPasswordButton(R.string.backup_share, hasPassword, enabled = !state.busy, filled = false) { encrypted(BackupEvent.BackUpAndShare) }
                }
                LastBackupLine(last)
                Text(
                    stringResource(R.string.backup_naming, exampleName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(Modifier.padding(top = 16.dp)) { autoSection() }

                SectionTitle(R.string.backup_restore_title, Modifier.padding(top = 16.dp))
                OutlinedButton(onClick = actions.restore, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_restore)) }
                if (state.hasSafetyBackup) {
                    Text(stringResource(R.string.backup_restore_safety_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onEvent(BackupEvent.OpenSafetyBackup) }, enabled = !state.busy) {
                        Text(stringResource(R.string.backup_restore_safety))
                    }
                }
                ToggleRow(stringResource(R.string.backup_reminder), stringResource(R.string.backup_reminder_detail), reminder, onReminder)

                Column(Modifier.padding(top = 16.dp)) { reportSection(snackbar) }

                // A copy anyone could read: set apart, in red, behind a warning and the phone's unlock.
                OutlinedButton(
                    onClick = { onEvent(BackupEvent.AskUnencrypted) },
                    enabled = !state.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.backup_unencrypted)) }

                // Spreadsheets and settings files from older versions are for fewer people (roadmap 2.0 task 3.5).
                AdvancedSection(Modifier.padding(top = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(R.string.backup_csv_title, Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = actions.exportFoodLog, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_food_log)) }
                            OutlinedButton(onClick = actions.exportWeights, enabled = !state.busy) { Text(stringResource(R.string.backup_csv_weights)) }
                        }
                        Column(Modifier.padding(top = 16.dp)) { settingsSection() }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.dialog?.let { BackupDialogs(it, onEvent, onUnencryptedContinue = onUnencryptedContinue) }
}

/** Looks disabled without a password but stays tappable; TalkBack hears why. */
@Composable
private fun NeedsPasswordButton(label: Int, hasPassword: Boolean, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val why = stringResource(R.string.backup_auto_needs_password)
    val modifier = Modifier
        .heightIn(min = 48.dp)
        .then(if (hasPassword) Modifier else Modifier.semantics { stateDescription = why })
    val content: @Composable RowScope.() -> Unit = { Text(stringResource(label)) }
    if (filled) {
        val colors = if (hasPassword) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Button(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier, content = content)
    } else {
        val colors = if (hasPassword) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        OutlinedButton(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier, content = content)
    }
}

@Composable
private fun SectionTitle(title: Int, modifier: Modifier = Modifier) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

/**
 * [newPhone]: opened from onboarding's "Restore a backup" (owner, 2026-10-25). Nothing is on the
 * phone yet, so the preview offers one "Restore" (a replace, which also brings the backup's
 * settings and units) instead of Merge or Replace.
 */
@Composable
internal fun BackupDialogs(dialog: BackupDialog, onEvent: (BackupEvent) -> Unit, newPhone: Boolean = false, onUnencryptedContinue: () -> Unit = {}) {
    val dismiss = { onEvent(BackupEvent.DismissDialog) }
    when (dialog) {
        BackupDialog.UnencryptedWarning -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.backup_unencrypted_title)) },
            text = { Text(stringResource(R.string.backup_unencrypted_body)) },
            confirmButton = {
                TextButton(onClick = onUnencryptedContinue) { Text(stringResource(R.string.backup_unencrypted_continue), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
        BackupDialog.KeepPassword -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.bpw_keep_title)) },
            text = { Text(stringResource(R.string.bpw_keep_body)) },
            confirmButton = { TextButton(onClick = { onEvent(BackupEvent.KeepPassword) }) { Text(stringResource(R.string.bpw_keep_yes)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.backup_notice_not_now)) } },
        )
        is BackupDialog.Password -> UnlockBackupDialog(dialog.wrong, onUnlock = { onEvent(BackupEvent.Unlock(it)) }, onDismiss = dismiss)
        is BackupDialog.Preview -> AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(stringResource(if (dialog.isSafetyBackup) R.string.backup_preview_safety_title else R.string.backup_preview_title))
            },
            text = { PreviewText(dialog.preview, dialog.photos, newPhone) },
            confirmButton = {
                if (newPhone) {
                    TextButton(onClick = { onEvent(BackupEvent.Replace) }) { Text(stringResource(R.string.onb_restore_confirm)) }
                } else {
                    TextButton(onClick = { onEvent(BackupEvent.Merge) }) { Text(stringResource(R.string.backup_merge)) }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                if (!newPhone) TextButton(onClick = { onEvent(BackupEvent.AskReplace) }) { Text(stringResource(R.string.backup_replace_ellipsis)) }
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
private fun PreviewText(preview: BackupPreview, photos: Int, newPhone: Boolean = false) {
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
        Text(
            stringResource(if (newPhone) R.string.onb_restore_choices else R.string.backup_preview_choices),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
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
    is BackupNotice.Folder -> problem?.let(::folderProblemText) ?: R.string.backup_backed_up
}
