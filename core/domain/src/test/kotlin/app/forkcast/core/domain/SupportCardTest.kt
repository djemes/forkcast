package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SupportCardTest {

    private val hiddenAt = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private fun later(days: Long) = hiddenAt + Duration.ofDays(days).toMillis()
    private fun shown(hiddenOn: String, installed: String, now: Long = hiddenAt) =
        SupportCard.isShown(SupportCard.major(hiddenOn), hiddenAt, installed, now)

    @Test fun shownUntilHidden() {
        assertThat(SupportCard.isShown(hiddenAtMajor = null, hiddenAt = null, versionName = "1.8.6", now = hiddenAt)).isTrue()
    }

    @Test fun staysHiddenAfterMinorAndPatchUpdatesWithinThreeMonths() {
        assertThat(shown("1.8.7", "1.8.7")).isFalse()
        assertThat(shown("1.8.7", "1.8.8", later(10))).isFalse()
        assertThat(shown("1.8.7", "1.9.0", later(60))).isFalse()
        assertThat(shown("1.8.7", "1.9.0-4-gabc1234", later(89))).isFalse()
    }

    @Test fun comesBackAfterThreeMonths() {
        assertThat(shown("1.8.7", "1.8.7", later(90))).isTrue()
        assertThat(shown("2.0.0", "2.3.1", later(200))).isTrue()
    }

    @Test fun comesBackAfterAMajorUpdateEvenWithinThreeMonths() {
        assertThat(shown("1.9.0", "2.0.0", later(1))).isTrue()
        assertThat(shown("2.4.1", "3.0.0-beta.1", later(1))).isTrue()
        // Hidden again on 2.x, the next 2.x updates keep it hidden.
        assertThat(shown("2.0.0", "2.9.9", later(30))).isFalse()
    }

    @Test fun aCardHiddenWithoutATimeShowsAgain() {
        assertThat(SupportCard.isShown(hiddenAtMajor = 1, hiddenAt = null, versionName = "1.8.7", now = hiddenAt)).isTrue()
    }

    @Test fun aDevBuildCountsAsMajorZero() {
        assertThat(SupportCard.major("0.0.0-dev+abc1234")).isEqualTo(0)
        assertThat(SupportCard.major("dev")).isEqualTo(0)
        assertThat(shown("dev", "dev")).isFalse()
    }
}
