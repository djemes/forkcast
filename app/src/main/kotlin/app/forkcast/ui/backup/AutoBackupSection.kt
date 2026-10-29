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
import app.forkcast.ui.help.HelpEntry
import app.forkcast.ui.help.HelpLink
import app.forkcast.ui.help.HelpLinks
import app.forkcast.core.data.backup.folder.CleanupPlan
import app.forkcast.core.data.backup.folder.CleanupResult
import app.forkcast.core.data.backup.folder.FolderBackupRepository
import app.forkcast.core.data.backup.folder.FolderFile
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.backup.folder.FolderStatus
import app.forkcast.core.data.backup.folder.NotRestorable
import app.forkcast.core.data.settings.LastBackup
import app.forkcast.core.model.RetentionPreset
import app.forkcast.core.data.backup.folder.ReencryptResult
import app.forkcast.ui.common.MealTimeField
import app.forkcast.ui.common.mealTimeText
import app.forkcast.ui.onboarding.NumberField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import java.time.temporal.WeekFields
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
    /** After a password change, until the message is shown. */
    val reencrypted: ReencryptResult? = null,
    /** The backup password, shown after the phone's unlock until the dialog closes. */
    val shownPassword: String? = null,
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

    fun setAuto(on: Boolean) = update { it.copy(auto = on, days = it.scheduleDays) }

    /** A day circle: deselecting the last day turns automatic backup off and keeps that day for next time. */
    fun toggleDay(day: Int) = update { s ->
        val days = s.scheduleDays
        when {
            day !in days -> s.copy(days = days + day)
            days.size == 1 -> s.copy(auto = false, days = days)
            else -> s.copy(days = days - day)
        }
    }

    fun setMinute(minute: Int) = update { it.copy(minute = minute, days = it.scheduleDays) }
    fun setRetention(preset: RetentionPreset) = update { it.copy(retention = preset) }
    fun setCustom(recent: Int?, months: Int?, years: Int?) = update {
        it.copy(customRecent = recent ?: it.customRecent, customMonths = months ?: it.customMonths, customYears = years ?: it.customYears)
    }

    /** The first backup password; [thenAuto] when it was asked for by the automatic-backup switch. */
    fun setPassword(password: String, thenAuto: Boolean = false) = viewModelScope.launch {
        backups.setPassword(password)
        if (thenAuto) backups.updateSettings { it.copy(auto = true, days = it.scheduleDays) }
    }

    /** After the unlock. */
    fun showPassword() = viewModelScope.launch { local.update { it.copy(shownPassword = backups.password()) } }
    fun hidePassword() = local.update { it.copy(shownPassword = null) }

    /** Re-encrypts the folder's backups, then reports what happened. */
    fun changePassword(password: String) = run(FolderWork.BACKING_UP) {
        val result = backups.changePassword(password)
        local.update { it.copy(reencrypted = result) }
        null
    }

    fun removePassword() = viewModelScope.launch { backups.removePassword() }

    private fun update(transform: (BackupSettings) -> BackupSettings) = viewModelScope.launch { backups.updateSettings(transform) }

    fun messageShown() = local.update { it.copy(problem = null, justBackedUp = false, cleanedUp = null, reencrypted = null) }

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
    /** The switch was turned on with no backup password: ask for one first. */
    val needPassword: () -> Unit = {},
    val toggleDay: (Int) -> Unit = {},
    val setMinute: (Int) -> Unit = {},
    val setRetention: (RetentionPreset) -> Unit = {},
    val setCustom: (recent: Int?, months: Int?, years: Int?) -> Unit = { _, _, _ -> },
)

/** Wires the section to its ViewModel, the folder picker and the unlock prompt. */
@Composable
fun AutoBackupSectionRoute(viewModel: AutoBackupViewModel, state: AutoBackupUiState, needPassword: () -> Unit) {
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
            needPassword = needPassword,
            toggleDay = viewModel::toggleDay,
            setMinute = viewModel::setMinute,
            setRetention = viewModel::setRetention,
            setCustom = viewModel::setCustom,
        ),
    )
}

/**
 * Automatic backup (owner, 2026-10-29): pick a folder once (on the phone, an SD card, or a cloud
 * app's folder), then an encrypted `.forkcast` file on the chosen days around the chosen time,
 * thinned out by the retention preset. Needs the backup password.
 */
