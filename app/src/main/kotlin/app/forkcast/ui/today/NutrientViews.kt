package app.forkcast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.NutrientUnit
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme

@Composable
fun nutrientName(key: NutrientKey): String = stringResource(
    when (key) {
        NutrientKey.ENERGY_KCAL -> R.string.n_energy
        NutrientKey.PROTEIN_G -> R.string.n_protein
        NutrientKey.CARBS_G -> R.string.n_carbs
        NutrientKey.FAT_G -> R.string.n_fat
        NutrientKey.SAT_FAT_G -> R.string.n_sat_fat
        NutrientKey.TRANS_FAT_G -> R.string.n_trans_fat
        NutrientKey.MONO_FAT_G -> R.string.n_mono_fat
        NutrientKey.POLY_FAT_G -> R.string.n_poly_fat
        NutrientKey.CHOLESTEROL_MG -> R.string.n_cholesterol
        NutrientKey.FIBER_G -> R.string.n_fiber
        NutrientKey.SUGARS_G -> R.string.n_sugars
        NutrientKey.ADDED_SUGARS_G -> R.string.n_added_sugars
        NutrientKey.SODIUM_MG -> R.string.n_sodium
        NutrientKey.POTASSIUM_MG -> R.string.n_potassium
        NutrientKey.CALCIUM_MG -> R.string.n_calcium
        NutrientKey.IRON_MG -> R.string.n_iron
        NutrientKey.MAGNESIUM_MG -> R.string.n_magnesium
        NutrientKey.VITAMIN_A_UG -> R.string.n_vitamin_a
        NutrientKey.VITAMIN_C_MG -> R.string.n_vitamin_c
        NutrientKey.VITAMIN_D_UG -> R.string.n_vitamin_d
        NutrientKey.VITAMIN_B12_UG -> R.string.n_vitamin_b12
        NutrientKey.CAFFEINE_MG -> R.string.n_caffeine
        NutrientKey.ALCOHOL_G -> R.string.n_alcohol
        NutrientKey.WATER_G -> R.string.n_water
    },
)

/** Formats an amount in the nutrient's own unit ("32 g", "410 mg", "2.4 µg", energy in the user's unit). */
fun formatNutrient(key: NutrientKey, amount: Double, f: UnitFormatter): String = when (key.unit) {
    NutrientUnit.KCAL -> f.energy(amount)
    NutrientUnit.G -> f.grams(amount)
    NutrientUnit.MG -> "${f.number(amount, if (amount < 10) 1 else 0)} mg"
    NutrientUnit.UG -> "${f.number(amount, if (amount < 10) 1 else 0)} µg"
}

private fun numberOnly(key: NutrientKey, amount: Double, f: UnitFormatter): String =
    if (key.unit == NutrientUnit.G) f.number(amount, if (amount < 10) 1 else 0) else formatNutrient(key, amount, f)

/** Protein / carbs / fat bars with eaten / target (DESIGN.md §5). Tap opens the full panel. */
@Composable
fun MacroBars(totals: DayTotals, targets: DailyTargets, f: UnitFormatter, onOpenPanel: () -> Unit) {
    val c = ForkcastTheme.colors
    val openLabel = stringResource(R.string.nutrients_open)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenPanel)
            .padding(vertical = 4.dp),
    ) {
        listOf(
            Triple(NutrientKey.PROTEIN_G, c.protein, R.string.macro_protein),
            Triple(NutrientKey.CARBS_G, c.carbs, R.string.macro_carbs),
            Triple(NutrientKey.FAT_G, c.fat, R.string.macro_fat),
        ).forEach { (key, color, label) ->
            val eaten = totals[key]
            val target = targets.targets[key]?.amount ?: 0.0
            NutrientBar(stringResource(label), eaten, target, color, stringResource(R.string.macro_progress, f.number(eaten), "${f.number(target)} g"))
        }
    }
}

@Composable
private fun NutrientBar(label: String, eaten: Double, target: Double, color: Color, valueText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
        stateDescription = valueText
    }) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
        LinearProgressIndicator(
            progress = { if (target > 0) (eaten / target).toFloat().coerceIn(0f, 1f) else 0f },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        Text(valueText, style = MaterialTheme.typography.labelLarge)
    }
}

/** Every tracked nutrient for the day vs its target. Goals vs limits are labeled, not just colored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutrientPanelSheet(totals: DayTotals, targets: DailyTargets, f: UnitFormatter, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.nutrients_title), style = MaterialTheme.typography.titleLarge)
            NutrientKey.entries.forEach { key ->
                val eaten = totals[key]
                val target = targets.targets[key]
                if (target == null && eaten == 0.0) return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nutrientName(key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    val right = when {
                        target == null -> "${formatNutrient(key, eaten, f)} · ${stringResource(R.string.nutrient_no_target)}"
                        target.kind == TargetKind.LIMIT ->
                            "${numberOnly(key, eaten, f)} / ${formatNutrient(key, target.amount, f)} ${stringResource(R.string.nutrient_limit)}"
                        else -> "${numberOnly(key, eaten, f)} / ${formatNutrient(key, target.amount, f)}"
                    }
                    Text(right, style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
