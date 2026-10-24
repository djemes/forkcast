package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BackupReminderTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private fun daysAgo(d: Long): Instant = now.minus(Duration.ofDays(d))

    @Test fun dueFourteenDaysAfterTheLastBackup() {
        assertThat(BackupReminder.due(daysAgo(13), daysAgo(100), null, now)).isFalse()
        assertThat(BackupReminder.due(daysAgo(14), daysAgo(100), null, now)).isTrue()
    }

    @Test fun beforeAnyBackupItCountsFromTheFirstLaunch() {
        assertThat(BackupReminder.due(null, daysAgo(3), null, now)).isFalse()
        assertThat(BackupReminder.due(null, daysAgo(20), null, now)).isTrue()
        assertThat(BackupReminder.due(null, null, null, now)).isFalse()
    }

    @Test fun dismissingWaitsAnotherFourteenDays() {
        assertThat(BackupReminder.due(daysAgo(30), null, daysAgo(2), now)).isFalse()
        assertThat(BackupReminder.due(daysAgo(30), null, daysAgo(15), now)).isTrue()
    }
}
