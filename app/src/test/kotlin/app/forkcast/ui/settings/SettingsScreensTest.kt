package app.forkcast.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.AppearanceSettings
import app.forkcast.core.model.ThemeMode
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {

    @get:Rule val compose = createComposeRule()

    @Test fun settingsListOpensAppearanceAndAbout() {
        var opened = ""
        compose.setContent {
            ForkcastTheme {
                SettingsScreen(onBack = {}, onOpenProfileGoals = {}, onOpenFoodDatabases = {}, onOpenBackup = {}, onOpenAppearance = { opened += "A" }, onOpenAbout = { opened += "B" })
            }
        }
        compose.onNodeWithText("Profile & goals").assertIsDisplayed()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        assertThat(opened).isEqualTo("AB")
    }

    @Test fun appearanceReportsThemeChoice() {
        val picked = mutableListOf<ThemeMode>()
        var wallpaper: Boolean? = null
        compose.setContent {
            ForkcastTheme {
                AppearanceScreen(
                    appearance = AppearanceSettings(themeMode = ThemeMode.SYSTEM),
                    wallpaperColorsAvailable = true,
                    onThemeMode = { picked += it },
                    onUseWallpaperColors = { wallpaper = it },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("System default").assertIsSelected()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Use wallpaper colors").performClick()
        assertThat(picked).containsExactly(ThemeMode.DARK)
        assertThat(wallpaper).isTrue()
    }

    @Test fun aboutShowsVersion() {
        compose.setContent { ForkcastTheme { AboutScreen(versionName = "0.1.0", versionCode = 42, onBack = {}) } }
        compose.onNodeWithText("Version 0.1.0 (42)").assertIsDisplayed()
    }
}
