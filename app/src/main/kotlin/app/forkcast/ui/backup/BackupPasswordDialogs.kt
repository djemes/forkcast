package app.forkcast.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.common.ToggleRow
import app.forkcast.core.data.backup.SettingsCrypto

/** A new password, twice, with the strength hint of the settings export. Valid when [onValid] gets non-null. */
@Composable
private fun NewPasswordFields(onValid: (String?) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    fun report() = onValid(first.takeIf { it.length >= SettingsCrypto.MIN_PASSPHRASE && it == second })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = first,
            onValueChange = { first = it; report() },
            label = { Text(stringResource(R.string.backup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            supportingText = {
                Text(
                    stringResource(
                        when (SettingsCrypto.strength(first)) {
                            0 -> R.string.settings_passphrase_short
                            1 -> R.string.settings_strength_weak
                            2 -> R.string.settings_strength_fair
                            else -> R.string.settings_strength_strong
                        },
                    ),
                )
            },
        )
        val mismatch = second.isNotEmpty() && second != first
        OutlinedTextField(
            value = second,
            onValueChange = { second = it; report() },
            label = { Text(stringResource(R.string.backup_password_again)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = mismatch,
            supportingText = if (mismatch) ({ Text(stringResource(R.string.settings_passphrase_mismatch)) }) else null,
        )
    }
}

/** Sets the password automatic backups use. */
@Composable
fun NewPasswordDialog(title: String, body: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var valid by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                NewPasswordFields { valid = it }
            }
        },
        confirmButton = { TextButton(onClick = { valid?.let(onDone) }, enabled = valid != null) { Text(stringResource(R.string.settings_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Save a backup file": meal photos and an optional password, then the file picker. */
@Composable
fun SaveOptionsDialog(onContinue: (photos: Boolean, password: String?) -> Unit, onDismiss: () -> Unit) {
    var photos by remember { mutableStateOf(true) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.backup_save_body), style = MaterialTheme.typography.bodyMedium)
                ToggleRow(stringResource(R.string.backup_save_photos), stringResource(R.string.backup_photos_detail), photos) { photos = it }
                ToggleRow(stringResource(R.string.backup_save_password), null, protect) { protect = it }
                if (protect) {
                    Text(stringResource(R.string.backup_password_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    NewPasswordFields { password = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(photos, if (protect) password else null) }, enabled = !protect || password != null) {
                Text(stringResource(R.string.backup_save_choose))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.backup_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
