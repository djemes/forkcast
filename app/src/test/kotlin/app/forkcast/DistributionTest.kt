package app.forkcast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DistributionTest {

    @Test
    fun `the shipped build is the github distribution`() {
        assertThat(Distribution.current).isEqualTo(Distribution.GITHUB)
        assertThat(Distribution.current.hasUpdater).isTrue()
        assertThat(Distribution.current.hasDonationLink).isTrue()
    }

    @Test
    fun `a play build has no updater and no donation link`() {
        val play = Distribution.fromId("play")
        assertThat(play.hasUpdater).isFalse()
        assertThat(play.hasDonationLink).isFalse()
    }
}
