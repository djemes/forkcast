package app.forkcast.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.domain.GoalPace
import app.forkcast.core.domain.GoalPlanner
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.UserProfile
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

enum class PaceMode { WEEKLY_RATE, TARGET_DATE }

/** Answers so far. Saved in SavedStateHandle so a process restart doesn't lose them. */
@Serializable
data class OnboardingDraft(
    val units: UnitPrefs,
    val sex: Sex? = null,
    val birthDate: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val activity: ActivityLevel? = null,
    val goalType: GoalType? = null,
    val targetKg: Double? = null,
    val paceMode: PaceMode = PaceMode.WEEKLY_RATE,
    val weeklyRateKg: Double? = null,
    val targetDate: String? = null,
) {
    fun birth(): LocalDate? = birthDate?.let(LocalDate::parse)
    fun date(): LocalDate? = targetDate?.let(LocalDate::parse)
}

enum class OnboardingStep { UNITS, SEX, BIRTH_DATE, HEIGHT, WEIGHT, ACTIVITY, GOAL, TARGET_WEIGHT, PACE, RESULT }

data class OnboardingUiState(
    val draft: OnboardingDraft,
    val step: OnboardingStep,
    val stepIndex: Int,
    val stepCount: Int,
    val canContinue: Boolean,
    /** Live budget once enough is known (pace step and result). */
    val preview: DailyTargets?,
    val today: LocalDate,
    val saving: Boolean = false,
    val finished: Boolean = false,
)

