package app.forkcast.ui.ai

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.ProviderReply
import app.forkcast.fakes.FakeConsentRepository
import app.forkcast.fakes.FakeAiRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.ui.common.SecretText
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AiSettingsTest {

    @get:Rule val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:05:00Z"), ZoneId.of("America/New_York"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedTestKeepsEditorOpenWithReason() = runTest(dispatcher) {
        val ai = FakeAiRepository(testErrors = mapOf("p0" to AiError.InvalidKey()))
        val vm = AiProfileEditViewModel(ai, SavedStateHandle(), FakeSettingsRepository(), clock, FakeConsentRepository())
        advanceUntilIdle()
        vm.onEvent(AiProfileEditEvent.SetProvider(ProviderId.CLAUDE))
        assertThat(vm.state.value.model).isEqualTo("claude-sonnet-5-5")
        vm.onEvent(AiProfileEditEvent.SetKey("sk-bad"))
        vm.onEvent(AiProfileEditEvent.SaveAndTest)
        advanceUntilIdle()
        assertThat(vm.state.value.done).isFalse()
        assertThat((vm.state.value.test as TestState.Failed).error).isInstanceOf(AiError.InvalidKey::class.java)
        // Saved anyway, so the user can fix the key later.
        assertThat(ai.profilesNow()).hasSize(1)
    }

    @Test fun openAiCompatibleNeedsUrlButNotKey() = runTest(dispatcher) {
        val vm = AiProfileEditViewModel(FakeAiRepository(), SavedStateHandle(), FakeSettingsRepository(), clock, FakeConsentRepository())
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
        val vm = AiSettingsViewModel(ai, settings, clock, FakeConsentRepository())
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
        // No free-tier privacy note on the editor any more (owner, 2026-10-25).
        compose.onNodeWithText("Gemini's free tier", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save and test").performScrollTo().performClick()
        assertThat(event).isEqualTo(AiProfileEditEvent.SaveAndTest)
    }

    @Test fun keyGuideFollowsTheProvider() {
        var provider by mutableStateOf(ProviderId.CLAUDE)
        compose.setContent {
            ForkcastTheme {
                AiProfileEditScreen(state = AiProfileEditUiState(loaded = true, provider = provider, model = "m"), onEvent = {}, onBack = {})
            }
        }
        compose.onNodeWithText("How to get an API key").performScrollTo().performClick()
        compose.onNodeWithText("Get a Claude API key").assertIsDisplayed()
        compose.onNodeWithText("Open API keys").assertExists()
        compose.onNodeWithText("Done").performClick()

        provider = ProviderId.GEMINI
        compose.onNodeWithText("How to get an API key").performScrollTo().performClick()
        compose.onNodeWithText("Get a Gemini API key").assertIsDisplayed()
        compose.onNodeWithText("Option 2: Google Cloud console").assertExists()
    }

    @Test fun keyGuideFillsTheServerUrl() {
        var event: AiProfileEditEvent? = null
        compose.setContent {
            ForkcastTheme {
                AiProfileEditScreen(state = AiProfileEditUiState(loaded = true, provider = ProviderId.OPENAI_COMPAT, model = "m"), onEvent = { event = it }, onBack = {})
            }
        }
        compose.onNodeWithText("How to get an API key").performScrollTo().performClick()
        compose.onNodeWithText("Use https://openrouter.ai/api as the server URL").performScrollTo().performClick()
        assertThat(event).isEqualTo(AiProfileEditEvent.SetBaseUrl("https://openrouter.ai/api"))
        compose.onNodeWithText("Get a key for an OpenAI-compatible service").assertDoesNotExist()
    }

    @Test fun everyGuideLinkIsHttps() {
        val links = ProviderId.entries.flatMap { p ->
            val g = keyGuide(p)
            g.options.flatMap { o -> o.steps.mapNotNull { it.link } } + listOfNotNull(g.footerLink)
        }
        assertThat(links).isNotEmpty()
        assertThat(links.filterNot { it.url.startsWith("https://") }).isEmpty()
    }

    @Test fun settingsScreenListsProfilesWithUsage() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        ai.saveProfile(AiProfileDraft(null, "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k")
        val statuses = ai.profiles.first()
        var tested: String? = null
        compose.setContent {
            ForkcastTheme {
                AiSettingsScreen(
                    state = AiSettingsUiState(
                        loaded = true, profiles = statuses.map { it.copy(usedToday = 3) }, roles = listOf(ProfileRole.FIRST),
                        consents = mapOf("gemini" to 0L),
                    ),
                    onEvent = { if (it is AiSettingsEvent.Test) tested = it.id },
                    onBack = {},
                    onEditProfile = {},
                    onAddAi = {},
                )
            }
        }
        compose.onNodeWithText("Gemini 3.8 Flash").assertIsDisplayed()
        compose.onNodeWithText("Gemini (Google) · gemini-3.8-flash").assertIsDisplayed()
        compose.onNodeWithText("Used first").assertIsDisplayed()
        compose.onNodeWithContentDescription("Number 1 in the order").assertIsDisplayed()
        compose.onNodeWithText("Used today: 3").assertIsDisplayed()
        compose.onNodeWithText("Test").performClick()
        assertThat(tested).isEqualTo("p0")
        compose.onNodeWithText("Personal context").performScrollTo().performTextInput("olive oil")
    }

    // ---- Owner report 2026-10-19: Save and test failed while Test on the list passed.

    @Test fun saveAndTestAndListTestRunTheSameCall() = runTest(dispatcher) {
        val rejected = AiError.InvalidKey(reply = ProviderReply(401, "ACCESS_TOKEN_TYPE_UNSUPPORTED", "Request had invalid authentication credentials."))
        val ai = FakeAiRepository(testErrors = mapOf("p0" to rejected))
        val editor = AiProfileEditViewModel(ai, SavedStateHandle(), FakeSettingsRepository(), clock, FakeConsentRepository())
        advanceUntilIdle()
        editor.onEvent(AiProfileEditEvent.SetKey("AQ.Ab8RN6Kq-new-format-key"))
        editor.onEvent(AiProfileEditEvent.SaveAndTest)
        advanceUntilIdle()
        val fromEditor = editor.state.value.test

        val list = AiSettingsViewModel(ai, FakeSettingsRepository(), clock, FakeConsentRepository())
        val job = backgroundScope.launch { list.state.collect {} }
        list.onEvent(AiSettingsEvent.Test("p0"))
        advanceUntilIdle()
        val fromList = list.state.value.tests["p0"]

        // Same repository call, same id, same result.
        assertThat(ai.tested).containsExactly("p0", "p0")
        assertThat(fromEditor).isEqualTo(fromList)
        assertThat((fromEditor as TestState.Failed).error).isSameInstanceAs(rejected)
        // The key went in exactly as typed (no format check) and shows masked afterwards.
        assertThat(ai.keys["p0"]).isEqualTo("AQ.Ab8RN6Kq-new-format-key")
        assertThat(editor.state.value.showSavedKey).isTrue()

        // Once Google accepts the key, Test again passes through the same call.
        ai.testErrors = emptyMap()
        editor.onEvent(AiProfileEditEvent.TestAgain)
        advanceUntilIdle()
        assertThat(editor.state.value.test).isInstanceOf(TestState.Ok::class.java)
        assertThat(ai.tested).hasSize(3)
        job.cancel()
    }

    @Test fun keyErrorShowsHttpStatusAndGoogleCode() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val tokenType = AiError.InvalidKey(reply = ProviderReply(401, "ACCESS_TOKEN_TYPE_UNSUPPORTED", "Expected OAuth 2 access token"))
        val text = res.aiErrorMessage(tokenType, ProviderId.GEMINI)
        assertThat(text).contains("Gemini rejected the API key (HTTP 401 · ACCESS_TOKEN_TYPE_UNSUPPORTED)")
        assertThat(text).contains("wait a few minutes")
        assertThat(aiErrorDetail(tokenType)).isEqualTo("Expected OAuth 2 access token")

        val disabled = AiError.InvalidKey(reply = ProviderReply(403, "SERVICE_DISABLED", null))
        assertThat(res.aiErrorMessage(disabled, ProviderId.GEMINI)).contains("(HTTP 403 · SERVICE_DISABLED)")
        assertThat(res.aiErrorMessage(AiError.InvalidKey(AiError.NO_KEY), ProviderId.GEMINI)).isEqualTo("No API key saved for this AI service.")
        // Without a provider reply the classic message stays.
        assertThat(res.aiErrorMessage(AiError.InvalidKey(), ProviderId.CLAUDE)).isEqualTo("Claude rejected the API key. Check it in Settings › AI services.")
    }

    @Test fun failedTestOnScreenShowsStatusCodeAndGoogleWords() {
        val error = AiError.InvalidKey(reply = ProviderReply(401, "ACCESS_TOKEN_TYPE_UNSUPPORTED", "Request had invalid authentication credentials."))
        compose.setContent {
            ForkcastTheme {
                AiProfileEditScreen(
                    state = AiProfileEditUiState(loaded = true, isNew = false, provider = ProviderId.GEMINI, model = "gemini-3.8-flash", name = "Gemini 3.8 Flash", hasSavedKey = true, savedKey = SecretText("AQ.Ab8RN6Kq-new-format-key"), test = TestState.Failed(error)),
                    onEvent = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("HTTP 401 · ACCESS_TOKEN_TYPE_UNSUPPORTED", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gemini said: Request had invalid authentication credentials.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Test again").performScrollTo().assertIsDisplayed()
    }

    // ---- Saved keys: masked, show/hide, copy (owner request 2026-10-19).

    /** Unlocked (the unlock prompt succeeded on this screen): the key can be shown and copied. */
    @Test fun savedKeyIsMaskedUntilShown() {
        val gate = app.forkcast.ui.common.KeyGate().apply { open() }
        compose.setContent {
            ForkcastTheme {
                androidx.compose.runtime.CompositionLocalProvider(app.forkcast.ui.common.LocalKeyGate provides gate) {
                    AiProfileEditScreen(
                        state = AiProfileEditUiState(loaded = true, isNew = false, provider = ProviderId.CLAUDE, model = "claude-sonnet-5-5", name = "Claude", hasSavedKey = true, savedKey = SecretText("sk-ant-api03-secret-tail1234")),
                        onEvent = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("sk-ant-api03-secret-tail1234").assertDoesNotExist()
        compose.onNodeWithText("••••••••••••1234").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show key").performClick()
        compose.onNodeWithText("sk-ant-api03-secret-tail1234").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy key").assertIsDisplayed()
        compose.onNodeWithText("Replace key").assertIsDisplayed()
    }

    /**
     * Owner, 2026-10-23: showing a key needs Android's unlock. Without a screen lock (Robolectric's
     * phone has none) the key stays masked and the dialog explains.
     */
    @Test fun savedKeyNeedsUnlockAndAScreenLock() {
        compose.setContent {
            ForkcastTheme {
                AiProfileEditScreen(
                    state = AiProfileEditUiState(loaded = true, isNew = false, provider = ProviderId.CLAUDE, model = "claude-sonnet-5-5", name = "Claude", hasSavedKey = true, savedKey = SecretText("sk-ant-api03-secret-tail1234")),
                    onEvent = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Show key").performClick()
        compose.onNodeWithText("Set a screen lock first").assertIsDisplayed()
        compose.onNodeWithText("sk-ant-api03-secret-tail1234").assertDoesNotExist()
        compose.onAllNodesWithText("Cancel").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("Copy key").performClick()
        compose.onNodeWithText("Set a screen lock first").assertIsDisplayed()
    }

    @Test fun keyGateClosesAfterAMinute() {
        val gate = app.forkcast.ui.common.KeyGate()
        gate.open(now = 1_000)
        assertThat(gate.isOpen(now = 60_999)).isTrue()
        assertThat(gate.isOpen(now = 61_000)).isFalse()
        gate.open(now = 1_000)
        gate.close()
        assertThat(gate.isOpen(now = 2_000)).isFalse()
    }

    @Test fun secretTextNeverPrintsItsValue() {
        val s = SecretText("AQ.Ab8RN6Kq-new-format-key")
        assertThat(s.toString()).doesNotContain("AQ.")
        assertThat(AiProfileEditUiState(savedKey = s).toString()).doesNotContain("new-format-key")
        assertThat(SecretText("short").masked).doesNotContain("short")
    }

    // ---- Delete: confirmation, Undo, order stays right.

    @Test fun deleteConfirmsThenUndoRestoresOrder() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        for (n in listOf("A", "B", "C")) ai.saveProfile(AiProfileDraft(null, n, ProviderId.GEMINI, "m-$n", null, TierLabel.FREE), "key-$n")
        val vm = AiSettingsViewModel(ai, FakeSettingsRepository(), clock, FakeConsentRepository())
        val job = backgroundScope.launch { vm.state.collect {} }
        val effects = mutableListOf<AiSettingsEffect>()
        val effectJob = backgroundScope.launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        vm.onEvent(AiSettingsEvent.Delete("p0"))
        advanceUntilIdle()
        assertThat(vm.state.value.profiles.map { it.profile.name }).containsExactly("B", "C").inOrder()
        assertThat(vm.state.value.roles).containsExactly(ProfileRole.FIRST, ProfileRole.BACKUP).inOrder()
        assertThat(effects).containsExactly(AiSettingsEffect.Deleted("A"))
        assertThat(ai.keys).doesNotContainKey("p0")

        vm.onEvent(AiSettingsEvent.UndoDelete)
        advanceUntilIdle()
        assertThat(vm.state.value.profiles.map { it.profile.name to it.profile.order }).containsExactly("A" to 0, "B" to 1, "C" to 2).inOrder()
        assertThat(ai.keys["p0"]).isEqualTo("key-A")
        // A second Undo does nothing.
        vm.onEvent(AiSettingsEvent.UndoDelete)
        advanceUntilIdle()
        assertThat(vm.state.value.profiles).hasSize(3)
        job.cancel()
        effectJob.cancel()
    }

    @Test fun deleteFromCardAsksFirst() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        ai.saveProfile(AiProfileDraft(null, "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k")
        ai.saveProfile(AiProfileDraft(null, "Gemini 3.5 Flash-Lite", ProviderId.GEMINI, "gemini-3.5-flash-lite", null, TierLabel.FREE), "k")
        val statuses = ai.profiles.first()
        val events = mutableListOf<AiSettingsEvent>()
        compose.setContent {
            ForkcastTheme {
                AiSettingsScreen(
                    state = AiSettingsUiState(loaded = true, profiles = statuses, roles = listOf(ProfileRole.FIRST, ProfileRole.BACKUP)),
                    onEvent = { events += it },
                    onBack = {},
                    onEditProfile = {},
                    onAddAi = {},
                )
            }
        }
        // Each card has its own visible Delete.
        compose.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        // Nothing is deleted until the dialog is confirmed; it says what moves up.
        assertThat(events.filterIsInstance<AiSettingsEvent.Delete>()).isEmpty()
        compose.onNodeWithText("Delete Gemini 3.8 Flash?").assertIsDisplayed()
        compose.onNodeWithText("Gemini 3.5 Flash-Lite moves up one place", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Delete").filterToOne(hasAnyAncestor(isDialog())).performClick()
        assertThat(events.filterIsInstance<AiSettingsEvent.Delete>().single().id).isEqualTo("p0")
    }

    @Test fun moveWithArrowsAndStatusSaysWhetherItWorks() = runTest(dispatcher) {
        val ai = FakeAiRepository()
        ai.saveProfile(AiProfileDraft(null, "A", ProviderId.GEMINI, "a", null, TierLabel.FREE), "k")
        ai.saveProfile(AiProfileDraft(null, "B", ProviderId.CLAUDE, "b", null, TierLabel.PAID), "k")
        val statuses = ai.profiles.first()
        val events = mutableListOf<AiSettingsEvent>()
        compose.setContent {
            ForkcastTheme {
                AiSettingsScreen(
                    state = AiSettingsUiState(
                        loaded = true,
                        settings = app.forkcast.core.model.AiSettings(
                            lastChecks = mapOf(
                                "p0" to app.forkcast.core.model.ProfileCheck(ok = true, atMillis = 0, latencyMs = 800),
                                "p1" to app.forkcast.core.model.ProfileCheck(ok = false, atMillis = 0, failure = app.forkcast.core.model.CheckFailure.KEY),
                            ),
                        ),
                        profiles = statuses,
                        roles = listOf(ProfileRole.FIRST, ProfileRole.BACKUP),
                    ),
                    onEvent = { events += it },
                    onBack = {},
                    onEditProfile = {},
                    onAddAi = {},
                )
            }
        }
        compose.onNodeWithText("Working: last test passed", substring = true).assertExists()
        compose.onNodeWithText("the key was rejected", substring = true).assertExists()
        // The order is under Advanced, collapsed (roadmap 2.0 task 3.5).
        compose.onNodeWithContentDescription("Move A up").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move A up").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move B up").performScrollTo().performClick()
        assertThat(events).contains(AiSettingsEvent.Move(1, 0))
    }

    @Test fun rolesFollowTheChain() {
        fun status(id: String, enabled: Boolean = true, key: Boolean = true, attention: Boolean = false, cooldown: Long? = null) = AiProfileStatus(
            AiProfile(id, id, ProviderId.GEMINI, "m", null, TierLabel.FREE, 0, enabled, attention, cooldown), key, 0,
        )
        val now = 1_000L
        val roles = profileRoles(
            listOf(
                status("off", enabled = false),
                status("nokey", key = false),
                status("rejected", attention = true),
                status("paused", cooldown = 5_000L),
                status("first"),
                status("backup"),
                status("cooled", cooldown = 500L),
            ),
            now,
        )
        assertThat(roles).containsExactly(
            ProfileRole.OFF, ProfileRole.NO_KEY, ProfileRole.KEY_REJECTED, ProfileRole.PAUSED,
            ProfileRole.FIRST, ProfileRole.BACKUP, ProfileRole.BACKUP,
        ).inOrder()
    }
}
