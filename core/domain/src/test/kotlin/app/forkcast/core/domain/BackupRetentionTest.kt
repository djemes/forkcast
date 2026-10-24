package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupRetentionTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(date: LocalDate) = date.atTime(3, 0).atZone(zone).toInstant()

    @Test fun keepsFourteenNewestAndFirstOfEachOfTwelveMonths() {
        // One backup a day for 500 days.
        val last = LocalDate.of(2026, 10, 19)
        val all = (0L until 500L).map { BackupRetention.Backup("d$it", at(last.minusDays(it))) }
        val delete = BackupRetention.toDelete(all, zone)
        val kept = all.filter { it.id !in delete }.map { it.createdAt.atZone(zone).toLocalDate() }
        // 14 newest: Oct 6–19. Monthly: Oct 1 and the 1st of Nov 2025 … Sep 2026 (12 months: Nov..Oct).
        assertThat(kept.filter { it >= LocalDate.of(2026, 10, 6) }).hasSize(14)
        val firsts = kept.filter { it < LocalDate.of(2026, 10, 6) }
        assertThat(firsts).containsExactlyElementsIn((0L until 12L).map { LocalDate.of(2026, 10, 1).minusMonths(it) })
        assertThat(kept).hasSize(26)
    }

    @Test fun fewBackupsAreAllKept() {
        val all = (0L until 5L).map { BackupRetention.Backup("d$it", at(LocalDate.of(2026, 10, 19).minusDays(it))) }
        assertThat(BackupRetention.toDelete(all, zone)).isEmpty()
    }

    @Test fun nextNightRun() {
        val evening = ZonedDateTime.of(LocalDate.of(2026, 10, 19), LocalTime.of(21, 30), zone)
        assertThat(BackupRetention.delayUntil(evening)).isEqualTo(Duration.ofMinutes(5 * 60 + 30))
        val early = ZonedDateTime.of(LocalDate.of(2026, 10, 19), LocalTime.of(1, 0), zone)
        assertThat(BackupRetention.delayUntil(early)).isEqualTo(Duration.ofHours(2))
    }

    @Test fun overdueAfterTwoPeriods() {
        val now = Instant.parse("2026-10-19T12:00:00Z")
        assertThat(BackupRetention.overdue(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(30)), 1, now)).isFalse()
        assertThat(BackupRetention.overdue(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(30)), 1, now)).isTrue()
        assertThat(BackupRetention.overdue(null, now.minus(Duration.ofDays(1)), 7, now)).isFalse()
    }

    /** Clocks change at 2 am in the US; the 3 am backup still lands at 3 am local, an hour sooner or later. */
    @Test fun nightRunAcrossDaylightSavingChanges() {
        val ny = java.time.ZoneId.of("America/New_York")
        // Spring forward (2026-03-08): 1:00 → 3:00 is one real hour (2:00–3:00 doesn't exist).
        val spring = java.time.ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny)
        assertThat(BackupRetention.delayUntil(spring)).isEqualTo(Duration.ofHours(1))
        // Fall back (2026-11-01): 0:30 → 3:00 is three and a half real hours (1:00–2:00 happens twice).
        val fall = java.time.ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ny)
        assertThat(BackupRetention.delayUntil(fall)).isEqualTo(Duration.ofMinutes(3 * 60 + 30))
    }

    /** New installs back up weekly on Mondays around 3 am (owner, 2026-10-23). */
    @Test fun weeklyBackupWaitsForMondayNight() {
        val zone = java.time.ZoneId.of("America/New_York")
        // Friday 2026-10-23, 10 pm → Monday 2026-10-26, 3 am.
        val friday = java.time.ZonedDateTime.of(2026, 10, 23, 22, 0, 0, 0, zone)
        assertThat(BackupRetention.delayUntilWeekday(friday, 1)).isEqualTo(java.time.Duration.ofHours(53))
        // Monday at 2 am → the same night at 3 am; at 4 am → next Monday.
        val monday = java.time.ZonedDateTime.of(2026, 10, 26, 2, 0, 0, 0, zone)
        assertThat(BackupRetention.delayUntilWeekday(monday, 1)).isEqualTo(java.time.Duration.ofHours(1))
        assertThat(BackupRetention.delayUntilWeekday(monday.withHour(4), 1)).isEqualTo(java.time.Duration.ofDays(7).minusHours(1))
        assertThat(app.forkcast.core.model.BackupSettings().frequency).isEqualTo(app.forkcast.core.model.BackupFrequency.WEEKLY)
    }
}
