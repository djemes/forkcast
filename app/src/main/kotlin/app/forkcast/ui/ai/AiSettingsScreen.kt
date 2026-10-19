package app.forkcast.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.model.ModelPrice
import app.forkcast.core.model.ProviderId
import app.forkcast.ui.settings.SettingsScaffold
import sh.calvin.reorderable.ReorderableColumn
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AiSettingsRoute(
    onBack: () -> Unit,
    onEditProfile: (String?) -> Unit,
    onSetUpFreeGemini: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AiSettingsScreen(state, viewModel::onEvent, onBack, onEditProfile, onSetUpFreeGemini)
}

private val RETENTION_CHOICES = listOf(30, 90, 180, 365, null)

@Composable
fun AiSettingsScreen(
    state: AiSettingsUiState,
    onEvent: (AiSettingsEvent) -> Unit,
    onBack: () -> Unit,
    onEditProfile: (String?) -> Unit,
    onSetUpFreeGemini: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_ai), onBack = onBack) {
        if (!state.loaded) return@SettingsScaffold
        val s = state.settings
        SwitchRow(stringResource(R.string.ai_enabled), stringResource(R.string.ai_enabled_body), s.enabled) {
            onEvent(AiSettingsEvent.SetEnabled(it))
        }
        if (!s.enabled) return@SettingsScaffold
        HorizontalDivider()

        SectionTitle(R.string.ai_chain_title)
        Text(
            stringResource(R.string.ai_chain_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (state.profiles.isEmpty()) {
            Text(
                stringResource(R.string.ai_no_profiles),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            Button(onClick = onSetUpFreeGemini, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.ai_setup_free))
            }
        } else {
            ReorderableColumn(
                list = state.profiles,
                onSettle = { from, to -> onEvent(AiSettingsEvent.Move(from, to)) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { index, item, _ ->
                key(item.profile.id) {
                    ReorderableItem {
                        ProfileCard(
                            item = item,
                            test = state.tests[item.profile.id],
                            index = index,
                            count = state.profiles.size,
                            dragHandle = { name ->
                                IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                    Icon(painterResource(R.drawable.ic_drag_handle), stringResource(R.string.ai_drag_handle, name))
                                }
                            },
                            onEvent = onEvent,
                            onEdit = { onEditProfile(item.profile.id) },
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = { onEditProfile(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Text(stringResource(R.string.ai_add_profile), modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        PersonalContextField(s.personalContext) { onEvent(AiSettingsEvent.SetPersonalContext(it)) }
        SwitchRow(stringResource(R.string.ai_auto_accept), stringResource(R.string.ai_auto_accept_body), s.autoAccept) {
            onEvent(AiSettingsEvent.SetAutoAccept(it))
        }
        RetentionPicker(s.photoRetentionDays) { onEvent(AiSettingsEvent.SetRetention(it)) }
        HorizontalDivider()

        UsageSection(state.usage, s.priceOverrides, onEvent)
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun ProfileCard(
    item: AiProfileStatus,
    test: TestState?,
    index: Int,
    count: Int,
    dragHandle: @Composable (String) -> Unit,
    onEvent: (AiSettingsEvent) -> Unit,
    onEdit: () -> Unit,
) {
    val p = item.profile
    val res = LocalContext.current.resources
    val moveUp = stringResource(R.string.ai_move_up)
    val moveDown = stringResource(R.string.ai_move_down)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { onEvent(AiSettingsEvent.Move(index, index - 1)); true })
                    if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onEvent(AiSettingsEvent.Move(index, index + 1)); true })
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
            dragHandle(p.name)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 8.dp),
            ) {
                Text("${index + 1}. ${p.name}", style = MaterialTheme.typography.titleSmall)
                val tier = if (p.provider == ProviderId.GEMINI) " · " + stringResource(p.tierLabel.label()) else ""
                Text(p.model + tier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = p.enabled,
                onCheckedChange = { onEvent(AiSettingsEvent.SetProfileEnabled(p.id, it)) },
                modifier = Modifier.semantics { contentDescription = res.getString(R.string.ai_profile_enabled, p.name) },
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            val status = when {
                p.needsAttention -> stringResource(R.string.ai_needs_attention)
                !item.hasKey && p.provider != ProviderId.OPENAI_COMPAT -> stringResource(R.string.ai_no_key)
                (p.cooldownUntil ?: 0) > System.currentTimeMillis() -> stringResource(R.string.ai_cooling_down, formatTime(p.cooldownUntil!!))
                else -> null
            }
            if (status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_used_today, item.usedToday),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(AiSettingsEvent.Test(p.id)) }, enabled = test !is TestState.Running) {
                    Text(stringResource(if (test is TestState.Running) R.string.ai_testing else R.string.ai_test))
                }
            }
            when (test) {
                is TestState.Ok -> Text(
                    stringResource(R.string.ai_test_ok, test.report.model, test.report.latencyMs.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestState.Failed -> Text(
                    res.aiErrorMessage(test.error, p.provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

private fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
private fun PersonalContextField(saved: String, onChange: (String) -> Unit) {
    // Local copy so typing never fights the stored value.
    var text by rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (saved != text && text.isEmpty()) text = saved }
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500); onChange(text) },
            label = { Text(stringResource(R.string.ai_personal_context)) },
            placeholder = { Text(stringResource(R.string.ai_personal_context_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ai_personal_context_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RetentionPicker(days: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = days?.let { stringResource(R.string.ai_retention_days, it) } ?: stringResource(R.string.ai_retention_forever)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.ai_photo_retention), style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.ai_photo_retention)) },
            text = {
                Column {
                    for (choice in RETENTION_CHOICES) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = choice == days, role = Role.RadioButton) { onChange(choice); open = false },
                        ) {
                            RadioButton(selected = choice == days, onClick = null)
                            Text(
                                choice?.let { stringResource(R.string.ai_retention_days, it) } ?: stringResource(R.string.ai_retention_forever),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun UsageSection(usage: MonthUsage?, overrides: Map<String, ModelPrice>, onEvent: (AiSettingsEvent) -> Unit) {
    SectionTitle(R.string.ai_usage_title)
    val numbers = NumberFormat.getIntegerInstance()
    val money = NumberFormat.getCurrencyInstance(Locale.US)
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (usage == null || usage.models.isEmpty()) {
            Text(stringResource(R.string.ai_usage_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(
                    R.string.ai_usage_summary,
                    usage.estimates,
                    numbers.format(usage.inputTokens),
                    numbers.format(usage.outputTokens),
                    money.format(usage.costUsd),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            for (m in usage.models) {
                val cost = when {
                    m.costUsd == null -> stringResource(R.string.ai_usage_no_price)
                    m.costUsd == 0.0 && m.inputTokens > 0 -> stringResource(R.string.ai_usage_free)
                    else -> money.format(m.costUsd)
                }
                Text(
                    stringResource(R.string.ai_usage_model, m.model, m.requests, cost),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { editing = m.model }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
    editing?.let { model ->
        PriceDialog(model, overrides[model], onDismiss = { editing = null }) { price ->
            onEvent(AiSettingsEvent.SetPrice(model, price))
            editing = null
        }
    }
}

@Composable
private fun PriceDialog(model: String, current: ModelPrice?, onDismiss: () -> Unit, onSave: (ModelPrice?) -> Unit) {
    var input by remember { mutableStateOf(current?.inputPerM?.toString().orEmpty()) }
    var output by remember { mutableStateOf(current?.outputPerM?.toString().orEmpty()) }
    val inV = input.replace(',', '.').toDoubleOrNull()
    val outV = output.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_edit_price, model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    input, { input = it }, label = { Text(stringResource(R.string.ai_price_input)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    output, { output = it }, label = { Text(stringResource(R.string.ai_price_output)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (inV != null && outV != null) ModelPrice(inV, outV) else null) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
