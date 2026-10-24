package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class MealTimingTest {
    private val d0 = LocalDate.of(2026, 10, 20)
    private fun day(n: Int) = d0.plusDays(n.toLong())
    private fun item(n: Int, slot: String, h: Int, m: Int, kcal: Double) =
        MealTiming.Item(day(n), slot, h * 60 + m, kcal)

    /** Four days: breakfast around 7:30, dinner around 7 pm, one late snack on the over-budget days. */
    private val items = listOf(
        item(0, "b", 7, 20, 400.0), item(0, "d", 19, 0, 700.0), item(0, "s", 21, 30, 400.0),
        item(1, "b", 7, 40, 400.0), item(1, "d", 18, 50, 700.0),
        item(2, "b", 7, 30, 400.0), item(2, "d", 19, 10, 700.0), item(2, "s", 22, 0, 500.0),
        item(3, "b", 8, 0, 400.0), item(3, "d", 19, 20, 700.0),
    )
    private val allowance = (0..3).associate { day(it) to 1500.0 }

    @Test fun typicalTimesWindowAndLateEating() {
        val i = MealTiming.insights(items, allowance, listOf("b", "l", "d"))!!
        assertThat(i.days).isEqualTo(4)
        // Breakfast 7:20, 7:30, 7:40, 8:00 → median 7:35, spread 10 min; no lunch logged.
        assertThat(i.meals.map { it.mealSlotId }).containsExactly("b", "d").inOrder()
        assertThat(i.meals[0].medianMinute).isEqualTo(7 * 60 + 35)
        assertThat(i.meals[0].spreadMinutes).isEqualTo(10)
        assertThat(i.windowStartMinute).isEqualTo(7 * 60 + 35)
        // Last bites 21:30, 18:50, 22:00, 19:20 → median 20:25.
        assertThat(i.windowEndMinute).isEqualTo(20 * 60 + 25)
        assertThat(i.lateKcalPerDay).isWithin(0.1).of(225.0)
        assertThat(i.lateShare).isWithin(0.001).of(900.0 / 5300.0)
        // Only day 2 (1,600 kcal) is over 1,500: one day is too few to compare.
        assertThat(i.comparison).isNull()
    }

    @Test fun overBudgetDaysCompareLateEating() {
        val tight = allowance.mapValues { 1400.0 }
        val c = MealTiming.insights(items, tight, listOf("b", "d"))!!.comparison!!
        assertThat(c.overDays).isEqualTo(2)
        assertThat(c.lateKcalOver).isWithin(0.1).of(450.0)
        assertThat(c.otherDays).isEqualTo(2)
        assertThat(c.lateKcalOther).isWithin(0.1).of(0.0)
    }

    /** Days with an item that has no time are left out; under three days says nothing. */
    @Test fun daysWithUntimedItemsDontCount() {
        val untimed = items.map { if (it.date.isBefore(day(2))) it.copy(minute = null) else it }
        assertThat(MealTiming.insights(untimed, allowance, listOf("b", "d"))).isNull()
    }
}
