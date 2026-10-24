package app.forkcast.ui.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.drive.CleanupPlan
import app.forkcast.core.data.drive.CleanupResult
import app.forkcast.core.data.drive.DriveBackup
import app.forkcast.core.data.drive.NotRestorable
import app.forkcast.core.data.drive.DriveProblem
import app.forkcast.core.data.drive.DriveStatus
import app.forkcast.core.model.BackupFrequency
import app.forkcast.core.model.BackupSettings
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DriveSectionTest {

    @get:Rule val compose = createComposeRule()

    @Test fun notConnectedOffersConnectAndExplains() {
        var connect = 0
        compose.setContent {
            ForkcastTheme { DriveSection(DriveUiState(DriveStatus(null, BackupSettings(), null, null), problem = DriveProblem.NOT_SET_UP), DriveActions(connect = { connect++ })) }
        }
        compose.onNodeWithText("not photos or API keys", substring = true).assertExists()
        compose.onNodeWithText("isn't set up for this build", substring = true).assertExists()
        compose.onNodeWithText("Connect Google Drive").performClick()
        assertThat(connect).isEqualTo(1)
    }

    @Test fun connectedShowsAccountChoicesAndRestore() {
        var frequency: BackupFrequency? = null
        var wifi: Boolean? = null
        var restored: DriveBackup? = null
        val backup = DriveBackup("b1", "forkcast-20261019-0300.json.gz", Instant.parse("2026-10-19T07:00:00Z"), 2048)
        compose.setContent {
            ForkcastTheme {
                DriveSection(
                    DriveUiState(DriveStatus("gabe@example.com", BackupSettings(), null, null), backups = listOf(backup)),
                    DriveActions(setFrequency = { frequency = it }, setWifiOnly = { wifi = it }, restore = { restored = it }),
                )
            }
        }
        compose.onNodeWithText("Backing up to gabe@example.com").assertExists()
        compose.onNodeWithText("Every week").performClick()
        assertThat(frequency).isEqualTo(BackupFrequency.WEEKLY)
        compose.onNodeWithText("Only on Wi-Fi").performClick()
        assertThat(wifi).isTrue()
        // The restore list is open: pick the backup.
        compose.onNodeWithText("Oct 19", substring = true).performClick()
        assertThat(restored).isEqualTo(backup)
    }

    @Test fun cleanupConfirmsHowManyGoAndWhichStays() {
        var confirmed = 0
        val keep = DriveBackup("k", "forkcast-20261024-0300.json.gz", Instant.parse("2026-10-24T07:00:00Z"), 2048)
        val old = (1..9).map { DriveBackup("o$it", "forkcast-202610${10 + it}-0300.json.gz", Instant.parse("2026-10-${10 + it}T07:00:00Z"), 2048) }
        compose.setContent {
            ForkcastTheme {
                DriveSection(
                    DriveUiState(DriveStatus("gabe@example.com", BackupSettings(), null, null), cleanup = CleanupPlan.Ready(keep, old.reversed())),
                    DriveActions(confirmCleanup = { confirmed++ }),
                )
            }
        }
        compose.onNodeWithText("Delete 9 old backups?").assertExists()
        compose.onNodeWithText("Backups from Oct 11", substring = true).assertExists()
        compose.onNodeWithText("to Oct 19", substring = true).assertExists()
        compose.onNodeWithText("Kept: Oct 24", substring = true).assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test fun cleanupOffersBackUpFirstWhenTheNewestCantBeRestored() {
        var backup = 0
        val latest = DriveBackup("l", "forkcast-20261024-0300.json.gz", Instant.parse("2026-10-24T07:00:00Z"), 2048)
        compose.setContent {
            ForkcastTheme {
                DriveSection(
                    DriveUiState(DriveStatus("gabe@example.com", BackupSettings(), null, null), cleanup = CleanupPlan.LatestNotRestorable(latest, NotRestorable.OLD_FORMAT)),
                    DriveActions(backupThenCleanup = { backup++ }),
                )
            }
        }
        compose.onNodeWithText("older version of Forkcast", substring = true).assertExists()
        compose.onNode(hasText("Back up now") and hasAnyAncestor(isDialog())).performClick()
        assertThat(backup).isEqualTo(1)
    }

    @Test fun cleanupResultSaysWhatWentAndWhatStayed() {
        var asked = 0
        val keep = DriveBackup("k", "forkcast-20261024-0300.json.gz", Instant.parse("2026-10-24T07:00:00Z"), 2048)
        compose.setContent {
            ForkcastTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DriveSection(
                        DriveUiState(DriveStatus("gabe@example.com", BackupSettings(), null, null), cleanedUp = CleanupResult(9, 0, keep)),
                        DriveActions(cleanup = { asked++ }),
                    )
                }
            }
        }
        compose.onNodeWithText("Deleted 9 backups. Kept Oct 24", substring = true).assertExists()
        compose.onNodeWithText("Delete old backups").performScrollTo().performClick()
        assertThat(asked).isEqualTo(1)
    }
}
