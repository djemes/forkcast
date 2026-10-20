package app.forkcast.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWeightRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MealsAndTargetsTest {

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun mealsAddRenameMoveDeleteUndo() = runTest {
        val repo = FakeMealSlotRepository()
        val vm = MealsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.slots.collect {} }

        // Invalid drafts change nothing.
        assertThat(vm.save(MealDraft(null, "lunch", null, null))).isFalse()
        assertThat(vm.save(MealDraft(null, "Late snack", 600, 600))).isFalse()

        assertThat(vm.save(MealDraft(null, "Second breakfast", 600, 660))).isTrue()
        assertThat(repo.state.value.map { it.name }).contains("Second breakfast")
        val l = repo.state.value.first { it.name == "Lunch" }
        assertThat(vm.save(MealDraft(l.id, "Midday", 690, 870))).isTrue()
        assertThat(repo.state.value.first { it.id == l.id }.startMinute).isEqualTo(690)

        vm.move(l.id, -1)
        assertThat(repo.state.value.sortedBy { it.sortOrder }.map { it.name }.take(2)).containsExactly("Midday", "Breakfast").inOrder()

        val deleted = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            val d = vm.deleted.first()
            vm.undo(d.id)
        }
        vm.delete(repo.state.value.first { it.name == "Midday" })
        deleted.join()
        assertThat(repo.state.value.map { it.name }).contains("Midday")
    }

    @Test fun mealsScreenEditsAName() {
        var saved: MealDraft? = null
        compose.setContent {
            ForkcastTheme {
                MealsScreen(FakeMealSlotRepository.DEFAULT, onSave = { saved = it; true }, onMove = { _, _ -> }, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Lunch").performClick()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("Breakfast")
        compose.onNodeWithText("Another meal already has this name.").assertExists()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("Midday")
        compose.onNodeWithText("Save").performClick()
        assertThat(saved!!.name).isEqualTo("Midday")
        assertThat(saved.start).isEqualTo(660)
    }

    @Test fun lastMealCantBeDeleted() {
        compose.setContent {
            ForkcastTheme {
                MealsScreen(FakeMealSlotRepository.DEFAULT.take(1), onSave = { true }, onMove = { _, _ -> }, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithContentDescription("More options for Breakfast").performClick()
        compose.onNodeWithText("Keep at least one meal.").assertIsNotEnabled()
    }

    @Test fun targetsShowDefaultsAndCustomOverrides() = runTest {
        val weights = FakeWeightRepository()
        val profiles = FakeProfileRepository(weights) { TestData.TODAY }.apply {
            profileState.value = TestData.PROFILE
            goalState.value = TestData.GOAL
        }
        val vm = DailyTargetsViewModel(profiles, FakeSettingsRepository())
        val first = vm.state.filterNotNull().first()
        val protein = first.main.first { it.key == NutrientKey.PROTEIN_G }
        assertThat(protein.custom).isFalse()
        assertThat(first.limits.first { it.key == NutrientKey.SODIUM_MG }.kind).isEqualTo(TargetKind.LIMIT)

        vm.set(NutrientKey.PROTEIN_G, 160.0)
        val after = vm.state.filterNotNull().first { s -> s.main.first { it.key == NutrientKey.PROTEIN_G }.custom }
        val p = after.main.first { it.key == NutrientKey.PROTEIN_G }
        assertThat(p.amount).isEqualTo(160.0)
        assertThat(p.default).isEqualTo(protein.amount)

        // Calories share Profile & goals' custom budget.
        vm.set(NutrientKey.ENERGY_KCAL, 2000.0)
        val kcal = vm.state.filterNotNull().first { s -> s.main.first().custom }.main.first()
        assertThat(kcal.amount).isEqualTo(2000.0)

        vm.set(NutrientKey.PROTEIN_G, null)
        assertThat(vm.state.filterNotNull().first { s -> !s.main.first { it.key == NutrientKey.PROTEIN_G }.custom }).isNotNull()
    }

    @Test fun targetsScreenSavesAnAmount() {
        val row = TargetRow(NutrientKey.FIBER_G, 33.0, 33.0, TargetKind.GOAL, custom = false)
        var set: Pair<NutrientKey, Double?>? = null
        compose.setContent {
            ForkcastTheme {
                DailyTargetsScreen(DailyTargetsState(UnitPrefs.US, emptyList(), listOf(row), emptyList()), { k, v -> set = k to v }, onBack = {})
            }
        }
        compose.onNodeWithText("Fiber").performScrollTo().performClick()
        compose.onNodeWithText("Amount").performTextClearance()
        compose.onNodeWithText("Amount").performTextInput("40")
        compose.onNodeWithText("Save").performClick()
        assertThat(set).isEqualTo(NutrientKey.FIBER_G to 40.0)
    }
}
