package app.forkcast.debug

import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The demo data looks like two months of real use (roadmap 2.0 task 3.7; debug builds only). */
class DemoDataTest {

    private val today = LocalDate.of(2026, 10, 25)
    private val zone = ZoneId.of("America/New_York")
    private val slots = listOf(
        MealSlot("b", "Breakfast", 0, 240, 660), MealSlot("l", "Lunch", 1, 660, 900),
        MealSlot("d", "Dinner", 2, 1020, 1260), MealSlot("s", "Snacks", 3, 0, 1439),
    )
    private val export = DemoData.build(today, zone, slots, endKg = 82.0, nowMs = 0L)

    @Test fun coversSixtyDaysUpToTodayInThisPhonesMeals() {
        val dates = export.logEntries.map { it.date }.toSet()
        assertThat(dates.min()).isEqualTo(today.minusDays(DemoData.DAYS - 1L))
        assertThat(dates.max()).isEqualTo(today)
        assertThat(export.logEntries.map { it.mealSlotId }.toSet()).containsExactly("b", "l", "d", "s")
        assertThat(export.foods.map { it.id }).containsAtLeastElementsIn(export.logEntries.mapNotNull { it.foodId }.toSet())
    }

    @Test fun mostPastDaysAreFullyLoggedAndPlausible() {
        val byDay = export.logEntries.filter { it.date != today }.groupBy { it.date }
        val complete = byDay.values.count { day ->
            day.map { it.mealSlotId }.distinct().size >= 2 && day.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL] ?: 0.0 } >= 1000.0
        }
        // Enough for the adaptive maintenance estimate: 14 complete days in any 4 weeks.
        assertThat(complete).isAtLeast(45)
        byDay.values.forEach { day -> assertThat(day.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL] ?: 0.0 }).isLessThan(4000.0) }
    }

    @Test fun weightTrendsDownToTodayAndWaterIsLogged() {
        val first = export.weights.minBy { it.timestamp }
        val last = export.weights.maxBy { it.timestamp }
        assertThat(first.kg).isGreaterThan(last.kg)
        assertThat(last.kg).isWithin(0.5).of(82.0)
        assertThat(export.weights.size).isAtLeast(40)
        assertThat(export.water.map { it.date }.toSet()).hasSize(DemoData.DAYS)
    }

    @Test fun idsAreUniqueAndTheSameDayGivesTheSameData() {
        val ids = export.logEntries.map { it.id } + export.weights.map { it.id } + export.water.map { it.id } + export.foods.map { it.id }
        assertThat(ids).containsNoDuplicates()
        // Loading it twice on one day merges as unchanged rather than doubling the log.
        assertThat(DemoData.build(today, zone, slots, endKg = 82.0, nowMs = 0L)).isEqualTo(export)
    }
}
