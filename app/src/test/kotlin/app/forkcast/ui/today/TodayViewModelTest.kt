package app.forkcast.ui.today

import app.cash.turbine.test
import app.forkcast.core.domain.BudgetState
import app.forkcast.core.model.NutrientKey
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeProfileRepository
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
    private fun vm() = TodayViewModel(FakeMealSlotRepository(), log, profiles, FakeSettingsRepository(), clock)

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
