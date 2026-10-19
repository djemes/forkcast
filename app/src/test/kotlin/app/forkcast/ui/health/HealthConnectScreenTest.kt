package app.forkcast.ui.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.health.HcAvailability
import app.forkcast.core.data.health.HealthError
import app.forkcast.core.data.health.HealthPermissions
import app.forkcast.core.data.health.HealthStatus
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.HealthSettings
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.UserProfile
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HealthConnectScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun state(
        settings: HealthSettings = HealthSettings(),
        granted: Set<String> = emptySet(),
        availability: HcAvailability = HcAvailability.AVAILABLE,
        error: HealthError? = null,
        heightCm: Double? = null,
    ) = HealthUiState(
        status = HealthStatus(availability, settings, granted, historySupported = true, backgroundSupported = true,
            lastSyncAt = if (settings.connected) Instant.now().minusSeconds(300) else null, lastError = error, heightCm = heightCm),
        units = UnitPrefs.METRIC,
        profile = UserProfile(Sex.MALE, LocalDate.of(1985, 1, 1), 178.0, ActivityLevel.LIGHT),
        syncing = false,
    )

    private fun show(state: HealthUiState, onRequest: () -> Unit = {}, onUpdate: ((HealthSettings) -> HealthSettings) -> Unit = {}, onUseHeight: (Double) -> Unit = {}) {
        compose.setContent {
            ForkcastTheme {
                HealthConnectScreen(state, onRequest, onUpdate, onSyncNow = {}, onUseHeight = onUseHeight, onDisconnect = {}, onBack = {})
            }
        }
    }

    @Test fun rationaleThenConnect() {
        var requested = 0
        var settings = HealthSettings()
        show(state(), onRequest = { requested++ }, onUpdate = { settings = it(settings) })
        compose.onNodeWithText("Forkcast reads").assertIsDisplayed()
        compose.onNodeWithText("Health data stays on this phone", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Water").performScrollTo().performClick()
        assertThat(settings.writeHydration).isFalse()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        assertThat(requested).isEqualTo(1)
    }

    @Test fun connectedShowsStatusMissingPermissionsAndHeight() {
        var height: Double? = null
        show(
            state(
                settings = HealthSettings(connected = true),
                granted = HealthPermissions.ALL - HealthPermissions.READ_STEPS - HealthPermissions.READ_IN_BACKGROUND,
                error = HealthError.PERMISSION,
                heightCm = 180.0,
            ),
            onUseHeight = { height = it },
        )
        compose.onNodeWithText("Last synced", substring = true).assertIsDisplayed()
        compose.onNodeWithText("stopped Forkcast", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not allowed yet: background reads, steps").assertIsDisplayed()
        compose.onNodeWithText("Syncs when you open Forkcast", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Your profile says 178 cm.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use 180 cm").performScrollTo().performClick()
        assertThat(height).isEqualTo(180.0)
        compose.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()
    }

    @Test fun unavailableOffersTheApp() {
        show(state(availability = HcAvailability.UNAVAILABLE))
        compose.onNodeWithText("Health Connect isn't available on this phone.").assertIsDisplayed()
        compose.onNodeWithText("Get Health Connect").assertIsDisplayed()
    }
}
