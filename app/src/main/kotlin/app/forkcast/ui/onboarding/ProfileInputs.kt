package app.forkcast.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.UnitConversions
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.EnergyUnit
import app.forkcast.core.model.HeightUnit
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WaterUnit
import app.forkcast.core.model.WeightUnit
import app.forkcast.ui.format.UnitFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Input pieces shared by onboarding and Settings → Profile & goals.

@Composable
fun UnitsInput(units: UnitPrefs, onChange: (UnitPrefs) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SegmentRow(
            label = stringResource(R.string.units_weight),
            options = listOf(WeightUnit.LB to R.string.unit_lb, WeightUnit.KG to R.string.unit_kg),
            selected = units.weight,
            // Water follows weight: lb → fl oz, kg → ml.
            onSelect = { onChange(units.copy(weight = it, water = if (it == WeightUnit.LB) WaterUnit.FL_OZ else WaterUnit.ML)) },
        )
        SegmentRow(
            label = stringResource(R.string.units_height),
            options = listOf(HeightUnit.FT_IN to R.string.unit_ft_in, HeightUnit.CM to R.string.unit_cm),
            selected = units.height,
            onSelect = { onChange(units.copy(height = it)) },
        )
        SegmentRow(
            label = stringResource(R.string.units_energy),
            options = listOf(EnergyUnit.KCAL to R.string.unit_kcal, EnergyUnit.KJ to R.string.unit_kj),
            selected = units.energy,
            onSelect = { onChange(units.copy(energy = it)) },
        )
    }
}

@Composable
fun <T> SegmentRow(label: String?, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(text)) }
            }
        }
    }
}

data class Choice<T>(val value: T, val label: String, val description: String? = null)

/** Big tappable options, one per row (DESIGN.md §7). */
@Composable
fun <T> ChoiceList(choices: List<Choice<T>>, selected: T?, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c ->
            val isSelected = c.value == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(c.value) },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(c.label, style = MaterialTheme.typography.titleMedium)
                        c.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun activityChoices(): List<Choice<ActivityLevel>> = listOf(
    Choice(ActivityLevel.SEDENTARY, stringResource(R.string.activity_sedentary), stringResource(R.string.activity_sedentary_desc)),
    Choice(ActivityLevel.LIGHT, stringResource(R.string.activity_light), stringResource(R.string.activity_light_desc)),
    Choice(ActivityLevel.MODERATE, stringResource(R.string.activity_moderate), stringResource(R.string.activity_moderate_desc)),
    Choice(ActivityLevel.VERY_ACTIVE, stringResource(R.string.activity_very), stringResource(R.string.activity_very_desc)),
    Choice(ActivityLevel.EXTRA_ACTIVE, stringResource(R.string.activity_extra), stringResource(R.string.activity_extra_desc)),
)

@Composable
fun activityLabel(level: ActivityLevel): String = activityChoices().first { it.value == level }.label

@Composable
fun formatDate(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale).format(date)

/** Shows the chosen date and opens the Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    date: LocalDate?,
    placeholder: String,
    initialIfEmpty: LocalDate,
    onChange: (LocalDate) -> Unit,
    selectableFrom: LocalDate? = null,
    selectableTo: LocalDate? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(date?.let { formatDate(it) } ?: placeholder, style = MaterialTheme.typography.titleMedium)
    }
    if (open) {
        val start = date ?: initialIfEmpty
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return (selectableFrom == null || !d.isBefore(selectableFrom)) && (selectableTo == null || !d.isAfter(selectableTo))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** A decimal number field that keeps the user's own text while they type. */
@Composable
fun NumberField(
    initial: String,
    label: String,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    supporting: String? = null,
    key: Any? = null,
) {
    var text by rememberSaveable(key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValue(UnitFormatter.parseNumber(it))
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun HeightInput(heightCm: Double?, units: UnitPrefs, onChange: (Double?) -> Unit) {
    when (units.height) {
        HeightUnit.CM -> NumberField(
            initial = heightCm?.let { UnitFormatter.trimNumber(it, 0) }.orEmpty(),
            label = stringResource(R.string.field_cm),
            suffix = stringResource(R.string.unit_cm),
            onValue = onChange,
            key = "cm",
        )
        HeightUnit.FT_IN -> {
            val (ft0, in0) = heightCm?.let { UnitConversions.cmToFeetInches(it) } ?: (null to null)
            var ft by rememberSaveable { mutableStateOf(ft0?.toDouble()) }
            var inch by rememberSaveable { mutableStateOf(in0?.toDouble()) }
            fun push() {
                val f = ft
                onChange(if (f == null) null else UnitConversions.feetInchesToCm(f.toInt(), inch ?: 0.0))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(ft0?.toString().orEmpty(), stringResource(R.string.field_feet), { ft = it; push() },
                    Modifier.weight(1f), suffix = "ft", key = "ft")
                NumberField(in0?.toString().orEmpty(), stringResource(R.string.field_inches), { inch = it; push() },
                    Modifier.weight(1f), suffix = "in", key = "in")
            }
        }
    }
}

@Composable
fun WeightInput(kg: Double?, units: UnitPrefs, onChange: (Double?) -> Unit, supporting: String? = null, key: String = "w") {
    val f = UnitFormatter(units)
    NumberField(
        initial = kg?.let { f.weightInput(it) }.orEmpty(),
        label = stringResource(R.string.field_weight, f.weightUnitLabel),
        suffix = f.weightUnitLabel,
        onValue = { onChange(it?.let(f::weightToKg)) },
        supporting = supporting,
        key = key + units.weight,
    )
}
