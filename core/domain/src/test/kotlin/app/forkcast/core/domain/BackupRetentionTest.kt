package app.forkcast.core.domain

import app.forkcast.core.model.BackupSettings
import app.forkcast.core.model.RetentionPreset
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupRetentionTest {

    private val zone = ZoneId.of("America/New_York")

    /** One backup a week at 3 am, newest first, going back [weeks] weeks from 2026-10-26. */
    private fun weekly(weeks: Int) = (0 until weeks).map { i ->
        val day = LocalDate.of(2026, 10, 26).minusWeeks(i.toLong())
        BackupRetention.Backup("w$i", day.atTime(3, 0).atZone(zone).toInstant())
    }

    private fun kept(backups: List<BackupRetention.Backup>, rule: BackupRetention.Rule) =
        backups.map { it.id }.toSet() - BackupRetention.toDelete(backups, zone, rule)

    private fun dateOf(all: List<BackupRetention.Backup>, id: String) = all.first { it.id == id }.createdAt.atZone(zone).toLocalDate()

    @Test fun `simple keeps the newest five`() {
        val all = weekly(9)
        assertThat(kept(all, BackupRetention.rule(BackupSettings(retention = RetentionPreset.SIMPLE)))).containsExactly("w0", "w1", "w2", "w3", "w4")
    }

    @Test fun `balanced keeps seven plus the newest of each of six months`() {
        val all = weekly(52)
        val keep = kept(all, BackupRetention.rule(BackupSettings()))
        // 7 recent (Sep 14 to Oct 26); October and September's newest are among them; then Aug, Jul, Jun, May.
        assertThat(keep).hasSize(11)
        assertThat(keep).containsAtLeast("w0", "w6")
        assertThat(keep.map { dateOf(all, it) }).contains(LocalDate.of(2026, 8, 31))
        assertThat(keep.minOf { dateOf(all, it) }).isAtLeast(LocalDate.of(2026, 5, 1))
    }

    @Test fun `long term keeps one per year for every year`() {
        val all = weekly(52 * 3)
        val keep = kept(all, BackupRetention.rule(BackupSettings(retention = RetentionPreset.LONG_TERM)))
        assertThat(keep.map { dateOf(all, it).year }.toSet()).containsExactly(2023, 2024, 2025, 2026)
    }

    @Test fun `custom uses its own numbers and never keeps fewer than one`() {
        val all = weekly(10)
        val rule = BackupRetention.rule(BackupSettings(retention = RetentionPreset.CUSTOM, customRecent = 0, customMonths = 0, customYears = 0))
        assertThat(kept(all, rule)).containsExactly("w0")
    }

    @Test fun `next run is the next chosen day at the chosen time`() {
        // A zone without a clock change in that week, so a week is exactly 7 days.
        val sundayNoon = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneId.of("UTC"))
        // Monday and Thursday at 3 am: Monday 3 am is next.
        assertThat(BackupRetention.delayUntilNext(sundayNoon, setOf(1, 4), 180)).isEqualTo(Duration.ofHours(15))
        // Sunday at 1 pm: later today.
        assertThat(BackupRetention.delayUntilNext(sundayNoon, setOf(7), 13 * 60)).isEqualTo(Duration.ofHours(1))
        // Sunday at 11 am: already past, so next Sunday.
        assertThat(BackupRetention.delayUntilNext(sundayNoon, setOf(7), 11 * 60)).isEqualTo(Duration.ofDays(7).minusHours(1))
        assertThat(BackupRetention.delayUntilNext(sundayNoon, emptySet(), 180)).isNull()
    }

    @Test fun `a late run still counts for the evening's day`() {
        // Chosen: Sunday at 11:30 pm. Android runs it at 12:20 am Monday.
        val mondayAfterMidnight = ZonedDateTime.of(2026, 10, 26, 0, 20, 0, 0, zone)
        assertThat(BackupRetention.isChosenDay(mondayAfterMidnight, setOf(7), 23 * 60 + 30)).isTrue()
        assertThat(BackupRetention.isChosenDay(mondayAfterMidnight, setOf(1), 23 * 60 + 30)).isFalse()
        // Chosen: Monday at 3 am, run at 3:10 am Monday.
        assertThat(BackupRetention.isChosenDay(mondayAfterMidnight.withHour(3).withMinute(10), setOf(1), 180)).isTrue()
    }
}
