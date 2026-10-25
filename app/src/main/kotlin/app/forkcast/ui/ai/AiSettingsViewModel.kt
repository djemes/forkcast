package app.forkcast.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.data.repository.DeletedAiProfile
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.AiSettings
import app.forkcast.core.model.CheckFailure
import app.forkcast.core.model.ProfileCheck
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.model.ModelPrice
import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.ai.ConnectionReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

sealed interface TestState {
    data object Running : TestState
    data class Ok(val report: ConnectionReport) : TestState
    data class Failed(val error: Throwable?) : TestState
}

/**
 * The one test both "Save and test" (profile editor) and "Test" (profile list) run, so the two
 * can never disagree: the same repository call, the same result mapping.
 */
suspend fun AiRepository.runTest(profileId: String): TestState =
    test(profileId).fold({ r -> TestState.Ok(r) }, { e -> TestState.Failed(e) })

/** Remembers a finished test on the profile, so the list can say whether it works after a restart. */
suspend fun SettingsRepository.recordCheck(profileId: String, result: TestState, nowMs: Long) {
    val check = when (result) {
        is TestState.Ok -> ProfileCheck(ok = true, atMillis = nowMs, latencyMs = result.report.latencyMs)
        is TestState.Failed -> ProfileCheck(ok = false, atMillis = nowMs, failure = checkFailure(result.error))
        TestState.Running -> return
    }
    updateAi { it.copy(lastChecks = it.lastChecks + (profileId to check)) }
}

fun checkFailure(error: Throwable?): CheckFailure = when (error) {
    is AiError.InvalidKey -> CheckFailure.KEY
    is AiError.RateLimited, is AiError.QuotaExhausted -> CheckFailure.QUOTA
    is AiError.ModelNotFound -> CheckFailure.MODEL
    is AiError.Network, is AiError.Timeout -> CheckFailure.NETWORK
    else -> CheckFailure.OTHER
}

/** What a profile does in the fallback chain right now, as the list shows it. */
enum class ProfileRole {
    /** The first profile a photo estimate goes to. */
    FIRST,
    /** Ready, used when the ones above can't answer. */
    BACKUP,
    OFF,
    NO_KEY,
    /** The last request said the key is wrong; skipped until a test passes. */
    KEY_REJECTED,
    /** Out of quota until its cooldown ends. */
    PAUSED,
    /** The user hasn't allowed its recipient to receive meals (roadmap 2.0 task 4.2): skipped. */
    NOT_ALLOWED,
}

/** Roles in list order. Mirrors AiChain.usable, plus "no key" and "not allowed" (such a profile can't answer). */
fun profileRoles(profiles: List<AiProfileStatus>, nowMs: Long, allowed: (AiProfile) -> Boolean = { true }): List<ProfileRole> {
    var firstTaken = false
    return profiles.map { s ->
        val p = s.profile
        when {
            !p.enabled -> ProfileRole.OFF
            !s.hasKey && p.provider != ProviderId.OPENAI_COMPAT -> ProfileRole.NO_KEY
            !allowed(p) -> ProfileRole.NOT_ALLOWED
            p.needsAttention -> ProfileRole.KEY_REJECTED
            (p.cooldownUntil ?: 0) > nowMs -> ProfileRole.PAUSED
            !firstTaken -> ProfileRole.FIRST.also { firstTaken = true }
            else -> ProfileRole.BACKUP
        }
    }
}

data class AiSettingsUiState(
    val loaded: Boolean = false,
    val settings: AiSettings = AiSettings(),
    val profiles: List<AiProfileStatus> = emptyList(),
    /** Same order as [profiles]. */
    val roles: List<ProfileRole> = emptyList(),
    val usage: MonthUsage? = null,
    val tests: Map<String, TestState> = emptyMap(),
    /** "Add backup model" when there's only one Gemini profile (owner, 2026-10-23). */
    val backupOffer: app.forkcast.core.data.repository.BackupOffer? = null,
    /** Recipients allowed to receive meals, with when (roadmap 2.0 task 4.2). */
    val consents: Map<String, Long> = emptyMap(),
) {
    fun allowed(profile: AiProfile): Boolean = AiRecipient.of(profile) in consents

    fun roleAt(index: Int): ProfileRole = roles.getOrElse(index) { ProfileRole.BACKUP }
}

