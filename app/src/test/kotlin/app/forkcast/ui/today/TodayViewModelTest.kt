package app.forkcast.ui.today

import app.cash.turbine.test
import app.forkcast.core.domain.BudgetState
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.WaterContainer
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSavedMealRepository
import app.forkcast.fakes.FakeWaterRepository
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
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.fakes.FakeAiEstimateRepository
import app.forkcast.core.data.db.LoggedDay
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
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
    private val water = FakeWaterRepository()
    private val estimates = FakeAiEstimateRepository()
    private val health = app.forkcast.fakes.FakeHealthRepository()
    private val maintenance = app.forkcast.fakes.FakeMaintenanceRepository()

    private fun vm() = TodayViewModel(FakeMealSlotRepository(), log, profiles, foods, saved, water, weights, FakeSettingsRepository(), estimates, health, maintenance, app.forkcast.fakes.FakeMissingKeysRepository(), clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** Activity above usual (SPEC §5.2) raises what's left; the Activity card says how. */
    @Test fun exerciseBonusRaisesTheAllowance() = runTest {
        maintenance.bonus.value = mapOf(TestData.TODAY to app.forkcast.core.domain.ActivityBonus(400.0, 0.0, 28, 400.0, 200.0, true))
        health.statusState.value = health.statusState.value.copy(settings = app.forkcast.core.model.HealthSettings(connected = true))
        health.activity.value = mapOf(TestData.TODAY to app.forkcast.core.model.ActivityDay(TestData.TODAY, 9000, 400.0, null, null, emptyList()))
        log.add(listOf(TestData.entry("Rice", "l", 205.0, 4.0)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.totals!!.allowanceKcal).isEqualTo(2490.0)
        assertThat(s.activity.connected).isTrue()
        assertThat(s.activity.day!!.steps).isEqualTo(9000)
        assertThat(s.activity.bonus.bonusKcal).isEqualTo(200.0)
    }

    @Test fun completenessFollowsTheRuleAndTheMark() = runTest {
        log.add(listOf(TestData.entry("Yogurt", "b", 500.0, 20.0), TestData.entry("Rice", "l", 600.0, 4.0)))
        val vm = vm()
        assertThat(vm.uiState.filterNotNull().first().autoComplete).isTrue()
        vm.onEvent(TodayEvent.SetCompleteness(app.forkcast.core.model.DayCompleteness.INCOMPLETE))
        assertThat(vm.uiState.filterNotNull().first { it.completeness == app.forkcast.core.model.DayCompleteness.INCOMPLETE }).isNotNull()
    }

    @Test fun neverMovesPastToday() = runTest {
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.NextDay)
        vm.onEvent(TodayEvent.PickDate(TestData.TODAY.plusDays(3)))
        assertThat(vm.uiState.filterNotNull().first().date).isEqualTo(TestData.TODAY)
    }

    @Test fun totalsFollowTheLog() = runTest {
        log.add(listOf(TestData.entry("Yogurt", "b", 146.0, 20.0), TestData.entry("Rice", "l", 205.0, 4.0)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.defaultMealId).isEqualTo("l")
        assertThat(s.totals!!.eatenKcal).isEqualTo(351.0)
        assertThat(s.totals.budgetKcal).isEqualTo(2290)
        assertThat(s.meals.first { it.slot.id == "b" }.entries.single().displayName).isEqualTo("Yogurt")
        assertThat(s.meals.first { it.slot.id == "l" }.kcal).isEqualTo(205.0)
    }

    @Test fun photoEstimatesShowPendingThenAsReviewableEntries() = runTest {
        val ids = log.add(listOf(TestData.entry("Chicken and rice", "l", 640.0).copy(source = app.forkcast.core.model.FoodSource.AI, aiEstimateId = "a1")))
        estimates.rows.value = listOf(
            FakeAiEstimateRepository.row("a1", TestData.TODAY, "l", EstimateStatus.DONE, logEntryId = ids.single(), kcal = 640.0),
            FakeAiEstimateRepository.row("a2", TestData.TODAY, "l", EstimateStatus.RUNNING),
        )
        val s = vm().uiState.filterNotNull().first()
        val lunch = s.meals.first { it.slot.id == "l" }
        // The logged estimate is its entry; the running one is a pending row.
        assertThat(lunch.entries.single().aiEstimateId).isEqualTo("a1")
        assertThat(lunch.pending.map { it.id }).containsExactly("a2")
        assertThat(s.unreviewedEstimateIds).containsExactly("a1")
        // Pending estimates don't count toward the totals until logged.
        assertThat(s.totals!!.eatenKcal).isEqualTo(640.0)
    }

    @Test fun forecastAppearsAfterSevenLoggedDays() = runTest {
        // Clock is 12:40; each past day had a 700 kcal dinner logged at 19:00 local.
        fun dinner(daysAgo: Long) = TestData.entry("Dinner", "d", 700.0, date = TestData.TODAY.minusDays(daysAgo))
            .copy(loggedAt = TestData.TODAY.minusDays(daysAgo).atTime(19, 0).atZone(zone).toInstant())
        log.add((1L..6L).map(::dinner))
        log.add(listOf(TestData.entry("Lunch", "l", 600.0)))
        assertThat(vm().uiState.filterNotNull().first().forecast).isNull()

        log.add(listOf(dinner(7)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.forecast!!.expectedRemainingKcal).isEqualTo(700.0)
        assertThat(s.forecast.forecastTotalKcal).isEqualTo(1300.0)
        assertThat(s.forecast.status).isEqualTo(app.forkcast.core.domain.Forecast.Status.ON_COURSE)
        assertThat(s.forecastFromMinute).isEqualTo(12 * 60 + 40)
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
        assertThat(s.totals.overKcal).isEqualTo(110.0)
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
            vm.onEvent(TodayEvent.Copy(ids, TestData.TODAY.minusDays(1), toMealSlotId = null))
            val copied = awaitItem() as TodayEffect.Copied
            assertThat(copied.count).isEqualTo(2)
        }
        val yesterday = log.rows.value.values.map { it.first }.filter { it.date == TestData.TODAY.minusDays(1) }
        assertThat(yesterday.map { it.mealSlotId }.toSet()).containsExactly("b")
        assertThat(yesterday.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL]!! }).isEqualTo(340.0)
    }

    /** Bug (owner, 2026-10-23): copying let a future date be picked. Nothing lands after today. */
    @Test fun copyNeverGoesToAFutureDay() = runTest {
        val ids = log.add(listOf(TestData.entry("Oats", "b", 300.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.effects.test {
            vm.onEvent(TodayEvent.Copy(ids, TestData.TODAY.plusDays(1), toMealSlotId = null))
            assertThat((awaitItem() as TodayEffect.Copied).toDate).isEqualTo(TestData.TODAY)
        }
        assertThat(log.rows.value.values.map { it.first.date }.toSet()).containsExactly(TestData.TODAY)
    }

    /** Edit sheet: day and time move together. */
    @Test fun editMovesEntryToAnotherDayAndTime() = runTest {
        val (id) = log.add(listOf(TestData.entry("Oats", "b", 300.0).copy(eatenMinute = 450)))
        val vm = vm()
        val entry = vm.uiState.filterNotNull().first { it.allEntries.isNotEmpty() }.allEntries.single()
        vm.onEvent(TodayEvent.UpdateEntry(entry, 1.0, "b", date = TestData.TODAY.minusDays(1), eatenMinute = 480))
        val moved = vm.uiState.filterNotNull().first { it.allEntries.isEmpty() }
        assertThat(moved.allEntries).isEmpty()
        val row = log.rows.value.getValue(id).first
        assertThat(row.date).isEqualTo(TestData.TODAY.minusDays(1))
        assertThat(row.eatenMinute).isEqualTo(480)
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

    /** ★ on one selected entry (roadmap 3.10): its food becomes a favorite with this amount; Unfavorite has Undo. */
    @Test fun favoriteAnEntryThenUnfavoriteAndUndo() = runTest {
        foods.foods.value = mapOf("f1" to food("f1", "Oatmeal", 300.0, 10.0, 1))
        log.add(listOf(TestData.entry("Oatmeal", "b", 450.0).copy(foodId = "f1", quantity = 1.5, servingLabel = "serving")))
        val vm = vm()
        val entry = vm.uiState.filterNotNull().first { it.allEntries.isNotEmpty() }.allEntries.single()
        vm.onEvent(TodayEvent.FavoriteEntry(entry.id))
        val fav = vm.uiState.filterNotNull().first { it.favorites.isNotEmpty() }.favorites.single()
        assertThat(fav.defaultAmount).isEqualTo(1.5 to "serving")

        vm.onEvent(TodayEvent.Unfavorite("Oatmeal", foodId = "f1"))
        assertThat(vm.uiState.filterNotNull().first { it.favorites.isEmpty() }.favorites).isEmpty()
        vm.onEvent(TodayEvent.RestoreFavorite(foodId = "f1"))
        assertThat(vm.uiState.filterNotNull().first { it.favorites.isNotEmpty() }.favorites.single().defaultAmount).isEqualTo(1.5 to "serving")

        vm.onEvent(TodayEvent.EditFavoriteFood("f1", 2.0, "serving"))
        assertThat(foods.foods.value.getValue("f1").defaultAmount).isEqualTo(2.0 to "serving")
        // Past entries never change.
        assertThat(log.rows.value.values.single().first.quantity).isEqualTo(1.5)
    }

    /** An AI estimate has no food: favoriting it makes one, "1 serving" = the whole plate, and the entry points to it. */
    @Test fun favoriteAnAiEntryMakesOneFood() = runTest {
        val (id) = log.add(listOf(TestData.entry("Chicken bowl", "l", 650.0).copy(source = app.forkcast.core.model.FoodSource.AI, servingLabel = "meal", grams = 400.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first { it.allEntries.isNotEmpty() }
        vm.onEvent(TodayEvent.FavoriteEntry(id))
        val fav = vm.uiState.filterNotNull().first { it.favorites.isNotEmpty() }.favorites.single()
        assertThat(fav.name).isEqualTo("Chicken bowl")
        assertThat(fav.source).isEqualTo(app.forkcast.core.model.FoodSource.AI)
        assertThat(fav.defaultAmount).isEqualTo(1.0 to "serving")
        assertThat(log.rows.value.getValue(id).first.foodId).isEqualTo(fav.id)
        vm.onEvent(TodayEvent.AddRecent(fav, "d"))
        val dinner = vm.uiState.filterNotNull().first { s -> s.meals.first { it.slot.id == "d" }.entries.isNotEmpty() }.meals.first { it.slot.id == "d" }
        assertThat(dinner.kcal).isWithin(1e-6).of(650.0)
    }

    /** A favorite meal: edit its amounts, unfavorite it, Undo. */
    @Test fun favoriteMealEditAndUnfavorite() = runTest {
        val ids = log.add(listOf(TestData.entry("Oats", "b", 300.0), TestData.entry("Coffee", "b", 40.0)))
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.SaveAsMeal(ids, "Usual breakfast"))
        val meal = vm.uiState.filterNotNull().first { it.savedMeals.isNotEmpty() }.savedMeals.single()
        vm.onEvent(TodayEvent.EditFavoriteMeal(meal.id, "Breakfast", mapOf(meal.items.first().id to 2.0), removed = setOf(meal.items.last().id)))
        val edited = vm.uiState.filterNotNull().first { it.savedMeals.single().name == "Breakfast" }.savedMeals.single()
        assertThat(edited.items.single().nutrients[app.forkcast.core.model.NutrientKey.ENERGY_KCAL]).isEqualTo(600.0)
        vm.onEvent(TodayEvent.Unfavorite("Breakfast", savedMealId = meal.id))
        vm.uiState.filterNotNull().first { it.savedMeals.isEmpty() }
        vm.onEvent(TodayEvent.RestoreFavorite(savedMealId = meal.id))
        assertThat(vm.uiState.filterNotNull().first { it.savedMeals.isNotEmpty() }.savedMeals.single().name).isEqualTo("Breakfast")
    }

    /** The Log sheet's Recent tab lists 15 foods (owner, 2026-10-29). */
    @Test fun recentListsFifteen() = runTest {
        foods.foods.value = (1..20).associate { "f$it" to food("f$it", "Food $it", 100.0, 1.0, it.toLong()) }
        val vm = vm()
        val s = vm.uiState.filterNotNull().first { it.recents.isNotEmpty() }
        assertThat(s.recents).hasSize(15)
        assertThat(s.recents.first().name).isEqualTo("Food 20")
    }

    @Test fun sameAsYesterdayCopiesThatMeal() = runTest {
        log.add(listOf(TestData.entry("Pasta", "d", 650.0, date = TestData.TODAY.minusDays(1))))
        val vm = vm()
        val s = vm.uiState.filterNotNull().first()
        assertThat(s.yesterdayMealIds).containsExactly("d")
        vm.onEvent(TodayEvent.SameAsYesterday("d"))
        assertThat(vm.uiState.value!!.meals.first { it.slot.id == "d" }.entries.single().displayName).isEqualTo("Pasta")
    }

    @Test fun waterGlassesAndUndo() = runTest {
        val vm = vm()
        val s = vm.uiState.filterNotNull().first()
        assertThat(s.water.targetMl).isEqualTo(2500.0) // male default
        vm.onEvent(TodayEvent.AddWater(s.water.glassMl))
        vm.onEvent(TodayEvent.AddWater(s.water.glassMl))
        assertThat(vm.uiState.value!!.water.ml).isWithin(1e-6).of(2 * s.water.glassMl)
        vm.onEvent(TodayEvent.RemoveLastWater)
        assertThat(vm.uiState.value!!.water.ml).isWithin(1e-6).of(s.water.glassMl)
    }

    /**
     * The water drop (owner, 2026-10-23): a container from the picker is added and becomes the
     * drop's; Undo removes additions newest first, only those since it appeared.
     */
    @Test fun waterContainersAndUndoWindow() = runTest {
        val vm = vm()
        vm.uiState.filterNotNull().first()
        vm.onEvent(TodayEvent.AddWater(250.0))
        vm.onEvent(TodayEvent.ClearWaterUndo) // Undo's window closed: that glass can't be undone any more.
        vm.onEvent(TodayEvent.AddWaterContainer(WaterContainer.CAN))
        val afterCan = vm.uiState.filterNotNull().first { it.water.lastUsed == WaterContainer.CAN }
        assertThat(afterCan.water.ml).isWithin(1e-6).of(250.0 + 355.0)
        assertThat(afterCan.water.undoCount).isEqualTo(1)
        vm.onEvent(TodayEvent.AddWaterContainer(WaterContainer.CAN))
        vm.onEvent(TodayEvent.UndoWater)
        vm.onEvent(TodayEvent.UndoWater)
        vm.onEvent(TodayEvent.UndoWater) // nothing left to undo: the first glass stays
        val after = vm.uiState.filterNotNull().first { it.water.undoCount == 0 }
        assertThat(after.water.ml).isWithin(1e-6).of(250.0)
    }

    /** Long-press on the total: the day's water becomes what was typed (newest entries go first). */
    @Test fun waterTotalCanBeSetDirectly() = runTest {
        val vm = vm()
        vm.onEvent(TodayEvent.AddWater(500.0))
        vm.onEvent(TodayEvent.AddWater(300.0))
        vm.onEvent(TodayEvent.SetWaterTotal(600.0))
        assertThat(vm.uiState.filterNotNull().first { it.water.ml < 700 }.water.ml).isWithin(1e-6).of(600.0)
        vm.onEvent(TodayEvent.SetWaterTotal(1000.0))
        assertThat(vm.uiState.filterNotNull().first { it.water.ml > 700 }.water.ml).isWithin(1e-6).of(1000.0)
    }

    @Test fun weightSummaryShowsLatestAndTrend() = runTest {
        weights.add(90.0, null, clock.instant().minusSeconds(86_400 * 7))
        weights.add(89.0, null, clock.instant())
        val w = vm().uiState.filterNotNull().first { it.weight != null }.weight!!
        assertThat(w.latestKg).isEqualTo(89.0)
        assertThat(w.trendKg).isLessThan(90.0)
        assertThat(w.weeklyChangeKg!!).isLessThan(0.0)
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

    @Test fun dayChangeNeverShowsTheOtherDaysEntries() = runTest {
        log.add(listOf(TestData.entry("Today oats", "b", 300.0), TestData.entry("Old pasta", "d", 650.0, date = TestData.TODAY.minusDays(1))))
        val vm = vm()
        val seen = mutableListOf<TodayUiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.filterNotNull().collect { seen += it } }
        vm.onEvent(TodayEvent.PreviousDay)
        vm.onEvent(TodayEvent.NextDay)
        job.cancel()
        // Every emitted state pairs a date with that date's own entries, never a mix.
        assertThat(seen.map { it.date }).contains(TestData.TODAY.minusDays(1))
        for (state in seen) assertThat(state.allEntries.all { it.date == state.date }).isTrue()
    }

    @Test fun emptyDayKnowsTheLastLoggedDay() = runTest {
        log.add(listOf(TestData.entry("A", "b", 100.0, date = TestData.TODAY.minusDays(3)), TestData.entry("B", "l", 100.0, date = TestData.TODAY.minusDays(1)), TestData.entry("C", "d", 100.0, date = TestData.TODAY.minusDays(1))))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.isEmptyDay).isTrue()
        assertThat(s.lastLogged).isEqualTo(LoggedDay(TestData.TODAY.minusDays(1), 2))
    }

    @Test fun resumingAfterMidnightMovesToTheNewDay() = runTest {
        val moving = MutableClock(clock.instant(), zone)
        val vm = TodayViewModel(FakeMealSlotRepository(), log, profiles, foods, saved, water, weights, FakeSettingsRepository(), estimates, health, maintenance, app.forkcast.fakes.FakeMissingKeysRepository(), moving)
        vm.uiState.filterNotNull().first()
        moving.now = moving.now.plusSeconds(12 * 3600) // 00:40 the next day
        vm.onEvent(TodayEvent.Resumed)
        assertThat(vm.uiState.filterNotNull().first { it.date == TestData.TODAY.plusDays(1) }.isToday).isTrue()

        // Looking at another day on purpose: resuming doesn't move it.
        vm.onEvent(TodayEvent.PreviousDay)
        moving.now = moving.now.plusSeconds(24 * 3600)
        vm.onEvent(TodayEvent.Resumed)
        assertThat(vm.uiState.value!!.date).isEqualTo(TestData.TODAY)
    }

    private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private fun food(id: String, name: String, kcalPer100: Double, proteinPer100: Double, usedAt: Long) = app.forkcast.core.model.Food(
        id, name, null, app.forkcast.core.model.FoodSource.USDA, null, null,
        listOf(app.forkcast.core.model.ServingSize("serving", 100.0)),
        mapOf(app.forkcast.core.model.NutrientKey.ENERGY_KCAL to kcalPer100, app.forkcast.core.model.NutrientKey.PROTEIN_G to proteinPer100),
        isFavorite = false, lastUsedAt = usedAt, lastQuantity = 1.0, lastServingLabel = "serving",
    )

    /** The budget sheet's "what fits": the user's own foods and saved meals that fit what's left; one tap logs. */
    @Test fun whatFitsComesFromHistoryAndLogsInOneTap() = runTest {
        foods.foods.value = listOf(
            food("f1", "Oatmeal", 300.0, 10.0, 3),
            food("f2", "Pizza", 2_400.0, 90.0, 2),
            food("f3", "Greek yogurt", 100.0, 18.0, 1),
        ).associateBy { it.id }
        saved.saveFrom("Usual lunch", listOf(TestData.entry("Turkey sandwich", "l", 420.0, 32.0)))
        log.add(listOf(TestData.entry("Breakfast", "b", 400.0, 30.0)))
        val vm = vm()
        val s = vm.uiState.filterNotNull().first { it.fits.isNotEmpty() }
        // Pizza is more than what's left; recent foods first, then saved meals.
        assertThat(s.fits.map { it.candidate.name }).containsExactly("Oatmeal", "Greek yogurt", "Usual lunch").inOrder()
        vm.onEvent(TodayEvent.LogFit(s.fits.first(), "l"))
        vm.uiState.filterNotNull().first { st -> st.allEntries.any { it.displayName == "Oatmeal" } }
        val logged = log.rows.value.values.map { it.first }.single { it.displayName == "Oatmeal" }
        assertThat(logged.mealSlotId).isEqualTo("l")
        assertThat(logged.date).isEqualTo(TestData.TODAY)
    }

    @Test fun overBudgetHasNothingThatFits() = runTest {
        foods.foods.value = mapOf("f1" to food("f1", "Oatmeal", 300.0, 10.0, 1))
        log.add(listOf(TestData.entry("Big day", "d", 4_000.0)))
        val s = vm().uiState.filterNotNull().first()
        assertThat(s.totals!!.remainingKcal).isLessThan(0.0)
        assertThat(s.fits).isEmpty()
    }

    @Test fun behindOnProteinLeadsWithProtein() = runTest {
        foods.foods.value = listOf(
            food("f1", "Crackers", 200.0, 3.0, 2),
            food("f2", "Chicken breast", 165.0, 31.0, 1),
        ).associateBy { it.id }
        log.add(listOf(TestData.entry("Pasta", "l", 1_100.0, 20.0)))
        val s = vm().uiState.filterNotNull().first { it.fits.isNotEmpty() }
        assertThat(s.proteinBehind).isTrue()
        assertThat(s.fits.first().candidate.name).isEqualTo("Chicken breast")
    }
}
