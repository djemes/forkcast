package app.forkcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeAiEstimateRepository
import app.forkcast.fakes.FakeAiRepository
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeHealthRepository
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMaintenanceRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeMissingKeysRepository
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSavedMealRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWaterRepository
import app.forkcast.fakes.FakeWeightRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.activity.ActivityScreen
import app.forkcast.ui.activity.ActivityViewModel
import app.forkcast.ui.ai.AiSettingsScreen
import app.forkcast.ui.ai.AiSettingsViewModel
import app.forkcast.ui.recipes.RecipesScreen
import app.forkcast.ui.recipes.RecipesUiState
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.TodayScreen
import app.forkcast.ui.today.TodayViewModel
import app.forkcast.ui.weight.WeightScreen
import app.forkcast.ui.weight.WeightViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A fresh install right after onboarding (roadmap 2.0 task 3.7): every screen's ViewModel reads
 * empty fake repositories, and each screen says what goes there and offers the next step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FirstRunTest {

    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 18, 12, 40, 0, 0, zone).toInstant(), zone)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun show(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                ForkcastTheme(darkTheme = dark) { content() }
            }
        }
    }

    private suspend fun freshToday(): app.forkcast.ui.today.TodayUiState {
        val weights = FakeWeightRepository()
        // Onboarding leaves a profile and a goal, and nothing else.
        val profiles = FakeProfileRepository(weights) { TestData.TODAY }.apply {
            profileState.value = TestData.PROFILE
            goalState.value = TestData.GOAL
        }
        val vm = TodayViewModel(
            FakeMealSlotRepository(), FakeLogRepository(), profiles, FakeFoodRepository(), FakeSavedMealRepository(), FakeWaterRepository(), weights,
            FakeSettingsRepository(), FakeAiEstimateRepository(), FakeHealthRepository(), FakeMaintenanceRepository(), FakeMissingKeysRepository(), clock,
        )
        return vm.uiState.filterNotNull().first()
    }

    @Test fun todayBeforeTheFirstEntryOffersToLogAMeal() = runTest {
        val state = freshToday()
        assertThat(state.isEmptyDay).isTrue()
        show { TodayScreen(state, onEvent = {}) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Start your log"))
        compose.onNodeWithText("Log what you eat", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Log a meal").performClick()
        // The Log sheet opens for the meal that fits the time (12:40, lunch).
        compose.onNodeWithText("Log to Lunch").assertExists()
    }

    @Test fun todayFirstDayCardWorksInDarkThemeAtDoubleFontScale() = runTest {
        val state = freshToday()
        show(dark = true, fontScale = 2f) { TodayScreen(state, onEvent = {}) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Log a meal"))
        compose.onNodeWithText("Log a meal").assertIsDisplayed()
    }

    @Test fun activityWithoutHealthConnectSaysWhatComesAndOffersSetup() = runTest {
        val vm = ActivityViewModel(SavedStateHandle(mapOf("date" to "2026-10-18")), FakeHealthRepository(), FakeMaintenanceRepository(), FakeSettingsRepository(), clock)
        val state = vm.state.filterNotNull().first()
        var connect = false
        show(dark = true, fontScale = 2f) { ActivityScreen(state, onBack = {}, onConnect = { connect = true }) }
        compose.onNodeWithText("No activity yet", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No data").assertDoesNotExist()
        compose.onNodeWithText("Set up Health Connect").performScrollTo().performClick()
        assertThat(connect).isTrue()
    }

    @Test fun activityConnectedButEmptyPointsToHelp() = runTest {
        val health = FakeHealthRepository().apply {
            statusState.value = statusState.value.copy(settings = app.forkcast.core.model.HealthSettings(connected = true))
        }
        val state = ActivityViewModel(SavedStateHandle(mapOf("date" to "2026-10-18")), health, FakeMaintenanceRepository(), FakeSettingsRepository(), clock)
            .state.filterNotNull().first()
        show { ActivityScreen(state, onBack = {}) }
        compose.onNodeWithText("No activity from Health Connect in the last 14 days", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Help").assertIsDisplayed()
    }

    @Test fun weightWithoutWeighInsSaysWhereTheTrendComesFrom() = runTest {
        val state = WeightViewModel(FakeWeightRepository(), FakeSettingsRepository(), clock).state.filterNotNull().first()
        show(dark = true, fontScale = 2f) { WeightScreen(state, onBack = {}, onAdd = { _, _, _ -> }, onDelete = {}) }
        compose.onNodeWithText("No weigh-ins yet. Add your first one below", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save weigh-in").performScrollTo().assertIsDisplayed()
    }

    @Test fun aiSettingsWithNoServiceOffersTheGuide() = runTest {
        val state = AiSettingsViewModel(FakeAiRepository(), FakeSettingsRepository(), clock).state.first { it.loaded }
        var added = false
        show { AiSettingsScreen(state, onEvent = {}, onBack = {}, onEditProfile = {}, onAddAi = { added = true }) }
        compose.onNodeWithText("No AI services yet. Add one to start estimating meals.").assertIsDisplayed()
        compose.onNodeWithText("Add AI").performScrollTo().performClick()
        assertThat(added).isTrue()
    }

    @Test fun recipesWithNoneExplainsThemAndOffersANewOne() {
        var created = false
        show(fontScale = 2f) { RecipesScreen(RecipesUiState(emptyList(), UnitPrefs.US, emptyMap()), null, {}, {}, {}, onNew = { created = true }) }
        compose.onNodeWithText("Build a dish you cook often", substring = true).assertIsDisplayed()
        compose.onNodeWithText("New recipe").performScrollTo().performClick()
        assertThat(created).isTrue()
    }
}
