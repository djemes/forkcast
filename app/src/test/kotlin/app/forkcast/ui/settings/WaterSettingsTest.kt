package app.forkcast.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WaterContainer
import app.forkcast.core.model.WaterContainers
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings › Water: the goal, then the four containers in one section (owner, 2026-10-24). */
@RunWith(AndroidJUnit4::class)
class WaterSettingsTest {

    @get:Rule val compose = createComposeRule()

    @Test fun allFourContainersAreEditableTheSameWay() {
        val changes = mutableListOf<Pair<WaterContainer, Double?>>()
        compose.setContent {
            ForkcastTheme {
                WaterSettingsScreen(
                    WaterSettingsState(UnitPrefs.METRIC, targetMl = null, defaultMl = 2_500.0, glassMl = 300.0, containers = WaterContainers(canMl = null)),
                    onBack = {}, onTarget = {}, onContainer = { c, ml -> changes += c to ml },
                )
            }
        }
        compose.onNodeWithText("Containers").performScrollTo().assertIsDisplayed()
        for (label in listOf("Glass size (ml)", "Can size (ml)", "Bottle size (ml)", "Tumbler size (ml)")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Glass size (ml)").performTextReplacement("330")
        compose.onNodeWithText("Tumbler size (ml)").performTextReplacement("750")
        assertThat(changes).containsAtLeast(WaterContainer.GLASS to 330.0, WaterContainer.TUMBLER to 750.0)
        // The glass was set: its reset goes back to the default 250 ml, like the other containers'.
        compose.onNodeWithText("Use the default (250", substring = true).performScrollTo().performClick()
        assertThat(changes.last()).isEqualTo(WaterContainer.GLASS to null)
    }
}
