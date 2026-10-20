package app.forkcast.ui.backup

import androidx.compose.ui.test.assertIsEnabled

import androidx.compose.ui.test.assertIsNotEnabled

import androidx.compose.ui.test.performTextInput

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.fakes.FakeBackupRepository
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupScreenTest {

    @get:Rule val compose = createComposeRule()

    private val noActions = BackupActions({}, {}, {}, {})

    @Test fun exportAndImportButtonsLaunchPickers() {
        val launched = mutableListOf<String>()
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(),
                    onEvent = {},
                    actions = BackupActions(
                        exportJson = { launched += "json" },
                        exportFoodLog = { launched += "log" },
                        exportWeights = { launched += "weights" },
                        import = { launched += "import" },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("Food log (CSV)").performClick()
        compose.onNodeWithText("Weights (CSV)").performClick()
        compose.onNodeWithText("Import backup").performScrollTo().performClick()
        assertThat(launched).containsExactly("json", "log", "weights", "import").inOrder()
        compose.onNodeWithText("Undo the last replace").assertDoesNotExist()
    }

    @Test fun previewShowsCountsAndMerges() {
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(dialog = BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false)),
                    onEvent = { events += it },
                    actions = noActions,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Import this backup?").assertIsDisplayed()
        compose.onNodeWithText("Food log entries: 340").assertIsDisplayed()
        compose.onNodeWithText("Weigh-ins: 30").assertIsDisplayed()
        compose.onNodeWithText("Dates: Sep 1, 2026 to Oct 18, 2026").assertIsDisplayed()
        compose.onNodeWithText("Replace…").performClick()
        compose.onNodeWithText("Merge").performClick()
        assertThat(events).containsExactly(BackupEvent.AskReplace, BackupEvent.Merge).inOrder()
    }

    @Test fun safetyBackupOfferedAfterReplace() {
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme {
                BackupScreen(BackupUiState(hasSafetyBackup = true), { events += it }, noActions, onBack = {})
            }
        }
        compose.onNodeWithText("Undo the last replace").performScrollTo().performClick()
        assertThat(events).containsExactly(BackupEvent.OpenSafetyBackup)
    }

    @Test fun passphraseMustMatchAndBeLongEnough() {
        var result: CharArray? = null
        compose.setContent { app.forkcast.ui.theme.ForkcastTheme { PassphraseDialog(confirm = true, onDone = { result = it }, onDismiss = {}) } }
        compose.onNodeWithText("Passphrase").performTextInput("short")
        compose.onNodeWithText("At least 8 characters").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Passphrase").performTextInput(" horse battery")
        compose.onNodeWithText("Passphrase again").performTextInput("short horse batter")
        compose.onNodeWithText("The two don't match").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Passphrase again").performTextInput("y")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        com.google.common.truth.Truth.assertThat(String(result!!)).isEqualTo("short horse battery")
    }

    @Test fun backupScreenOffersZipAndSettingsExport() {
        var zip = false
        compose.setContent {
            app.forkcast.ui.theme.ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(),
                    onEvent = {},
                    actions = BackupActions(exportJson = {}, exportZip = { zip = true }, exportFoodLog = {}, exportWeights = {}, import = {}),
                    onBack = {},
                    settingsSection = { SettingsTransferSection(busy = false, onExport = {}, onImport = {}) },
                )
            }
        }
        compose.onNodeWithText("Export with photos (.zip)").performClick()
        com.google.common.truth.Truth.assertThat(zip).isTrue()
        compose.onNodeWithText("Export settings…").performScrollTo().assertExists()
    }
}
