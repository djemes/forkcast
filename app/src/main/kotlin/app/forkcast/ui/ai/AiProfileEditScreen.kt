package app.forkcast.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.ProviderId
import app.forkcast.ui.common.DiscardChangesDialog
import app.forkcast.ui.common.SavedKeyField
import app.forkcast.ui.settings.SettingsScaffold
import kotlinx.coroutines.launch

/** [onDelete] gets the profile id after the user confirmed; the profile list deletes it and offers Undo. */
@Composable
fun AiProfileEditRoute(onBack: () -> Unit, onDelete: (String) -> Unit, viewModel: AiProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onBack() }
    AiProfileEditScreen(state, viewModel::onEvent, onBack, onDelete = { viewModel.savedProfileId?.let(onDelete) })
}

@Composable
fun AiProfileEditScreen(
    state: AiProfileEditUiState,
    onEvent: (AiProfileEditEvent) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val title = stringResource(if (state.isNew) R.string.ai_profile_new else R.string.ai_profile_edit)
    var confirmDelete by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    // Changes since the screen loaded; Back or Cancel asks before throwing them away.
    val initial = remember(state.loaded) { state }
    val dirty = state.loaded && !state.done && (
        state.name != initial.name || state.model != initial.model || state.baseUrl != initial.baseUrl ||
            state.provider != initial.provider || state.keyInput.isNotBlank() || state.copyKeyFrom != initial.copyKeyFrom
        )
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) DiscardChangesDialog(onDiscard = { confirmDiscard = false; onBack() }, onKeepEditing = { confirmDiscard = false })
    SettingsScaffold(title = title, onBack = onBack, dirty = dirty) {
        if (!state.loaded) return@SettingsScaffold
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderPicker(state.provider) { onEvent(AiProfileEditEvent.SetProvider(it)) }

            if (state.provider == ProviderId.OPENAI_COMPAT) {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { onEvent(AiProfileEditEvent.SetBaseUrl(it)) },
                    label = { Text(stringResource(R.string.ai_base_url)) },
                    placeholder = { Text(stringResource(R.string.ai_base_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isCleartext) {
                    Text(stringResource(R.string.ai_cleartext_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            KeyField(state, onEvent)

            Heading(R.string.ai_model)
            OutlinedTextField(
                value = state.model,
                onValueChange = { onEvent(AiProfileEditEvent.SetModel(it)) },
                label = { Text(stringResource(R.string.ai_model)) },
                supportingText = state.recommendedModel?.let { { Text(stringResource(R.string.ai_recommended) + ": " + it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onEvent(AiProfileEditEvent.LoadModels); pickModel = true },
                enabled = state.keyReady && !state.loadingModels,
            ) { Text(stringResource(if (state.loadingModels) R.string.ai_loading_models else R.string.ai_choose_model)) }
            state.modelsError?.let { TestResult(TestState.Failed(it), state.provider) }

            OutlinedTextField(
                value = state.name,
                onValueChange = { onEvent(AiProfileEditEvent.SetName(it)) },
                label = { Text(stringResource(R.string.ai_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onEvent(AiProfileEditEvent.SaveAndTest) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(if (state.saving) R.string.ai_testing else R.string.ai_save_and_test))
            }
            OutlinedButton(
                onClick = { if (dirty) confirmDiscard = true else onBack() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
            // Same result view as the profile list's Test (TestResult), from the same call (runTest).
            if (!state.saving) TestResult(state.test, state.provider)
            if (state.test is TestState.Failed && !state.isNew) {
                OutlinedButton(onClick = { onEvent(AiProfileEditEvent.TestAgain) }) { Text(stringResource(R.string.ai_test_again)) }
            }
            if (!state.isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                    Text(stringResource(R.string.ai_delete_profile), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (pickModel && state.models != null) {
        ModelPicker(state, onPick = { onEvent(AiProfileEditEvent.SetModel(it)); pickModel = false }, onDismiss = { pickModel = false })
    }
    if (confirmDelete) {
        DeleteProfileDialog(
            name = state.name,
            movesUp = null,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(selected: ProviderId, onPick: (ProviderId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = stringResource(selected.label()),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.ai_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in ProviderId.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(p.label())) },
                    onClick = { onPick(p); open = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun Heading(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun KeyField(state: AiProfileEditUiState, onEvent: (AiProfileEditEvent) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var guide by remember { mutableStateOf(false) }
    Heading(R.string.ai_api_key)
    // Steps and links for the selected provider (owner request 2026-10-19).
    TextButton(onClick = { guide = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(painterResource(R.drawable.ic_help), contentDescription = null)
        Text(stringResource(R.string.ai_get_key), modifier = Modifier.padding(start = 8.dp))
    }
    if (guide) {
        KeyGuideDialog(
            provider = state.provider,
            onUseServerUrl = { onEvent(AiProfileEditEvent.SetBaseUrl(it)); guide = false },
            onDismiss = { guide = false },
        )
    }
    if (state.keySources.isNotEmpty()) {
        for (src in state.keySources) {
            FilterChip(
                selected = state.copyKeyFrom == src,
                onClick = { onEvent(AiProfileEditEvent.UseKeyFrom(if (state.copyKeyFrom == src) null else src)) },
                label = { Text(stringResource(R.string.ai_use_key_from, src.name)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    val saved = state.savedKey
    if (saved != null && state.showSavedKey) {
        // A saved key shows masked, with show/hide and copy (owner request 2026-10-19).
        SavedKeyField(stringResource(R.string.ai_api_key), saved)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEvent(AiProfileEditEvent.ReplaceKey(true)) }) { Text(stringResource(R.string.key_replace)) }
        }
    } else if (state.copyKeyFrom == null) {
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = { onEvent(AiProfileEditEvent.SetKey(it)) },
            label = { Text(stringResource(if (state.replacingKey) R.string.key_new else R.string.ai_api_key)) },
            supportingText = when {
                state.replacingKey -> { { Text(stringResource(R.string.ai_api_key_replacing)) } }
                state.provider == ProviderId.OPENAI_COMPAT -> { { Text(stringResource(R.string.ai_api_key_optional)) } }
                else -> null
            },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (visible) R.string.fdb_hide_key else R.string.fdb_show_key),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                        onEvent(AiProfileEditEvent.SetKey(it.toString()))
                    }
                }
            }) { Text(stringResource(R.string.fdb_paste)) }
            if (state.replacingKey) {
                TextButton(onClick = { onEvent(AiProfileEditEvent.ReplaceKey(false)) }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun ModelPicker(state: AiProfileEditUiState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_model)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                LazyColumn {
                    items(state.models.orEmpty().size) { i ->
                        val m = state.models!![i]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = m.id == state.model, role = Role.RadioButton) { onPick(m.id) },
                        ) {
                            RadioButton(selected = m.id == state.model, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(m.displayName)
                                val tag = if (m.id == state.recommendedModel) " · " + stringResource(R.string.ai_recommended) else ""
                                Text(m.id + tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
