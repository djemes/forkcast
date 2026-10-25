package app.forkcast.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.AgreementAcceptance
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.ProviderId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Roadmap 2.0 Phase 4: the accepted terms and AI permissions are kept on this phone, across restarts. */
@RunWith(AndroidJUnit4::class)
class ConsentRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T14:00:00Z"), ZoneId.of("America/New_York"))

    @Test fun agreementIsStoredWithVersionTimeAndAppVersion() = runTest {
        val repo = DefaultConsentRepository(DevicePrefs(context), clock)
        assertThat(repo.agreement.first()).isNull()
        repo.acceptAgreement(1, "1.8.0")
        // A new process reads what was saved.
        val again = DefaultConsentRepository(DevicePrefs(context), clock)
        assertThat(again.agreement.first()).isEqualTo(AgreementAcceptance(1, clock.millis(), "1.8.0"))
    }

    @Test fun aiPermissionIsPerRecipientAndCanBeTakenBack() = runTest {
        val repo = DefaultConsentRepository(DevicePrefs(context), clock)
        val gemini = AiRecipient.of(ProviderId.GEMINI, null)
        val openRouter = AiRecipient.of(ProviderId.OPENAI_COMPAT, "https://OpenRouter.ai/api")
        repo.allowAi(gemini)
        repo.allowAi(openRouter)
        val again = DefaultConsentRepository(DevicePrefs(context), clock)
        assertThat(again.aiConsents.first()).containsExactly(gemini, clock.millis(), "openai:openrouter.ai", clock.millis())
        assertThat(again.hasAiConsent(AiRecipient.of(ProviderId.OPENAI_COMPAT, "http://192.168.1.20:11434"))).isFalse()
        again.revokeAi(gemini)
        assertThat(DefaultConsentRepository(DevicePrefs(context), clock).hasAiConsent(gemini)).isFalse()
        assertThat(again.hasAiConsent(openRouter)).isTrue()
    }

    @Test fun recipientHosts() {
        assertThat(AiRecipient.host("openrouter.ai/api")).isEqualTo("openrouter.ai")
        assertThat(AiRecipient.host("http://127.0.0.1:8765/ok")).isEqualTo("127.0.0.1")
        assertThat(AiRecipient.host(null)).isEmpty()
        assertThat(AiRecipient.of(ProviderId.CLAUDE, "ignored")).isEqualTo("claude")
    }
}
