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
}
