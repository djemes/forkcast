package app.forkcast.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings › AI: Basics by default, the expert parts under Advanced (roadmap 2.0 task 3.5). */
@RunWith(AndroidJUnit4::class)
class AiSettingsAdvancedTest {

    @get:Rule val compose = createComposeRule()

    private val state = AiSettingsUiState(
        loaded = true,
        profiles = listOf(
            AiProfileStatus(AiProfile("p0", "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE, 0, true, false, null), true, 0),
            AiProfileStatus(AiProfile("p1", "Gemini 3.5 Flash-Lite", ProviderId.GEMINI, "gemini-3.5-flash-lite", null, TierLabel.FREE, 1, true, false, null), true, 0),
        ),
        roles = listOf(ProfileRole.FIRST, ProfileRole.BACKUP),
    )

    private fun show(edited: MutableList<String?> = mutableListOf(), dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                ForkcastTheme(darkTheme = dark) {
                    AiSettingsScreen(state, onEvent = {}, onBack = {}, onEditProfile = { edited += it }, onAddAi = {})
                }
            }
        }
    }

    @Test fun basicsShowServicesTestAndAddAiWithAdvancedCollapsed() {
        show()
        compose.onNodeWithText("Your AI services").assertIsDisplayed()
        compose.onNodeWithText("Gemini 3.8 Flash").assertIsDisplayed()
        compose.onNodeWithText("Add AI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Personal context").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Add an AI service manually").assertDoesNotExist()
        compose.onNodeWithText("Usage this month").assertDoesNotExist()
    }

    @Test fun advancedHoldsTheOrderEditManualSetupAndUsage() {
        val edited = mutableListOf<String?>()
        show(edited)
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Which AI goes first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Usage this month").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add an AI service manually").performScrollTo().performClick()
        assertThat(edited).containsExactly(null)
    }

    @Test fun darkThemeAtDoubleFontScaleKeepsTestAddAndAdvancedReachable() {
        show(dark = true, fontScale = 2f)
        compose.onNodeWithText("Gemini 3.8 Flash").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add AI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Add an AI service manually").performScrollTo().assertIsDisplayed()
    }
}
