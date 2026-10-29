package app.forkcast.core.domain

import app.forkcast.core.model.BackupSettings
import app.forkcast.core.model.RetentionPreset
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Which backups in the folder to keep (owner, 2026-10-29) and when the next one runs. Applied
 * after each backup to Forkcast's own files only, so a failing week never thins out the history.
 */
object BackupRetention {

    /** The newest [recent], plus the newest backup of each of the last [months] months and [years] years that have one. */
    data class Rule(val recent: Int, val months: Int = 0, val years: Int = 0)

    data class Backup(val id: String, val createdAt: Instant)

    /** Simple 5 · Balanced 7 + 6 months · Long-term 14 + 12 months + every year · Custom. */
    fun rule(settings: BackupSettings): Rule = when (settings.retention) {
        RetentionPreset.SIMPLE -> Rule(recent = 5)
        RetentionPreset.BALANCED -> Rule(recent = 7, months = 6)
        RetentionPreset.LONG_TERM -> Rule(recent = 14, months = 12, years = EVERY_YEAR)
        RetentionPreset.CUSTOM -> Rule(
            recent = settings.customRecent.coerceIn(1, MAX_RECENT),
            months = settings.customMonths.coerceIn(0, MAX_MONTHS),
            years = settings.customYears.coerceIn(0, MAX_YEARS),
        )
    }

    fun toDelete(backups: List<Backup>, zone: ZoneId, rule: Rule): Set<String> {
        val newestFirst = backups.sortedByDescending { it.createdAt }
        val keep = newestFirst.take(rule.recent.coerceAtLeast(1)).map { it.id }.toMutableSet()
        fun newestPer(period: (Backup) -> Comparable<*>, count: Int) {
            if (count <= 0) return
            // newestFirst is sorted, so each group's first is its newest and groups come newest first.
            newestFirst.groupBy(period).values.take(count).forEach { keep += it.first().id }
        }
        newestPer({ YearMonth.from(it.createdAt.atZone(zone)) }, rule.months)
        newestPer({ Year.from(it.createdAt.atZone(zone)) }, rule.years)
        return backups.map { it.id }.toSet() - keep
    }

    /**
     * The next run on one of [days] (ISO, 1 = Monday) at [minute] after midnight, after [now].
     * Null when no day is chosen.
     */
    fun nextRun(now: ZonedDateTime, days: Set<Int>, minute: Int): ZonedDateTime? {
        val valid = days.filter { it in 1..7 }.toSet()
        if (valid.isEmpty()) return null
        val at = LocalTime.of(minute.coerceIn(0, MINUTES_PER_DAY - 1) / 60, minute.coerceIn(0, MINUTES_PER_DAY - 1) % 60)
        var next = now.with(at)
        while (!next.isAfter(now) || next.dayOfWeek.value !in valid) next = next.plusDays(1)
        return next
    }

    fun delayUntilNext(now: ZonedDateTime, days: Set<Int>, minute: Int): Duration? =
        nextRun(now, days, minute)?.let { Duration.between(now, it) }

    /**
     * Whether a daily run at [now] belongs to a chosen day: the day of the slot ([minute] after
     * midnight) nearest to [now], so a late-evening backup that Android runs after midnight still
     * counts for the evening's day.
     */
    fun isChosenDay(now: ZonedDateTime, days: Set<Int>, minute: Int): Boolean {
        val m = minute.coerceIn(0, MINUTES_PER_DAY - 1)
        val today = now.with(LocalTime.of(m / 60, m % 60))
        val nearest = listOf(today.minusDays(1), today, today.plusDays(1)).minBy { Duration.between(it, now).abs() }
        return nearest.dayOfWeek.value in days
    }

    const val EVERY_YEAR = Int.MAX_VALUE
    const val MAX_RECENT = 60
    const val MAX_MONTHS = 36
    const val MAX_YEARS = 20
    private const val MINUTES_PER_DAY = 24 * 60
}
