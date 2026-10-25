package app.forkcast.core.data.repository

import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.AgreementAcceptance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user agreed to on this phone (roadmap 2.0 Phase 4): the Terms of Use and Disclaimer
 * (task 4.1) and, per AI recipient, permission to send meals (task 4.2). Kept in [DevicePrefs]:
 * never in backups, settings exports or Android backup, so a new phone asks again.
 */
interface ConsentRepository {
    val agreement: Flow<AgreementAcceptance?>
    suspend fun acceptAgreement(version: Int, appVersion: String)

    /** Recipient ([app.forkcast.core.model.AiRecipient]) → when it was allowed (epoch ms). */
    val aiConsents: Flow<Map<String, Long>>
    fun hasAiConsent(recipient: String): Boolean
    suspend fun allowAi(recipient: String)
    /** Settings › AI services: that recipient's services are skipped until it is allowed again. */
    suspend fun revokeAi(recipient: String)
}

@Singleton
class DefaultConsentRepository @Inject constructor(
    private val device: DevicePrefs,
    private val clock: Clock,
) : ConsentRepository {

    override val agreement: Flow<AgreementAcceptance?> = device.state.map { it.agreement }.distinctUntilChanged()

    override suspend fun acceptAgreement(version: Int, appVersion: String) {
        device.update { it.copy(agreement = AgreementAcceptance(version, clock.millis(), appVersion)) }
    }

    override val aiConsents: Flow<Map<String, Long>> = device.state.map { it.aiConsents }.distinctUntilChanged()

    override fun hasAiConsent(recipient: String): Boolean = recipient in device.state.value.aiConsents

    override suspend fun allowAi(recipient: String) {
        device.update { it.copy(aiConsents = it.aiConsents + (recipient to clock.millis())) }
    }

    override suspend fun revokeAi(recipient: String) {
        device.update { it.copy(aiConsents = it.aiConsents - recipient) }
    }
}
