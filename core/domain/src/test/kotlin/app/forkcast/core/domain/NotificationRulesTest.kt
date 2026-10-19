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
}