sealed interface AiSettingsEvent {
    data class SetEnabled(val enabled: Boolean) : AiSettingsEvent
    data class SetProfileEnabled(val id: String, val enabled: Boolean) : AiSettingsEvent
    /** Move within the list, from the drag handle, the Move up/down menu or the accessibility actions. */
    data class Move(val from: Int, val to: Int) : AiSettingsEvent
    data class Test(val id: String) : AiSettingsEvent
    /** Already confirmed by the user (list menu or profile editor). Shows "Deleted …" with Undo. */
    data class Delete(val id: String) : AiSettingsEvent
    data object UndoDelete : AiSettingsEvent
    data class SetPersonalContext(val text: String) : AiSettingsEvent
    data class SetAutoAccept(val enabled: Boolean) : AiSettingsEvent
    data class SetRetention(val days: Int?) : AiSettingsEvent
    data class SetPrice(val model: String, val price: ModelPrice?) : AiSettingsEvent
    data object AddBackupModel : AiSettingsEvent
    /** From the permission sheet, for a profile's recipient ([AiRecipient]). */
    data class AllowAi(val recipient: String) : AiSettingsEvent
    data class RevokeAi(val recipient: String) : AiSettingsEvent
}

sealed interface AiSettingsEffect {
    data class Deleted(val name: String) : AiSettingsEffect
    data class BackupAdded(val name: String) : AiSettingsEffect
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val ai: AiRepository,
    private val settingsRepo: SettingsRepository,
    private val clock: Clock,
    private val consent: ConsentRepository,
) : ViewModel() {

    private val tests = MutableStateFlow<Map<String, TestState>>(emptyMap())
    private val effectChannel = Channel<AiSettingsEffect>(Channel.BUFFERED)
    val effects: Flow<AiSettingsEffect> = effectChannel.receiveAsFlow()

    /** The last deletion, until Undo or the next deletion. Holds the key in memory only. */
    private var lastDeleted: DeletedAiProfile? = null

    val state: StateFlow<AiSettingsUiState> = combine(
        settingsRepo.settings.map { it.ai },
        ai.profiles,
        ai.monthUsage(YearMonth.now(clock)),
        combine(tests, consent.aiConsents, ::Pair),
        ai.profiles.mapLatest { runCatching { ai.backupOffer() }.getOrNull() },
    ) { settings, profiles, usage, (t, consents), offer ->
        AiSettingsUiState(
            loaded = true,
            settings = settings,
            profiles = profiles,
            roles = profileRoles(profiles, clock.millis()) { AiRecipient.of(it) in consents },
            usage = usage,
            tests = t,
            backupOffer = offer,
            consents = consents,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    fun onEvent(event: AiSettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is AiSettingsEvent.SetEnabled -> settingsRepo.updateAi { it.copy(enabled = event.enabled) }
                is AiSettingsEvent.SetProfileEnabled -> ai.setEnabled(event.id, event.enabled)
                is AiSettingsEvent.Move -> {
                    val ids = ai.profilesNow().sortedBy { it.order }.map { it.id }.toMutableList()
                    if (event.from !in ids.indices || event.to !in ids.indices) return@launch
                    ids.add(event.to, ids.removeAt(event.from))
                    ai.reorder(ids)
                }
                is AiSettingsEvent.Test -> {
                    tests.update { it + (event.id to TestState.Running) }
                    val result = ai.runTest(event.id)
                    tests.update { it + (event.id to result) }
                    settingsRepo.recordCheck(event.id, result, clock.millis())
                }
                is AiSettingsEvent.Delete -> {
                    val deleted = ai.deleteProfile(event.id) ?: return@launch
                    lastDeleted = deleted
                    tests.update { it - event.id }
                    settingsRepo.updateAi { it.copy(lastChecks = it.lastChecks - event.id) }
                    effectChannel.send(AiSettingsEffect.Deleted(deleted.name))
                }
                AiSettingsEvent.UndoDelete -> {
                    val deleted = lastDeleted ?: return@launch
                    lastDeleted = null
                    ai.restoreProfile(deleted)
                }
                is AiSettingsEvent.SetPersonalContext -> settingsRepo.updateAi { it.copy(personalContext = event.text) }
                is AiSettingsEvent.SetAutoAccept -> settingsRepo.updateAi { it.copy(autoAccept = event.enabled) }
                is AiSettingsEvent.SetRetention -> settingsRepo.updateAi { it.copy(photoRetentionDays = event.days) }
                AiSettingsEvent.AddBackupModel -> {
                    val name = ai.backupOffer()?.name ?: return@launch
                    ai.addBackupModel() ?: return@launch
                    effectChannel.send(AiSettingsEffect.BackupAdded(name))
                }
                is AiSettingsEvent.AllowAi -> consent.allowAi(event.recipient)
                is AiSettingsEvent.RevokeAi -> consent.revokeAi(event.recipient)
                is AiSettingsEvent.SetPrice -> settingsRepo.updateAi {
                    it.copy(priceOverrides = if (event.price == null) it.priceOverrides - event.model else it.priceOverrides + (event.model to event.price))
                }
            }
        }
    }

    override fun onCleared() {
        lastDeleted = null
    }
}
