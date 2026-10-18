package app.forkcast.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.domain.GoalPace
import app.forkcast.core.domain.GoalPlanner
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.BudgetSummary
import app.forkcast.ui.onboarding.Choice
import app.forkcast.ui.onboarding.ChoiceList
import app.forkcast.ui.onboarding.DateField
import app.forkcast.ui.onboarding.HeightInput
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.onboarding.PaceMode
import app.forkcast.ui.onboarding.SegmentRow
import app.forkcast.ui.onboarding.UnitsInput
import app.forkcast.ui.onboarding.WeightInput
import app.forkcast.ui.onboarding.activityChoices
import app.forkcast.ui.onboarding.activityLabel
import app.forkcast.ui.onboarding.formatDate
import app.forkcast.ui.onboarding.pacePresetsKg
import java.time.LocalDate

private enum class EditField { UNITS, SEX, BIRTH, HEIGHT, ACTIVITY, GOAL, BUDGET }

@Composable
fun ProfileGoalsRoute(onBack: () -> Unit, viewModel: ProfileGoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.settings_profile_goals), onBack = onBack) {
        state?.let { ProfileGoalsContent(it, viewModel) }
    }
}

@Composable
private fun ProfileGoalsContent(s: ProfileGoalsState, vm: ProfileGoalsViewModel) {
    val f = UnitFormatter(s.units)
    var editing by rememberSaveable { mutableStateOf<EditField?>(null) }
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)

    @Composable
    fun Row(title: Int, value: String, field: EditField?) = ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(value) },
        colors = colors,
        modifier = if (field != null) Modifier.clickable { editing = field } else Modifier,
    )

    @Composable
    fun Section(title: Int) = Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
            .semantics { heading() },
    )

    Column(Modifier.padding(horizontal = 16.dp)) {
        BudgetSummary(s.targets, s.units, s.profile.sex, s.profile.heightCm, s.profile.activityLevel)
    }
    Row(
        R.string.profile_custom_budget,
        if (s.targets.isCustomBudget) "${f.energy(s.targets.budgetKcal.toDouble())} · ${stringResource(R.string.custom_chip)}"
        else stringResource(R.string.profile_custom_budget_summary, f.energy(s.targets.plan.budgetKcal.toDouble())),
        EditField.BUDGET,
    )

    Section(R.string.profile_section_goal)
    Row(R.string.profile_goal_type, goalLabel(s.goal.goalType), EditField.GOAL)
    if (s.goal.goalType != GoalType.MAINTAIN) {
        Row(R.string.profile_target, f.weight(s.goal.targetWeightKg), EditField.GOAL)
        s.targets.plan.pace?.let { p ->
            Row(
                R.string.profile_pace,
                "${stringResource(R.string.pace_per_week, f.weight(p.kgPerWeek))} · ${formatDate(p.targetDate)}",
                EditField.GOAL,
            )
        }
    }
    Row(R.string.profile_current_weight, stringResource(R.string.profile_current_weight_summary, f.weight(s.currentKg)), null)

    Section(R.string.profile_section_body)
    Row(R.string.profile_units, "${f.weightUnitLabel} · ${if (s.units.height.name == "CM") "cm" else "ft/in"} · ${f.energyUnitLabel}", EditField.UNITS)
    Row(R.string.profile_sex, stringResource(if (s.profile.sex == Sex.MALE) R.string.sex_male else R.string.sex_female), EditField.SEX)
    Row(R.string.profile_birth, formatDate(s.profile.birthDate), EditField.BIRTH)
    Row(R.string.profile_height, f.height(s.profile.heightCm), EditField.HEIGHT)
    Row(R.string.profile_activity, activityLabel(s.profile.activityLevel), EditField.ACTIVITY)

    editing?.let { field -> EditDialog(field, s, vm) { editing = null } }
}

@Composable
private fun goalLabel(type: GoalType) = stringResource(
    when (type) {
        GoalType.LOSE -> R.string.goal_lose
        GoalType.MAINTAIN -> R.string.goal_maintain
        GoalType.GAIN -> R.string.goal_gain
    },
)

