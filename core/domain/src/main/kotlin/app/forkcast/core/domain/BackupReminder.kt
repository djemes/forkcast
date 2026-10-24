package app.forkcast.core.domain

import java.time.Duration
import java.time.Instant

/**
 * The gentle "no backup lately" reminder on Today (roadmap 2.0, task 1.4): due once [days] have
 * passed since the last backup (or, before any, since Forkcast was first opened on this phone)
 * and since the reminder was last dismissed.
 */
object BackupReminder {
    const val DAYS = 14

    fun due(lastBackup: Instant?, firstSeen: Instant?, dismissedAt: Instant?, now: Instant, days: Int = DAYS): Boolean {
        val since = listOfNotNull(lastBackup ?: firstSeen, dismissedAt).maxOrNull() ?: return false
        return Duration.between(since, now) >= Duration.ofDays(days.toLong())
    }
}
