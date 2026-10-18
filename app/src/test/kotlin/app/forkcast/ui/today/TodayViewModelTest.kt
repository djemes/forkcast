package app.forkcast.ui.today

import app.cash.turbine.test
import app.forkcast.core.domain.BudgetState
import app.forkcast.core.model.NutrientKey
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSavedMealRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWeightRepository
import app.forkcast.fakes.TestData
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
import org.junit.Test
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 18, 12, 40, 0, 0, zone).toInstant(), zone)
    private val weights = FakeWeightRepository()
    private val profiles = FakeProfileRepository(weights) { TestData.TODAY }.apply {
        profileState.value = TestData.PROFILE
        goalState.value = TestData.GOAL
    }
    private val log = FakeLogRepository()
    private val foods = FakeFoodRepository()
    private val saved = FakeSavedMealRepository()
    private fun vm() = TodayViewModel(FakeMealSlotRepository(), log, profiles, foods, saved, FakeSettingsRepository(), clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun totalsFollowTheLog() = runTest {
        log.add(listOf(TestData.entry("Yogurt", "b", 146.0, 20.0), TestData.entry("Rice", "l", 205.0, 4.0)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.defaultMealId).isEqualTo("l")
        assertThat(s.totals!!.eatenKcal).isEqualTo(351.0)
        assertThat(s.totals!!.budgetKcal).isEqualTo(2290)
        assertThat(s.meals.first { it.slot.id == "b" }.entries.single().displayName).isEqualTo("Yogurt")
        assertThat(s.meals.first { it.slot.id == "l" }.kcal).isEqualTo(205.0)
    }

    @Test fun deleteAndUndo() = runTest {
        val ids = log.add(listOf(TestData.entry("Rice", "l", 205.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.effects.test {
            vm.onEvent(TodayEvent.Delete(ids))
            val effect = awaitItem() as TodayEffect.Deleted
            assertThat(effect.label).isEqualTo("Rice")
            assertThat(vm.uiState.value!!.allEntries).isEmpty()
            vm.onEvent(TodayEvent.Undo(effect.ids))
            assertThat(vm.uiState.value!!.allEntries).hasSize(1)
        }
    }

    @Test fun multiSelectDelete() = runTest {
        val ids = log.add(listOf(TestData.entry("A", "b", 100.0), TestData.entry("B", "l", 100.0), TestData.entry("C", "d", 100.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.ToggleSelect(ids[0]))
        vm.onEvent(TodayEvent.ToggleSelect(ids[2]))
        assertThat(vm.uiState.value!!.selection).containsExactly(ids[0], ids[2])
        vm.onEvent(TodayEvent.DeleteSelected)
        assertThat(vm.uiState.value!!.allEntries.map { it.displayName }).containsExactly("B")
        assertThat(vm.uiState.value!!.isSelecting).isFalse()
    }

    @Test fun editRescalesFromSnapshotAndMovesMeal() = runTest {
        log.add(listOf(TestData.entry("Rice", "l", 200.0, 4.0)))
        val vm = vm()
        val e = vm.uiState.filterNotNull().first().allEntries.single()
        vm.onEvent(TodayEvent.UpdateEntry(e, 1.5, "d"))
        val updated = vm.uiState.value!!.allEntries.single()
        assertThat(updated.mealSlotId).isEqualTo("d")
        assertThat(updated.grams).isWithin(1e-9).of(150.0)
        assertThat(updated.nutrients[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(300.0)
    }

    @Test fun overBudgetState() = runTest {
        log.add(listOf(TestData.entry("Feast", "d", 2400.0)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.totals!!.state).isEqualTo(BudgetState.OVER)
        assertThat(s.totals!!.overKcal).isEqualTo(110.0)
    }

    @Test fun quickAddLogsToTheDayOnScreen() = runTest {
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.PreviousDay)
        vm.effects.test {
            vm.onEvent(TodayEvent.QuickAdd("s", "Coffee", 45.0, null, 5.0, null))
            assertThat((awaitItem() as TodayEffect.Added).mealName).isEqualTo("Snacks")
        }
        val e = log.rows.value.values.single().first
        assertThat(e.date).isEqualTo(TestData.TODAY.minusDays(1))
        assertThat(e.nutrients[NutrientKey.ENERGY_KCAL]).isEqualTo(45.0)
    }

    @Test fun recentsAddWithLastUsedAmount() = runTest {
        val id = foods.put(FakeFoodRepository.food("Oats", kcal = 380.0).copy(lastUsedAt = 1L, lastServingLabel = "g", lastQuantity = 40.0))
        val vm = vm()
        val food = vm.uiState.filterNotNull().first { it.recents.isNotEmpty() }.recents.single()
        assertThat(food.id).isEqualTo(id)
        vm.onEvent(TodayEvent.AddRecent(food, "b"))
        val e = log.rows.value.values.single().first
        assertThat(e.grams).isEqualTo(40.0)
        assertThat(e.nutrients[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(152.0)
    }

    @Test fun copySelectionToAnotherDayKeepsSnapshots() = runTest {
        val ids = log.add(listOf(TestData.entry("Oats", "b", 300.0), TestData.entry("Coffee", "b", 40.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.effects.test {
            vm.onEvent(TodayEvent.Copy(ids, TestData.TODAY.plusDays(1), toMealSlotId = null))
            val copied = awaitItem() as TodayEffect.Copied
            assertThat(copied.count).isEqualTo(2)
        }
        val tomorrow = log.rows.value.values.map { it.first }.filter { it.date == TestData.TODAY.plusDays(1) }
        assertThat(tomorrow.map { it.mealSlotId }.toSet()).containsExactly("b")
        assertThat(tomorrow.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL]!! }).isEqualTo(340.0)
    }

    @Test fun saveAsMealThenLogIt() = runTest {
        val ids = log.add(listOf(TestData.entry("Oats", "b", 300.0), TestData.entry("Coffee", "b", 40.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.SaveAsMeal(ids, " Usual breakfast "))
        val meal = vm.uiState.filterNotNull().first { it.savedMeals.isNotEmpty() }.savedMeals.single()
        assertThat(meal.name).isEqualTo("Usual breakfast")
        vm.onEvent(TodayEvent.LogSavedMeal(meal.id, "l"))
        val lunch = vm.uiState.value!!.meals.first { it.slot.id == "l" }
        assertThat(lunch.entries.map { it.displayName }).containsExactly("Oats", "Coffee")
        assertThat(lunch.kcal).isEqualTo(340.0)
    }

    @Test fun sameAsYesterdayCopiesThatMeal() = runTest {
        log.add(listOf(TestData.entry("Pasta", "d", 650.0, date = TestData.TODAY.minusDays(1))))
        val vm = vm()
        val s = vm.uiState.filterNotNull().first()
        assertThat(s.yesterdayMealIds).containsExactly("d")
        vm.onEvent(TodayEvent.SameAsYesterday("d"))
        assertThat(vm.uiState.value!!.meals.first { it.slot.id == "d" }.entries.single().displayName).isEqualTo("Pasta")
    }

    @Test fun dayNavigationClearsSelection() = runTest {
        val ids = log.add(listOf(TestData.entry("A", "b", 100.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.ToggleSelect(ids[0]))
        vm.onEvent(TodayEvent.PreviousDay)
        assertThat(vm.uiState.value!!.date).isEqualTo(TestData.TODAY.minusDays(1))
        assertThat(vm.uiState.value!!.isSelecting).isFalse()
        vm.onEvent(TodayEvent.GoToToday)
        assertThat(vm.uiState.value!!.isToday).isTrue()
    }
}
