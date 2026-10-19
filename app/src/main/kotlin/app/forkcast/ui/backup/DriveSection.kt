package app.forkcast.ui.backup

import android.app.PendingIntent
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import app.forkcast.core.data.drive.DefaultDriveBackupRepository
import app.forkcast.core.data.drive.DriveAccess
import app.forkcast.core.data.drive.DriveAuth
import app.forkcast.core.data.drive.DriveBackup
import app.forkcast.core.data.drive.DriveBackupRepository
import app.forkcast.core.data.drive.DriveProblem
import app.forkcast.core.data.drive.DriveProblemException
import app.forkcast.core.data.drive.DriveStatus
import app.forkcast.core.model.BackupFrequency
import app.forkcast.ui.theme.ForkcastTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Drive section is doing right now (one thing at a time). */
enum class DriveWork { CONNECTING, BACKING_UP, LISTING }

data class DriveUiState(
    val status: DriveStatus,
    val working: DriveWork? = null,
    /** The latest problem from a tap (connect, back up, list); the saved one is in [status]. */
    val problem: DriveProblem? = null,
    /** Backup just succeeded from "Back up now". */
    val justBackedUp: Boolean = false,
    /** The restore list, while its dialog is open. */
    val backups: List<DriveBackup>? = null,
)

sealed interface DriveEffect {
    /** Google wants the user to pick an account or agree: launch it. */
    class Consent(val intent: PendingIntent) : DriveEffect
}

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val drive: DriveBackupRepository,
    private val auth: DriveAuth,
) : ViewModel() {

    private val local = MutableStateFlow(DriveUiState(DriveStatus(null, app.forkcast.core.model.BackupSettings(), null, null)))
    private val effects = Channel<DriveEffect>(Channel.BUFFERED)
    val effect: Flow<DriveEffect> = effects.receiveAsFlow()

    val state: StateFlow<DriveUiState> = combine(local, drive.status) { l, s -> l.copy(status = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun connect() = run(DriveWork.CONNECTING) { handle(auth.authorize()) }

    fun onConsent(data: Intent?) = run(DriveWork.CONNECTING) { handle(auth.fromConsent(data)) }

    private suspend fun handle(access: DriveAccess): DriveProblem? = when (access) {
        is DriveAccess.Granted -> drive.connect(access.token)
        is DriveAccess.NeedsConsent -> { effects.send(DriveEffect.Consent(access.intent)); null }
        is DriveAccess.Failed -> when (access.reason) {
            DriveAccess.CANCELED -> null
            DriveAccess.NOT_SET_UP -> DriveProblem.NOT_SET_UP
            DriveAccess.OFFLINE -> DriveProblem.OFFLINE
            else -> DriveProblem.ERROR
        }
    }

    fun backupNow() = run(DriveWork.BACKING_UP) {
        drive.backupNow(interactive = true).also { if (it == null) local.update { s -> s.copy(justBackedUp = true) } }
    }

    fun openRestore() = run(DriveWork.LISTING) {
        try {
            val list = drive.backups()
            local.update { it.copy(backups = list) }
            null
        } catch (e: DriveProblemException) {
            e.problem
        }
    }

    fun closeRestore() = local.update { it.copy(backups = null) }

    fun disconnect() = viewModelScope.launch { drive.disconnect() }

    fun setAuto(on: Boolean) = viewModelScope.launch { drive.updateSettings { it.copy(auto = on) } }
    fun setFrequency(f: BackupFrequency) = viewModelScope.launch { drive.updateSettings { it.copy(frequency = f) } }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { drive.updateSettings { it.copy(wifiOnly = on) } }
    fun setChargingOnly(on: Boolean) = viewModelScope.launch { drive.updateSettings { it.copy(chargingOnly = on) } }

    fun messageShown() = local.update { it.copy(problem = null, justBackedUp = false) }

    private fun run(work: DriveWork, block: suspend () -> DriveProblem?) {
        if (local.value.working != null) return
        viewModelScope.launch {
            local.update { it.copy(working = work, problem = null, justBackedUp = false) }
            val problem = block()
            local.update { it.copy(working = null, problem = problem) }
        }
    }
}

/** Callbacks the section needs; the route wires them to [DriveViewModel] and the restore preview. */
data class DriveActions(
    val connect: () -> Unit = {},
    val backupNow: () -> Unit = {},
    val openRestore: () -> Unit = {},
    val closeRestore: () -> Unit = {},
    val restore: (DriveBackup) -> Unit = {},
    val disconnect: () -> Unit = {},
    val setAuto: (Boolean) -> Unit = {},
    val setFrequency: (BackupFrequency) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val setChargingOnly: (Boolean) -> Unit = {},
)

/** Wires the Drive section to its ViewModel, including Google's consent screen. */
@Composable
fun DriveSectionRoute(viewModel: DriveViewModel, state: DriveUiState, onRestore: (DriveBackup) -> Unit) {
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsent(result.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { e ->
            when (e) {
                is DriveEffect.Consent -> consent.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
            }
        }
    }
    DriveSection(
        state,
        DriveActions(
            connect = viewModel::connect,
            backupNow = viewModel::backupNow,
            openRestore = viewModel::openRestore,
            closeRestore = viewModel::closeRestore,
            restore = { viewModel.closeRestore(); onRestore(it) },
            disconnect = viewModel::disconnect,
            setAuto = viewModel::setAuto,
            setFrequency = viewModel::setFrequency,
            setWifiOnly = viewModel::setWifiOnly,
            setChargingOnly = viewModel::setChargingOnly,
        ),
    )
}

/**
 * Google Drive backup (SPEC §4.11): connect once, then a nightly backup of the data (no photos,
 * no keys) to a "Forkcast Backups" folder only Forkcast can see; restore goes through the same
 * preview as a file import.
 */
@Composable
fun DriveSection(state: DriveUiState, actions: DriveActions) {
    val s = state.status
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (state.working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!s.connected) {
            Text(stringResource(R.string.drive_intro), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.connect, enabled = state.working == null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.drive_connect))
            }
        } else {
            val account = s.account?.takeIf { it != DefaultDriveBackupRepository.UNKNOWN_ACCOUNT }
            Text(
                if (account != null) stringResource(R.string.drive_connected_as, account) else stringResource(R.string.drive_connected),
                style = MaterialTheme.typography.bodyMedium,
            )
            LastBackupLine(s)
            ToggleRow(stringResource(R.string.drive_auto), stringResource(R.string.drive_auto_detail), s.settings.auto, actions.setAuto)
            if (s.settings.auto) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.settings.frequency == BackupFrequency.DAILY,
                        onClick = { actions.setFrequency(BackupFrequency.DAILY) },
                        label = { Text(stringResource(R.string.drive_daily)) },
                    )
                    FilterChip(
                        selected = s.settings.frequency == BackupFrequency.WEEKLY,
                        onClick = { actions.setFrequency(BackupFrequency.WEEKLY) },
                        label = { Text(stringResource(R.string.drive_weekly)) },
                    )
                }
                ToggleRow(stringResource(R.string.drive_wifi_only), null, s.settings.wifiOnly, actions.setWifiOnly)
                ToggleRow(stringResource(R.string.drive_charging_only), null, s.settings.chargingOnly, actions.setChargingOnly)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.backupNow, enabled = state.working == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_backup_now))
                }
                OutlinedButton(onClick = actions.openRestore, enabled = state.working == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_restore))
                }
            }
            TextButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.drive_disconnect)) }
        }
        when {
            state.justBackedUp -> Text(stringResource(R.string.drive_backed_up), style = MaterialTheme.typography.bodyMedium, color = ForkcastTheme.colors.pine)
            state.problem != null -> Text(problemText(state.problem), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    state.backups?.let { RestoreDialog(it, actions.restore, actions.closeRestore) }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.drive_disconnect_title)) },
            text = { Text(stringResource(R.string.drive_disconnect_body)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; actions.disconnect() }) { Text(stringResource(R.string.drive_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LastBackupLine(s: DriveStatus) {
    val context = LocalContext.current
    val last = s.lastBackupAt?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    Text(
        if (last != null) stringResource(R.string.drive_last_backup, last) else stringResource(R.string.drive_no_backup_yet),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    s.lastProblem?.let { Text(problemText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun problemText(p: DriveProblem): String = stringResource(driveProblemText(p))

/** Message for each Drive problem (section line and restore dialog). */
fun driveProblemText(p: DriveProblem): Int =
    when (p) {
        DriveProblem.NOT_CONNECTED -> R.string.drive_problem_not_connected
        DriveProblem.RECONNECT -> R.string.drive_problem_reconnect
        DriveProblem.NOT_SET_UP -> R.string.drive_problem_not_set_up
        DriveProblem.OFFLINE -> R.string.drive_problem_offline
        DriveProblem.QUOTA_FULL -> R.string.drive_problem_quota
        DriveProblem.BUSY -> R.string.drive_problem_busy
        DriveProblem.ERROR -> R.string.drive_problem_error
    }

@Composable
private fun ToggleRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(checked) },
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RestoreDialog(backups: List<DriveBackup>, onPick: (DriveBackup) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_restore_title)) },
        text = {
            if (backups.isEmpty()) {
                Text(stringResource(R.string.drive_restore_empty))
            } else {
                Column {
                    Text(stringResource(R.string.drive_restore_hint), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                        items(backups, key = { it.id }) { b ->
                            val whenText = b.createdAt?.let {
                                DateUtils.formatDateTime(context, it.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_YEAR)
                            } ?: b.name
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(b) }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(whenText, style = MaterialTheme.typography.bodyLarge)
                                b.sizeBytes?.let { Text(Formatter.formatShortFileSize(context, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
