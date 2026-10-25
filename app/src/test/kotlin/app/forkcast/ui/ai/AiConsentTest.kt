package app.forkcast.ui.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.fakes.FakeAiEstimateRepository
import app.forkcast.fakes.FakeAiRepository
import app.forkcast.fakes.FakeConsentRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.photo.PhotoMealEvent
import app.forkcast.ui.photo.PhotoMealViewModel
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** Roadmap 2.0 task 4.2: permission before anything goes to an AI service, everywhere a request starts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AiConsentTest {

    @get:Rule val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-25T16:05:00Z"), ZoneId.of("America/New_York"))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun sheetSaysWhatIsSentAndWhoReceivesIt() {
        var allowed = false
        compose.setContent { ForkcastTheme { AiConsentSheet(ProviderId.GEMINI, null, onAllow = { allowed = true }, onNotNow = {}) } }
        compose.onNodeWithText("Allow Gemini to receive your meals?").assertIsDisplayed()
        compose.onNodeWithText("Never sent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Google, through the Gemini API, straight from your phone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Read Google's terms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("consent_allow").performScrollTo().performClick()
        assertThat(allowed).isTrue()
    }

    @Test fun setupGuideAsksOnWhatThisMeans() = runTest {
        val consent = FakeConsentRepository(allowed = emptySet())
        val vm = AiSetupViewModel(FakeAiRepository(), FakeSettingsRepository(), clock, SavedStateHandle(), consent)
        vm.onEvent(AiSetupEvent.Choose(ProviderId.CLAUDE))
        assertThat(vm.state.value.allowed).isFalse()
        vm.onEvent(AiSetupEvent.AllowAndNext)
        assertThat(consent.hasAiConsent("claude")).isTrue()
        assertThat(vm.state.value.step).isEqualTo(SetupStep.GET_KEY)
        assertThat(vm.state.value.allowed).isTrue()
    }

    @Test fun editorWaitsForPermissionBeforeTalkingToTheServer() = runTest {
        val ai = FakeAiRepository()
        val consent = FakeConsentRepository(allowed = emptySet())
        val vm = AiProfileEditViewModel(ai, SavedStateHandle(), FakeSettingsRepository(), clock, consent)
        vm.onEvent(AiProfileEditEvent.SetProvider(ProviderId.OPENAI_COMPAT))
        vm.onEvent(AiProfileEditEvent.SetBaseUrl("https://openrouter.ai/api"))
        vm.onEvent(AiProfileEditEvent.LoadModels)
        assertThat(vm.state.value.askPermissionFor).isEqualTo(AiProfileEditEvent.LoadModels)
        assertThat(vm.state.value.models).isNull()

        vm.onEvent(AiProfileEditEvent.NotNow)
        assertThat(vm.state.value.askPermissionFor).isNull()
        assertThat(consent.consents.value).isEmpty()

        vm.onEvent(AiProfileEditEvent.LoadModels)
        vm.onEvent(AiProfileEditEvent.AllowAi)
        assertThat(consent.hasAiConsent("openai:openrouter.ai")).isTrue()
        assertThat(vm.state.value.models).isNotEmpty()
    }

    @Test fun settingsShowsNotAllowedAndTakesBack() = runTest {
        val ai = FakeAiRepository().apply {
            saveProfile(AiProfileDraft(null, "Gemini", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k")
            saveProfile(AiProfileDraft(null, "Claude", ProviderId.CLAUDE, "claude-sonnet-5-5", null, TierLabel.PAID), "k")
        }
        val consent = FakeConsentRepository(allowed = setOf("claude"))
        val vm = AiSettingsViewModel(ai, FakeSettingsRepository(), clock, consent)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.first { it.loaded }.roles).containsExactly(ProfileRole.NOT_ALLOWED, ProfileRole.FIRST).inOrder()

        vm.onEvent(AiSettingsEvent.AllowAi(AiRecipient.of(ProviderId.GEMINI, null)))
        vm.onEvent(AiSettingsEvent.RevokeAi("claude"))
        assertThat(vm.state.value.roles).containsExactly(ProfileRole.FIRST, ProfileRole.NOT_ALLOWED).inOrder()
        assertThat(vm.state.value.consents.keys).containsExactly("gemini")
    }

    @Test fun photoAsksBeforeTheCameraOpens() = runTest {
        val ai = FakeAiRepository().apply { saveProfile(AiProfileDraft(null, "Gemini", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), "k") }
        val consent = FakeConsentRepository(allowed = emptySet())
        val vm = PhotoMealViewModel(
            SavedStateHandle(mapOf("mealSlotId" to "l", "date" to TestData.TODAY.toString())),
            FakeAiEstimateRepository(), ai, FakeMealSlotRepository(), FakeSettingsRepository(), clock, consent,
        )
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.first { it.loaded }.askPermission?.provider).isEqualTo(ProviderId.GEMINI)
        vm.onEvent(PhotoMealEvent.AllowAi)
        assertThat(vm.state.value.askPermission).isNull()
        assertThat(consent.hasAiConsent("gemini")).isTrue()
    }
}
