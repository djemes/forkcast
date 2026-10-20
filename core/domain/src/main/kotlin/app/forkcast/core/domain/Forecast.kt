package app.forkcast.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The forecast arc and Evening outlook (SPEC §4.2, §4.8): expected additional intake for the
 * rest of today = median kcal the user logged after the current time of day over their last
 * 14 logged days. Needs 7+ logged days; before that there is no forecast (no fake data).
 */
object Forecast {
    const val MIN_DAYS = 7
    const val WINDOW_DAYS = 14
    /** "Tight" = forecast up to 5% over budget (DESIGN.md §5). */
    const val TIGHT_MARGIN = 0.05

    /** One logged item: its day and the local minute of day it was logged. */
    data class Logged(val date: LocalDate, val minuteOfDay: Int, val kcal: Double)

    enum class Status { ON_COURSE, TIGHT, LIKELY_OVER, OVER }

    data class Outlook(
        val status: Status,
        val eatenKcal: Double,
        /** Median kcal usually logged after now. */
        val expectedRemainingKcal: Double,
        val forecastTotalKcal: Double,
        /** LIKELY_OVER: rounded to the nearest 10; OVER: eaten minus budget. */
        val overByKcal: Double,
    )

    /** Median kcal logged after [minuteOfDay] on the last 14 logged days before [today], or null with < 7 days. */
    fun expectedAfter(history: List<Logged>, today: LocalDate, minuteOfDay: Int): Double? {
        val days = history.filter { it.date < today && it.kcal > 0 }.map { it.date }.distinct().sortedDescending().take(WINDOW_DAYS)
        if (days.size < MIN_DAYS) return null
        val perDay = days.map { d -> history.filter { it.date == d && it.minuteOfDay > minuteOfDay }.sumOf { it.kcal } }
        return median(perDay)
    }

    fun outlook(eatenKcal: Double, budgetKcal: Double, expectedRemainingKcal: Double): Outlook {
        val total = eatenKcal + expectedRemainingKcal
        return when {
            eatenKcal > budgetKcal -> Outlook(Status.OVER, eatenKcal, expectedRemainingKcal, total, eatenKcal - budgetKcal)
            total <= budgetKcal -> Outlook(Status.ON_COURSE, eatenKcal, expectedRemainingKcal, total, 0.0)
            total <= budgetKcal * (1 + TIGHT_MARGIN) -> Outlook(Status.TIGHT, eatenKcal, expectedRemainingKcal, total, total - budgetKcal)
            else -> Outlook(Status.LIKELY_OVER, eatenKcal, expectedRemainingKcal, total, ((total - budgetKcal) / 10.0).roundToInt() * 10.0)
        }
    }

    /**
     * The minute of day an entry was eaten, for [Logged]: when it was logged if that was on the
     * entry's own day, else the start of its meal's window (else noon). Yesterday's dinner typed
     * in this morning, or a "Same as yesterday" copy at 7 pm, would otherwise count at that hour.
     */
    fun minuteEaten(date: LocalDate, loggedAt: Instant, zone: ZoneId, slotStartMinute: Int?): Int {
        val t = HealthRules.nutritionStart(date, loggedAt, zone, slotStartMinute).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
