package app.forkcast.core.domain

/**
 * When notifications fire (SPEC §4.8). Pure rules; the data layer posts them and keeps the
 * once-per-day log.
 */
object NotificationRules {
    const val DEFAULT_LUNCH_MINUTE = 13 * 60 + 30
    const val DEFAULT_DINNER_MINUTE = 20 * 60
    /** Protein check fires when more than this share of the target is still to go. */
    const val PROTEIN_REMAINING_SHARE = 0.25

    /**
     * Thresholds (percent of budget) the day has reached and that haven't fired today. When a
     * big entry crosses several at once, only the highest is announced; all are recorded, so
     * editing entries down and back up never re-fires them.
     */
    data class ThresholdDecision(val announce: Int?, val record: List<Int>)

    fun budgetThresholds(eatenKcal: Double, budgetKcal: Double, thresholds: List<Int>, alreadyFired: Set<Int>): ThresholdDecision {
        if (budgetKcal <= 0) return ThresholdDecision(null, emptyList())
        val pct = eatenKcal / budgetKcal * 100
        val reached = thresholds.filter { pct >= it && it !in alreadyFired }.sorted()
        return ThresholdDecision(reached.lastOrNull(), reached)
    }

    /** Quiet hours may wrap past midnight (22:00–07:00 by default). */
    fun inQuietHours(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun proteinCheckDue(eatenG: Double, targetG: Double): Boolean =
        targetG > 0 && (targetG - eatenG) > targetG * PROTEIN_REMAINING_SHARE

    /** Default meal reminders, matched by meal name: Lunch 13:30, Dinner 20:00. */
    fun defaultMealReminders(slots: List<Pair<String, String>>): Map<String, Int> = buildMap {
        for ((id, name) in slots) {
            when (name.trim().lowercase()) {
                "lunch" -> put(id, DEFAULT_LUNCH_MINUTE)
                "dinner" -> put(id, DEFAULT_DINNER_MINUTE)
            }
        }
    }

    /**
     * The next time any reminder is due after [nowMinute]: minutes from now, wrapping to
     * tomorrow. Null when there are no reminders.
     */
    fun minutesUntilNext(nowMinute: Int, reminderMinutes: Collection<Int>): Int? =
        reminderMinutes.minOfOrNull { m -> if (m > nowMinute) m - nowMinute else m + 24 * 60 - nowMinute }
}
