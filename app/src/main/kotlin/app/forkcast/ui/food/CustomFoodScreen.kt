package app.forkcast.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.NutrientUnit
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.today.nutrientName

@Composable
fun CustomFoodRoute(
    onBack: () -> Unit,
    onSaved: (foodId: String, isNew: Boolean) -> Unit,
    onDeleted: () -> Unit,
    viewModel: CustomFoodViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is CustomFoodEffect.Saved -> onSaved(it.foodId, it.isNew)
                CustomFoodEffect.Deleted -> onDeleted()
            }
        }
    }
    if (form.loaded) CustomFoodScreen(form, viewModel, onBack)
}

@Composable
private fun CustomFoodScreen(form: CustomFoodForm, vm: CustomFoodViewModel, onBack: () -> Unit) {
    val f = UnitFormatter(form.units)
    SettingsScaffold(
        title = stringResource(if (form.existing == null) R.string.custom_food_title else R.string.custom_food_edit_title),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            if (form.fromLabel) {
                Text(stringResource(R.string.label_review_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            TextInput(form.name, stringResource(R.string.custom_name), isError = form.name.isBlank(), supporting = stringResource(R.string.custom_required)) {
                vm.update { copy(name = it) }
            }
            TextInput(form.brand, stringResource(R.string.custom_brand)) { vm.update { copy(brand = it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.servingLabel,
                    onValueChange = { v -> vm.update { copy(servingLabel = v) } },
                    label = { Text(stringResource(R.string.custom_serving_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                NumberField(
                    initial = form.servingGrams?.let { UnitFormatter.trimNumber(it, 1) }.orEmpty(),
                    label = stringResource(R.string.custom_serving_grams),
                    suffix = "g",
                    onValue = { v -> vm.update { copy(servingGrams = v) } },
                    key = "grams-${form.seed}",
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = form.barcode,
                onValueChange = { v -> vm.update { copy(barcode = v.filter { it.isDigit() }) } },
                label = { Text(stringResource(R.string.custom_barcode)) },
                isError = form.barcodeInvalid,
                supportingText = if (form.barcodeInvalid) ({ Text(stringResource(R.string.custom_barcode_invalid)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.custom_per_serving),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            NutrientKey.entries.filter { it != NutrientKey.WATER_G }.forEach { key ->
                val isEnergy = key == NutrientKey.ENERGY_KCAL
                val unit = when (key.unit) {
                    NutrientUnit.KCAL -> f.energyUnitLabel
                    NutrientUnit.G -> "g"
                    NutrientUnit.MG -> "mg"
                    NutrientUnit.UG -> "µg"
                }
                NumberField(
                    initial = form.perServing[key]?.let {
                        UnitFormatter.trimNumber(if (isEnergy) f.energyValue(it) else it, if (isEnergy) 0 else 2)
                    }.orEmpty(),
                    label = nutrientName(key) + if (isEnergy) " *" else "",
                    suffix = unit,
                    onValue = { v -> vm.setNutrient(key, v?.let { if (isEnergy) f.energyToKcal(it) else it }) },
                    key = "${key.name}-${form.seed}",
                )
            }
            Button(
                onClick = vm::save,
                enabled = form.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) { Text(stringResource(R.string.action_save)) }
            if (form.existing != null) {
                OutlinedButton(onClick = vm::delete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.custom_delete))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

@Composable
private fun TextInput(value: String, label: String, isError: Boolean = false, supporting: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = isError,
        supportingText = if (isError && supporting != null) ({ Text(supporting) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}
