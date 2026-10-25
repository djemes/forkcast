package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.ui.common.SavedKeyField
import app.forkcast.ui.common.SecretText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoodDatabasesViewModel @Inject constructor(
    private val secrets: SecretStore,
    private val missingKeys: app.forkcast.core.data.repository.MissingKeysRepository,
) : ViewModel() {
    /**
     * The saved USDA key, for the masked field with show/hide and copy (owner request
     * 2026-10-19). It stays encrypted at rest and is never logged.
     */
    val savedKey: StateFlow<SecretText?> = secrets.present
        .map { names -> if (SecretName.USDA_API_KEY in names) secrets.get(SecretName.USDA_API_KEY)?.let(::SecretText) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { secrets.refresh() }
    }

    fun saveUsdaKey(key: String) = viewModelScope.launch {
        secrets.set(SecretName.USDA_API_KEY, key)
        missingKeys.noteFoodDatabaseKey(true)
    }

    /** Removed on purpose: don't ask for it after a restore. */
    fun removeUsdaKey() = viewModelScope.launch {
        secrets.set(SecretName.USDA_API_KEY, null)
        missingKeys.noteFoodDatabaseKey(false)
    }
}

@Composable
fun FoodDatabasesRoute(onBack: () -> Unit, viewModel: FoodDatabasesViewModel = hiltViewModel()) {
    val savedKey by viewModel.savedKey.collectAsStateWithLifecycle()
    FoodDatabasesScreen(
        savedKey = savedKey,
        onSave = viewModel::saveUsdaKey,
        onRemove = viewModel::removeUsdaKey,
        onBack = onBack,
    )
}

@Composable
fun FoodDatabasesScreen(savedKey: SecretText?, onSave: (String) -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    // A saved key shows read-only until "Replace key"; with no key the entry field shows right away.
    var replacing by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_food_databases), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.fdb_usda_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.fdb_usda_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (savedKey != null) R.string.fdb_status_own else R.string.fdb_status_demo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (savedKey != null && !replacing) {
                SavedKeyField(stringResource(R.string.fdb_usda_key), savedKey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { replacing = true }) { Text(stringResource(R.string.key_replace)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.fdb_remove_key)) }
                }
            } else {
                KeyEntry(
                    label = stringResource(if (savedKey != null) R.string.key_new else R.string.fdb_usda_key),
                    onSave = { onSave(it); replacing = false },
                    onCancel = if (savedKey != null) ({ replacing = false }) else null,
                )
            }
            TextButton(onClick = { uri.openUri(USDA_SIGNUP_URL) }) { Text(stringResource(R.string.fdb_get_key)) }

            Text(
                stringResource(R.string.fdb_off_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            Text(stringResource(R.string.fdb_off_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Masked key entry with show/hide and Paste. [onCancel] null hides the Cancel button. */
@Composable
internal fun KeyEntry(label: String, onSave: (String) -> Unit, onCancel: (() -> Unit)?) {
    var key by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    app.forkcast.ui.common.SecureWhile(visible && key.isNotEmpty())
    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text(label) },
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
                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { key = it.toString().trim() }
            }
        }) { Text(stringResource(R.string.fdb_paste)) }
        Button(onClick = { onSave(key); key = "" }, enabled = key.length >= 20) { Text(stringResource(R.string.action_save)) }
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** FoodData Central's "Get an API Key" page (the api.data.gov form loads on it; checked 2026-10-20). */
const val USDA_SIGNUP_URL = "https://fdc.nal.usda.gov/api-key-signup"
