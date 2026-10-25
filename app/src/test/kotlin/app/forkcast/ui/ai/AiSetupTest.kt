package app.forkcast.ui.ai

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.BackupOffer
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiError
import app.forkcast.fakes.FakeAiRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import androidx.compose.runtime.getValue

/** The guided AI setup (roadmap 2.0 task 3.3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AiSetupTest {

    @get:Rule val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T16:05:00Z"), ZoneId.of("America/New_York"))
    private val geminiKey = "AQ.Ab8RN6Kq-new-format-key-0123456789"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(ai: FakeAiRepository, settings: FakeSettingsRepository = FakeSettingsRepository()) =
        AiSetupViewModel(ai, settings, clock, SavedStateHandle())

    @Test fun geminiPasteChecksSavesAndAddsTheBackup() = runTest(dispatcher) {
        val ai = FakeAiRepository().apply { offer = BackupOffer("p0", "Gemini 3.8 Flash", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite") }
        val settings = FakeSettingsRepository().also { s -> s.updateAi { it.copy(enabled = false) } }
        val vm = vm(ai, settings)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.GEMINI))
        vm.onEvent(AiSetupEvent.Next)
        vm.onEvent(AiSetupEvent.Next)
        assertThat(vm.state.value.step).isEqualTo(SetupStep.PASTE)

        // A pasted key that looks right is checked at once, with surrounding junk removed.
        vm.onEvent(AiSetupEvent.Pasted("  $geminiKey\n"))
        advanceUntilIdle()

        assertThat(ai.testedKeys).containsExactly(geminiKey)
        val s = vm.state.value
        assertThat(s.step).isEqualTo(SetupStep.DONE)
        assertThat(s.outcome).isEqualTo(SetupOutcome.WORKS)
        assertThat(s.backupName).isEqualTo("Gemini 3.5 Flash-Lite")
        assertThat(s.key).isEmpty()
        val profiles = ai.profilesNow()
        assertThat(profiles.map { it.model }).containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite").inOrder()
        assertThat(profiles.all { it.tierLabel == TierLabel.FREE }).isTrue()
        assertThat(ai.keys.values.toSet()).containsExactly(geminiKey)
        // Finishing the guide turns AI on and remembers the passing check.
        assertThat(settings.settings.first().ai.enabled).isTrue()
        assertThat(settings.settings.first().ai.lastChecks["p0"]?.ok).isTrue()
    }

    @Test fun aRejectedKeyIsNeverSaved() = runTest(dispatcher) {
        val ai = FakeAiRepository().apply { keyErrors = mapOf(geminiKey to AiError.InvalidKey()) }
        val vm = vm(ai)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.GEMINI))
        repeat(2) { vm.onEvent(AiSetupEvent.Next) }
        vm.onEvent(AiSetupEvent.Pasted(geminiKey))
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s.step).isEqualTo(SetupStep.PASTE)
        assertThat(s.error).isInstanceOf(AiError.InvalidKey::class.java)
        assertThat(s.canSaveAnyway).isFalse()
        assertThat(ai.profilesNow()).isEmpty()
    }

    @Test fun anOddPasteWaitsForCheckKey() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        val vm = vm(ai)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.CLAUDE))
        repeat(2) { vm.onEvent(AiSetupEvent.Next) }
        vm.onEvent(AiSetupEvent.Pasted(geminiKey))
        advanceUntilIdle()
        assertThat(ai.testedKeys).isEmpty()
        assertThat(vm.state.value.keyCheck).isEqualTo(app.forkcast.core.domain.KeyCheck.GEMINI_KEY)
    }

    @Test fun busyGeminiStillSavesTheKey() = runTest(dispatcher) {
        val ai = FakeAiRepository().apply { keyErrors = mapOf(geminiKey to AiError.RateLimited(null)) }
        val vm = vm(ai)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.GEMINI))
        repeat(2) { vm.onEvent(AiSetupEvent.Next) }
        vm.onEvent(AiSetupEvent.Pasted(geminiKey))
        advanceUntilIdle()
        assertThat(vm.state.value.outcome).isEqualTo(SetupOutcome.BUSY)
        assertThat(ai.profilesNow()).hasSize(1)
    }

    @Test fun noConnectionOffersSaveAnyway() = runTest(dispatcher) {
        val claudeKey = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz"
        val ai = FakeAiRepository().apply { keyErrors = mapOf(claudeKey to AiError.Network()) }
        val vm = vm(ai)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.CLAUDE))
        repeat(2) { vm.onEvent(AiSetupEvent.Next) }
        vm.onEvent(AiSetupEvent.Pasted(claudeKey))
        advanceUntilIdle()
        assertThat(vm.state.value.canSaveAnyway).isTrue()

        vm.onEvent(AiSetupEvent.SaveAnyway)
        advanceUntilIdle()
        assertThat(vm.state.value.outcome).isEqualTo(SetupOutcome.UNCHECKED)
        val p = ai.profilesNow().single()
        assertThat(p.provider).isEqualTo(ProviderId.CLAUDE)
        assertThat(p.tierLabel).isEqualTo(TierLabel.PAID)
    }

    @Test fun walkThroughOnScreen() {
        val ai = FakeAiRepository()
        val vm = vm(ai)
        var closed = false
        compose.setContent {
            val state by vm.state.collectAsState()
            ForkcastTheme { AiSetupScreen(state, vm::onEvent, onClose = { closed = true }, onAdvanced = {}) }
        }
        compose.onNodeWithText("Gemini, free").performClick()
        compose.onNodeWithText("What gets sent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open Google AI Studio").assertIsDisplayed()
        compose.onNodeWithText("I have my key").performClick()
        compose.onNodeWithText("API key").performTextInput("sk-ant-api03-abcdefghijklmnopqrstuvwxyz")
        // The format check speaks up before anything is sent.
        compose.onNodeWithText("This looks like a Claude key, and this is for Gemini.").assertIsDisplayed()
        assertThat(ai.testedKeys).isEmpty()
        compose.onNodeWithText("Check key").performClick()
        dispatcher.scheduler.advanceUntilIdle()
        compose.onNodeWithText("Gemini 3.8 Flash is ready").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertThat(closed).isTrue()
    }
}
