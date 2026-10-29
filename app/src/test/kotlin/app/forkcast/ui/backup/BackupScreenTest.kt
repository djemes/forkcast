package app.forkcast.ui.backup

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.backup.folder.CleanupPlan
import app.forkcast.core.data.backup.folder.FolderFile
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.backup.folder.FolderStatus
import app.forkcast.core.data.settings.LastBackup
import app.forkcast.core.model.RetentionPreset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import app.forkcast.core.model.BackupSettings
import app.forkcast.fakes.FakeBackupRepository
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupScreenTest {

    @get:Rule val compose = createComposeRule()

    private val noActions = BackupActions()

    @Test fun backupRestoreAndSpreadsheetButtons() {
        val launched = mutableListOf<String>()
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(),
                    onEvent = { events += it },
                    actions = BackupActions(
                        restore = { launched += "restore" },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("No backup yet.").assertIsDisplayed()
        // No password yet: Back up now says why instead of backing up.
        compose.onNodeWithText("Back up now").performClick()
        compose.onNodeWithText("Set a backup password first. Backups are always encrypted with it.").assertIsDisplayed()
        compose.onNodeWithText("Restore from a backup…").performScrollTo().performClick()
        compose.onNodeWithText("Back up without encryption").performScrollTo().performClick()
        // The meal report's spreadsheet replaced the CSV exports (owner, 2026-10-29).
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Food log (CSV)").assertDoesNotExist()
        assertThat(events).containsExactly(BackupEvent.AskUnencrypted)
        assertThat(launched).containsExactly("restore")
        compose.onNodeWithText("Undo the last replace").assertDoesNotExist()
    }

    @Test fun lastBackup() {
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    BackupUiState(),
                    {},
                    noActions,
                    onBack = {},
                    last = LastBackup(1_761_462_000_000, "2025-10-26_0300_forkcast-backup.forkcast", "Backups", null),
                )
            }
        }
        compose.onNodeWithText("Last backup:", substring = true).assertIsDisplayed()
        // The reminder switch moved to Settings › Notifications (owner, 2026-10-29).
        compose.onNodeWithText("Remind me to back up").assertDoesNotExist()
    }

    @Test fun withAPasswordBothBackupButtonsWork() {
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme { BackupScreen(BackupUiState(), { events += it }, noActions, onBack = {}, hasPassword = true, exampleName = "2026-10-29_0300_forkcast-backup.forkcast") }
        }
        compose.onNodeWithText("Back up now").performClick()
        compose.onNodeWithText("Back up and share").performClick()
        compose.onNodeWithText("for example 2026-10-29_0300_forkcast-backup.forkcast", substring = true).assertExists()
        assertThat(events).containsExactly(BackupEvent.BackUpNow, BackupEvent.BackUpAndShare).inOrder()
    }

    @Test fun theUnencryptedWarningNamesWhatsInside() {
        var continued = false
        compose.setContent {
            ForkcastTheme { BackupScreen(BackupUiState(dialog = BackupDialog.UnencryptedWarning), {}, noActions, onBack = {}, onUnencryptedContinue = { continued = true }) }
        }
        compose.onNodeWithText("It includes your API keys", substring = true).assertIsDisplayed()
        compose.onNodeWithText("I understand, continue").performClick()
        assertThat(continued).isTrue()
    }

    @Test fun aNewPasswordNeedsTenCharactersTwiceOrAGeneratedOne() {
        var saved: String? = null
        compose.setContent { ForkcastTheme { NewPasswordDialog("Set a backup password", null, onDone = { saved = it }, onDismiss = {}, onCopy = {}) } }
        compose.onNodeWithText("Password").performTextInput("short")
        compose.onNodeWithText("At least 10 characters").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextInput(" horse battery")
        compose.onNodeWithText("Type it again").performTextInput("short horse batter")
        compose.onNodeWithText("The two don't match").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Type it again").performTextInput("y")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertThat(saved).isEqualTo("short horse battery")
        compose.onNodeWithText("Save it in your password manager", substring = true).assertExists()
    }

    @Test fun aGeneratedPasswordFillsBothFields() {
        var saved: String? = null
        compose.setContent { ForkcastTheme { NewPasswordDialog("Set a backup password", null, onDone = { saved = it }, onDismiss = {}, onCopy = {}) } }
        compose.onNodeWithText("Generate a strong password").performClick()
        compose.onNodeWithText("Strong").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertThat(saved).matches("[a-zA-Z2-9]{4}(-[a-zA-Z2-9]{4}){4}")
    }

    @Test fun thePasswordSectionOffersWhatFits() {
        val log = mutableListOf<String>()
        var has by mutableStateOf(false)
        compose.setContent {
            ForkcastTheme { BackupPasswordSection(has, working = false, { log += "set" }, { log += "show" }, { log += "change" }, { log += "remove" }) }
        }
        compose.onNodeWithText("Set a backup password").performClick()
        has = true
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertThat(log).containsExactly("set", "show", "change", "remove").inOrder()
    }

    @Test fun previewShowsCountsPhotosAndMerges() {
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(dialog = BackupDialog.Preview(FakeBackupRepository.PREVIEW, isSafetyBackup = false, photos = 4)),
                    onEvent = { events += it },
                    actions = noActions,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Import this backup?").assertIsDisplayed()
        compose.onNodeWithText("Food log entries: 340").assertIsDisplayed()
        compose.onNodeWithText("Weigh-ins: 30").assertIsDisplayed()
        compose.onNodeWithText("Meal photos: 4").assertIsDisplayed()
        compose.onNodeWithText("Dates: Sep 1, 2026 to Oct 18, 2026").assertIsDisplayed()
        compose.onNodeWithText("Replace…").performClick()
        compose.onNodeWithText("Merge").performClick()
        assertThat(events).containsExactly(BackupEvent.AskReplace, BackupEvent.Merge).inOrder()
    }

    @Test fun wrongPasswordIsSaid() {
        val events = mutableListOf<BackupEvent>()
        compose.setContent {
            ForkcastTheme { BackupScreen(BackupUiState(dialog = BackupDialog.Password(wrong = true)), { events += it }, noActions, onBack = {}) }
        }
        compose.onNodeWithText("Wrong password, or the file was changed.").assertIsDisplayed()
        compose.onNodeWithText("Password").performTextInput("again")
        compose.onNodeWithText("Open").performClick()
        assertThat(events).containsExactly(BackupEvent.Unlock("again"))
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

    @Test fun oldSettingsFilesCanStillBeImported() {
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(),
                    onEvent = {},
                    actions = noActions,
                    onBack = {},
                    settingsSection = { SettingsTransferSection(busy = false, onImport = {}) },
                )
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Export settings…").assertDoesNotExist()
        compose.onNodeWithText("Import settings…").performScrollTo().assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Scrolling(content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) { content() }

    private fun status(chosen: Boolean, settings: BackupSettings = BackupSettings(), problem: FolderProblem? = null, hasPassword: Boolean = true) =
        FolderStatus(if (chosen) "content://tree/1" else null, if (chosen) "Backups" else null, settings, null, problem, hasPassword = hasPassword)

    @Test fun noFolderYetOffersToChooseOne() {
        var chose = false
        compose.setContent {
            ForkcastTheme { AutoBackupSection(AutoBackupUiState(status(chosen = false)), AutoBackupActions(chooseFolder = { chose = true })) }
        }
        compose.onNodeWithText("Automatic backup").assertIsDisplayed()
        compose.onNodeWithText("Choose a folder").performClick()
        assertThat(chose).isTrue()
        compose.onNodeWithText("Back up now").assertDoesNotExist()
    }

    @Test fun folderChosenShowsDaysTimeRetentionAndCleanup() {
        val log = mutableListOf<String>()
        compose.setContent {
            ForkcastTheme {
                Scrolling {
                    AutoBackupSection(
                        AutoBackupUiState(status(chosen = true)),
                        AutoBackupActions(
                            cleanup = { log += "cleanup" },
                            toggleDay = { log += "day $it" },
                            setRetention = { log += "keep $it" },
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("Saving to: Backups").assertIsDisplayed()
        // Monday is on by default (1.5 to 1.8.1's weekly setting).
        compose.onNodeWithContentDescription("Monday, on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Wednesday, off").performClick()
        compose.onNodeWithText("Android may move background work a little", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("The last 7, plus one a month for 6 months.").performScrollTo().assertExists()
        compose.onNodeWithText("Long-term").performScrollTo().performClick()
        compose.onNodeWithText("Delete old backups").performScrollTo().performClick()
        assertThat(log).containsExactly("day 3", "keep ${RetentionPreset.LONG_TERM}", "cleanup").inOrder()
    }

    @Test fun theSwitchAsksForAPasswordWhenThereIsNone() {
        val log = mutableListOf<String>()
        compose.setContent {
            ForkcastTheme {
                AutoBackupSection(
                    AutoBackupUiState(status(chosen = true, hasPassword = false)),
                    AutoBackupActions(needPassword = { log += "ask" }, setAuto = { log += "auto $it" }),
                )
            }
        }
        compose.onNodeWithText("Needs a backup password").assertIsDisplayed()
        compose.onNodeWithText("Back up automatically").performClick()
        assertThat(log).containsExactly("ask")
        compose.onNodeWithContentDescription("Monday, on").assertDoesNotExist()
    }

    @Test fun aLostFolderSaysWhatToDo() {
        compose.setContent {
            ForkcastTheme { AutoBackupSection(AutoBackupUiState(status(chosen = true, problem = FolderProblem.FOLDER_LOST)), AutoBackupActions()) }
        }
        compose.onNodeWithText("Choose a folder again", substring = true).assertIsDisplayed()
    }

    @Test fun cleanupDialogNamesWhatGoesAndWhatStays() {
        var confirmed = false
        val keep = FolderFile("k", "forkcast-backup-2026-10-26.forkcast", null, null)
        val old = listOf(FolderFile("a", "forkcast-backup-2026-10-19.forkcast", null, null))
        compose.setContent {
            ForkcastTheme {
                AutoBackupSection(
                    AutoBackupUiState(status(chosen = true), cleanup = CleanupPlan.Ready(keep, old)),
                    AutoBackupActions(confirmCleanup = { confirmed = true }),
                )
            }
        }
        compose.onNodeWithText("Delete 1 old backup?").assertIsDisplayed()
        compose.onNodeWithText("Kept: forkcast-backup-2026-10-26.forkcast", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertThat(confirmed).isTrue()
    }
}
