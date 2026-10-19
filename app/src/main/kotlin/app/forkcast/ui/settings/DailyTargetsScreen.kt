package app.forkcast.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.DailyValues
import app.forkcast.core.domain.NutrientTargets
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.NutrientUnit
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.today.formatNutrient
import app.forkcast.ui.today.nutrientName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** One editable target: what's in effect, what the default would be, and whether it's the user's. */
data class TargetRow(val key: NutrientKey, val amount: Double, val default: Double, val kind: TargetKind, val custom: Boolean)

data class DailyTargetsState(val units: UnitPrefs, val main: List<TargetRow>, val limits: List<TargetRow>, val micros: List<TargetRow>)

/**
 * Settings › Daily targets (SPEC §4.12: override any nutrient target). Calories use the same
 * override as Profile & goals' "Custom budget", so the two screens always agree.
 */
@HiltViewModel
class DailyTargetsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<DailyTargetsState?> = combine(
        profiles.observeDailyTargets(), profiles.activeGoal, profiles.overrides, settings.settings,
    ) { t, goal, overrides, s ->
        if (t == null || goal == null) return@combine null
        val defaults = NutrientTargets.defaults(t.budgetKcal, t.currentKg, goal.targetWeightKg)
        fun row(key: NutrientKey): TargetRow? {
            if (key == NutrientKey.ENERGY_KCAL) {
                return TargetRow(key, t.budgetKcal.toDouble(), t.plan.budgetKcal.toDouble(), TargetKind.LIMIT, t.isCustomBudget)
            }
            val effective = t.targets[key] ?: return null
            val def = defaults[key]
            return TargetRow(key, effective.amount, def?.amount ?: effective.amount, effective.kind, key in overrides)
        }
        DailyTargetsState(
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            main = MAIN.mapNotNull(::row),
            limits = LIMITS.mapNotNull(::row),
            micros = DailyValues.micronutrients.map { it.key }.mapNotNull(::row),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [amountInBaseUnit] is grams/mg/µg/kcal; null resets to the default. */
    fun set(key: NutrientKey, amountInBaseUnit: Double?) = viewModelScope.launch { profiles.setOverride(key, amountInBaseUnit) }

    private companion object {
        val MAIN = listOf(NutrientKey.ENERGY_KCAL, NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G)
        val LIMITS = listOf(NutrientKey.FIBER_G, NutrientKey.SAT_FAT_G, NutrientKey.ADDED_SUGARS_G, NutrientKey.SODIUM_MG)
    }
}

@Composable
fun DailyTargetsRoute(onBack: () -> Unit, viewModel: DailyTargetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DailyTargetsScreen(state, viewModel::set, onBack)
}

@Composable
fun DailyTargetsScreen(state: DailyTargetsState?, onSet: (NutrientKey, Double?) -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<TargetRow?>(null) }
    SettingsScaffold(title = stringResource(R.string.settings_daily_targets), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val f = UnitFormatter(s.units, LocalLocale.current.platformLocale)
        Text(stringResource(R.string.targets_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        Section(R.string.targets_section_main, s.main, f) { editing = it }
        Section(R.string.targets_section_limits, s.limits, f) { editing = it }
        Section(R.string.targets_section_micros, s.micros, f) { editing = it }
    }
    editing?.let { row -> TargetDialog(row, state?.units ?: UnitPrefs.US, onSave = { onSet(row.key, it); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun Section(title: Int, rows: List<TargetRow>, f: UnitFormatter, onEdit: (TargetRow) -> Unit) {
    if (rows.isEmpty()) return
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
    rows.forEach { row -> TargetRowView(row, f) { onEdit(row) } }
}

@Composable
private fun TargetRowView(row: TargetRow, f: UnitFormatter, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(nutrientName(row.key), style = MaterialTheme.typography.bodyLarge)
            // Goal vs limit is a word, not a color (CLAUDE.md accessibility).
            val kind = stringResource(if (row.kind == TargetKind.GOAL) R.string.targets_goal else R.string.targets_limit)
            val sub = if (row.custom) "$kind · " + stringResource(R.string.targets_default, formatNutrient(row.key, row.default, f)) else kind
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatNutrient(row.key, row.amount, f), style = MaterialTheme.typography.labelLarge)
            if (row.custom) {
                Text(
                    stringResource(R.string.targets_custom),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Number entry in the nutrient's own unit (energy in the user's kcal/kJ). */
@Composable
private fun TargetDialog(row: TargetRow, units: UnitPrefs, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
    val f = UnitFormatter(units, LocalLocale.current.platformLocale)
    val unit = when (row.key.unit) {
        NutrientUnit.KCAL -> f.energyUnitLabel
        NutrientUnit.G -> "g"
        NutrientUnit.MG -> "mg"
        NutrientUnit.UG -> "µg"
    }
    val toShown: (Double) -> Double = { if (row.key.unit == NutrientUnit.KCAL) f.energyValue(it) else it }
    val toBase: (Double) -> Double = { if (row.key.unit == NutrientUnit.KCAL) f.energyToKcal(it) else it }
    var value by remember { mutableStateOf<Double?>(toShown(row.amount)) }
    val name = nutrientName(row.key)
    val valid = value?.let { it > 0 && it < 1_000_000 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.targets_edit, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.targets_kind_line, name,
                        stringResource(if (row.kind == TargetKind.GOAL) R.string.targets_kind_goal else R.string.targets_kind_limit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                NumberField(
                    initial = UnitFormatter.trimNumber(toShown(row.amount), if (row.amount < 10) 1 else 0),
                    label = stringResource(R.string.targets_amount),
                    onValue = { value = it },
                    suffix = unit,
                    isError = !valid,
                )
                if (!valid) Text(stringResource(R.string.targets_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.targets_default, formatNutrient(row.key, row.default, f)), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { value?.let { onSave(toBase(it)) } }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (row.custom) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.targets_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
