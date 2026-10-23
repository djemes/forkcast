package app.forkcast.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When food was eaten (owner, 2026-10-23). Entries keep a time of day (minutes after local
 * midnight on their date). New entries default to the time they're logged; entries for another
 * day, and entries from before meal times existed, get a typical time for their meal.
 */
object MealTimes {
    /** Snacks and meals without a window, logged on another day: mid-afternoon. */
    const val FALLBACK_MINUTE = 15 * 60

    /** The middle of a meal's window (wrapping past midnight), or null when it has none. */
    fun windowMiddle(startMinute: Int?, endMinute: Int?): Int? {
        if (startMinute == null || endMinute == null) return null
        val length = (endMinute - startMinute + DAY) % DAY
        return (startMinute + length / 2) % DAY
    }

    /** Minute of day of [instant] in [zone], if that falls on [date]. */
    fun minuteOn(date: LocalDate, instant: Instant, zone: ZoneId): Int? {
        val t = instant.atZone(zone)
        return if (t.toLocalDate() == date) t.hour * 60 + t.minute else null
    }

    /**
     * The typical time for an entry in a meal: the middle of its window; without a window, when it
     * was logged if that was on the day, else [FALLBACK_MINUTE]. Always an estimate.
     */
    fun typicalMinute(startMinute: Int?, endMinute: Int?, date: LocalDate, loggedAt: Instant, zone: ZoneId): Int =
        windowMiddle(startMinute, endMinute) ?: minuteOn(date, loggedAt, zone) ?: FALLBACK_MINUTE

    /**
     * A new entry's time and whether it's estimated: when it's logged, if that is on the entry's
     * day (the owner's default); otherwise the meal's typical time.
     */
    fun forNewEntry(startMinute: Int?, endMinute: Int?, date: LocalDate, loggedAt: Instant, zone: ZoneId): Pair<Int, Boolean> =
        minuteOn(date, loggedAt, zone)?.let { it to false }
            ?: (typicalMinute(startMinute, endMinute, date, loggedAt, zone) to true)

    /** No time later than now on today (no future, like dates: owner, 2026-10-19). */
    fun clampToNow(minute: Int, date: LocalDate, today: LocalDate, nowMinute: Int): Int =
        if (date == today) minOf(minute, nowMinute) else minute.coerceIn(0, DAY - 1)

    /** The meal's time: when its first item was eaten (a coffee added later doesn't move breakfast). */
    fun mealMinute(entryMinutes: List<Int?>): Int? = entryMinutes.filterNotNull().minOrNull()

    private const val DAY = 24 * 60
}
