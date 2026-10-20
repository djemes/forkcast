package app.forkcast.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.AppearanceSettings
import app.forkcast.core.model.ThemeMode
import app.forkcast.core.data.update.UpdateCheck
import app.forkcast.core.network.update.Release
import app.forkcast.core.network.update.ReleaseAsset
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.update.AppInstaller
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
                SettingsScreen(onBack = {}, actions = SettingsActions(units = { opened += "U" }, appearance = { opened += "A" }, about = { opened += "B" }))
            }
        }
        compose.onNodeWithText("Goal & budget").assertIsDisplayed()
        compose.onNodeWithText("You and your goal").assertIsDisplayed()
        compose.onNodeWithText("Units").performScrollTo().performClick()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        assertThat(opened).isEqualTo("UAB")
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

    @Test fun notificationsToggleAndThresholds() {
        var settings = app.forkcast.core.model.NotificationSettings(permissionAsked = true)
        compose.setContent {
            ForkcastTheme {
                NotificationsScreen(
                    state = NotificationsUiState(settings, app.forkcast.fakes.FakeMealSlotRepository.DEFAULT),
                    onUpdate = { settings = it(settings) },
                    onMealTime = { _, _ -> },
                    onMeal = { _, _ -> },
                    onMealCategory = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Evening outlook").performClick()
        assertThat(settings.eveningEnabled).isTrue()
        compose.onNodeWithText("110%").performClick()
        assertThat(settings.budgetThresholds).containsExactly(80, 100, 110).inOrder()
    }

    @Test fun aboutShowsVersion() {
        compose.setContent { ForkcastTheme { AboutScreen(versionName = "0.1.0", versionCode = 42, onBack = {}) } }
        compose.onNodeWithText("Version 0.1.0 (42)").assertIsDisplayed()
        compose.onNodeWithText("Open on GitHub").performScrollTo().assertIsDisplayed()
    }

    private fun section(ui: UpdateUi, onCheck: () -> Unit = {}, onDownload: (Release) -> Unit = {}) =
        UpdateSection(ui, AppInstaller.Status.Idle, token = null, onCheck = onCheck, onDownload = onDownload, onInstall = {}, onSaveToken = {}, onRemoveToken = {})

    private val release = Release("v1.1.0", "Forkcast 1.1.0", "Portuguese (Brazil)", "https://github.com/djemes/forkcast/releases/tag/v1.1.0", ReleaseAsset("forkcast-1.1.0.apk", "u", 1))

    @Test fun updateCheckWithoutTokenPointsToReleases() {
        var checked = false
        compose.setContent {
            ForkcastTheme { AboutScreen("1.0.0", 126, onBack = {}, updates = section(UpdateUi.Result(UpdateCheck.NoToken), onCheck = { checked = true })) }
        }
        compose.onNodeWithText("Check for updates").performClick()
        assertThat(checked).isTrue()
        compose.onNodeWithText("Open Releases on GitHub").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GitHub token").performScrollTo().assertIsDisplayed()
    }

    @Test fun availableUpdateOffersInstall() {
        var picked: Release? = null
        compose.setContent {
            ForkcastTheme { AboutScreen("1.0.0", 126, onBack = {}, updates = section(UpdateUi.Result(UpdateCheck.Available(release)), onDownload = { picked = it })) }
        }
        compose.onNodeWithText("Version 1.1.0 is available.").assertIsDisplayed()
        compose.onNodeWithText("Download and install").performScrollTo().performClick()
        assertThat(picked).isEqualTo(release)
    }

    @Test fun upToDate() {
        compose.setContent { ForkcastTheme { AboutScreen("1.0.0", 126, onBack = {}, updates = section(UpdateUi.Result(UpdateCheck.UpToDate("v1.0.0")))) } }
        compose.onNodeWithText("You have the newest version (1.0.0).").assertIsDisplayed()
    }
}
