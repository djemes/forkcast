package app.forkcast.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.forkcast.R
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.AppearanceSettings
import app.forkcast.core.model.ThemeMode
import app.forkcast.core.data.update.UpdateCheck
import app.forkcast.core.network.update.Release
import app.forkcast.core.model.UpdateSettings
import app.forkcast.core.network.update.ReleaseAsset
import app.forkcast.core.network.update.UpdateError
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
                SettingsScreen(onBack = {}, actions = SettingsActions(units = { opened += "U" }, appearance = { opened += "A" }, about = { opened += "B" }))
            }
        }
        compose.onNodeWithText("Goal & budget").assertIsDisplayed()
        compose.onNodeWithText("Your goal and log").assertIsDisplayed()
        compose.onNodeWithText("Units").performScrollTo().performClick()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        assertThat(opened).isEqualTo("UAB")
    }

    /** The owner's grouping (2026-10-25): everything shows; Advanced exists only for debug tools. */
    @Test fun settingsShowsEveryEntryInItsGroup() {
        var opened = ""
        compose.setContent {
            ForkcastTheme {
                SettingsScreen(
                    onBack = {},
                    actions = SettingsActions(help = { opened += "H" }, dailyTargets = { opened += "T" }, foodDatabases = { opened += "F" }),
                )
            }
        }
        compose.onNodeWithText("Your goal and log").assertIsDisplayed()
        compose.onNodeWithText("Daily targets").performScrollTo().performClick()
        compose.onNodeWithText("Data and connections").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI services").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Food databases").performScrollTo().performClick()
        compose.onNodeWithText("Help").performScrollTo().performClick()
        // No developer tools in this build, so no Advanced either.
        compose.onNodeWithText("Advanced").assertDoesNotExist()
        assertThat(opened).isEqualTo("TFH")
    }

    @Test fun debugToolsWaitUnderAdvanced() {
        var ran = false
        compose.setContent {
            ForkcastTheme {
                SettingsScreen(
                    onBack = {},
                    actions = SettingsActions(debugTools = listOf(DebugTool(R.string.settings_help, R.string.settings_help_summary) { ran = true })),
                )
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onAllNodesWithText("Help")[1].performScrollTo().performClick()
        assertThat(ran).isTrue()
    }

    @Test fun settingsInDarkThemeAtDoubleFontScaleKeepsEveryBasicReachable() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ForkcastTheme(darkTheme = true) { SettingsScreen(onBack = {}, actions = SettingsActions(updates = {}, language = {})) }
            }
        }
        listOf("Goal & budget", "Daily targets", "Units", "AI services", "Food databases", "Backup & data", "Notifications", "Updates", "Language", "Help", "About").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
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
        var backupReminder: Boolean? = null
        compose.setContent {
            ForkcastTheme {
                NotificationsScreen(
                    state = NotificationsUiState(settings, app.forkcast.fakes.FakeMealSlotRepository.DEFAULT),
                    onUpdate = { settings = it(settings) },
                    onMealTime = { _, _ -> },
                    onMeal = { _, _ -> },
                    onMealCategory = {},
                    onBack = {},
                    onBackupReminder = { backupReminder = it },
                )
            }
        }
        compose.onNodeWithText("Evening outlook").performClick()
        assertThat(settings.eveningEnabled).isTrue()
        compose.onNodeWithText("110%").performClick()
        assertThat(settings.budgetThresholds).containsExactly(80, 100, 110).inOrder()
        // Moved here from Backup & data (owner, 2026-10-29); on by default.
        compose.onNodeWithText("Remind me to back up").performScrollTo().performClick()
        assertThat(backupReminder).isFalse()
    }

    @Test fun aboutShowsVersion() {
        compose.setContent { ForkcastTheme { AboutScreen(versionName = "0.1.0", versionCode = 42, onBack = {}) } }
        compose.onNodeWithText("Version 0.1.0 (42)").assertIsDisplayed()
        compose.onNodeWithText("Open on GitHub").performScrollTo().assertIsDisplayed()
    }

    @Test fun aboutLeadsToUpdatesInTheGithubBuild() {
        var opened = false
        compose.setContent { ForkcastTheme { AboutScreen("1.0.0", 126, onBack = {}, onOpenUpdates = { opened = true }) } }
        compose.onNodeWithText("Check for updates").performClick()
        assertThat(opened).isTrue()
    }

    private val release = Release("v1.6.0", "Forkcast 1.6.0", "Backups to a folder", "https://github.com/djemes/forkcast/releases/tag/v1.6.0", ReleaseAsset("Forkcast-v1.6.0.apk", "u", 1))

    private fun updates(ui: UpdateUi, actions: UpdatesActions = UpdatesActions(), options: UpdateSettings = UpdateSettings()) {
        compose.setContent { ForkcastTheme { UpdatesScreen("1.5.0", onBack = {}, state = UpdatesState(ui, options = options), actions = actions) } }
    }

    /** Roadmap 5.5: the repository is public, so there's no token field (and no Advanced) any more. */
    @Test fun failedCheckPointsToReleasesWithNoTokenField() {
        var checked = false
        updates(UpdateUi.Result(UpdateCheck.Failed(UpdateError.NoRelease())), UpdatesActions(check = { checked = true }))
        compose.onNodeWithText("You have Forkcast 1.5.0.").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performClick()
        assertThat(checked).isTrue()
        compose.onNodeWithText("GitHub has no release yet.").assertIsDisplayed()
        compose.onNodeWithText("Open Releases on GitHub").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Advanced").assertDoesNotExist()
        compose.onNodeWithText("token", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun availableUpdateShowsWhatsNewThenInstalls() {
        var picked: Release? = null
        updates(UpdateUi.Result(UpdateCheck.Available(release)), UpdatesActions(download = { picked = it }))
        compose.onNodeWithText("Version 1.6.0 is available.").assertIsDisplayed()
        compose.onNodeWithText("What's new").assertIsDisplayed()
        compose.onNodeWithText("Backups to a folder").assertIsDisplayed()
        compose.onNodeWithText("Download and install").performScrollTo().performClick()
        assertThat(picked).isEqualTo(release)
    }

    @Test fun aBetaSaysSo() {
        updates(UpdateUi.Result(UpdateCheck.Available(release.copy(tag = "v1.7.0-beta.1", prerelease = true))))
        compose.onNodeWithText("Beta 1.7.0-beta.1 is available.", substring = true).assertIsDisplayed()
    }

    @Test fun refusedDownloadsSayWhyAndChangeNothing() {
        updates(UpdateUi.Refused(app.forkcast.core.data.update.ApkProblem.WRONG_CHECKSUM, null))
        compose.onNodeWithText("doesn't match what the release lists", substring = true).assertIsDisplayed()
    }

    @Test fun automaticAndBetaSwitches() {
        val log = mutableListOf<String>()
        updates(UpdateUi.Idle, UpdatesActions(setAuto = { log += "auto $it" }, setBeta = { log += "beta $it" }))
        compose.onNodeWithText("Check automatically").performScrollTo().performClick()
        compose.onNodeWithText("Beta updates").performScrollTo().performClick()
        assertThat(log).containsExactly("auto false", "beta true").inOrder()
    }

    @Test fun upToDate() {
        updates(UpdateUi.Result(UpdateCheck.UpToDate("v1.5.0")))
        compose.onNodeWithText("You have the newest version (1.5.0).").assertIsDisplayed()
    }
}