sealed interface OnboardingEvent {
    data class Units(val units: UnitPrefs) : OnboardingEvent
    data class SexChosen(val sex: Sex) : OnboardingEvent
    data class BirthDate(val date: LocalDate) : OnboardingEvent
    data class HeightCm(val cm: Double?) : OnboardingEvent
    data class WeightKg(val kg: Double?) : OnboardingEvent
    data class Activity(val level: ActivityLevel) : OnboardingEvent
    data class GoalChosen(val type: GoalType) : OnboardingEvent
    data class TargetKg(val kg: Double?) : OnboardingEvent
    data class Pace(val mode: PaceMode) : OnboardingEvent
    data class WeeklyRateKg(val kg: Double?) : OnboardingEvent
    data class TargetDate(val date: LocalDate) : OnboardingEvent
    data object Next : OnboardingEvent
    data object Back : OnboardingEvent
    data object Finish : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val profileRepository: ProfileRepository,
    private val weightRepository: WeightRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        build(
            draft = savedState.get<String>(KEY_DRAFT)?.let { runCatching { json.decodeFromString<OnboardingDraft>(it) }.getOrNull() }
                ?: OnboardingDraft(units = UnitFormatter.defaultsFor(Locale.getDefault())),
            step = savedState.get<String>(KEY_STEP)?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
                ?: OnboardingStep.UNITS,
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.Units -> edit { copy(units = event.units) }
            is OnboardingEvent.SexChosen -> edit { copy(sex = event.sex) }
            is OnboardingEvent.BirthDate -> edit { copy(birthDate = event.date.toString()) }
            is OnboardingEvent.HeightCm -> edit { copy(heightCm = event.cm) }
            is OnboardingEvent.WeightKg -> edit { copy(weightKg = event.kg) }
            is OnboardingEvent.Activity -> edit { copy(activity = event.level) }
            is OnboardingEvent.GoalChosen -> edit { withGoalType(event.type) }
            is OnboardingEvent.TargetKg -> edit { copy(targetKg = event.kg) }
            is OnboardingEvent.Pace -> edit { syncPace(event.mode) }
            is OnboardingEvent.WeeklyRateKg -> edit { copy(weeklyRateKg = event.kg, paceMode = PaceMode.WEEKLY_RATE) }
            is OnboardingEvent.TargetDate -> edit { copy(targetDate = event.date.toString(), paceMode = PaceMode.TARGET_DATE) }
            OnboardingEvent.Next -> move(+1)
            OnboardingEvent.Back -> move(-1)
            OnboardingEvent.Finish -> finish()
        }
    }

    private fun today() = LocalDate.now(clock)

    private fun OnboardingDraft.withGoalType(type: GoalType): OnboardingDraft {
        val w = weightKg
        return when (type) {
            GoalType.MAINTAIN -> copy(goalType = type, targetKg = w)
            GoalType.LOSE -> copy(
                goalType = type,
                targetKg = targetKg?.takeIf { w == null || it < w } ?: w?.let { it - 5.0 },
                weeklyRateKg = weeklyRateKg ?: DEFAULT_LOSE_RATE_KG,
            )
            GoalType.GAIN -> copy(
                goalType = type,
                targetKg = targetKg?.takeIf { w == null || it > w } ?: w?.let { it + 3.0 },
                weeklyRateKg = weeklyRateKg ?: DEFAULT_GAIN_RATE_KG,
            )
        }
    }

    /** Switching mode carries the current pace over, so the two stay in sync (SPEC §4.1). */
    private fun OnboardingDraft.syncPace(mode: PaceMode): OnboardingDraft {
        val w = weightKg ?: return copy(paceMode = mode)
        val t = targetKg ?: return copy(paceMode = mode)
        return when (mode) {
            PaceMode.TARGET_DATE -> {
                val resolved = weeklyRateKg?.let { GoalPlanner.resolvePace(w, t, GoalPace.ByWeeklyRate(it), today()) }
                copy(paceMode = mode, targetDate = resolved?.targetDate?.toString() ?: targetDate)
            }
            PaceMode.WEEKLY_RATE -> {
                val resolved = date()?.let { GoalPlanner.resolvePace(w, t, GoalPace.ByDate(it), today()) }
                copy(paceMode = mode, weeklyRateKg = resolved?.kgPerWeek ?: weeklyRateKg)
            }
        }
    }

    private fun edit(change: OnboardingDraft.() -> OnboardingDraft) {
        _state.update { build(it.draft.change(), it.step) }
    }

    private fun move(delta: Int) {
        _state.update { s ->
            val steps = stepsFor(s.draft)
            val i = (steps.indexOf(s.step) + delta).coerceIn(0, steps.lastIndex)
            build(s.draft, steps[i])
        }
    }

    private fun build(draft: OnboardingDraft, step: OnboardingStep): OnboardingUiState {
        savedState[KEY_DRAFT] = json.encodeToString(OnboardingDraft.serializer(), draft)
        savedState[KEY_STEP] = step.name
        val steps = stepsFor(draft)
        val s = if (step in steps) step else steps.last { it.ordinal <= step.ordinal }
        return OnboardingUiState(
            draft = draft,
            step = s,
            stepIndex = steps.indexOf(s),
            stepCount = steps.size,
            canContinue = isValid(draft, s),
            preview = preview(draft),
            today = today(),
        )
    }

    private fun isValid(d: OnboardingDraft, step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.UNITS -> true
        OnboardingStep.SEX -> d.sex != null
        OnboardingStep.BIRTH_DATE -> d.birth()?.let { it.isBefore(today().minusYears(MIN_AGE)) && it.isAfter(today().minusYears(120)) } == true
        OnboardingStep.HEIGHT -> d.heightCm?.let { it in 90.0..250.0 } == true
        OnboardingStep.WEIGHT -> d.weightKg?.let { it in 25.0..350.0 } == true
        OnboardingStep.ACTIVITY -> d.activity != null
        OnboardingStep.GOAL -> d.goalType != null
        OnboardingStep.TARGET_WEIGHT -> d.targetKg?.let { t ->
            val w = d.weightKg ?: return@let false
            t in 25.0..350.0 && when (d.goalType) {
                GoalType.LOSE -> t < w
                GoalType.GAIN -> t > w
                else -> true
            }
        } == true
        OnboardingStep.PACE -> pace(d) != null
        OnboardingStep.RESULT -> preview(d) != null
    }

    private fun pace(d: OnboardingDraft): GoalPace? = when (d.paceMode) {
        PaceMode.WEEKLY_RATE -> d.weeklyRateKg?.takeIf { it > 0 }?.let { GoalPace.ByWeeklyRate(it) }
        PaceMode.TARGET_DATE -> d.date()?.takeIf { it.isAfter(today()) }?.let { GoalPace.ByDate(it) }
    }

    private fun toGoal(d: OnboardingDraft): Goal? {
        val type = d.goalType ?: return null
        val w = d.weightKg ?: return null
        val pace = if (type == GoalType.MAINTAIN) null else pace(d) ?: return null
        return Goal(
            id = "",
            goalType = type,
            startDate = today(),
            startWeightKg = w,
            targetWeightKg = if (type == GoalType.MAINTAIN) w else d.targetKg ?: return null,
            targetDate = (pace as? GoalPace.ByDate)?.targetDate,
            weeklyRateKg = (pace as? GoalPace.ByWeeklyRate)?.kgPerWeek,
        )
    }

    private fun toProfile(d: OnboardingDraft): UserProfile? = UserProfile(
        sex = d.sex ?: return null,
        birthDate = d.birth() ?: return null,
        heightCm = d.heightCm ?: return null,
        activityLevel = d.activity ?: return null,
    )

    private fun preview(d: OnboardingDraft): DailyTargets? {
        val profile = toProfile(d) ?: return null
        val goal = toGoal(d) ?: return null
        return DailyTargetsCalculator.compute(profile, goal, d.weightKg ?: return null, today())
    }

    private fun finish() {
        val s = _state.value
        val profile = toProfile(s.draft) ?: return
        val goal = toGoal(s.draft) ?: return
        val weight = s.draft.weightKg ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            settingsRepository.setUnits(s.draft.units)
            profileRepository.saveProfile(profile)
            profileRepository.saveGoal(goal)
            weightRepository.add(weight, null, clock.instant())
            savedState.remove<String>(KEY_DRAFT)
            savedState.remove<String>(KEY_STEP)
            _state.update { it.copy(saving = false, finished = true) }
        }
    }

    companion object {
        fun stepsFor(d: OnboardingDraft): List<OnboardingStep> = OnboardingStep.entries.filter {
            d.goalType != GoalType.MAINTAIN || (it != OnboardingStep.TARGET_WEIGHT && it != OnboardingStep.PACE)
        }

        const val DEFAULT_LOSE_RATE_KG = 0.45359237 // 1 lb
        const val DEFAULT_GAIN_RATE_KG = 0.226796185 // 0.5 lb
        const val MIN_AGE = 13L
        private const val KEY_DRAFT = "onboarding_draft"
        private const val KEY_STEP = "onboarding_step"
    }
}
