package app.forkcast.core.domain

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.Food
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class WeeklyCheckInTest {

    private val start = LocalDate.of(2026, 10, 12) // Monday
    private val end = start.plusDays(6)

    private fun input(
        kcal: Map<LocalDate, Double> = (0L..5L).associate { start.plusDays(it) to 2000.0 },
        bonus: Map<LocalDate, Double> = mapOf(start to 100.0),
        before: Double? = 90.0,
        after: Double? = 89.5,
        weighIns: Int = 5,
        planned: Double? = -0.5,
    ) = WeeklyCheckIn.Input(
        start, end, kcal, kcal.keys.take(4).toSet(), 2150, bonus, before, after, weighIns, planned,
        maintenanceKcal = 2700.0, maintenanceBeforeKcal = 2650.0,
        stepsByDay = mapOf(start to 8000L, start.plusDays(1) to 10000L),
        activeKcalByDay = mapOf(start to 400.0, start.plusDays(1) to 500.0),
    )

    @Test fun summarizesFoodAgainstTheBudgetOnCompleteDays() {
        val s = WeeklyCheckIn.summarize(input())
        assertThat(s.daysLogged).isEqualTo(6)
        assertThat(s.completeDays).isEqualTo(4)
        assertThat(s.averagedOverComplete).isTrue()
        assertThat(s.averagedDays).isEqualTo(4)
        assertThat(s.avgIntakeKcal).isEqualTo(2000.0)
        // Budget 2,150 on the 4 complete days plus a 100 bonus on one of them.
        assertThat(s.avgAllowanceKcal!!).isWithin(0.01).of(2150.0 + 100.0 / 4)
        assertThat(s.avgDifferenceKcal!!).isWithin(0.01).of(2000.0 - 2150.0 - 100.0 / 4)
        assertThat(s.totalBonusKcal).isEqualTo(100.0)
        assertThat(s.maintenanceChangeKcal).isEqualTo(50.0)
        assertThat(s.avgSteps).isEqualTo(9000L)
        assertThat(s.avgActiveKcal).isEqualTo(450.0)
    }

    @Test fun paceAgainstThePlan() {
        assertThat(WeeklyCheckIn.summarize(input(after = 89.5)).pace).isEqualTo(WeeklyCheckIn.Pace.ON_PACE)
        assertThat(WeeklyCheckIn.summarize(input(after = 89.2)).pace).isEqualTo(WeeklyCheckIn.Pace.AHEAD)
        assertThat(WeeklyCheckIn.summarize(input(after = 89.9)).pace).isEqualTo(WeeklyCheckIn.Pace.BEHIND)
        assertThat(WeeklyCheckIn.summarize(input(weighIns = 1)).pace).isEqualTo(WeeklyCheckIn.Pace.NO_DATA)
        // Maintaining: steady within 0.15 kg is on pace.
        assertThat(WeeklyCheckIn.pace(0.1, null, 4)).isEqualTo(WeeklyCheckIn.Pace.ON_PACE)
        assertThat(WeeklyCheckIn.pace(0.4, null, 4)).isEqualTo(WeeklyCheckIn.Pace.BEHIND)
        // Gaining.
        assertThat(WeeklyCheckIn.pace(0.6, 0.25, 4)).isEqualTo(WeeklyCheckIn.Pace.AHEAD)
    }

    /** A half-logged day would make intake look low, so it's left out when complete days exist. */
    @Test fun incompleteDaysOnlyWhenNothingIsComplete() {
        val kcal = mapOf(start to 2000.0, start.plusDays(1) to 400.0)
        val withComplete = WeeklyCheckIn.summarize(input(kcal = kcal).copy(completeDays = setOf(start)))
        assertThat(withComplete.avgIntakeKcal).isEqualTo(2000.0)
        val none = WeeklyCheckIn.summarize(input(kcal = kcal).copy(completeDays = emptySet()))
        assertThat(none.avgIntakeKcal).isEqualTo(1200.0)
        assertThat(none.averagedOverComplete).isFalse()
    }

    @Test fun emptyWeek() {
        val s = WeeklyCheckIn.summarize(input(kcal = emptyMap(), before = null, after = null, weighIns = 0))
        assertThat(s.daysLogged).isEqualTo(0)
        assertThat(s.avgIntakeKcal).isNull()
        assertThat(s.trendChangeKg).isNull()
        assertThat(s.pace).isEqualTo(WeeklyCheckIn.Pace.NO_DATA)
    }

    @Test fun lastWeekEndsTheDayBeforeTheCheckInDay() {
        val wednesday = LocalDate.of(2026, 10, 21)
        assertThat(WeeklyCheckIn.lastWeek(wednesday, DayOfWeek.MONDAY)).isEqualTo(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18))
        assertThat(WeeklyCheckIn.lastWeek(LocalDate.of(2026, 10, 19), DayOfWeek.MONDAY).start).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(WeeklyCheckIn.lastWeek(wednesday, DayOfWeek.SUNDAY)).isEqualTo(LocalDate.of(2026, 10, 11)..LocalDate.of(2026, 10, 17))
    }

    // ---- Edit sheet servings (owner, 2026-10-19)

    private val rice = Food("f", "Rice, cooked", null, FoodSource.USDA, "1", null, listOf(ServingSize("1 cup", 158.0)),
        mapOf(NutrientKey.ENERGY_KCAL to 130.0), false, null, null, null)
    private fun entry(grams: Double?, quantity: Double = 1.0, label: String = "1 cup") = LogEntry(
        "e", start, "l", "f", "Rice", null, quantity, label, grams, mapOf(NutrientKey.ENERGY_KCAL to 205.0), FoodSource.USDA, null, Instant.EPOCH,
    )

    @Test fun editServingsComeFromTheFoodOrTheEntry() {
        assertThat(FoodLogging.editServings(entry(158.0), rice).map { it.label }).containsExactly("1 cup", "g", "oz").inOrder()
        // Food gone: the entry's own serving at its logged grams, then g and oz.
        val noFood = FoodLogging.editServings(entry(316.0, quantity = 2.0), null)
        assertThat(noFood.first()).isEqualTo(ServingSize("1 cup", 158.0))
        assertThat(noFood.map { it.label }).containsExactly("1 cup", "g", "oz").inOrder()
        // Quick add: no grams, only the quantity changes.
        assertThat(FoodLogging.editServings(entry(null), rice)).isEmpty()
    }

    @Test fun switchingServingKeepsTheAmount() {
        assertThat(FoodLogging.convertQuantity(1.5, ServingSize("1 cup", 158.0), ServingSize("g", 1.0))).isEqualTo(237.0)
        assertThat(FoodLogging.convertQuantity(237.0, ServingSize("g", 1.0), ServingSize("1 cup", 158.0))).isWithin(1e-9).of(1.5)
    }
}
