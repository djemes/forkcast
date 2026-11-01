package app.forkcast.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PortionSliderTest {

    @get:Rule val compose = createComposeRule()

    private var portion by mutableDoubleStateOf(1.0)

    private fun show(gramsAtOne: Double? = null) {
        compose.setContent {
            ForkcastTheme {
                PortionSlider(portion, kcalAtOne = 640.0, f = UnitFormatter(UnitPrefs.US, Locale.US), onChange = { portion = it }, gramsAtOne = gramsAtOne)
            }
        }
    }

    private fun spoken(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test fun headlineBalloonAndTalkBackFollowThePortion() {
        portion = 0.5
        show()
        compose.onNodeWithText("½ portion · 320\u202Fkcal").assertExists()
        compose.onNodeWithContentDescription("Portion").assert(spoken("Half portion, 320 kilocalories"))
        portion = 1.5
        compose.onNodeWithText("1½× · 960\u202Fkcal").assertExists()
        compose.onNodeWithContentDescription("Portion").assert(spoken("One and a half portions, 960 kilocalories"))
    }

    @Test fun labelsJumpAdjustStepsAndResetGoesBackTo1x() {
        show()
        compose.onNodeWithText("3×").performClick()
        assertThat(portion).isEqualTo(3.0)
        compose.onNodeWithText("¼").performClick()
        assertThat(portion).isEqualTo(0.25)
        // Adjust gestures and volume keys set the next step's index.
        compose.onNodeWithContentDescription("Portion").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        assertThat(portion).isEqualTo(1.25)
        val actions = compose.onNodeWithContentDescription("Portion").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Back to 1×" }.action() }
        assertThat(portion).isEqualTo(1.0)
    }

    @Test fun customSitsInTheHeadlineRowWithItsOwnLabel() {
        portion = 0.5
        show()
        val headline = compose.onNodeWithText("½ portion · 320\u202Fkcal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val custom = compose.onNodeWithContentDescription("Custom amount").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        // Same row: the button is to the right of the headline and overlaps it vertically.
        assertThat(custom.left).isAtLeast(headline.right)
        assertThat(custom.top).isLessThan(headline.bottom)
        assertThat(custom.bottom).isGreaterThan(headline.top)
        assertThat(custom.height).isAtLeast(48f * compose.density.density - 0.5f)
    }

    @Test fun customTakesAMultiplierOrGrams() {
        show(gramsAtOne = 200.0)
        compose.onNodeWithContentDescription("Custom amount").performClick()
        compose.onNodeWithText("Times the portion").performTextReplacement("1.75")
        compose.onNodeWithText("OK").performClick()
        assertThat(portion).isEqualTo(1.75)
        compose.onNodeWithText("1.75× · 1,120\u202Fkcal").assertExists()

        compose.onNodeWithText("Custom…").performClick()
        compose.onNodeWithText("Enter grams instead").performClick()
        compose.onNodeWithText("Grams eaten").performTextReplacement("80")
        compose.onNodeWithText("OK").performClick()
        assertThat(portion).isEqualTo(0.4)
        compose.onNodeWithContentDescription("Portion").assert(spoken("0.4 times the portion, 256 kilocalories"))
    }
}
