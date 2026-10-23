package app.forkcast.ui.food

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.network.FoodApiError
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.testSecretStore
import app.forkcast.navigation.FoodDetailDestination
import app.forkcast.navigation.SearchDestination
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FoodFlowViewModelTest {

    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val foods = FakeFoodRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun search() = SearchViewModel(
        SavedStateHandle(route = SearchDestination("l", "2026-10-18")),
        foods, testSecretStore(tmp.root), FakeSettingsRepository(), savedMeals, log, app.forkcast.fakes.FakeMealSlotRepository(),
        app.forkcast.fakes.FakeAiRepository(), app.forkcast.core.data.ai.PhotoStore(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
    )
    private val savedMeals = app.forkcast.fakes.FakeSavedMealRepository()
    private val log = app.forkcast.fakes.FakeLogRepository()

    /** stateIn(WhileSubscribed) only runs while someone collects. */
    private fun TestScope.collect(vm: SearchViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test fun localResultsAppearBeforeRemoteAndRemoteIsDeduped() = runTest(dispatcher) {
        foods.put(FakeFoodRepository.food("Rice, white, cooked", sourceId = "1"))
        foods.usdaResults = { listOf(FakeFoodRepository.food("Rice, brown", sourceId = "2", barcode = "00012345678905")) }
        foods.offResults = {
            listOf(
                FakeFoodRepository.food("Same product", sourceId = "x", barcode = "00012345678905"),
                FakeFoodRepository.food("Other rice", sourceId = "y"),
            )
        }
        val vm = search()
        collect(vm)
        vm.onQueryChange("rice")
        dispatcher.scheduler.runCurrent()
        assertThat(vm.state.value.local.map { it.name }).containsExactly("Rice, white, cooked")
        assertThat(vm.state.value.usda).isEqualTo(RemoteResults.Idle) // still debouncing
        advanceUntilIdle()
        assertThat((vm.state.value.usda as RemoteResults.Done).foods).hasSize(1)
        assertThat((vm.state.value.off as RemoteResults.Done).foods.map { it.name }).containsExactly("Other rice")
    }

    @Test fun offlineIsReportedPerSource() = runTest(dispatcher) {
        foods.usdaResults = { throw FoodApiError.Offline() }
        foods.offResults = { throw FoodApiError.RateLimited() }
        val vm = search()
        collect(vm)
        vm.onQueryChange("anything")
        advanceUntilIdle()
        assertThat((vm.state.value.usda as RemoteResults.Failed).error).isInstanceOf(FoodApiError.Offline::class.java)
        assertThat((vm.state.value.off as RemoteResults.Failed).error).isInstanceOf(FoodApiError.RateLimited::class.java)
    }

    @Test fun demoKeyIsFlaggedUntilAKeyExists() = runTest(dispatcher) {
        val vm = search()
        collect(vm)
        advanceUntilIdle()
        assertThat(vm.state.value.usingDemoKey).isTrue()
    }

    @Test fun openingARemoteResultCachesIt() = runTest(dispatcher) {
        val vm = search()
        vm.effects.test {
            vm.open(FakeFoodRepository.food("Oats", sourceId = "99"))
            advanceUntilIdle()
            val id = (awaitItem() as SearchEffect.OpenFood).foodId
            assertThat(foods.foods.value[id]!!.name).isEqualTo("Oats")
        }
    }

    @Test fun detailKeepsAmountWhenSwitchingServingsAndAdds() = runTest(dispatcher) {
        val id = foods.put(FakeFoodRepository.food("Rice", kcal = 130.0))
        val log = FakeLogRepository()
        val vm = FoodDetailViewModel(
            SavedStateHandle(route = FoodDetailDestination(id, "l", "2026-10-17")),
            foods, log, FakeMealSlotRepository(), FakeSettingsRepository(),
            Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("UTC")),
        )
        backgroundScope.launch { vm.state.collect {} }
        val s = vm.state.filterNotNull().first()
        assertThat(s.serving.label).isEqualTo("1 cup")
        assertThat(s.quantity).isEqualTo(1.0)
        assertThat(s.mealName).isEqualTo("Lunch")

        vm.selectServing(s.servings.first { it.label == "g" })
        advanceUntilIdle()
        assertThat(vm.state.value!!.quantity).isEqualTo(158.0)

        vm.step(+1) // grams step by 10
        advanceUntilIdle()
        assertThat(vm.state.value!!.quantity).isEqualTo(168.0)

        vm.effects.test {
            vm.add()
            advanceUntilIdle()
            assertThat((awaitItem() as FoodDetailEffect.Added).mealName).isEqualTo("Lunch")
        }
        val entry = log.rows.value.values.single().first
        assertThat(entry.date.toString()).isEqualTo("2026-10-17") // the day that was on screen
        assertThat(entry.grams).isEqualTo(168.0)
        assertThat(entry.nutrients[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(218.4)
    }
}
