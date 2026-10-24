package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppVersionTest {

    @Test fun parsesTagsAndBuildNames() {
        assertThat(AppVersion.parse("v1.2.3")).isEqualTo(AppVersion(1, 2, 3))
        assertThat(AppVersion.parse("1.0.0")).isEqualTo(AppVersion(1, 0, 0))
        assertThat(AppVersion.parse("0.4.0-3-gd3807d7-dirty")).isEqualTo(AppVersion(0, 4, 0, 3))
        assertThat(AppVersion.parse("0.0.0-dev")).isEqualTo(AppVersion(0, 0, 0))
        assertThat(AppVersion.parse("dev")).isNull()
    }

    @Test fun newerReleases() {
        // The owner's phone: a build 3 commits after 0.4.0.
        assertThat(AppVersion.isNewer("v1.0.0", "0.4.0-3-gd3807d7-dirty")).isTrue()
        assertThat(AppVersion.isNewer("v1.1.0", "1.0.0")).isTrue()
        assertThat(AppVersion.isNewer("v1.0.10", "1.0.9")).isTrue()
    }

    @Test fun betasComeBeforeTheirRelease() {
        assertThat(AppVersion.parse("v2.0.0-beta.2")).isEqualTo(AppVersion(2, 0, 0, pre = AppVersion.Pre(AppVersion.Stage.BETA, 2)))
        assertThat(AppVersion.isNewer("v2.0.0-beta.1", "1.9.0")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0-beta.2", "2.0.0-beta.1")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0-beta.10", "2.0.0-beta.9")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0-rc.1", "2.0.0-beta.3")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0", "2.0.0-beta.3")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0", "2.0.0-beta.3-4-gabc1234")).isTrue()
        assertThat(AppVersion.isNewer("v2.0.0-beta.1", "2.0.0")).isFalse()
        assertThat(AppVersion.isNewer("v2.0.0-beta.1", "1.9.0-5-gabc1234")).isTrue()
        assertThat(AppVersion.parse("v2.0.0-beta.1")!!.isPrerelease).isTrue()
        assertThat(AppVersion.parse("v2.0.0")!!.isPrerelease).isFalse()
    }

    @Test fun sameOrOlderIsUpToDate() {
        assertThat(AppVersion.isNewer("v1.0.0", "1.0.0")).isFalse()
        // A build made after 1.0.0 is already past it.
        assertThat(AppVersion.isNewer("v1.0.0", "1.0.0-2-gabc1234")).isFalse()
        assertThat(AppVersion.isNewer("v0.4.0", "1.0.0")).isFalse()
        assertThat(AppVersion.isNewer("nightly", "1.0.0")).isFalse()
    }
}