@Composable
fun AutoBackupSection(state: AutoBackupUiState, actions: AutoBackupActions) {
    val s = state.status
    var confirmForget by rememberSaveable { mutableStateOf(false) }
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
            s.lastProblem?.takeIf { state.problem == null }?.let {
                Text(stringResource(folderProblemText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                HelpLink(HelpLinks.folder(it))
            }
            val on = s.settings.auto && s.hasPassword
            ToggleRow(
                stringResource(R.string.backup_auto_switch),
                if (s.hasPassword) null else stringResource(R.string.backup_auto_needs_password),
                on,
                { want -> if (want && !s.hasPassword) actions.needPassword() else actions.setAuto(want) },
            )
            if (on) {
                Text(stringResource(R.string.backup_auto_days), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                DayCircles(s.settings.scheduleDays, actions.toggleDay)
                MealTimeField(s.settings.minute, actions.setMinute, label = stringResource(R.string.backup_auto_time))
                Text(
                    stringResource(R.string.backup_auto_around, mealTimeText(s.settings.minute)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RetentionChoice(s.settings, actions)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            state.problem != null -> {
                Text(stringResource(folderProblemText(state.problem)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                HelpLink(HelpLinks.folder(state.problem))
            }
        }
        if ((state.cleanedUp?.failed ?: 0) > 0) HelpLink(HelpEntry.BACKUP_AUTO_FAILED)
    }

    state.cleanup?.let { CleanupDialog(it, actions) }
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

/** S M T W T F S, the week as the phone's locale starts it; each circle a 48 dp toggle with the day's full name. */
@Composable
private fun DayCircles(days: Set<Int>, onToggle: (Int) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val first = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    Row(modifier = Modifier.fillMaxWidth()) {
        for (offset in 0L until 7L) {
            val day = first.plus(offset)
            val selected = day.value in days
            val name = day.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
            val description = stringResource(if (selected) R.string.backup_auto_day_on else R.string.backup_auto_day_off, name)
            // The whole seventh of the row is the touch target (48 dp tall); the circle sits inside it.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(day.value) })
                    .semantics { contentDescription = description },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                ) {
                    Text(
                        day.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Simple · Balanced · Long-term · Custom, with what each keeps; Custom shows its three numbers. */
@Composable
private fun RetentionChoice(settings: BackupSettings, actions: AutoBackupActions) {
    Text(stringResource(R.string.backup_ret_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RetentionPreset.entries.forEach { p ->
            FilterChip(selected = settings.retention == p, onClick = { actions.setRetention(p) }, label = { Text(stringResource(retentionName(p))) })
        }
    }
    Text(stringResource(retentionBody(settings.retention)), style = MaterialTheme.typography.bodyMedium)
    if (settings.retention == RetentionPreset.CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(settings.customRecent.toString(), stringResource(R.string.backup_ret_recent), { actions.setCustom(it?.toInt(), null, null) }, Modifier.weight(1f))
            NumberField(settings.customMonths.toString(), stringResource(R.string.backup_ret_months), { actions.setCustom(null, it?.toInt(), null) }, Modifier.weight(1f))
            NumberField(settings.customYears.toString(), stringResource(R.string.backup_ret_years), { actions.setCustom(null, null, it?.toInt()) }, Modifier.weight(1f))
        }
    }
    Text(stringResource(R.string.backup_ret_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun retentionName(p: RetentionPreset): Int = when (p) {
    RetentionPreset.SIMPLE -> R.string.backup_ret_simple
    RetentionPreset.BALANCED -> R.string.backup_ret_balanced
    RetentionPreset.LONG_TERM -> R.string.backup_ret_long
    RetentionPreset.CUSTOM -> R.string.backup_ret_custom
}

private fun retentionBody(p: RetentionPreset): Int = when (p) {
    RetentionPreset.SIMPLE -> R.string.backup_ret_simple_body
    RetentionPreset.BALANCED -> R.string.backup_ret_balanced_body
    RetentionPreset.LONG_TERM -> R.string.backup_ret_long_body
    RetentionPreset.CUSTOM -> R.string.backup_ret_custom_body
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
                Column {
                    Text(
                        when (plan.reason) {
                            NotRestorable.OLD_FORMAT -> stringResource(R.string.backup_cleanup_old_format, time)
                            NotRestorable.UNREADABLE -> stringResource(R.string.backup_cleanup_unreadable, time)
                            NotRestorable.PASSWORD -> stringResource(R.string.backup_cleanup_password, time)
                        },
                    )
                    HelpLink(if (plan.reason == NotRestorable.PASSWORD) HelpEntry.BACKUP_PASSWORD else HelpEntry.BACKUP_RESTORE_FAILED, onOpen = actions.closeCleanup)
                }
            },
            confirmButton = { TextButton(onClick = actions.backupThenCleanup) { Text(stringResource(R.string.backup_now_to_folder)) } },
            dismissButton = { TextButton(onClick = actions.closeCleanup) { Text(stringResource(R.string.cancel)) } },
        )
        is CleanupPlan.Failed -> Unit
    }
}
