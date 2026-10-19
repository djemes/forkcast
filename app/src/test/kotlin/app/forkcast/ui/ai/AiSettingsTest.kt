package app.forkcast.ui.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.AiProfileDraft
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AiSettingsTest {

    @get:Rule val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:05:00Z"), ZoneId.of("America/New_York"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun guidedFreeGeminiSetupAddsFlashLiteOnSameKey() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        val vm = AiProfileEditViewModel(ai, SavedStateHandle(mapOf("preset" to AiProfileEditViewModel.FREE_GEMINI)))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.provider).isEqualTo(ProviderId.GEMINI)
        assertThat(s.model).isEqualTo("gemini-3.8-flash")
        assertThat(s.showFreeTierNote).isTrue()
        assertThat(s.canSave).isFalse()

        vm.onEvent(AiProfileEditEvent.SetKey("  g-key  "))
        vm.onEvent(AiProfileEditEvent.SaveAndTest)
        advanceUntilIdle()

        val profiles = ai.profilesNow()
        assertThat(profiles.map { it.model }).containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite").inOrder()
        assertThat(profiles.all { it.tierLabel == TierLabel.FREE }).isTrue()
        assertThat(ai.keys.values.toSet()).containsExactly("g-key")
        assertThat(vm.state.value.done).isTrue()
    }

    @Test fun failedTestKeepsEditorOpenWithReason() = runTest(dispatcher) {
        val ai = FakeAiRepository(testErrors = mapOf("p0" to AiError.InvalidKey()))
        val vm = AiProfileEditViewModel(ai, SavedStateHandle())
        advanceUntilIdle()
        vm.onEvent(AiProfileEditEvent.SetProvider(ProviderId.CLAUDE))
        assertThat(vm.state.value.model).isEqualTo("claude-sonnet-5-5")
        assertThat(vm.state.value.showFreeTierNote).isFalse()
        vm.onEvent(AiProfileEditEvent.SetKey("sk-bad"))
        vm.onEvent(AiProfileEditEvent.SaveAndTest)
        advanceUntilIdle()
        assertThat(vm.state.value.done).isFalse()
        assertThat((vm.state.value.test as TestState.Failed).error).isInstanceOf(AiError.InvalidKey::class.java)
        // Saved anyway, so the user can fix the key later.
        assertThat(ai.profilesNow()).hasSize(1)
    }

    @Test fun openAiCompatibleNeedsUrlButNotKey() = runTest(dispatcher) {
        val vm = AiProfileEditViewModel(FakeAiRepository(), SavedStateHandle())
        advanceUntilIdle()
        vm.onEvent(AiProfileEditEvent.SetProvider(ProviderId.OPENAI_COMPAT))
        vm.onEvent(AiProfileEditEvent.SetModel("llava"))
        vm.onEvent(AiProfileEditEvent.SetBaseUrl(""))
        assertThat(vm.state.value.canSave).isFalse()
        vm.onEvent(AiProfileEditEvent.SetBaseUrl("http://ollama.lan:11434"))
        assertThat(vm.state.value.canSave).isTrue()
        assertThat(vm.state.value.isCleartext).isTrue()
    }

    @Test fun reorderAndPersonalContext() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        ai.saveProfile(AiProfileDraft(null, "A", ProviderId.GEMINI, "a", null, TierLabel.FREE), "k")
        ai.saveProfile(AiProfileDraft(null, "B", ProviderId.CLAUDE, "b", null, TierLabel.PAID), "k")
        val settings = FakeSettingsRepository()
        val vm = AiSettingsViewModel(ai, settings, clock)
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onEvent(AiSettingsEvent.Move(1, 0))
        vm.onEvent(AiSettingsEvent.SetPersonalContext("27 cm plates"))
        advanceUntilIdle()
        assertThat(ai.profilesNow().sortedBy { it.order }.map { it.name }).containsExactly("B", "A").inOrder()
        assertThat(settings.state.value.ai.personalContext).isEqualTo("27 cm plates")
        job.cancel()
    }

    @Test fun editorHappyPathOnScreen() {
        var event: AiProfileEditEvent? = null
        compose.setContent {
            ForkcastTheme {
                AiProfileEditScreen(
                    state = AiProfileEditUiState(loaded = true, provider = ProviderId.GEMINI, model = "gemini-3.8-flash", name = "Gemini 3.8 Flash", keyInput = "k", recommendedModel = "gemini-3.8-flash"),
                    onEvent = { event = it },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("On Gemini's free tier", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save and test").performScrollTo().performClick()
        assertThat(event).isEqualTo(AiProfileEditEvent.SaveAndTest)
    }

    @Test fun settingsScreenListsProfilesWithUsage() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        ai.saveProfile(AiProfileDraft(null, "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k")
        val statuses = ai.profiles.first()
        var tested: String? = null
        compose.setContent {
            ForkcastTheme {
                AiSettingsScreen(
                    state = AiSettingsUiState(loaded = true, profiles = statuses.map { it.copy(usedToday = 3) }),
                    onEvent = { if (it is AiSettingsEvent.Test) tested = it.id },
                    onBack = {},
                    onEditProfile = {},
                    onSetUpFreeGemini = {},
                )
            }
        }
        compose.onNodeWithText("1. Gemini 3.8 Flash").assertIsDisplayed()
        compose.onNodeWithText("Used today: 3").assertIsDisplayed()
        compose.onNodeWithText("Test").performClick()
        assertThat(tested).isEqualTo("p0")
        compose.onNodeWithText("Personal context").performScrollTo().performTextInput("olive oil")
    }
}
