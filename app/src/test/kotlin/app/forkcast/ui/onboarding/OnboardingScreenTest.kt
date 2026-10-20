package app.forkcast.ui.onboarding

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWeightRepository
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Onboarding happy path through the real screen (SPEC §9). Birth date is set via the VM (date picker UI is Material's). */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun happyPathReachesBudget() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, zone).toInstant(), zone)
        val weights = FakeWeightRepository()
        val profiles = FakeProfileRepository(weights) { LocalDate.of(2026, 10, 18) }
        val vm = OnboardingViewModel(SavedStateHandle(), profiles, weights, FakeSettingsRepository(), clock)
        compose.setContent {
            val state by vm.state.collectAsState()
            ForkcastTheme { OnboardingScreen(state, vm::onEvent) }
        }
        fun next() = compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("Welcome to Forkcast").assertIsDisplayed()
        compose.onNodeWithText("kg").performClick()
        compose.onNodeWithText("cm").performClick()
        next()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Male").performClick(); next()
        vm.onEvent(OnboardingEvent.BirthDate(LocalDate.of(1986, 10, 18))); next()
        compose.onNodeWithText("Centimeters").performTextInput("180"); next()
        compose.onNodeWithText("Weight (kg)").performTextInput("90"); next()
        compose.onNodeWithText("Moderately active").performScrollTo().performClick(); next()
        compose.onNodeWithText("Lose weight").performClick(); next()
        // Target field is pre-filled with a suggestion; replace it.
        compose.onNodeWithText("Weight (kg)").performTextReplacement("80"); next()
        compose.onNodeWithText("0.5 kg per week").performScrollTo().performClick(); next()

        compose.onNodeWithText("Your daily budget").assertIsDisplayed()
        compose.onNodeWithText("2,290 kcal per day").assertIsDisplayed()
        compose.onNodeWithText("Start tracking").performClick()
        compose.waitUntil(5_000) { vm.state.value.finished }
        assertThat(vm.state.value.draft.units).isEqualTo(UnitPrefs.METRIC)
        assertThat(profiles.goalState.value!!.targetWeightKg).isEqualTo(80.0)
    }
}
