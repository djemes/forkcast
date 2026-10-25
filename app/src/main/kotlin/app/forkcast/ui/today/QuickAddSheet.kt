package app.forkcast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.forkcast.R
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField

/** Just calories, optionally protein/carbs/fat, with a label (SPEC §4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    mealName: String,
    f: UnitFormatter,
    onAdd: (label: String, kcal: Double, protein: Double?, carbs: Double?, fat: Double?, eatenMinute: Int?) -> Unit,
    onDismiss: () -> Unit,
    /** Shown in the time field until changed (now on today, else the meal's typical time). */
    defaultMinute: Int = 12 * 60,
    /** Now, on today. */
    latestMinute: Int? = null,
) {
    var minute by rememberSaveable { mutableStateOf<Int?>(null) }
    val defaultLabel = stringResource(R.string.quick_add_default_label)
    var label by rememberSaveable { mutableStateOf("") }
    var energy by rememberSaveable { mutableStateOf<Double?>(null) }
    var protein by rememberSaveable { mutableStateOf<Double?>(null) }
    var carbs by rememberSaveable { mutableStateOf<Double?>(null) }
    var fat by rememberSaveable { mutableStateOf<Double?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.quick_add_title, mealName), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            NumberField("", stringResource(R.string.quick_add_energy, f.energyUnitLabel), { energy = it }, suffix = f.energyUnitLabel, key = "qa-kcal")
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.quick_add_label)) },
                placeholder = { Text(defaultLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("", stringResource(R.string.quick_add_protein), { protein = it }, Modifier.weight(1f), key = "qa-p")
                NumberField("", stringResource(R.string.quick_add_carbs), { carbs = it }, Modifier.weight(1f), key = "qa-c")
                NumberField("", stringResource(R.string.quick_add_fat), { fat = it }, Modifier.weight(1f), key = "qa-f")
            }
            app.forkcast.ui.common.MealTimeField(minute ?: defaultMinute, onChange = { minute = it }, latestMinute = latestMinute)
            Button(
                onClick = {
                    energy?.let { onAdd(label.trim().ifEmpty { defaultLabel }, f.energyToKcal(it), protein, carbs, fat, minute) }
                },
                enabled = (energy ?: 0.0) > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.food_add_to, mealName)) }
        }
    }
}
