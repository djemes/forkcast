package app.forkcast.ui.today

import app.cash.turbine.test
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.model.MealSlot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
class TodayViewModelTest {

    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 18, 12, 40, 0, 0, zone)
    private val clock = Clock.fixed(now.toInstant(), zone)
    private val slots = listOf(
        MealSlot("b", "Breakfast", 0, 240, 660),
        MealSlot("l", "Lunch", 1, 660, 900),
        MealSlot("d", "Dinner", 2, 1020, 1320),
        MealSlot("s", "Snacks", 3, null, null),
    )
    private val repo = object : MealSlotRepository {
        override fun observeMealSlots() = MutableStateFlow(slots)
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun startsOnTodayWithTimeBasedDefaultMeal() = runTest {
        val vm = TodayViewModel(repo, clock)
        vm.uiState.filterNotNull().test {
            val s = awaitItem()
            assertThat(s.date).isEqualTo(LocalDate.of(2026, 10, 18))
            assertThat(s.isToday).isTrue()
            assertThat(s.meals.map { it.name }).containsExactly("Breakfast", "Lunch", "Dinner", "Snacks").inOrder()
            assertThat(s.defaultMealId).isEqualTo("l")
        }
    }

    @Test fun dayNavigation() = runTest {
        val vm = TodayViewModel(repo, clock)
        vm.uiState.filterNotNull().test {
            awaitItem()
            vm.onEvent(TodayEvent.PreviousDay)
            assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 10, 17))
            vm.onEvent(TodayEvent.NextDay)
            vm.onEvent(TodayEvent.NextDay)
            skipItems(1)
            assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 10, 19))
            vm.onEvent(TodayEvent.PickDate(LocalDate.of(2026, 1, 2)))
            assertThat(awaitItem().isToday).isFalse()
            vm.onEvent(TodayEvent.GoToToday)
            assertThat(awaitItem().isToday).isTrue()
        }
    }
}
