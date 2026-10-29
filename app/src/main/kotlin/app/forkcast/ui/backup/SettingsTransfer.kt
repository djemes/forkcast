package app.forkcast.ui.backup

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.ui.help.HelpEntry
import app.forkcast.ui.help.HelpLink
import app.forkcast.core.data.backup.OpenedSettings
import app.forkcast.core.data.backup.SettingsBackupRepository
import app.forkcast.core.data.backup.SettingsCrypto
import app.forkcast.core.data.backup.SettingsImportError
import app.forkcast.core.data.backup.SettingsPayload
import app.forkcast.core.data.backup.SettingsPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

sealed interface SettingsTransferStep {
    data object ImportPassphrase : SettingsTransferStep
    data class Preview(val preview: SettingsPreview) : SettingsTransferStep
    data class Error(val error: SettingsImportError?) : SettingsTransferStep
}

data class SettingsTransferState(
    val step: SettingsTransferStep? = null,
    val busy: Boolean = false,
    /** "Settings file saved" / "imported" snackbar text resource, consumed by the screen. */
    val notice: Int? = null,
)

/**
 * Importing a settings file from 1.2 to 1.8.1 (SPEC §4.11). Making one is gone since 1.8.2: backups
 * carry settings and keys (owner, 2026-10-29); reading them stays until 2.0 (compatibility rule).
 */
@HiltViewModel
class SettingsTransferViewModel @Inject constructor(
    private val repo: SettingsBackupRepository,
    private val documents: DocumentIo,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsTransferState())
    val state: StateFlow<SettingsTransferState> = _state.asStateFlow()

    private var importText: String? = null
    private var opened: SettingsPayload? = null

    fun picked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val text = try {
                documents.readText(uri)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            importText = text
            _state.update {
                it.copy(busy = false, step = if (text == null) SettingsTransferStep.Error(SettingsImportError.UNREADABLE) else SettingsTransferStep.ImportPassphrase)
            }
        }
    }

    fun unlock(value: CharArray) {
        val text = importText ?: return dismiss()
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = repo.open(text, value)
            value.fill('\u0000')
            val step = when (result) {
                is OpenedSettings.Ok -> {
                    opened = result.payload
                    SettingsTransferStep.Preview(result.preview)
                }
                is OpenedSettings.Failed -> SettingsTransferStep.Error(result.error)
            }
            _state.update { it.copy(busy = false, step = step) }
        }
    }

    fun apply() {
        val payload = opened ?: return dismiss()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, step = null) }
            repo.apply(payload)
            opened = null
            importText = null
            _state.update { it.copy(busy = false, notice = R.string.settings_imported) }
        }
    }

    fun dismiss() {
        opened = null
        importText = null
        _state.update { it.copy(step = null) }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }
}

/** The "Settings and keys" block on the Backup & data screen. */
@Composable
fun SettingsTransferSection(busy: Boolean, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_transfer_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.settings_transfer_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = !busy) { Text(stringResource(R.string.settings_import)) }
        }
    }
}

@Composable
fun SettingsTransferDialogs(
    step: SettingsTransferStep,
    onUnlock: (CharArray) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (step) {
        SettingsTransferStep.ImportPassphrase -> PassphraseDialog(confirm = false, onDone = onUnlock, onDismiss = onDismiss)
        is SettingsTransferStep.Preview -> {
            val p = step.preview
            val made = runCatching {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.parse(p.exportedAt).atZone(ZoneId.systemDefault()))
            }.getOrDefault(p.exportedAt)
            val profiles = p.profileNames.joinToString(", ").ifEmpty { stringResource(R.string.settings_preview_none) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.settings_preview_title)) },
                text = {
                    Column {
                        Text(pluralStringResource(R.plurals.settings_preview_body, p.keyCount, made, profiles, p.keyCount))
                        if (p.aiServers.isNotEmpty()) AiServersNote(p.aiServers)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onApply) { Text(stringResource(R.string.settings_replace), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is SettingsTransferStep.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            dismissButton = {
                HelpLink(if (step.error == SettingsImportError.WRONG_PASSPHRASE) HelpEntry.BACKUP_PASSWORD else HelpEntry.BACKUP_RESTORE_FAILED, onOpen = onDismiss)
            },
            text = {
                Text(
                    stringResource(
                        when (step.error) {
                            SettingsImportError.WRONG_PASSPHRASE -> R.string.settings_wrong_passphrase
                            SettingsImportError.NOT_FORKCAST -> R.string.settings_not_forkcast
                            SettingsImportError.NEWER_VERSION -> R.string.settings_newer
                            SettingsImportError.UNREADABLE, null -> R.string.settings_unreadable
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** Export asks twice and shows a strength hint; import asks once. */
@Composable
fun PassphraseDialog(confirm: Boolean, onDone: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val strength = SettingsCrypto.strength(first)
    val long = first.length >= SettingsCrypto.MIN_PASSPHRASE
    val ok = if (confirm) long && first == second else first.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (confirm) R.string.settings_passphrase_title_export else R.string.settings_passphrase_title_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) Text(stringResource(R.string.settings_passphrase_body_export), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.settings_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = if (confirm) {
                        {
                            Text(
                                stringResource(
                                    when (strength) {
                                        0 -> R.string.settings_passphrase_short
                                        1 -> R.string.settings_strength_weak
                                        2 -> R.string.settings_strength_fair
                                        else -> R.string.settings_strength_strong
                                    },
                                ),
                            )
                        }
                    } else {
                        null
                    },
                )
                if (confirm) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text(stringResource(R.string.settings_passphrase_again)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = second.isNotEmpty() && second != first,
                        supportingText = if (second.isNotEmpty() && second != first) {
                            { Text(stringResource(R.string.settings_passphrase_mismatch)) }
                        } else {
                            null
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(first.toCharArray()); first = ""; second = "" }, enabled = ok, modifier = Modifier) {
                Text(stringResource(R.string.settings_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