@Composable
private fun EditDialog(field: EditField, s: ProfileGoalsState, vm: ProfileGoalsViewModel, close: () -> Unit) {
    var units by remember { mutableStateOf(s.units) }
    var sex by remember { mutableStateOf(s.profile.sex) }
    var birth by remember { mutableStateOf(s.profile.birthDate) }
    var height by remember { mutableStateOf<Double?>(s.profile.heightCm) }
    var activity by remember { mutableStateOf(s.profile.activityLevel) }
    var budget by remember { mutableStateOf<Double?>(UnitFormatter(s.units).energyValue(s.targets.budgetKcal.toDouble())) }
    val goal = remember { GoalEditState(s) }

    val title = stringResource(
        when (field) {
            EditField.UNITS -> R.string.profile_units
            EditField.SEX -> R.string.profile_sex
            EditField.BIRTH -> R.string.profile_birth
            EditField.HEIGHT -> R.string.profile_height
            EditField.ACTIVITY -> R.string.profile_activity
            EditField.GOAL -> R.string.profile_section_goal
            EditField.BUDGET -> R.string.profile_custom_budget
        },
    )
    val valid = when (field) {
        EditField.HEIGHT -> height?.let { it in 90.0..250.0 } == true
        EditField.BUDGET -> budget?.let { it > 0 } == true
        EditField.GOAL -> goal.isValid(s.currentKg, s.today)
        else -> true
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (field) {
                    EditField.UNITS -> UnitsInput(units) { units = it }
                    EditField.SEX -> ChoiceList(
                        listOf(Choice(Sex.MALE, stringResource(R.string.sex_male)), Choice(Sex.FEMALE, stringResource(R.string.sex_female))),
                        sex,
                    ) { sex = it }
                    EditField.BIRTH -> DateField(birth, "", birth, { birth = it }, selectableTo = s.today.minusYears(13))
                    EditField.HEIGHT -> HeightInput(height, s.units) { height = it }
                    EditField.ACTIVITY -> ChoiceList(activityChoices(), activity) { activity = it }
                    EditField.GOAL -> GoalEditor(goal, s)
                    EditField.BUDGET -> {
                        val f = UnitFormatter(s.units)
                        NumberField(
                            initial = UnitFormatter.trimNumber(f.energyValue(s.targets.budgetKcal.toDouble()), 0),
                            label = stringResource(R.string.profile_custom_budget_field, f.energyUnitLabel),
                            onValue = { budget = it },
                        )
                        // Live re-check of guardrails for the typed budget.
                        budget?.let { b ->
                            val preview = DailyTargetsCalculator.compute(
                                s.profile, s.goal, s.currentKg, s.today,
                                mapOf(app.forkcast.core.model.NutrientKey.ENERGY_KCAL to f.energyToKcal(b)),
                            )
                            app.forkcast.ui.onboarding.Guardrails(preview.guardrails, f)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                when (field) {
                    EditField.UNITS -> vm.setUnits(units)
                    EditField.SEX -> vm.updateProfile { it.copy(sex = sex) }
                    EditField.BIRTH -> vm.updateProfile { it.copy(birthDate = birth) }
                    EditField.HEIGHT -> height?.let { h -> vm.updateProfile { it.copy(heightCm = h) } }
                    EditField.ACTIVITY -> vm.updateProfile { it.copy(activityLevel = activity) }
                    EditField.GOAL -> goal.save(vm, s)
                    EditField.BUDGET -> budget?.let { vm.setCustomBudget(UnitFormatter(s.units).energyToKcal(it)) }
                }
                close()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            if (field == EditField.BUDGET && s.targets.isCustomBudget) {
                TextButton(onClick = { vm.setCustomBudget(null); close() }) { Text(stringResource(R.string.profile_use_calculated)) }
            } else {
                TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Editable copy of the goal; target date and weekly rate stay in sync like in onboarding. */
private class GoalEditState(s: ProfileGoalsState) {
    var type by mutableStateOf(s.goal.goalType)
    var targetKg by mutableStateOf<Double?>(s.goal.targetWeightKg)
    var mode by mutableStateOf(if (s.goal.targetDate != null && s.goal.weeklyRateKg == null) PaceMode.TARGET_DATE else PaceMode.WEEKLY_RATE)
    var rateKg by mutableStateOf<Double?>(s.targets.plan.pace?.kgPerWeek ?: s.goal.weeklyRateKg)
    var date by mutableStateOf<LocalDate?>(s.targets.plan.pace?.targetDate ?: s.goal.targetDate)

    fun pace(): GoalPace? = when (mode) {
        PaceMode.WEEKLY_RATE -> rateKg?.takeIf { it > 0 }?.let { GoalPace.ByWeeklyRate(it) }
        PaceMode.TARGET_DATE -> date?.let { GoalPace.ByDate(it) }
    }

    fun isValid(currentKg: Double, today: LocalDate): Boolean {
        if (type == GoalType.MAINTAIN) return true
        val t = targetKg ?: return false
        val directionOk = if (type == GoalType.LOSE) t < currentKg else t > currentKg
        return directionOk && pace()?.let { GoalPlanner.resolvePace(currentKg, t, it, today) } != null
    }

    fun save(vm: ProfileGoalsViewModel, s: ProfileGoalsState) {
        val p = pace()
        vm.updateGoal(
            type,
            targetKg ?: s.currentKg,
            (p as? GoalPace.ByWeeklyRate)?.kgPerWeek,
            (p as? GoalPace.ByDate)?.targetDate,
        )
    }
}

@Composable
private fun GoalEditor(g: GoalEditState, s: ProfileGoalsState) {
    val f = UnitFormatter(s.units)
    ChoiceList(
        listOf(
            Choice(GoalType.LOSE, stringResource(R.string.goal_lose)),
            Choice(GoalType.MAINTAIN, stringResource(R.string.goal_maintain)),
            Choice(GoalType.GAIN, stringResource(R.string.goal_gain)),
        ),
        g.type,
    ) { g.type = it }
    if (g.type == GoalType.MAINTAIN) return
    WeightInput(g.targetKg, s.units, { g.targetKg = it }, key = "goal-target")
    SegmentRow(
        label = null,
        options = listOf(PaceMode.WEEKLY_RATE to R.string.pace_by_rate, PaceMode.TARGET_DATE to R.string.pace_by_date),
        selected = g.mode,
        onSelect = { mode ->
            // Carry the pace across so rate and date stay in sync.
            val t = g.targetKg
            if (t != null) g.pace()?.let { GoalPlanner.resolvePace(s.currentKg, t, it, s.today) }?.let {
                g.rateKg = it.kgPerWeek
                g.date = it.targetDate
            }
            g.mode = mode
        },
    )
    when (g.mode) {
        PaceMode.WEEKLY_RATE -> {
            val presets = pacePresetsKg(s.units, g.type)
            val current = g.rateKg
            val choices = presets.map { Choice(it, stringResource(R.string.pace_per_week, f.weight(it))) }.toMutableList()
            if (current != null && presets.none { kotlin.math.abs(it - current) < 1e-6 }) {
                choices += Choice(current, stringResource(R.string.pace_custom_rate, f.weight(current)))
            }
            ChoiceList(choices, choices.firstOrNull { current != null && kotlin.math.abs(it.value - current) < 1e-6 }?.value) { g.rateKg = it }
        }
        PaceMode.TARGET_DATE -> DateField(g.date, "", s.today.plusMonths(4), { g.date = it }, selectableFrom = s.today.plusDays(7))
    }
    val t = g.targetKg
    val resolved = t?.let { g.pace()?.let { p -> GoalPlanner.resolvePace(s.currentKg, t, p, s.today) } }
    resolved?.let {
        Text(
            if (g.mode == PaceMode.WEEKLY_RATE) stringResource(R.string.pace_reach_on, f.weight(t), formatDate(it.targetDate))
            else stringResource(R.string.pace_rate_needed, f.weight(it.kgPerWeek)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
