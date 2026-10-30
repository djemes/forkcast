package app.forkcast.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.model.ModelPrice
import java.text.NumberFormat
import java.util.Locale

/**
 * Settings › AI › Advanced (roadmap 2.0 task 3.5): this month's usage with prices. The order's
 * arrows moved onto the cards, and adding a service by hand is the setup guide's own Advanced
 * path (owner, 2026-10-30).
 */
@Composable
internal fun AiAdvanced(state: AiSettingsUiState, onEvent: (AiSettingsEvent) -> Unit) {
    UsageSection(state.usage, state.settings.priceOverrides, onEvent)
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
                pluralStringResource(
                    R.plurals.ai_usage_summary,
                    usage.estimates,
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
                    pluralStringResource(R.plurals.ai_usage_model, m.requests, m.model, m.requests, cost),
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
