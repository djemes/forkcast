package app.forkcast.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWeightRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, zone).toInstant(), zone)
    private val today = LocalDate.of(2026, 10, 18)
    private val weights = FakeWeightRepository()
    private val profiles = FakeProfileRepository(weights) { today }
    private val settings = FakeSettingsRepository()
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = OnboardingViewModel(saved, profiles, weights, settings, clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun OnboardingViewModel.fillToGoal() {
        onEvent(OnboardingEvent.Next) // welcome
        onEvent(OnboardingEvent.Units(UnitPrefs.METRIC)); onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.SexChosen(Sex.MALE)); onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.BirthDate(LocalDate.of(1986, 10, 18))); onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.HeightCm(180.0)); onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.WeightKg(90.0)); onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.Activity(ActivityLevel.MODERATE)); onEvent(OnboardingEvent.Next)
    }

    @Test fun happyPathSavesEverything() = runTest {
        val vm = vm()
        vm.fillToGoal()
        vm.onEvent(OnboardingEvent.GoalChosen(GoalType.LOSE)); vm.onEvent(OnboardingEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.TARGET_WEIGHT)
        vm.onEvent(OnboardingEvent.TargetKg(80.0)); vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.WeeklyRateKg(0.5)); vm.onEvent(OnboardingEvent.Next)
        val s = vm.state.value
        assertThat(s.step).isEqualTo(OnboardingStep.RESULT)
        assertThat(s.preview!!.budgetKcal).isEqualTo(2290)

        // Accepting the budget saves everything, then the optional extras; no way back into the questions.
        vm.onEvent(OnboardingEvent.Finish)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.EXTRAS)
        assertThat(vm.state.value.saved).isTrue()
        assertThat(vm.state.value.finished).isFalse()
        vm.onEvent(OnboardingEvent.Back)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.EXTRAS)
        vm.onEvent(OnboardingEvent.Done)
        assertThat(vm.state.value.finished).isTrue()
        assertThat(profiles.profileState.value!!.heightCm).isEqualTo(180.0)
        assertThat(profiles.goalState.value!!.weeklyRateKg).isEqualTo(0.5)
        assertThat(weights.list.value.single().kg).isEqualTo(90.0)
        assertThat(settings.state.value.units).isEqualTo(UnitPrefs.METRIC)
    }

    @Test fun cannotContinueWithoutAnswer() {
        val vm = vm()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SEX)
        assertThat(vm.state.value.canContinue).isFalse()
    }

    @Test fun maintainSkipsTargetAndPace() {
        val vm = vm()
        vm.fillToGoal()
        vm.onEvent(OnboardingEvent.GoalChosen(GoalType.MAINTAIN))
        assertThat(vm.state.value.stepCount).isEqualTo(OnboardingStep.entries.size - 2)
        vm.onEvent(OnboardingEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RESULT)
        assertThat(vm.state.value.preview!!.budgetKcal).isEqualTo(2840)
    }

    @Test fun switchingPaceModeKeepsRateAndDateInSync() {
        val vm = vm()
        vm.fillToGoal()
        vm.onEvent(OnboardingEvent.GoalChosen(GoalType.LOSE)); vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.TargetKg(80.0)); vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.WeeklyRateKg(0.5))
        vm.onEvent(OnboardingEvent.Pace(PaceMode.TARGET_DATE))
        assertThat(vm.state.value.draft.date()).isEqualTo(today.plusDays(140))
        vm.onEvent(OnboardingEvent.TargetDate(today.plusDays(70)))
        vm.onEvent(OnboardingEvent.Pace(PaceMode.WEEKLY_RATE))
        assertThat(vm.state.value.draft.weeklyRateKg).isWithin(1e-9).of(1.0)
        // 1 kg/week from 90 kg is above 1% → warning, but still allowed to continue
        assertThat(vm.state.value.preview!!.guardrails).isNotEmpty()
        assertThat(vm.state.value.canContinue).isTrue()
    }

    @Test fun targetMustBeInGoalDirection() {
        val vm = vm()
        vm.fillToGoal()
        vm.onEvent(OnboardingEvent.GoalChosen(GoalType.LOSE)); vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.TargetKg(95.0))
        assertThat(vm.state.value.canContinue).isFalse()
    }

    @Test fun draftSurvivesProcessRestart() {
        val saved = SavedStateHandle()
        vm(saved).apply { fillToGoal() }
        val restored = vm(saved)
        assertThat(restored.state.value.step).isEqualTo(OnboardingStep.GOAL)
        assertThat(restored.state.value.draft.weightKg).isEqualTo(90.0)
    }
}
