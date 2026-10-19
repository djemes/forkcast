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

    /** A meal for the reminder rules: id, name, and the time to use when it has none yet. */
    data class Meal(val id: String, val name: String, val fallbackMinute: Int)

    /** Meal reminders as stored: the category switch, enabled meals with times, and remembered times of disabled ones. */
    data class MealReminders(val categoryOn: Boolean, val enabled: Map<String, Int>, val off: Map<String, Int>)

    /** Meals that remind, with times: what's enabled, or (never set up) the defaults. */
    fun effectiveMeals(enabled: Map<String, Int>, off: Map<String, Int>, meals: List<Meal>): Map<String, Int> =
        if (enabled.isEmpty() && off.isEmpty()) defaultMealReminders(meals.map { it.id to it.name }) else enabled

    /**
     * One meal switched (owner, 2026-10-19). Turning off the last enabled meal turns the
     * category off instead, keeping the setup so it comes back as it was.
     */
    fun setMeal(r: MealReminders, meals: List<Meal>, mealId: String, on: Boolean): MealReminders {
        val current = effectiveMeals(r.enabled, r.off, meals)
        val fallback = meals.firstOrNull { it.id == mealId }?.fallbackMinute ?: DEFAULT_DINNER_MINUTE
        return if (on) {
            val minute = r.off[mealId] ?: current[mealId] ?: fallback
            MealReminders(true, current + (mealId to minute), r.off - mealId)
        } else {
            val minute = current[mealId] ?: return r
            if (current.keys == setOf(mealId)) MealReminders(false, current, r.off)
            else MealReminders(r.categoryOn, current - mealId, r.off + (mealId to minute))
        }
    }

    /** The category switched: off keeps every meal's setting; on restores it, or (nothing to restore) turns all meals on. */
    fun setCategory(r: MealReminders, meals: List<Meal>, on: Boolean): MealReminders {
        val current = effectiveMeals(r.enabled, r.off, meals)
        if (!on) return MealReminders(false, current, r.off)
        if (current.isNotEmpty()) return MealReminders(true, current, r.off)
        return MealReminders(true, meals.associate { it.id to (r.off[it.id] ?: it.fallbackMinute) }, emptyMap())
    }

    /**
     * The next time any reminder is due after [nowMinute]: minutes from now, wrapping to
     * tomorrow. Null when there are no reminders.
     */
    fun minutesUntilNext(nowMinute: Int, reminderMinutes: Collection<Int>): Int? =
        reminderMinutes.minOfOrNull { m -> if (m > nowMinute) m - nowMinute else m + 24 * 60 - nowMinute }
}
