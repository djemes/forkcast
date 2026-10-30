package app.forkcast.ui.common

import android.app.Application
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** Outside links (owner, 2026-10-30, v1.8.9): an app that claims the link, else a Custom Tab in the app's colors. */
@RunWith(AndroidJUnit4::class)
class LinkOpenerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val toolbar = 0xFF16201C.toInt()

    @Test fun theTabUsesTheAppsColorsAndTheme() {
        val tab = LinkOpener(app, toolbar, darkTheme = true).customTab().intent
        assertThat(tab.hasExtra(CustomTabsIntent.EXTRA_SESSION)).isTrue()
        assertThat(tab.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1)).isEqualTo(CustomTabsIntent.COLOR_SCHEME_DARK)
        assertThat(CustomTabsIntent.getColorSchemeParams(tab, CustomTabsIntent.COLOR_SCHEME_DARK).toolbarColor).isEqualTo(toolbar)
        assertThat(tab.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1)).isEqualTo(CustomTabsIntent.SHOW_PAGE_TITLE)

        val light = LinkOpener(app, toolbar, darkTheme = false).customTab().intent
        assertThat(light.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1)).isEqualTo(CustomTabsIntent.COLOR_SCHEME_LIGHT)
    }

    @Test fun anAppThatClaimsTheLinkGetsItFirst() {
        LinkOpener(app, toolbar, darkTheme = false).openUri("https://github.com/djemes/forkcast")
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.dataString).isEqualTo("https://github.com/djemes/forkcast")
        assertThat(started.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER).isNotEqualTo(0)
    }

    @Test fun noBrowserSaysSoInsteadOfCrashing() {
        shadowOf(app).checkActivities(true)
        LinkOpener(app, toolbar, darkTheme = false).openUri("https://ko-fi.com/example")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(app.getString(R.string.link_no_browser))
    }
}
