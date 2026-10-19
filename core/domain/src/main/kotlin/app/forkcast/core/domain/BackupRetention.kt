package app.forkcast.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Which Drive backups to keep (SPEC §4.11): the newest [KEEP_RECENT], plus the first backup of
 * each of the last [KEEP_MONTHLY] months that have one. Everything else is deleted after a
 * successful upload, so a failing day never thins out the history.
 */
object BackupRetention {

    const val KEEP_RECENT = 14
    const val KEEP_MONTHLY = 12

    data class Backup(val id: String, val createdAt: Instant)

    fun toDelete(backups: List<Backup>, zone: ZoneId): Set<String> {
        val newestFirst = backups.sortedByDescending { it.createdAt }
        val keep = newestFirst.take(KEEP_RECENT).map { it.id }.toMutableSet()
        newestFirst
            .groupBy { YearMonth.from(it.createdAt.atZone(zone)) }
            .toSortedMap(compareByDescending { it })
            .values
            .take(KEEP_MONTHLY)
            .forEach { month -> keep += month.minBy { it.createdAt }.id }
        return backups.map { it.id }.toSet() - keep
    }

    /** Backups run at night: around this local time. */
    val NIGHT: LocalTime = LocalTime.of(3, 0)

    /** Time until the next [at] after [now] (tomorrow's if today's has passed). */
    fun delayUntil(now: ZonedDateTime, at: LocalTime = NIGHT): Duration {
        var next = now.with(at)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** A backup is overdue (worth a notification) after twice its period without a success. */
    fun overdue(lastSuccess: Instant?, connectedSince: Instant, periodDays: Int, now: Instant): Boolean {
        val since = lastSuccess ?: connectedSince
        return Duration.between(since, now) > Duration.ofDays(2L * periodDays)
    }
}
