package app.forkcast.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.forkcast.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** "How much did you eat?" presets (owner, 2026-10-29): fractions and multipliers in one row. */
val PORTIONS = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0)

/** The preset [ratio] is (within rounding of the amount field), or null for any other amount. */
fun presetPortion(ratio: Double): Double? = PORTIONS.firstOrNull { abs(it - ratio) < 0.005 }

/**
 * ¼ ½ ¾ 1× 1½× 2× 3× and Custom (a percentage) in one scrolling row, shared by the AI review and
 * the entry edit sheet. [portion] null: nothing selected (the amount is in another serving).
 * [percentLabel] names what the percentage is of.
 */
@Composable
fun PortionChips(portion: Double?, @StringRes percentLabel: Int, onPick: (Double) -> Unit, modifier: Modifier = Modifier) {
    var custom by remember { mutableStateOf(false) }
    val preset = portion?.let(::presetPortion)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.horizontalScroll(rememberScrollState())) {
        PORTIONS.forEach { p ->
            FilterChip(selected = preset == p, onClick = { onPick(p) }, label = { Text(portionLabel(p)) })
        }
        val isCustom = portion != null && preset == null
        FilterChip(
            selected = isCustom,
            onClick = { custom = true },
            label = {
                Text(if (isCustom) stringResource(R.string.portion_custom_value, (portion!! * 100).roundToInt()) else stringResource(R.string.portion_custom))
            },
        )
    }
    if (custom) {
        var text by rememberSaveable { mutableStateOf(((portion ?: 1.0) * 100).roundToInt().toString()) }
        val value = text.toIntOrNull()?.takeIf { it in 1..999 }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(stringResource(R.string.portion_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(percentLabel)) },
                    suffix = { Text("%") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(onClick = { value?.let { onPick(it / 100.0) }; custom = false }, enabled = value != null) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

fun portionLabel(p: Double): String = when (presetPortion(p)) {
    0.25 -> "¼"
    0.5 -> "½"
    0.75 -> "¾"
    1.0 -> "1×"
    1.5 -> "1½×"
    2.0 -> "2×"
    3.0 -> "3×"
    else -> "${(p * 100).roundToInt()}%"
}
