package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationRulesTest {

    @Test fun thresholdFiresOncePerDay() {
        val first = NotificationRules.budgetThresholds(1700.0, 2100.0, listOf(80, 100), emptySet())
        assertThat(first.announce).isEqualTo(80)
        // Editing down and back up: 80 already fired, nothing new.
        val again = NotificationRules.budgetThresholds(1750.0, 2100.0, listOf(80, 100), setOf(80))
        assertThat(again.announce).isNull()
        assertThat(NotificationRules.budgetThresholds(1000.0, 2100.0, listOf(80, 100), emptySet()).announce).isNull()
    }

    @Test fun bigEntryAnnouncesOnlyTheHighestButRecordsAll() {
        val d = NotificationRules.budgetThresholds(2400.0, 2100.0, listOf(80, 100, 110), emptySet())
        assertThat(d.announce).isEqualTo(110)
        assertThat(d.record).containsExactly(80, 100, 110)
    }

    @Test fun quietHoursWrapMidnight() {
        assertThat(NotificationRules.inQuietHours(23 * 60, 22 * 60, 7 * 60)).isTrue()
        assertThat(NotificationRules.inQuietHours(6 * 60 + 59, 22 * 60, 7 * 60)).isTrue()
        assertThat(NotificationRules.inQuietHours(7 * 60, 22 * 60, 7 * 60)).isFalse()
        assertThat(NotificationRules.inQuietHours(13 * 60, 12 * 60, 14 * 60)).isTrue()
        assertThat(NotificationRules.inQuietHours(13 * 60, 0, 0)).isFalse()
    }

    @Test fun proteinCheckOnlyWhenMoreThanAQuarterLeft() {
        assertThat(NotificationRules.proteinCheckDue(eatenG = 80.0, targetG = 140.0)).isTrue()
        assertThat(NotificationRules.proteinCheckDue(eatenG = 110.0, targetG = 140.0)).isFalse()
    }

    @Test fun defaultMealRemindersByName() {
        val m = NotificationRules.defaultMealReminders(listOf("b" to "Breakfast", "l" to "Lunch", "d" to "dinner"))
        assertThat(m).containsExactly("l", 13 * 60 + 30, "d", 20 * 60)
    }

    @Test fun nextReminderWrapsToTomorrow() {
        assertThat(NotificationRules.minutesUntilNext(12 * 60, listOf(13 * 60 + 30, 20 * 60))).isEqualTo(90)
        assertThat(NotificationRules.minutesUntilNext(21 * 60, listOf(7 * 60 + 30))).isEqualTo(10 * 60 + 30)
        assertThat(NotificationRules.minutesUntilNext(12 * 60, emptyList())).isNull()
    }

    private val meals = listOf(
        NotificationRules.Meal("b", "Breakfast", 11 * 60),
        NotificationRules.Meal("l", "Lunch", 15 * 60),
        NotificationRules.Meal("d", "Dinner", 22 * 60),
    )

    @Test fun neverSetUpUsesDefaults() {
        assertThat(NotificationRules.effectiveMeals(emptyMap(), emptyMap(), meals)).containsExactly("l", 13 * 60 + 30, "d", 20 * 60)
    }

    /** Turning off the last meal turns the category off and keeps that meal, so it all comes back as it was. */
    @Test fun lastMealOffTurnsTheCategoryOffAndOnRestores() {
        var r = NotificationRules.MealReminders(true, emptyMap(), emptyMap())
        r = NotificationRules.setMeal(r, meals, "l", false)
        assertThat(r).isEqualTo(NotificationRules.MealReminders(true, mapOf("d" to 1200), mapOf("l" to 810)))
        r = NotificationRules.setMeal(r, meals, "d", false)
        assertThat(r.categoryOn).isFalse()
        assertThat(r.enabled).containsExactly("d", 1200)
        r = NotificationRules.setCategory(r, meals, true)
        assertThat(r).isEqualTo(NotificationRules.MealReminders(true, mapOf("d" to 1200), mapOf("l" to 810)))
        // Back on: Lunch returns at its old time.
        r = NotificationRules.setMeal(r, meals, "l", true)
        assertThat(r.enabled).containsExactly("d", 1200, "l", 810)
    }

    @Test fun pausingTheCategoryKeepsEveryMeal() {
        var r = NotificationRules.MealReminders(true, mapOf("b" to 600, "d" to 1230), mapOf("l" to 800))
        r = NotificationRules.setCategory(r, meals, false)
        r = NotificationRules.setCategory(r, meals, true)
        assertThat(r).isEqualTo(NotificationRules.MealReminders(true, mapOf("b" to 600, "d" to 1230), mapOf("l" to 800)))
    }

    @Test fun nothingEverEnabledTurnsAllOn() {
        val noDefaults = listOf(NotificationRules.Meal("x", "Meal 1", 600), NotificationRules.Meal("y", "Meal 2", 900))
        val r = NotificationRules.setCategory(NotificationRules.MealReminders(false, emptyMap(), emptyMap()), noDefaults, true)
        assertThat(r.enabled).containsExactly("x", 600, "y", 900)
    }
}
