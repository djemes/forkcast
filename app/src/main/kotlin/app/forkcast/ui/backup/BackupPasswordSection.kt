package app.forkcast.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.common.DeviceAuth
import app.forkcast.ui.common.NoScreenLockDialog
import kotlinx.coroutines.launch

/** Which password dialog is open: a first one (and then turn automatic backup on), or a change. */
enum class PasswordAsk { NEW, NEW_THEN_AUTO, CHANGE }

/**
 * The backup password (owner, 2026-10-29): set once, kept until changed or removed. Showing,
 * copying, changing and removing it ask for the phone's unlock first (the same prompt as saved
 * keys); a change re-encrypts the folder's backups.
 */
@Composable
fun BackupPasswordSectionRoute(
    viewModel: AutoBackupViewModel,
    state: AutoBackupUiState,
    ask: PasswordAsk?,
    onAsk: (PasswordAsk?) -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var noLock by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.bpw_unlock_title)
    val subtitle = stringResource(R.string.bpw_unlock_subtitle)
    val unlocked: (() -> Unit) -> Unit = { then ->
        if (!DeviceAuth.hasScreenLock(context)) noLock = true else DeviceAuth.authenticate(context, title, subtitle, onSuccess = then)
    }
    val copied = stringResource(R.string.bpw_copied)
    val copy = rememberPasswordCopier { scope.launch { snackbar.showSnackbar(copied) } }

    // After a change: how many were updated, and which couldn't be.
    LaunchedEffect(state.reencrypted) {
        val r = state.reencrypted ?: return@LaunchedEffect
        viewModel.messageShown()
        val message = when {
            r.problem != null -> resources.getString(R.string.bpw_change_folder_lost)
            r.failed.isNotEmpty() -> resources.getString(R.string.bpw_change_failed, r.failed.joinToString(", "))
            else -> resources.getQuantityString(R.plurals.bpw_changed, r.updated, r.updated)
        }
        snackbar.showSnackbar(message, withDismissAction = r.failed.isNotEmpty() || r.problem != null)
    }

    BackupPasswordSection(
        hasPassword = state.status.hasPassword,
        working = state.working != null,
        onSet = { onAsk(PasswordAsk.NEW) },
        onShow = { unlocked { viewModel.showPassword() } },
        onChange = { unlocked { onAsk(PasswordAsk.CHANGE) } },
        onRemove = { unlocked { confirmRemove = true } },
    )

    if (noLock) NoScreenLockDialog(onDismiss = { noLock = false }, body = stringResource(R.string.bpw_no_lock))
    state.shownPassword?.let { ShowPasswordDialog(it, onCopy = copy, onDismiss = viewModel::hidePassword) }
    ask?.let { a ->
        NewPasswordDialog(
            title = stringResource(if (a == PasswordAsk.CHANGE) R.string.bpw_dialog_change else R.string.bpw_dialog_new),
            body = if (a == PasswordAsk.CHANGE) stringResource(R.string.bpw_change_body) else null,
            onDone = { pw ->
                onAsk(null)
                if (a == PasswordAsk.CHANGE) viewModel.changePassword(pw) else viewModel.setPassword(pw, thenAuto = a == PasswordAsk.NEW_THEN_AUTO)
            },
            onDismiss = { onAsk(null) },
            onCopy = copy,
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.bpw_remove_title)) },
            text = { Text(stringResource(R.string.bpw_remove_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; viewModel.removePassword() }) {
                    Text(stringResource(R.string.bpw_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun BackupPasswordSection(
    hasPassword: Boolean,
    working: Boolean,
    onSet: () -> Unit,
    onShow: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bpw_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(if (hasPassword) R.string.bpw_is_set else R.string.bpw_none), style = MaterialTheme.typography.bodyMedium)
        if (!hasPassword) {
            Button(onClick = onSet, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bpw_set)) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShow, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bpw_show)) }
                OutlinedButton(onClick = onChange, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bpw_change)) }
                TextButton(onClick = onRemove, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.bpw_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
