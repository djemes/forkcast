package app.forkcast.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.domain.DayMath
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.data.db.LoggedDay
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.performTextClearance
import app.forkcast.core.model.WaterContainer
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = TestData.TODAY
    private val entries = listOf(
        TestData.entry("Greek yogurt", "b", 146.0, 20.0).copy(id = "y"),
        TestData.entry("Banana", "b", 105.0, 1.0).copy(id = "n"),
    )
    private val targets = DailyTargetsCalculator.compute(TestData.PROFILE, TestData.GOAL, 90.0, today)
    private val state = TodayUiState(
        date = today,
        today = today,
        meals = FakeMealSlotRepository.DEFAULT.map { slot ->
            val list = entries.filter { it.mealSlotId == slot.id }
            MealSection(slot, list, DayMath.mealKcal(entries, slot.id))
        },
        defaultMealId = "l",
        units = UnitPrefs.US,
        targets = targets,
        totals = DayMath.totals(entries, targets.budgetKcal),
    )

    /** Lazy rows only exist once scrolled into view. */
    private fun row(text: String) = compose.onNode(hasScrollToNodeAction())
        .performScrollToNode(hasText(text, substring = true))
        .let { compose.onNode(hasText(text, substring = true) and hasClickAction()) }

    /** REVIEW-2 S7: after a crash, Today offers the report; Not now lets it go. */
    @Test fun crashCardOffersTheReport() {
        var discarded = false
        compose.setContent {
            ForkcastTheme {
                TodayScreen(state, onEvent = {}, crashCard = { CrashReportCard(onSave = {}, onDiscard = { discarded = true }) })
            }
        }
        compose.onNodeWithText("Forkcast closed unexpectedly").assertIsDisplayed()
        compose.onNodeWithText("Save report…").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        assertThat(discarded).isTrue()
    }

    @Test fun showsRingMacrosAndEntries() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Sun, Oct 18").assertIsDisplayed()
        // On today there's no "Today" button, just the calendar.
        compose.onNodeWithContentDescription("Go to today").assertDoesNotExist()
        // 2,290 budget - 251 eaten = 2,039 left (the ring is one merged node with a spoken summary)
        compose.onNodeWithContentDescription("2,039 kcal left", substring = true).assertIsDisplayed()
        row("Greek yogurt").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("251 kcal"))
        compose.onNodeWithText("251 kcal").assertIsDisplayed()
    }

    /** Activity above usual: the ring names it in what's left; the Activity card shows steps, workouts, usual and the bonus. */
    @Test fun activityBonusShowsInRingAndActivityCard() {
        val run = app.forkcast.core.model.ExerciseSession("s", null, 56, 0, 40 * 60_000L, 310.0)
        val day = app.forkcast.core.model.ActivityDay(today, 8200, 400.0, 2650.0, 40, listOf(run))
        val withExercise = state.copy(
            totals = DayMath.totals(entries, targets.budgetKcal, 200.0),
            activity = ActivitySummary(true, true, day, app.forkcast.core.domain.ActivityBonus(400.0, 300.0, 28, 100.0, 200.0, true)),
        )
        compose.setContent { ForkcastTheme { TodayScreen(withExercise, onEvent = {}) } }
        // Energy amounts use a thin space before the unit.
        compose.onNodeWithContentDescription("including 200 kcal from activity. 2,239 kcal left", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("2,039 kcal left", substring = true).assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("toward this day", substring = true))
        compose.onNodeWithText("8,200 steps · 400 kcal active").assertExists()
        compose.onNodeWithText("Run · 40 min · 310 kcal").assertExists()
        compose.onNodeWithText("+200 kcal toward this day's budget").assertIsDisplayed()
        compose.onNodeWithText("Usual: 300 kcal active a day").assertExists()
    }

    @Test fun notConnectedActivityCardOffersHealthConnect() {
        var opened = false
        compose.setContent {
            ForkcastTheme { TodayScreen(state.copy(activity = state.activity.copy(available = true)), onEvent = {}, actions = TodayActions(openHealthConnect = { opened = true })) }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("See steps and workouts", substring = true))
        compose.onNodeWithText("See steps and workouts", substring = true).performClick()
        assertThat(opened).isTrue()
    }

    @Test fun logButtonOpensSheetForDefaultMealAndChipsSwitch() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        compose.onNodeWithContentDescription("Log").performClick()
        compose.onNodeWithText("Log to Lunch").assertIsDisplayed()
        compose.onAllNodesWithText("Dinner").filterToOne(hasClickAction()).performClick()
        compose.onNodeWithText("Log to Dinner").assertIsDisplayed()
    }

    @Test fun longPressSelects() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = { events += it }) } }
        row("Banana").performTouchInput { longClick() }
        assertThat(events).contains(TodayEvent.ToggleSelect("n"))
    }

    @Test fun tapOpensEditor() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        row("Banana").performClick()
        compose.onNodeWithText("Edit entry").assertIsDisplayed()
    }

    /** The edit sheet can switch the serving; the amount carries over (owner, 2026-10-19). */
    @Test fun editSheetSwitchesServing() {
        val events = mutableListOf<TodayEvent>()
        val servings = listOf(app.forkcast.core.model.ServingSize("serving", 100.0), app.forkcast.core.model.ServingSize("g", 1.0))
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = { events += it }, editServings = "n" to servings) } }
        row("Banana").performClick()
        compose.onAllNodesWithText("g").filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        val update = events.filterIsInstance<TodayEvent.UpdateEntry>().single()
        assertThat(update.serving?.label).isEqualTo("g")
        assertThat(update.quantity).isEqualTo(100.0)
    }

    @Test fun selectionBarDeletes() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(selection = setOf("n")), onEvent = { events += it }) } }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertThat(events).contains(TodayEvent.DeleteSelected)
    }

    @Test fun arrowsSendDayEvents() {
        val events = mutableListOf<TodayEvent>()
        // On a past day both arrows work; on today the next arrow is off (no future days).
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(date = today.minusDays(1)), onEvent = { events += it }) } }
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        assertThat(events).containsExactly(TodayEvent.PreviousDay, TodayEvent.NextDay).inOrder()
    }

    /** Water card (owner, 2026-10-23): the drop adds the last-used container; a tap on the total asks for another amount. */
    @Test fun waterDropAndOtherAmount() {
        val events = mutableListOf<TodayEvent>()
        val water = WaterSummary(ml = 500.0, targetMl = 2500.0, containers = listOf(WaterContainer.GLASS to 250.0, WaterContainer.BOTTLE to 500.0), lastUsed = WaterContainer.BOTTLE)
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(units = UnitPrefs.METRIC, water = water), onEvent = { events += it }) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("+ Bottle · 500 ml"))
        compose.onNodeWithText("+ Bottle · 500 ml").performClick()
        assertThat(events.last()).isEqualTo(TodayEvent.AddWaterContainer(WaterContainer.BOTTLE))
        compose.onNodeWithText("0.5 L / 2.5 L").performClick()
        compose.onNodeWithText("Amount (ml)").performTextInput("330")
        compose.onAllNodesWithText("Add").filterToOne(hasAnyAncestor(isDialog())).performClick()
        assertThat(events.last()).isEqualTo(TodayEvent.AddWater(330.0))
    }

    /** Long-press on the total: type the day's total (numbers only). */
    @Test fun waterTotalLongPressEditsTheDay() {
        val events = mutableListOf<TodayEvent>()
        val water = WaterSummary(ml = 500.0, targetMl = 2500.0, containers = listOf(WaterContainer.GLASS to 250.0))
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(units = UnitPrefs.METRIC, water = water), onEvent = { events += it }) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("0.5 L / 2.5 L"))
        compose.onNodeWithText("0.5 L / 2.5 L").performTouchInput { longClick() }
        compose.onNodeWithText("Total (ml)").performTextClearance()
        compose.onNodeWithText("Total (ml)").performTextInput("1200")
        compose.onAllNodesWithText("Save").filterToOne(hasAnyAncestor(isDialog())).performClick()
        assertThat(events.last()).isEqualTo(TodayEvent.SetWaterTotal(1200.0))
    }

    /** Undo shows after an addition and removes one at a time. */
    @Test fun waterUndoShowsWhileThereIsSomethingToUndo() {
        val events = mutableListOf<TodayEvent>()
        val water = WaterSummary(ml = 250.0, targetMl = 2500.0, containers = listOf(WaterContainer.GLASS to 250.0), undoCount = 1, undoToken = 1)
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(units = UnitPrefs.METRIC, water = water), onEvent = { events += it }) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Undo"))
        compose.onNodeWithText("Undo").performClick()
        assertThat(events).contains(TodayEvent.UndoWater)
    }

    @Test fun noNextDayOnToday() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        compose.onNodeWithContentDescription("Next day").assertIsNotEnabled()
    }

    @Test fun worksAtDoubleFontScale() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ForkcastTheme { TodayScreen(state, onEvent = {}) }
            }
        }
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Log").assertIsDisplayed()
        row("Banana").assertIsDisplayed()
    }

    /** The meal's subtotal and time are one 48 dp target; at 200 % they move under the name. */
    @Test fun mealTimeIsABigTargetAtDoubleFontScale() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                // Breakfast eaten at 7:30, so its header shows a time.
                val timed = state.copy(meals = state.meals.map { m -> m.copy(entries = m.entries.map { it.copy(eatenMinute = 450) }) })
                ForkcastTheme { TodayScreen(timed, onEvent = {}) }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Change Breakfast's time", substring = true))
        compose.onNode(hasContentDescription("Change Breakfast's time", substring = true) and hasClickAction())
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test fun otherDayIsObviousAndTodayIsOneTapAway() {
        val events = mutableListOf<TodayEvent>()
        val past = state.copy(date = today.minusDays(2), meals = state.meals.map { it.copy(entries = emptyList(), kcal = 0.0) })
        compose.setContent { ForkcastTheme { TodayScreen(past, onEvent = { events += it }) } }
        compose.onNodeWithText("Fri, Oct 16").assertIsDisplayed()
        compose.onNodeWithText("2 days ago").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go to today").performClick()
        assertThat(events).contains(TodayEvent.GoToToday)
    }

    @Test fun emptyDayPointsToTheLastLog() {
        val events = mutableListOf<TodayEvent>()
        val empty = state.copy(
            meals = state.meals.map { it.copy(entries = emptyList(), kcal = 0.0) },
            lastLogged = LoggedDay(today.minusDays(1), 6),
        )
        compose.setContent { ForkcastTheme { TodayScreen(empty, onEvent = { events += it }) } }
        row("Last logged: Yesterday · 6 entries").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        assertThat(events).contains(TodayEvent.PickDate(today.minusDays(1)))
    }

    @Test fun dayWithEntriesHasNoLastLoggedHint() {
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(lastLogged = LoggedDay(today.minusDays(1), 6)), onEvent = {}) } }
        compose.onNodeWithText("Last logged", substring = true).assertDoesNotExist()
    }

    @Test fun missingKeysAskOnceWithNames() {
        val events = mutableListOf<TodayEvent>()
        var opened: Boolean? = null
        val missing = listOf(
            app.forkcast.core.data.repository.MissingKey("ai:1", "Gemini (free)"),
            app.forkcast.core.data.repository.MissingKey("usda", null),
        )
        compose.setContent {
            ForkcastTheme { TodayScreen(state.copy(missingKeys = missing), onEvent = { events += it }, actions = TodayActions(openKeys = { opened = it })) }
        }
        compose.onNodeWithText("Gemini (free), USDA food database", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add keys").performClick()
        assertThat(opened).isTrue()
        compose.onNodeWithText("Not now").performClick()
        assertThat(events).contains(TodayEvent.DismissMissingKeys)
    }

    @Test fun trendsIconOnTodayOnly() {
        var trends = 0
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}, actions = TodayActions(openTrends = { trends++ })) } }
        compose.onNodeWithContentDescription("Open trends").performClick()
        assertThat(trends).isEqualTo(1)
    }
}
