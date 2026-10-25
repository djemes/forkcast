package app.forkcast.fakes

import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.model.AgreementAcceptance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Terms and AI permissions (roadmap 2.0 Phase 4). Gemini and Claude are allowed unless a test says otherwise. */
class FakeConsentRepository(allowed: Set<String> = setOf("gemini", "claude")) : ConsentRepository {
    val accepted = MutableStateFlow<AgreementAcceptance?>(null)
    val consents = MutableStateFlow(allowed.associateWith { 0L })

    override val agreement: Flow<AgreementAcceptance?> = accepted
    override suspend fun acceptAgreement(version: Int, appVersion: String) { accepted.value = AgreementAcceptance(version, 0L, appVersion) }
    override val aiConsents: Flow<Map<String, Long>> = consents
    override fun hasAiConsent(recipient: String): Boolean = recipient in consents.value
    override suspend fun allowAi(recipient: String) = consents.update { it + (recipient to 0L) }
    override suspend fun revokeAi(recipient: String) = consents.update { it - recipient }
}
