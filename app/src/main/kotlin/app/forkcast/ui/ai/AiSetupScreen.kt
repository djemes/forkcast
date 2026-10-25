package app.forkcast.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.GuideLinks
import app.forkcast.R
import app.forkcast.core.domain.KeyCheck
import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.ai.AiError
import app.forkcast.ui.common.SecureWhile
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
fun AiSetupRoute(onClose: () -> Unit, onAdvanced: () -> Unit, viewModel: AiSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AiSetupScreen(state, viewModel::onEvent, onClose, onAdvanced)
}

/** The guided AI setup (roadmap 2.0 task 3.3; SPEC §4.7). Every step says what happens next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSetupScreen(state: AiSetupUiState, onEvent: (AiSetupEvent) -> Unit, onClose: () -> Unit, onAdvanced: () -> Unit) {
    val first = state.step == SetupStep.CHOOSE
    val done = state.step == SetupStep.DONE
    BackHandler(enabled = !first && !done) { onEvent(AiSetupEvent.Back) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (first || done) onClose() else onEvent(AiSetupEvent.Back) }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            val progress = stringResource(R.string.setup_step_of, state.stepNumber, AiSetupUiState.STEP_COUNT)
            LinearProgressIndicator(
                progress = { state.stepNumber.toFloat() / AiSetupUiState.STEP_COUNT },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = progress },
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (state.step) {
                    SetupStep.CHOOSE -> ChooseStep(onEvent, onAdvanced)
                    SetupStep.ABOUT -> AboutStep(state.provider)
                    SetupStep.GET_KEY -> KeyStepsStep(state.provider)
                    SetupStep.PASTE -> PasteStep(state, onEvent)
                    SetupStep.DONE -> DoneStep(state)
                }
            }
            val bottom: Pair<Int, () -> Unit>? = when (state.step) {
                SetupStep.CHOOSE, SetupStep.PASTE -> null
                SetupStep.ABOUT -> R.string.setup_continue to { onEvent(AiSetupEvent.Next) }
                SetupStep.GET_KEY -> R.string.setup_have_key to { onEvent(AiSetupEvent.Next) }
                SetupStep.DONE -> R.string.setup_done to onClose
            }
            bottom?.let { (label, action) ->
                Button(onClick = action, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp)) { Text(stringResource(label)) }
            }
        }
    }
}

@Composable
internal fun Title(text: String, body: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** The service's name in sentences ("Checking with Gemini…"). */
@Composable
internal fun serviceName(provider: ProviderId): String = provider.shortName(LocalResources.current)

@Composable
private fun PasteStep(state: AiSetupUiState, onEvent: (AiSetupEvent) -> Unit) {
    val name = serviceName(state.provider)
    Title(stringResource(R.string.setup_paste_title), stringResource(R.string.setup_paste_body, name))
    var visible by remember { mutableStateOf(false) }
    SecureWhile(visible && state.key.isNotEmpty())
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val paste: () -> Unit = {
        scope.launch {
            clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                onEvent(AiSetupEvent.Pasted(it.toString()))
            }
        }
    }
    OutlinedTextField(
        value = state.key,
        onValueChange = { onEvent(AiSetupEvent.SetKey(it)) },
        label = { Text(stringResource(R.string.setup_key_field)) },
        singleLine = true,
        enabled = !state.checking,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    stringResource(if (visible) R.string.setup_hide_key else R.string.setup_show_key),
                )
            }
        },
        supportingText = keyCheckText(state.keyCheck, state.provider)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = paste,
            enabled = !state.checking,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.setup_paste)) }
        OutlinedButton(onClick = { onEvent(AiSetupEvent.Check) }, enabled = state.canCheck, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.setup_check))
        }
    }
    if (state.checking) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(stringResource(R.string.setup_checking, name), style = MaterialTheme.typography.bodyLarge)
        }
    }
    state.error?.let { SetupError(it, state, onEvent, paste) }
    Text(stringResource(R.string.setup_key_stored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The soft format check's word on the key, or null when there's nothing to say. */
@Composable
private fun keyCheckText(check: KeyCheck, provider: ProviderId): String? {
    val name = serviceName(provider)
    return when (check) {
        KeyCheck.HAS_SPACE -> stringResource(R.string.key_check_space)
        KeyCheck.TOO_SHORT -> stringResource(R.string.key_check_short)
        KeyCheck.NOT_A_KEY -> stringResource(R.string.key_check_not_key)
        KeyCheck.GEMINI_KEY -> stringResource(R.string.key_check_gemini, name)
        KeyCheck.CLAUDE_KEY -> stringResource(R.string.key_check_claude, name)
        KeyCheck.OPENAI_KEY -> stringResource(R.string.key_check_openai)
        KeyCheck.UNFAMILIAR -> if (provider == ProviderId.CLAUDE) stringResource(R.string.key_check_claude_prefix) else null
        KeyCheck.EMPTY, KeyCheck.LOOKS_RIGHT -> null
    }
}

@Composable
private fun SetupError(error: Throwable, state: AiSetupUiState, onEvent: (AiSetupEvent) -> Unit, paste: () -> Unit) {
    val name = serviceName(state.provider)
    val uri = LocalUriHandler.current
    val resources = LocalResources.current
    var details by rememberSaveable { mutableStateOf(false) }
    val (message, link) = when {
        error is AiError.InvalidKey && error.needsWorkspace ->
            stringResource(R.string.setup_err_workspace) to SetupLink(R.string.setup_open_keys, GuideLinks.CLAUDE_KEYS)
        error is AiError.InvalidKey -> stringResource(R.string.setup_err_key, name) to null
        error is AiError.QuotaExhausted -> stringResource(R.string.setup_err_credit) to SetupLink(R.string.setup_open_billing, GuideLinks.CLAUDE_BILLING)
        error is AiError.Network -> stringResource(R.string.setup_err_network) to null
        error is AiError.Timeout || error is AiError.Server -> stringResource(R.string.setup_err_busy, name) to null
        error is AiError.ModelNotFound -> stringResource(R.string.setup_err_model, name, error.model) to null
        else -> stringResource(R.string.setup_err_other, name) to null
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }) {
            Text(
                stringResource(R.string.setup_err_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        link?.let { l ->
            OutlinedButton(onClick = { uri.openUri(l.url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(l.label)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error is AiError.InvalidKey) {
                Button(onClick = paste, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_other_key))
                }
            } else {
                Button(onClick = { onEvent(AiSetupEvent.Check) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_try_again)) }
            }
            if (state.canSaveAnyway) {
                OutlinedButton(onClick = { onEvent(AiSetupEvent.SaveAnyway) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_save_anyway))
                }
            }
        }
        TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (details) R.string.setup_hide_details else R.string.setup_details))
        }
        if (details) {
            Text(resources.aiErrorMessage(error, state.provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            aiErrorDetail(error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun DoneStep(state: AiSetupUiState) {
    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
    Title(stringResource(R.string.setup_done_title, state.modelName), stringResource(R.string.setup_done_body, state.modelName))
    state.backupName?.let { Text(stringResource(R.string.setup_done_backup, it), style = MaterialTheme.typography.bodyLarge) }
    when (state.outcome) {
        SetupOutcome.BUSY -> Text(stringResource(R.string.setup_done_busy, serviceName(state.provider)), style = MaterialTheme.typography.bodyLarge)
        SetupOutcome.UNCHECKED -> Text(stringResource(R.string.setup_done_unchecked), style = MaterialTheme.typography.bodyLarge)
        else -> Unit
    }
    Text(stringResource(R.string.setup_done_try), style = MaterialTheme.typography.bodyLarge)
}
