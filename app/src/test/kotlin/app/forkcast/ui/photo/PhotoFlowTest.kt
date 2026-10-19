package app.forkcast.ui.photo

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.forkcast.core.data.ai.EstimateDetail
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeAiEstimateRepository
import app.forkcast.fakes.FakeAiRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PhotoFlowTest {

    @get:Rule val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:40:00Z"), ZoneId.of("America/New_York"))
    private val photo: Uri = Uri.parse("content://media/1")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun photoThenDescriptionThenEstimateQueuesAndReturns() = runTest {
        val ai = FakeAiRepository().apply { saveProfile(AiProfileDraft(null, "Gemini", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k") }
        val estimates = FakeAiEstimateRepository()
        val vm = PhotoMealViewModel(
            SavedStateHandle(mapOf("mealSlotId" to "l", "date" to TestData.TODAY.toString())),
            estimates, ai, FakeMealSlotRepository(), FakeSettingsRepository(), clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value.firstIsFreeTier).isTrue()
        vm.onEvent(PhotoMealEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(PhotoStep.CAPTURE)
        vm.onEvent(PhotoMealEvent.AddPhotos(listOf(photo, photo, photo, photo)))
        assertThat(vm.state.value.photos).hasSize(3)
        vm.onEvent(PhotoMealEvent.RemovePhoto(2))
        vm.onEvent(PhotoMealEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(PhotoStep.DETAILS)
        vm.onEvent(PhotoMealEvent.SetDescription("chicken thigh, rice"))
        vm.effect.test {
            vm.onEvent(PhotoMealEvent.Estimate)
            assertThat(awaitItem()).isEqualTo(PhotoMealEffect.Queued("Lunch"))
        }
        val (meal, photos, description) = estimates.created.single()
        assertThat(meal).isEqualTo("l")
        assertThat(photos).hasSize(2)
        assertThat(description).isEqualTo("chicken thigh, rice")
    }

    @Test fun noProfileBlocksEstimateAndPointsToSettings() {
        var openedSettings = false
        compose.setContent {
            ForkcastTheme {
                PhotoMealScreen(
                    state = PhotoMealUiState(loaded = true, step = PhotoStep.DETAILS, photos = listOf(photo), mealSlotId = "l",
                        slots = FakeMealSlotRepository.DEFAULT, hasProfile = false),
                    onEvent = {},
                    onBack = {},
                    onOpenAiSettings = { openedSettings = true },
                )
            }
        }
        compose.onNodeWithText("Estimate").assertIsNotEnabled()
        compose.onNodeWithText("Open AI settings").performScrollTo().performClick()
        assertThat(openedSettings).isTrue()
    }

    @Test fun detailsStepSendsEstimate() {
        val events = mutableListOf<PhotoMealEvent>()
        compose.setContent {
            ForkcastTheme {
                PhotoMealScreen(
                    state = PhotoMealUiState(loaded = true, step = PhotoStep.DETAILS, photos = listOf(photo), mealSlotId = "l",
                        slots = FakeMealSlotRepository.DEFAULT, hasProfile = true, notificationPermissionAsked = true),
                    onEvent = { events += it },
                    onBack = {},
                    onOpenAiSettings = {},
                )
            }
        }
        compose.onNodeWithText("What's on the plate? (optional)").performTextInput("toast")
        compose.onNodeWithText("Dinner").performClick()
        compose.onNodeWithText("Estimate").assertIsEnabled().performClick()
        assertThat(events).contains(PhotoMealEvent.SetDescription("toast"))
        assertThat(events).contains(PhotoMealEvent.SetMeal("d"))
        assertThat(events.last()).isEqualTo(PhotoMealEvent.Estimate)
    }

    private fun component(id: String, name: String, grams: Double, kcal: Double, hidden: Boolean, usda: Boolean) = AiComponentEntity(
        id = id, estimateId = "a1", sortOrder = 0, name = name, preparation = "unknown", grams = grams, gramsLow = grams * 0.8,
        gramsHigh = grams * 1.2, confidence = 0.6, isHiddenIngredient = hidden, usdaQuery = name,
        matchedFoodId = if (usda) "f" else null, matchedFdcId = null, matchedName = if (usda) "Rice, white, cooked" else null, matchScore = null,
        aiPer100g = mapOf(NutrientKey.ENERGY_KCAL to kcal * 100 / grams),
        usdaPer100g = if (usda) mapOf(NutrientKey.ENERGY_KCAL to kcal * 100 / grams) else null,
        useSource = if (usda) ComponentSource.USDA else ComponentSource.AI,
        nutrientsUsed = mapOf(NutrientKey.ENERGY_KCAL to kcal), createdAt = 0, updatedAt = 0,
    )

    @Test fun reviewShowsRangeSourceAndLikelyAdded() {
        val estimate = FakeAiEstimateRepository.row("a1", TestData.TODAY, "l", EstimateStatus.DONE, kcal = 640.0)
            .copy(result = MealEstimateJson(mealName = "Chicken and rice", assumptions = listOf("Rice is plain")))
        val detail = EstimateDetail(
            estimate,
            listOf(component("c1", "White rice", 200.0, 260.0, hidden = false, usda = true), component("c2", "Olive oil", 7.0, 62.0, hidden = true, usda = false)),
        )
        val events = mutableListOf<ReviewEvent>()
        compose.setContent {
            ForkcastTheme { ReviewScreen(ReviewUiState(loaded = true, detail = detail, units = UnitPrefs.METRIC), { events += it }, {}) }
        }
        // Energy is formatted with a thin space before the unit ("640 kcal").
        compose.onNodeWithText("~640 kcal, likely 512–768 kcal").assertExists()
        compose.onNodeWithText("Estimated by Gemini 3.8 Flash · free").assertExists()
        compose.onNodeWithText("USDA: Rice, white, cooked").assertExists()
        compose.onNodeWithText("Likely added").assertExists()
        compose.onNodeWithContentDescription("More White rice").performClick()
        assertThat(events.last()).isEqualTo(ReviewEvent.SetGrams("c1", 205.0))
        compose.onNodeWithText("Use AI values").performClick()
        assertThat(events.last()).isEqualTo(ReviewEvent.SetSource("c1", ComponentSource.AI))
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(ReviewEvent.Save)
    }

    @Test fun reviewViewModelSavesAndCloses() = runTest {
        val estimates = FakeAiEstimateRepository()
        val vm = ReviewViewModel(SavedStateHandle(mapOf("estimateId" to "a1")), estimates, app.forkcast.core.data.ai.PhotoStore(androidx.test.core.app.ApplicationProvider.getApplicationContext()), FakeSettingsRepository())
        vm.effect.test {
            vm.onEvent(ReviewEvent.Correct("only ate half"))
            vm.onEvent(ReviewEvent.Save)
            assertThat(awaitItem()).isEqualTo(ReviewEffect.Close)
        }
        assertThat(estimates.calls).containsExactly("correct:only ate half", "save").inOrder()
        assertThat(vm.state.first { it.loaded }.detail).isNull()
    }
}
