package app.forkcast.ui.help

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelpScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun show(vm: HelpViewModel = HelpViewModel(SavedStateHandle()), dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                ForkcastTheme(darkTheme = dark) {
                    val state by vm.state.collectAsState()
                    HelpScreen(state, vm::onEvent, onBack = {})
                }
            }
        }
    }

    @Test fun aLinkOpensHelpScrolledToItsAnswer() {
        show(HelpViewModel(SavedStateHandle(mapOf("entry" to HelpEntry.AI_LIMITS.name))))
        compose.onNodeWithText("It says the AI is busy or at its limit").assertIsDisplayed()
        compose.onNodeWithText("reset at midnight Pacific time", substring = true).assertIsDisplayed()
    }

    @Test fun searchFiltersQuestionsAndAnswers() {
        show()
        compose.onNodeWithText("How do I start?").assertIsDisplayed()
        compose.onNodeWithText("Search help").performTextInput("Flash-Lite")
        compose.onNodeWithText("It says the AI is busy or at its limit").assertIsDisplayed()
        compose.onNodeWithText("How do I start?").assertDoesNotExist()
        compose.onNodeWithText("Getting started").assertDoesNotExist()
    }

    @Test fun nothingFoundSaysSo() {
        show()
        compose.onNodeWithText("Search help").performTextInput("zzqx")
        compose.onNodeWithText("No answers match “zzqx”. Try fewer or other words.").assertIsDisplayed()
    }

    @Test fun aQuestionOpensItsAnswerAndTheGlossaryIsThere() {
        show()
        compose.onNodeWithText("How do I start?").performClick()
        compose.onNodeWithText("Log what you eat on Today", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("TDEE (maintenance)"))
        compose.onNodeWithText("TDEE (maintenance)").performClick()
        compose.onNodeWithText("Total daily energy expenditure", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun darkThemeAtDoubleFontScaleKeepsSearchAndQuestionsReachable() {
        show(dark = true, fontScale = 2f)
        compose.onNodeWithText("Search help").assertIsDisplayed()
        compose.onNodeWithText("How do I start?").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("%DV (Daily Value)"))
        compose.onNodeWithText("%DV (Daily Value)").assertIsDisplayed()
    }

    @Test fun anErrorsHelpLinkOpensItsEntry() {
        var opened: HelpEntry? = null
        compose.setContent {
            CompositionLocalProvider(LocalHelp provides { opened = it }) { ForkcastTheme { HelpLink(HelpEntry.LOG_USDA_KEY) } }
        }
        compose.onNodeWithContentDescription("Help: Do I need a USDA key, and what if it's rejected?").performClick()
        assertThat(opened).isEqualTo(HelpEntry.LOG_USDA_KEY)
    }
}
