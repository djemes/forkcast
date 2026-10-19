package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthRulesTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 18)

    @Test fun mealKindByNameThenWindow() {
        assertThat(HealthRules.mealKind("Breakfast", null)).isEqualTo(MealKind.BREAKFAST)
        assertThat(HealthRules.mealKind("Almoço", null)).isEqualTo(MealKind.LUNCH)
        assertThat(HealthRules.mealKind("Late dinner", 23 * 60)).isEqualTo(MealKind.DINNER)
        assertThat(HealthRules.mealKind("Snacks", null)).isEqualTo(MealKind.SNACK)
        assertThat(HealthRules.mealKind("Pre-workout", 7 * 60)).isEqualTo(MealKind.BREAKFAST)
        assertThat(HealthRules.mealKind("Meal 2", 12 * 60)).isEqualTo(MealKind.LUNCH)
        assertThat(HealthRules.mealKind("Meal 3", 19 * 60)).isEqualTo(MealKind.DINNER)
        assertThat(HealthRules.mealKind("Meal 4", 16 * 60)).isEqualTo(MealKind.SNACK)
        assertThat(HealthRules.mealKind("Other", null)).isEqualTo(MealKind.UNKNOWN)
    }

    @Test fun nutritionStartUsesLoggedTimeOnTheSameDay() {
        val logged = day.atTime(12, 40).atZone(zone).toInstant()
        assertThat(HealthRules.nutritionStart(day, logged, zone, 11 * 60)).isEqualTo(logged)
    }

    @Test fun nutritionStartForAnotherDayUsesMealStartOrNoon() {
        val logged = day.atTime(9, 0).atZone(zone).toInstant()
        val yesterday = day.minusDays(1)
        assertThat(HealthRules.nutritionStart(yesterday, logged, zone, 17 * 60))
            .isEqualTo(yesterday.atTime(17, 0).atZone(zone).toInstant())
        assertThat(HealthRules.nutritionStart(yesterday, logged, zone, null))
            .isEqualTo(yesterday.atTime(12, 0).atZone(zone).toInstant())
    }

    @Test fun duplicateIsTheNearestManualWithinTenMinutes() {
        val t = Instant.parse("2026-10-18T11:00:00Z")
        val manual = listOf(t.minus(Duration.ofMinutes(9)), t.plus(Duration.ofMinutes(3)), t.plus(Duration.ofMinutes(11)))
        assertThat(HealthRules.duplicateOf(t, manual) { it }).isEqualTo(t.plus(Duration.ofMinutes(3)))
        assertThat(HealthRules.duplicateOf(t, listOf(t.plus(Duration.ofMinutes(10)))) { it }).isNotNull()
        assertThat(HealthRules.duplicateOf(t, listOf(t.minus(Duration.ofMinutes(11)))) { it }).isNull()
    }
}
