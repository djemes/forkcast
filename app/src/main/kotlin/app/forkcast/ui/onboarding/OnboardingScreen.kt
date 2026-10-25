package app.forkcast.ui.onboarding

import app.forkcast.ui.format.unitLabels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import app.forkcast.ui.backup.BackupDialog
import app.forkcast.ui.backup.BackupDialogs
import app.forkcast.ui.backup.BackupEvent
import app.forkcast.ui.backup.BackupViewModel
import app.forkcast.core.domain.GoalPace
import app.forkcast.core.domain.GoalPlanner
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightUnit
import app.forkcast.ui.format.UnitFormatter
import kotlin.math.abs

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    extras: ExtrasActions = ExtrasActions(),
    viewModel: OnboardingViewModel = hiltViewModel(),
    extrasViewModel: OnboardingExtrasViewModel = hiltViewModel(),
    backup: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extrasState by extrasViewModel.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    // Moving phones: restore a backup instead of answering the questions (owner, 2026-10-25).
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { backup.onEvent(BackupEvent.Open(it)) } }
    var restoring by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(backupState.dialog) {
        val dialog = backupState.dialog
        if (dialog is BackupDialog.Done) restoring = true
        if (dialog == null && restoring) {
            restoring = false
            viewModel.onEvent(OnboardingEvent.Restored)
        }
    }
    OnboardingScreen(
        state, viewModel::onEvent, extrasState, extras,
        onNotificationsAsked = { extrasViewModel.notificationPermissionAsked() },
        onRestore = { pick.launch(arrayOf("*/*")) },
    )
    backupState.dialog?.let { BackupDialogs(it, backup::onEvent, newPhone = true) }
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    extras: ExtrasState = ExtrasState(),
    extrasActions: ExtrasActions = ExtrasActions(),
    onNotificationsAsked: () -> Unit = {},
    /** Null hides "Restore a backup" (tests, previews). */
    onRestore: (() -> Unit)? = null,
) {
    // Once the budget is saved there's no going back into the questions (Settings has them all).
    val canGoBack = state.stepIndex > 0 && !state.saved
    BackHandler(enabled = canGoBack) { onEvent(OnboardingEvent.Back) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                if (canGoBack) {
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
                if (state.step == OnboardingStep.EXTRAS) {
                    OnboardingExtras(extras, extrasActions, onNotificationsAsked)
                } else {
                    StepContent(state, onEvent)
                }
            }
            val (event, label) = when (state.step) {
                OnboardingStep.WELCOME -> OnboardingEvent.Next to R.string.onb_welcome_start
                OnboardingStep.RESULT -> OnboardingEvent.Finish to R.string.action_continue
                OnboardingStep.EXTRAS -> OnboardingEvent.Done to R.string.action_start
                else -> OnboardingEvent.Next to R.string.action_continue
            }
            Button(
                onClick = { onEvent(event) },
                enabled = state.canContinue && !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(label)) }
            if (state.step == OnboardingStep.WELCOME && onRestore != null) {
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.onb_restore), textAlign = TextAlign.Center) }
            }
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
    val f = UnitFormatter(d.units, labels = unitLabels())
    when (state.step) {
        OnboardingStep.WELCOME -> {
            Welcome()
            if (state.restoreIncomplete) RestoreIncomplete()
        }
        // The agreement step (roadmap 2.0 Phase 4) goes here, between welcome and units.
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
        OnboardingStep.EXTRAS -> Unit
    }
}

/** What Forkcast does, in two lines, and that it stays on the phone. */
@Composable
private fun Welcome() {
    // The app icon, layered the way the launcher shows it.
    androidx.compose.foundation.layout.Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
    Title(stringResource(R.string.onb_welcome_title), stringResource(R.string.onb_welcome_body))
    Text(stringResource(R.string.onb_welcome_private), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.not_medical_advice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** After a restore without a profile or goal: the questions come next. */
@Composable
private fun RestoreIncomplete() {
    Text(stringResource(R.string.onb_restore_incomplete), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
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
    val f = UnitFormatter(d.units, labels = unitLabels())
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
