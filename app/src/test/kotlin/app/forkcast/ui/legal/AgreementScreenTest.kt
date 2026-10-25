package app.forkcast.ui.legal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.R
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Roadmap 2.0 task 4.1: two required confirmations, Decline closes, a newer version says what changed. */
@RunWith(AndroidJUnit4::class)
class AgreementScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun agreeNeedsBothConfirmations() {
        var accepted = 0
        compose.setContent { ForkcastTheme { AgreementScreen(whatChanged = null, onAccept = { accepted++ }, onDecline = {}) } }
        compose.onNodeWithText("In short").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10. General").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("agree").assertIsNotEnabled()
        compose.onNodeWithText("I am 18 or older").performClick()
        compose.onNodeWithTag("agree").assertIsNotEnabled()
        compose.onNodeWithText("I have read and agree to the Terms of Use and Disclaimer").performClick()
        compose.onNodeWithText("Check both boxes to continue.").assertDoesNotExist()
        compose.onNodeWithTag("agree").assertIsEnabled().performClick()
        assertThat(accepted).isEqualTo(1)
    }

    @Test fun declineAsksThenCloses() {
        var declined = false
        compose.setContent { ForkcastTheme { AgreementScreen(whatChanged = null, onAccept = {}, onDecline = { declined = true }) } }
        compose.onNodeWithText("Decline").performClick()
        compose.onNodeWithText("Back to the terms").performClick()
        assertThat(declined).isFalse()
        compose.onNodeWithText("Decline").performClick()
        compose.onNodeWithText("Close Forkcast").performClick()
        assertThat(declined).isTrue()
    }

    /** A test build's bumped version: the note shows above the terms. */
    @Test fun newerVersionShowsWhatChanged() {
        compose.setContent { ForkcastTheme { AgreementScreen(whatChanged = R.string.terms_data_body, onAccept = {}, onDecline = {}) } }
        compose.onNodeWithText("What changed").assertIsDisplayed()
        assertThat(Agreement.needsAcceptance(acceptedVersion = 1, current = 2)).isTrue()
        assertThat(Agreement.needsAcceptance(acceptedVersion = 2, current = 2)).isFalse()
        assertThat(Agreement.needsAcceptance(acceptedVersion = null, current = 1)).isTrue()
    }

    /** Every version after the first explains itself. */
    @Test fun everyNewerVersionHasANote() {
        (2..Agreement.VERSION).forEach { assertThat(Agreement.changes).containsKey(it) }
    }
}
