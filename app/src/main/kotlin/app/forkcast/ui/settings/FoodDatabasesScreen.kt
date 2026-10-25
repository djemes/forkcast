package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import app.forkcast.GuideLinks
import app.forkcast.core.data.repository.FoodDatabaseKeyRepository
import app.forkcast.core.domain.ApiKeyFormat
import app.forkcast.core.network.FoodApiError
import app.forkcast.ui.common.SavedKeyField
import app.forkcast.ui.common.SecretText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** Where the USDA key check stands (roadmap 2.0 task 3.3: the AI guide's pattern, shorter). */
sealed interface UsdaKeyCheck {
    data object Checking : UsdaKeyCheck
    /** Saved after a passing check. */
    data object Saved : UsdaKeyCheck
    /** The check failed; [canSaveAnyway] unless USDA refused the key itself. */
    data class Failed(val error: Throwable) : UsdaKeyCheck {
        val canSaveAnyway: Boolean get() = error !is FoodApiError.InvalidKey
    }
}

@HiltViewModel
class FoodDatabasesViewModel @Inject constructor(
    private val keys: FoodDatabaseKeyRepository,
) : ViewModel() {
    /**
     * The saved USDA key, for the masked field with show/hide and copy (owner request
     * 2026-10-19). It stays encrypted at rest and is never logged.
     */
    val savedKey: StateFlow<SecretText?> = keys.savedKey
        .map { it?.let(::SecretText) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _check = MutableStateFlow<UsdaKeyCheck?>(null)
    val check: StateFlow<UsdaKeyCheck?> = _check.asStateFlow()

    /** The key being checked, kept only in memory for "Save anyway". */
    private var pending: String? = null

    /** Checks the key with FoodData Central first, then saves it. A busy server still means the key works. */
    fun saveUsdaKey(raw: String) {
        val key = ApiKeyFormat.clean(raw)
        if (key.isEmpty()) return
        pending = key
        _check.value = UsdaKeyCheck.Checking
        viewModelScope.launch {
            val error = keys.test(key).exceptionOrNull()
            if (error == null || error is FoodApiError.RateLimited) save(key) else _check.value = UsdaKeyCheck.Failed(error)
        }
    }

    fun saveAnyway() {
        val key = pending ?: return
        viewModelScope.launch { save(key) }
    }

    fun dismissCheck() {
        pending = null
        _check.value = null
    }

    private suspend fun save(key: String) {
        keys.save(key)
        pending = null
        _check.value = UsdaKeyCheck.Saved
    }

    fun removeUsdaKey() = viewModelScope.launch {
        keys.remove()
        _check.value = null
    }
}

@Composable
fun FoodDatabasesRoute(onBack: () -> Unit, viewModel: FoodDatabasesViewModel = hiltViewModel()) {
    val savedKey by viewModel.savedKey.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    FoodDatabasesScreen(
        savedKey = savedKey,
        check = check,
        onSave = viewModel::saveUsdaKey,
        onSaveAnyway = viewModel::saveAnyway,
        onDismissCheck = viewModel::dismissCheck,
        onRemove = viewModel::removeUsdaKey,
        onBack = onBack,
    )
}

@Composable
fun FoodDatabasesScreen(
    savedKey: SecretText?,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    check: UsdaKeyCheck? = null,
    onSaveAnyway: () -> Unit = {},
    onDismissCheck: () -> Unit = {},
) {
    val uri = LocalUriHandler.current
    // A saved key shows read-only until "Replace key"; with no key the steps and the entry field show right away.
    var replacing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(check) { if (check == UsdaKeyCheck.Saved) replacing = false }

    SettingsScaffold(title = stringResource(R.string.settings_food_databases), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.fdb_usda_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.fdb_usda_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (savedKey != null) R.string.fdb_status_own else R.string.fdb_status_demo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (check == UsdaKeyCheck.Saved && savedKey != null) {
                Text(
                    stringResource(R.string.fdb_key_works),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (savedKey != null && !replacing) {
                SavedKeyField(stringResource(R.string.fdb_usda_key), savedKey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { replacing = true }) { Text(stringResource(R.string.key_replace)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.fdb_remove_key)) }
                }
            } else {
                if (savedKey == null) {
                    Text(stringResource(R.string.fdb_steps_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    listOf(R.string.fdb_step_1, R.string.fdb_step_2, R.string.fdb_step_3).forEachIndexed { i, step ->
                        Row {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 24.dp))
                            Column {
                                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                                if (i == 0) {
                                    OutlinedButton(
                                        onClick = { uri.openUri(GuideLinks.USDA_SIGNUP) },
                                        modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp),
                                    ) { Text(stringResource(R.string.fdb_open_signup)) }
                                }
                            }
                        }
                    }
                }
                KeyEntry(
                    label = stringResource(if (savedKey != null) R.string.key_new else R.string.fdb_usda_key),
                    onSave = onSave,
                    onCancel = if (savedKey != null) ({ replacing = false; onDismissCheck() }) else null,
                    saveLabel = stringResource(R.string.fdb_save_check),
                    enabled = check != UsdaKeyCheck.Checking,
                )
                when (check) {
                    UsdaKeyCheck.Checking -> Text(
                        stringResource(R.string.fdb_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is UsdaKeyCheck.Failed -> UsdaCheckFailed(check, onSaveAnyway)
                    else -> Unit
                }
            }

            Text(
                stringResource(R.string.fdb_off_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            Text(stringResource(R.string.fdb_off_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UsdaCheckFailed(check: UsdaKeyCheck.Failed, onSaveAnyway: () -> Unit) {
    val message = when (check.error) {
        is FoodApiError.InvalidKey -> R.string.fdb_err_key
        is FoodApiError.Offline -> R.string.fdb_err_offline
        else -> R.string.fdb_err_server
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        if (check.canSaveAnyway) {
            OutlinedButton(onClick = onSaveAnyway, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_save_anyway)) }
        }
    }
}

/** Masked key entry with show/hide and Paste. [onCancel] null hides the Cancel button. */
@Composable
internal fun KeyEntry(
    label: String,
    onSave: (String) -> Unit,
    onCancel: (() -> Unit)?,
    saveLabel: String? = null,
    enabled: Boolean = true,
) {
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
        Button(onClick = { onSave(key); key = "" }, enabled = enabled && key.length >= 20) { Text(saveLabel ?: stringResource(R.string.action_save)) }
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}
