package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoodDatabasesViewModel @Inject constructor(private val secrets: SecretStore) : ViewModel() {
    /** Only whether a key exists; the value never leaves the store except for API calls. */
    val present: StateFlow<Set<SecretName>> = secrets.present

    init {
        viewModelScope.launch { secrets.refresh() }
    }

    fun saveUsdaKey(key: String) = viewModelScope.launch { secrets.set(SecretName.USDA_API_KEY, key) }
    fun removeUsdaKey() = viewModelScope.launch { secrets.set(SecretName.USDA_API_KEY, null) }
}

@Composable
fun FoodDatabasesRoute(onBack: () -> Unit, viewModel: FoodDatabasesViewModel = hiltViewModel()) {
    val present by viewModel.present.collectAsStateWithLifecycle()
    FoodDatabasesScreen(
        hasUsdaKey = SecretName.USDA_API_KEY in present,
        onSave = viewModel::saveUsdaKey,
        onRemove = viewModel::removeUsdaKey,
        onBack = onBack,
    )
}

@Composable
fun FoodDatabasesScreen(hasUsdaKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    SettingsScaffold(title = stringResource(R.string.settings_food_databases), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.fdb_usda_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.fdb_usda_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (hasUsdaKey) R.string.fdb_status_own else R.string.fdb_status_demo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text(stringResource(R.string.fdb_usda_key)) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { key = it.toString().trim() }
                    }
                }) { Text(stringResource(R.string.fdb_paste)) }
                Button(onClick = { onSave(key); key = "" }, enabled = key.length >= 20) { Text(stringResource(R.string.action_save)) }
                if (hasUsdaKey) TextButton(onClick = onRemove) { Text(stringResource(R.string.fdb_remove_key)) }
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

/** // VERIFY: signup page URL (fdc.nal.usda.gov links to the api.data.gov form). */
const val USDA_SIGNUP_URL = "https://fdc.nal.usda.gov/api-key-signup"
