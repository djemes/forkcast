package app.forkcast.ui.backup

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.backup.folder.CleanupPlan
import app.forkcast.core.data.backup.folder.CleanupResult
import app.forkcast.core.data.backup.folder.FolderBackupRepository
import app.forkcast.core.data.backup.folder.FolderFile
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.backup.folder.FolderStatus
import app.forkcast.core.data.backup.folder.NotRestorable
import app.forkcast.core.data.settings.LastBackup
import app.forkcast.core.model.BackupFrequency
import app.forkcast.core.model.BackupSettings
import app.forkcast.ui.common.DeviceAuth
import app.forkcast.ui.common.ToggleRow
import app.forkcast.ui.common.NoScreenLockDialog
import app.forkcast.ui.theme.ForkcastTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import javax.inject.Inject

/** What the automatic-backup section is doing right now (one thing at a time). */
enum class FolderWork { CHOOSING, BACKING_UP, CHECKING, DELETING }

data class AutoBackupUiState(
    val status: FolderStatus,
    val working: FolderWork? = null,
    /** The latest problem from a tap; the saved one is in [status]. */
    val problem: FolderProblem? = null,
    /** "Back up now" just worked. */
    val justBackedUp: Boolean = false,
    /** "Delete old backups": the checked plan, while its dialog is open. */
    val cleanup: CleanupPlan? = null,
    /** How the last cleanup went, until the message is shown. */
    val cleanedUp: CleanupResult? = null,
)

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val backups: FolderBackupRepository,
) : ViewModel() {

    private val local = MutableStateFlow(AutoBackupUiState(FolderStatus(null, null, BackupSettings(), null, null, hasPassword = false)))

    val state: StateFlow<AutoBackupUiState> = combine(local, backups.status) { l, s -> l.copy(status = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** The folder picker returned (null = canceled). A new folder gets its first backup right away. */
    fun folderChosen(uri: Uri?) {
        if (uri == null) return
        run(FolderWork.CHOOSING) {
            backups.chooseFolder(uri.toString()) ?: backups.backupNow().also { ok(it) }
        }
    }

    fun backupNow() = run(FolderWork.BACKING_UP) { backups.backupNow().also { ok(it) } }

    private fun ok(problem: FolderProblem?) {
        if (problem == null) local.update { it.copy(justBackedUp = true) }
    }

    fun forgetFolder() = viewModelScope.launch { backups.forgetFolder() }

    /** After the unlock: checks the newest backup and shows what would be deleted (nothing is yet). */
    fun checkCleanup() = run(FolderWork.CHECKING) { plan() }

    /** The newest backup couldn't be restored: back up now, then check again. */
    fun backupThenCheckCleanup() = run(FolderWork.BACKING_UP) {
        local.update { it.copy(cleanup = null) }
        backups.backupNow() ?: plan()
    }

    fun confirmCleanup() {
        val plan = local.value.cleanup as? CleanupPlan.Ready ?: return
        run(FolderWork.DELETING) {
            local.update { it.copy(cleanup = null) }
            val result = backups.deleteOld(plan)
            local.update { it.copy(cleanedUp = result) }
            null
        }
    }

    fun closeCleanup() = local.update { it.copy(cleanup = null) }

    private suspend fun plan(): FolderProblem? = when (val p = backups.cleanupPlan()) {
        is CleanupPlan.Failed -> p.problem
        else -> { local.update { it.copy(cleanup = p) }; null }
    }

    fun setAuto(on: Boolean) = update { it.copy(auto = on) }
    fun setFrequency(f: BackupFrequency) = update { it.copy(frequency = f) }
    fun setKeep(n: Int) = update { it.copy(keep = n) }
    fun setPhotos(on: Boolean) = update { it.copy(photos = on) }
    fun setReminder(on: Boolean) = update { it.copy(reminder = on) }

    /** Turns the password on with [password], or off (null), which also removes the saved one. */
    fun setPassword(password: String?) = viewModelScope.launch {
        backups.setPassword(password)
        backups.updateSettings { it.copy(password = password != null) }
    }

    private fun update(transform: (BackupSettings) -> BackupSettings) = viewModelScope.launch { backups.updateSettings(transform) }

    fun messageShown() = local.update { it.copy(problem = null, justBackedUp = false, cleanedUp = null) }

    private fun run(work: FolderWork, block: suspend () -> FolderProblem?) {
        if (local.value.working != null) return
        viewModelScope.launch {
            local.update { it.copy(working = work, problem = null, justBackedUp = false, cleanedUp = null) }
            val problem = block()
            local.update { it.copy(working = null, problem = problem) }
        }
    }
}

/** Callbacks the section needs; the route wires them to [AutoBackupViewModel]. */
data class AutoBackupActions(
    val chooseFolder: () -> Unit = {},
    val forgetFolder: () -> Unit = {},
    val backupNow: () -> Unit = {},
    /** "Delete old backups": the unlock prompt first, then the check. */
    val cleanup: () -> Unit = {},
    val confirmCleanup: () -> Unit = {},
    val backupThenCleanup: () -> Unit = {},
    val closeCleanup: () -> Unit = {},
    val setAuto: (Boolean) -> Unit = {},
    val setFrequency: (BackupFrequency) -> Unit = {},
    val setKeep: (Int) -> Unit = {},
    val setPhotos: (Boolean) -> Unit = {},
    val setPassword: (String?) -> Unit = {},
)

/** Wires the section to its ViewModel, the folder picker and the unlock prompt. */
@Composable
fun AutoBackupSectionRoute(viewModel: AutoBackupViewModel, state: AutoBackupUiState) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { viewModel.folderChosen(it) }
    // Deleting backups asks the same unlock prompt as showing a saved key (owner, 2026-10-24).
    val context = LocalContext.current
    var noLock by rememberSaveable { mutableStateOf(false) }
    val unlockTitle = stringResource(R.string.backup_cleanup_unlock_title)
    val unlockSubtitle = stringResource(R.string.backup_cleanup_unlock_subtitle)
    if (noLock) NoScreenLockDialog(onDismiss = { noLock = false }, body = stringResource(R.string.backup_cleanup_no_lock_body))
    AutoBackupSection(
        state,
        AutoBackupActions(
            chooseFolder = { pickFolder.launch(null) },
            forgetFolder = viewModel::forgetFolder,
            backupNow = viewModel::backupNow,
            cleanup = {
                if (!DeviceAuth.hasScreenLock(context)) noLock = true
                else DeviceAuth.authenticate(context, unlockTitle, unlockSubtitle, onSuccess = { viewModel.checkCleanup() })
            },
            confirmCleanup = viewModel::confirmCleanup,
            backupThenCleanup = viewModel::backupThenCheckCleanup,
            closeCleanup = viewModel::closeCleanup,
            setAuto = viewModel::setAuto,
            setFrequency = viewModel::setFrequency,
            setKeep = viewModel::setKeep,
            setPhotos = viewModel::setPhotos,
            setPassword = viewModel::setPassword,
        ),
    )
}

/**
 * Automatic backup (roadmap 2.0, task 1.4): pick a folder once (on the phone, an SD card, or a
 * cloud app's folder), then a `.forkcast` file every week or day, keeping the newest few.
 */
@Composable
fun AutoBackupSection(state: AutoBackupUiState, actions: AutoBackupActions) {
    val s = state.status
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var askPassword by rememberSaveable { mutableStateOf(false) }
    val idle = state.working == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_auto_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (!idle) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!s.chosen) {
            Text(stringResource(R.string.backup_auto_intro), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.chooseFolder, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_auto_choose))
            }
        } else {
            Text(
                s.folderName?.let { stringResource(R.string.backup_auto_folder, it) } ?: stringResource(R.string.backup_auto_folder_unnamed),
                style = MaterialTheme.typography.bodyMedium,
            )
            s.lastProblem?.let { Text(stringResource(folderProblemText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            ToggleRow(stringResource(R.string.backup_auto_switch), null, s.settings.auto, actions.setAuto)
            if (s.settings.auto) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.settings.frequency == BackupFrequency.WEEKLY,
                        onClick = { actions.setFrequency(BackupFrequency.WEEKLY) },
                        label = { Text(stringResource(R.string.backup_weekly)) },
                    )
                    FilterChip(
                        selected = s.settings.frequency == BackupFrequency.DAILY,
                        onClick = { actions.setFrequency(BackupFrequency.DAILY) },
                        label = { Text(stringResource(R.string.backup_daily)) },
                    )
                }
                val whenText = if (s.settings.frequency == BackupFrequency.WEEKLY) {
                    val day = DayOfWeek.of(s.settings.weekday.coerceIn(1, 7)).getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)
                    stringResource(R.string.backup_weekly_on, day.replaceFirstChar { it.titlecase() })
                } else {
                    stringResource(R.string.backup_daily_at)
                }
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.backup_keep_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackupSettings.KEEP_CHOICES.forEach { n ->
                        FilterChip(
                            selected = s.settings.keep == n,
                            onClick = { actions.setKeep(n) },
                            label = { Text(pluralStringResource(R.plurals.backup_keep_choice, n, n)) },
                        )
                    }
                }
                ToggleRow(stringResource(R.string.backup_auto_photos), stringResource(R.string.backup_photos_detail), s.settings.photos, actions.setPhotos)
                ToggleRow(
                    stringResource(R.string.backup_auto_password),
                    stringResource(R.string.backup_auto_password_detail),
                    s.settings.password && s.hasPassword,
                    { on -> if (on) askPassword = true else actions.setPassword(null) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.backupNow, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_now_to_folder))
                }
                OutlinedButton(onClick = actions.cleanup, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_cleanup))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.chooseFolder, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_auto_change))
                }
                TextButton(onClick = { confirmForget = true }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_auto_forget))
                }
            }
        }
        when {
            state.justBackedUp -> Text(stringResource(R.string.backup_backed_up), style = MaterialTheme.typography.bodyMedium, color = ForkcastTheme.colors.pine)
            state.cleanedUp != null -> Text(cleanupResultText(state.cleanedUp), style = MaterialTheme.typography.bodyMedium, color = ForkcastTheme.colors.pine)
            state.problem != null -> Text(stringResource(folderProblemText(state.problem)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    state.cleanup?.let { CleanupDialog(it, actions) }
    if (askPassword) {
        NewPasswordDialog(
            title = stringResource(R.string.backup_auto_password),
            body = stringResource(R.string.backup_auto_password_body),
            onDone = { askPassword = false; actions.setPassword(it) },
            onDismiss = { askPassword = false },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.backup_auto_forget_title)) },
            text = { Text(stringResource(R.string.backup_auto_forget_body)) },
            confirmButton = { TextButton(onClick = { confirmForget = false; actions.forgetFolder() }) { Text(stringResource(R.string.backup_auto_forget)) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "Last backup: Oct 24, 3:00 AM · Backups · 1.2 MB", or that there's none yet. */
@Composable
fun LastBackupLine(last: LastBackup?) {
    val context = LocalContext.current
    val text = if (last == null) {
        stringResource(R.string.backup_none_yet)
    } else {
        val time = DateUtils.formatDateTime(context, last.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        val details = listOfNotNull(last.place ?: last.name.takeIf { it.isNotBlank() }, last.sizeBytes?.let { Formatter.formatShortFileSize(context, it) })
        if (details.isEmpty()) stringResource(R.string.backup_last, time)
        else stringResource(R.string.backup_last_details, time, details.joinToString(stringResource(R.string.backup_detail_separator)))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Message for each folder problem (section line and notification). */
fun folderProblemText(p: FolderProblem): Int = when (p) {
    FolderProblem.NO_FOLDER -> R.string.backup_problem_no_folder
    FolderProblem.FOLDER_LOST -> R.string.backup_problem_folder_lost
    FolderProblem.PASSWORD_MISSING -> R.string.backup_problem_password_missing
    FolderProblem.WRITE_FAILED -> R.string.backup_problem_write_failed
}

/** "Oct 24, 3:00 AM" from the file's time, else its name. */
@Composable
private fun fileTime(f: FolderFile): String {
    val context = LocalContext.current
    return f.modifiedAt?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    } ?: f.name
}

@Composable
private fun cleanupResultText(r: CleanupResult): String {
    val kept = fileTime(r.kept)
    return if (r.failed == 0) pluralStringResource(R.plurals.backup_cleanup_done, r.deleted, r.deleted, kept)
    else pluralStringResource(R.plurals.backup_cleanup_partial, r.deleted, r.deleted, r.failed, kept)
}

/**
 * "Delete old backups" after the check: how many go (with their dates) and which one stays; or
 * why nothing can go yet, with Back up now when the newest backup can't be restored.
 */
@Composable
private fun CleanupDialog(plan: CleanupPlan, actions: AutoBackupActions) {
    when (plan) {
        is CleanupPlan.Ready -> {
            val oldest = plan.delete.minWithOrNull(compareBy<FolderFile> { it.modifiedAt ?: 0L }) ?: plan.delete.last()
            val newest = plan.delete.maxWithOrNull(compareBy<FolderFile> { it.modifiedAt ?: 0L }) ?: plan.delete.first()
            AlertDialog(
                onDismissRequest = actions.closeCleanup,
                title = { Text(pluralStringResource(R.plurals.backup_cleanup_title, plan.delete.size, plan.delete.size)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (plan.delete.size == 1) stringResource(R.string.backup_cleanup_single, fileTime(oldest))
                            else stringResource(R.string.backup_cleanup_range, fileTime(oldest), fileTime(newest)),
                        )
                        Text(stringResource(R.string.backup_cleanup_keep, fileTime(plan.keep)), color = ForkcastTheme.colors.pine)
                    }
                },
                confirmButton = {
                    TextButton(onClick = actions.confirmCleanup) {
                        Text(stringResource(R.string.backup_cleanup_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = actions.closeCleanup) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is CleanupPlan.NothingToDelete -> AlertDialog(
            onDismissRequest = actions.closeCleanup,
            title = { Text(stringResource(R.string.backup_cleanup_nothing_title)) },
            text = {
                Text(plan.keep?.let { stringResource(R.string.backup_cleanup_nothing_one, fileTime(it)) } ?: stringResource(R.string.backup_cleanup_nothing_none))
            },
            confirmButton = { TextButton(onClick = actions.closeCleanup) { Text(stringResource(R.string.ok)) } },
        )
        is CleanupPlan.LatestNotRestorable -> AlertDialog(
            onDismissRequest = actions.closeCleanup,
            title = { Text(stringResource(R.string.backup_cleanup_first_title)) },
            text = {
                val time = fileTime(plan.latest)
                Text(
                    when (plan.reason) {
                        NotRestorable.OLD_FORMAT -> stringResource(R.string.backup_cleanup_old_format, time)
                        NotRestorable.UNREADABLE -> stringResource(R.string.backup_cleanup_unreadable, time)
                        NotRestorable.PASSWORD -> stringResource(R.string.backup_cleanup_password, time)
                    },
                )
            },
            confirmButton = { TextButton(onClick = actions.backupThenCleanup) { Text(stringResource(R.string.backup_now_to_folder)) } },
            dismissButton = { TextButton(onClick = actions.closeCleanup) { Text(stringResource(R.string.cancel)) } },
        )
        is CleanupPlan.Failed -> Unit
    }
}
