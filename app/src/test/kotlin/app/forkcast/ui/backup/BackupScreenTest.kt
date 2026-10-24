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
import app.forkcast.core.model.BackupFrequency
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
                        exportFoodLog = { launched += "log" },
                        exportWeights = { launched += "weights" },
                        restore = { launched += "restore" },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("No backup yet.").assertIsDisplayed()
        compose.onNodeWithText("Save a backup file…").performClick()
        compose.onNodeWithText("Restore from a backup…").performClick()
        compose.onNodeWithText("Food log (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Weights (CSV)").performScrollTo().performClick()
        assertThat(events).containsExactly(BackupEvent.AskSave)
        assertThat(launched).containsExactly("restore", "log", "weights").inOrder()
        compose.onNodeWithText("Undo the last replace").assertDoesNotExist()
    }

    @Test fun lastBackupAndReminder() {
        var reminder: Boolean? = null
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    BackupUiState(),
                    {},
                    noActions,
                    onBack = {},
                    last = LastBackup(1_761_462_000_000, "forkcast-backup-2025-10-26.forkcast", "Backups", null),
                    reminder = true,
                    onReminder = { reminder = it },
                )
            }
        }
        compose.onNodeWithText("Last backup:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Remind me to back up").performClick()
        assertThat(reminder).isFalse()
    }

    @Test fun saveOptionsNeedAMatchingPasswordWhenProtected() {
        var chosen: Pair<Boolean, String?>? = null
        compose.setContent {
            ForkcastTheme { SaveOptionsDialog(onContinue = { photos, password -> chosen = photos to password }, onDismiss = {}) }
        }
        compose.onNodeWithText("Include meal photos").performClick()
        compose.onNodeWithText("Protect with a password").performClick()
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextInput("a long backup password")
        compose.onNodeWithText("Password again").performTextInput("a long backup passwor")
        compose.onNodeWithText("The two don't match").assertExists()
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
        compose.onNodeWithText("Password again").performTextInput("d")
        compose.onNodeWithText("Choose where to save").assertIsEnabled().performClick()
        assertThat(chosen).isEqualTo(false to "a long backup password")
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

    @Test fun passphraseMustMatchAndBeLongEnough() {
        var result: CharArray? = null
        compose.setContent { ForkcastTheme { PassphraseDialog(confirm = true, onDone = { result = it }, onDismiss = {}) } }
        compose.onNodeWithText("Passphrase").performTextInput("short")
        compose.onNodeWithText("At least 8 characters").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Passphrase").performTextInput(" horse battery")
        compose.onNodeWithText("Passphrase again").performTextInput("short horse batter")
        compose.onNodeWithText("The two don't match").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Passphrase again").performTextInput("y")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertThat(String(result!!)).isEqualTo("short horse battery")
    }

    @Test fun settingsExportIsStillOffered() {
        compose.setContent {
            ForkcastTheme {
                BackupScreen(
                    state = BackupUiState(),
                    onEvent = {},
                    actions = noActions,
                    onBack = {},
                    settingsSection = { SettingsTransferSection(busy = false, onExport = {}, onImport = {}) },
                )
            }
        }
        compose.onNodeWithText("Export settings…").performScrollTo().assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Scrolling(content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) { content() }

    private fun status(chosen: Boolean, settings: BackupSettings = BackupSettings(), problem: FolderProblem? = null) =
        FolderStatus(if (chosen) "content://tree/1" else null, if (chosen) "Backups" else null, settings, null, problem, hasPassword = false)

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

    @Test fun folderChosenShowsScheduleKeepAndActions() {
        val log = mutableListOf<String>()
        compose.setContent {
            ForkcastTheme {
                Scrolling {
                AutoBackupSection(
                    AutoBackupUiState(status(chosen = true)),
                    AutoBackupActions(
                        backupNow = { log += "now" },
                        cleanup = { log += "cleanup" },
                        setFrequency = { log += "freq $it" },
                        setKeep = { log += "keep $it" },
                        setPhotos = { log += "photos $it" },
                    ),
                )
                }
            }
        }
        compose.onNodeWithText("Saving to: Backups").assertIsDisplayed()
        compose.onNodeWithText("On Monday, around 3 am").assertIsDisplayed()
        compose.onNodeWithText("Every day").performScrollTo().performClick()
        compose.onNodeWithText("Newest 16").performScrollTo().performClick()
        compose.onNodeWithText("Include meal photos").performScrollTo().performClick()
        compose.onNodeWithText("Back up now").performScrollTo().performClick()
        compose.onNodeWithText("Delete old backups").performScrollTo().performClick()
        assertThat(log).containsExactly("freq ${BackupFrequency.DAILY}", "keep 16", "photos true", "now", "cleanup").inOrder()
    }

    @Test fun passwordSwitchAsksForAPasswordFirst() {
        var set: String? = "unset"
        compose.setContent {
            ForkcastTheme { AutoBackupSection(AutoBackupUiState(status(chosen = true)), AutoBackupActions(setPassword = { set = it })) }
        }
        compose.onNodeWithText("Protect with a password").performClick()
        assertThat(set).isEqualTo("unset")
        compose.onNodeWithText("Password").performTextInput("a long backup password")
        compose.onNodeWithText("Password again").performTextInput("a long backup password")
        compose.onNodeWithText("Continue").performClick()
        assertThat(set).isEqualTo("a long backup password")
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
