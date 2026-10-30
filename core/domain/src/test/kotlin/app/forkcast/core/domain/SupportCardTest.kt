package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SupportCardTest {

    @Test fun shownUntilHidden() {
        assertThat(SupportCard.isShown(hiddenAtMajor = null, versionName = "1.8.6")).isTrue()
    }

    @Test fun staysHiddenAfterMinorAndPatchUpdates() {
        val hidden = SupportCard.major("1.8.6")
        assertThat(SupportCard.isShown(hidden, "1.8.6")).isFalse()
        assertThat(SupportCard.isShown(hidden, "1.8.7")).isFalse()
        assertThat(SupportCard.isShown(hidden, "1.9.0")).isFalse()
        assertThat(SupportCard.isShown(hidden, "1.9.0-4-gabc1234")).isFalse()
    }

    @Test fun comesBackAfterAMajorUpdate() {
        assertThat(SupportCard.isShown(SupportCard.major("1.9.0"), "2.0.0")).isTrue()
        assertThat(SupportCard.isShown(SupportCard.major("2.4.1"), "3.0.0-beta.1")).isTrue()
        // Hidden again on 2.x, it stays hidden for the rest of 2.x.
        assertThat(SupportCard.isShown(SupportCard.major("2.0.0"), "2.9.9")).isFalse()
    }

    @Test fun aDevBuildCountsAsMajorZero() {
        assertThat(SupportCard.major("0.0.0-dev+abc1234")).isEqualTo(0)
        assertThat(SupportCard.major("dev")).isEqualTo(0)
        assertThat(SupportCard.isShown(0, "dev")).isFalse()
    }
}
