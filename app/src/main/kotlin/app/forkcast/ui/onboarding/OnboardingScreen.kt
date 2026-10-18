package app.forkcast.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.domain.GoalPace
import app.forkcast.core.domain.GoalPlanner
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightUnit
import app.forkcast.ui.format.UnitFormatter
import kotlin.math.abs

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    OnboardingScreen(state, viewModel::onEvent)
}

@Composable
fun OnboardingScreen(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    BackHandler(enabled = state.stepIndex > 0) { onEvent(OnboardingEvent.Back) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                if (state.stepIndex > 0) {
                    IconButton(onClick = { onEvent(OnboardingEvent.Back) }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                val progressLabel = stringResource(R.string.onboarding_step_of, state.stepIndex + 1, state.stepCount)
                LinearProgressIndicator(
                    progress = { (state.stepIndex + 1f) / state.stepCount },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                        .semantics { contentDescription = progressLabel },
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                StepContent(state, onEvent)
            }
            val last = state.step == OnboardingStep.RESULT
            Button(
                onClick = { onEvent(if (last) OnboardingEvent.Finish else OnboardingEvent.Next) },
                enabled = state.canContinue && !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(if (last) R.string.action_start else R.string.action_continue)) }
        }
    }
}

@Composable
private fun Title(text: String, body: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun StepContent(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    val d = state.draft
    val f = UnitFormatter(d.units)
    when (state.step) {
        OnboardingStep.UNITS -> {
            Title(stringResource(R.string.onb_units_title), stringResource(R.string.onb_units_body))
            UnitsInput(d.units) { onEvent(OnboardingEvent.Units(it)) }
        }
        OnboardingStep.SEX -> {
            Title(stringResource(R.string.onb_sex_title), stringResource(R.string.onb_sex_body))
            ChoiceList(
                listOf(Choice(Sex.MALE, stringResource(R.string.sex_male)), Choice(Sex.FEMALE, stringResource(R.string.sex_female))),
                d.sex,
            ) { onEvent(OnboardingEvent.SexChosen(it)) }
        }
        OnboardingStep.BIRTH_DATE -> {
            Title(stringResource(R.string.onb_birth_title), stringResource(R.string.onb_birth_body))
            DateField(
                date = d.birth(),
                placeholder = stringResource(R.string.onb_birth_pick),
                initialIfEmpty = state.today.minusYears(35),
                selectableTo = state.today.minusYears(OnboardingViewModel.MIN_AGE),
                selectableFrom = state.today.minusYears(120),
                onChange = { onEvent(OnboardingEvent.BirthDate(it)) },
            )
        }
        OnboardingStep.HEIGHT -> {
            Title(stringResource(R.string.onb_height_title))
            HeightInput(d.heightCm, d.units) { onEvent(OnboardingEvent.HeightCm(it)) }
        }
        OnboardingStep.WEIGHT -> {
            Title(stringResource(R.string.onb_weight_title), stringResource(R.string.onb_weight_body))
            WeightInput(d.weightKg, d.units, { onEvent(OnboardingEvent.WeightKg(it)) })
        }
        OnboardingStep.ACTIVITY -> {
            Title(stringResource(R.string.onb_activity_title), stringResource(R.string.onb_activity_body))
            ChoiceList(activityChoices(), d.activity) { onEvent(OnboardingEvent.Activity(it)) }
        }
        OnboardingStep.GOAL -> {
            Title(stringResource(R.string.onb_goal_title))
            ChoiceList(
                listOf(
                    Choice(GoalType.LOSE, stringResource(R.string.goal_lose)),
                    Choice(GoalType.MAINTAIN, stringResource(R.string.goal_maintain)),
                    Choice(GoalType.GAIN, stringResource(R.string.goal_gain)),
                ),
                d.goalType,
            ) { onEvent(OnboardingEvent.GoalChosen(it)) }
        }
        OnboardingStep.TARGET_WEIGHT -> {
            Title(stringResource(R.string.onb_target_title))
            val current = d.weightKg?.let { f.weight(it) }.orEmpty()
            WeightInput(
                d.targetKg, d.units, { onEvent(OnboardingEvent.TargetKg(it)) },
                supporting = stringResource(
                    if (d.goalType == GoalType.GAIN) R.string.onb_target_gain_hint else R.string.onb_target_lose_hint, current,
                ),
                key = "target",
            )
        }
        OnboardingStep.PACE -> {
            Title(stringResource(R.string.onb_pace_title), stringResource(R.string.onb_pace_body))
            PaceInput(state, onEvent)
            state.preview?.let { Guardrails(it.guardrails, f) }
        }
        OnboardingStep.RESULT -> {
            Title(stringResource(R.string.onb_result_title))
            state.preview?.let { p ->
                BudgetSummary(p, d.units, d.sex ?: Sex.MALE, d.heightCm ?: 0.0, d.activity ?: return@let)
            }
        }
    }
}

/** Preset weekly paces in the user's unit, stored as kg. */
fun pacePresetsKg(units: UnitPrefs, goalType: GoalType?): List<Double> {
    val lb = units.weight == WeightUnit.LB
    val lose = if (lb) listOf(0.5, 1.0, 1.5, 2.0).map { it * 0.45359237 } else listOf(0.25, 0.5, 0.75, 1.0)
    val gain = if (lb) listOf(0.25, 0.5, 1.0).map { it * 0.45359237 } else listOf(0.1, 0.25, 0.5)
    return if (goalType == GoalType.GAIN) gain else lose
}

@Composable
private fun PaceInput(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    val d = state.draft
    val f = UnitFormatter(d.units)
    SegmentRow(
        label = null,
        options = listOf(PaceMode.WEEKLY_RATE to R.string.pace_by_rate, PaceMode.TARGET_DATE to R.string.pace_by_date),
        selected = d.paceMode,
        onSelect = { onEvent(OnboardingEvent.Pace(it)) },
    )
    val w = d.weightKg ?: return
    val t = d.targetKg ?: return
    when (d.paceMode) {
        PaceMode.WEEKLY_RATE -> {
            val presets = pacePresetsKg(d.units, d.goalType)
            val rate = d.weeklyRateKg
            val choices = presets.map { Choice(it, stringResource(R.string.pace_per_week, f.weight(it))) }.toMutableList()
            if (rate != null && presets.none { abs(it - rate) < 1e-6 }) {
                choices += Choice(rate, stringResource(R.string.pace_custom_rate, f.weight(rate)))
            }
            ChoiceList(choices, choices.firstOrNull { rate != null && abs(it.value - rate) < 1e-6 }?.value) {
                onEvent(OnboardingEvent.WeeklyRateKg(it))
            }
            rate?.let { GoalPlanner.resolvePace(w, t, GoalPace.ByWeeklyRate(it), state.today) }?.let {
                Text(stringResource(R.string.pace_reach_on, f.weight(t), formatDate(it.targetDate)), style = MaterialTheme.typography.bodyLarge)
            }
        }
        PaceMode.TARGET_DATE -> {
            DateField(
                date = d.date(),
                placeholder = stringResource(R.string.pace_by_date),
                initialIfEmpty = state.today.plusMonths(4),
                selectableFrom = state.today.plusDays(7),
                onChange = { onEvent(OnboardingEvent.TargetDate(it)) },
            )
            d.date()?.let { GoalPlanner.resolvePace(w, t, GoalPace.ByDate(it), state.today) }?.let {
                Text(stringResource(R.string.pace_rate_needed, f.weight(it.kgPerWeek)), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
