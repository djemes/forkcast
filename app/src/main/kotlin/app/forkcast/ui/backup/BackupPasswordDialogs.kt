package app.forkcast.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.BackupPassword
import app.forkcast.ui.common.KeyClipboard
import app.forkcast.ui.common.copySensitive
import app.forkcast.ui.help.HelpEntry
import app.forkcast.ui.help.HelpLink
import app.forkcast.ui.theme.ForkcastTheme
import kotlinx.coroutines.launch

/**
 * A new backup password (owner, 2026-10-29): typed twice, or generated; at least
 * [BackupPassword.MIN_LENGTH] characters, a strength meter, a copy button and the note to save it
 * somewhere else. [onValid] gets the password while it's acceptable, else null.
 */
@Composable
private fun NewPasswordFields(onValid: (String?) -> Unit, onCopy: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    fun report() = onValid(first.takeIf { BackupPassword.isAcceptable(it) && it == second })
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = first,
            onValueChange = { first = it; report() },
            label = { Text(stringResource(R.string.bpw_field)) },
            singleLine = true,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (visible) R.string.fdb_hide_key else R.string.fdb_show_key),
                    )
                }
            },
            supportingText = if (first.length < BackupPassword.MIN_LENGTH) ({ Text(stringResource(R.string.bpw_min, BackupPassword.MIN_LENGTH)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        StrengthMeter(BackupPassword.strength(first))
        val mismatch = second.isNotEmpty() && second != first
        OutlinedTextField(
            value = second,
            onValueChange = { second = it; report() },
            label = { Text(stringResource(R.string.bpw_field_again)) },
            singleLine = true,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = mismatch,
            supportingText = if (mismatch) ({ Text(stringResource(R.string.bpw_mismatch)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Wraps: next to each other when they fit, Copy below at large text or on narrow screens.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = {
                    val generated = BackupPassword.generate()
                    first = generated
                    second = generated
                    visible = true
                    report()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.bpw_generate)) }
            if (first.isNotEmpty()) {
                TextButton(onClick = { onCopy(first) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bpw_copy)) }
            }
        }
        Text(stringResource(R.string.bpw_save_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** Four steps and a word, so the color is never the only signal. */
@Composable
private fun StrengthMeter(score: Int) {
    if (score == 0) return
    val label = stringResource(
        when (score) {
            1 -> R.string.bpw_strength_weak
            2 -> R.string.bpw_strength_fair
            3 -> R.string.bpw_strength_good
            else -> R.string.bpw_strength_strong
        },
    )
    val color: Color = when (score) {
        1 -> MaterialTheme.colorScheme.error
        2 -> ForkcastTheme.colors.apricot
        else -> ForkcastTheme.colors.pine
    }
    val description = stringResource(R.string.bpw_strength_label, label)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        LinearProgressIndicator(progress = { score / 4f }, color = color, modifier = Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Sets or changes the backup password; [body] says what happens to existing backups. */
@Composable
fun NewPasswordDialog(title: String, body: String?, onDone: (String) -> Unit, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    var valid by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                NewPasswordFields(onValid = { valid = it }, onCopy = onCopy)
            }
        },
        confirmButton = { TextButton(onClick = { valid?.let(onDone) }, enabled = valid != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The saved password, after the phone's unlock, in a monospace face with Copy. */
@Composable
fun ShowPasswordDialog(password: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bpw_dialog_show)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(password, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.bpw_save_note), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onCopy(password) }) { Text(stringResource(R.string.bpw_copy)) } },
    )
}

/** Copies like a saved key (marked sensitive, cleared after a minute), then [onCopied]. */
@Composable
fun rememberPasswordCopier(onCopied: () -> Unit): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.bpw_title)
    return { text ->
        scope.launch {
            clipboard.copySensitive(label, text)
            KeyClipboard.clearSoon(context, text)
            onCopied()
        }
    }
}

/** The picked backup is password-protected. */
@Composable
fun UnlockBackupDialog(wrong: Boolean, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_unlock_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.backup_unlock_wrong)) }) else null,
                )
                if (wrong) HelpLink(HelpEntry.BACKUP_PASSWORD, onOpen = onDismiss)
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.backup_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
