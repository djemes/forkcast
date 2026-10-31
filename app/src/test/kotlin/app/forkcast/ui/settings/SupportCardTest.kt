package app.forkcast.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Ko-fi card at the top of Settings (owner, 2026-10-30). */
@RunWith(AndroidJUnit4::class)
class SupportCardTest {

    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    private var undone = 0

    private fun settings(url: String, darkTheme: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            var shown by remember { mutableStateOf(true) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalDensity provides Density(density.density, fontScale)) {
                ForkcastTheme(darkTheme = darkTheme) {
                    SettingsScreen(
                        onBack = {},
                        actions = SettingsActions(),
                        supportCard = SupportCardState(shown, url, onHide = { shown = false }, onUndoHide = { undone++; shown = true }),
                    )
                }
            }
        }
    }

    @Test fun tapOpensTheKofiPage() {
        settings("https://ko-fi.com/example")
        compose.onNodeWithText("Support on Ko-fi").assertIsDisplayed()
        compose.onNodeWithText("Free and ad-free. Tips help keep it that way.").assertIsDisplayed()
        compose.onNodeWithText("Support on Ko-fi").performClick()
        assertThat(opened).containsExactly("https://ko-fi.com/example")
    }

    @Test fun withoutALinkItSaysComingSoon() {
        settings("")
        compose.onNodeWithText("Support on Ko-fi").performClick()
        compose.onNodeWithText("Coming soon").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test fun hideRemovesItPointsToAboutAndCanBeUndone() {
        settings("https://ko-fi.com/example")
        compose.onNodeWithContentDescription("Hide the Ko-fi card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Support on Ko-fi").assertDoesNotExist()
        compose.onNodeWithText("Hidden. You can still support Forkcast from Settings › About.").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertThat(undone).isEqualTo(1)
        compose.onNodeWithText("Support on Ko-fi").assertIsDisplayed()
    }

    @Test fun fitsInDarkThemeAtDoubleFontScale() {
        settings("", darkTheme = true, fontScale = 2f)
        compose.onNodeWithText("Support on Ko-fi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide the Ko-fi card").assertIsDisplayed()
        compose.onNodeWithText("Goal & budget").assertExists()
    }

    @Test fun noCardWithoutTheDonationLink() {
        compose.setContent { ForkcastTheme { SettingsScreen(onBack = {}, actions = SettingsActions(), supportCard = null) } }
        compose.onNodeWithText("Support on Ko-fi").assertDoesNotExist()
    }
}
