package app.forkcast.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.ApiKeyFormat
import app.forkcast.core.domain.KeyCheck
import app.forkcast.core.domain.KeyService
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

enum class SetupStep { CHOOSE, ABOUT, GET_KEY, PASTE, DONE }

/** How a finished setup went, for the last step's wording. */
enum class SetupOutcome {
    /** The key passed the check. */
    WORKS,
    /** The provider accepted the key but is rate limited or out of today's free quota. */
    BUSY,
    /** Saved without a passing check ("Save anyway"). */
    UNCHECKED,
}

data class AiSetupUiState(
    val step: SetupStep = SetupStep.CHOOSE,
    val provider: ProviderId = ProviderId.GEMINI,
    /** The key as pasted or typed; [ApiKeyFormat.clean] runs before it's used. */
    val key: String = "",
    val checking: Boolean = false,
    /** The last check's error, shown with a plain explanation; null before a check or after a new paste. */
    val error: Throwable? = null,
    val outcome: SetupOutcome? = null,
    /** The model's display name, for "Gemini 3.8 Flash is ready". */
    val modelName: String = "",
    /** The free backup model added on the same key (Gemini), if any. */
    val backupName: String? = null,
    /** Recipients the user allowed to receive meals (roadmap 2.0 task 4.2). */
    val allowedRecipients: Set<String> = emptySet(),
) {
    /** "What this means" doubles as the permission question: its button is "Allow and continue" until allowed. */
    val allowed: Boolean get() = AiRecipient.of(provider, null) in allowedRecipients
    val service: KeyService get() = if (provider == ProviderId.CLAUDE) KeyService.CLAUDE else KeyService.GEMINI
    val keyCheck: KeyCheck get() = ApiKeyFormat.check(service, key)
    val canCheck: Boolean get() = keyCheck != KeyCheck.EMPTY && !checking
    /** A key the provider refused is never saved; anything else may be ("Save anyway"). */
    val canSaveAnyway: Boolean get() = error != null && error !is AiError.InvalidKey && !checking
    val stepNumber: Int get() = step.ordinal + 1

    companion object {
        val STEP_COUNT = SetupStep.entries.size
    }
}

sealed interface AiSetupEvent {
    data class Choose(val provider: ProviderId) : AiSetupEvent
    data object Next : AiSetupEvent
    /** "Allow and continue" on "What this means": records the permission, then goes on. */
    data object AllowAndNext : AiSetupEvent
    data object Back : AiSetupEvent
    data class SetKey(val key: String) : AiSetupEvent
    /** Paste fills the field and checks at once when the key looks usable. */
    data class Pasted(val key: String) : AiSetupEvent
    data object Check : AiSetupEvent
    data object SaveAnyway : AiSetupEvent
}

/**
 * The guided AI setup (roadmap 2.0 task 3.3): choose → what it means → get a key → paste → done.
 * The key is checked with the provider before anything is saved, so a mistyped key never leaves a
 * broken profile behind. Gemini also gets its budget model on the same key (a separate free quota).
 */
@HiltViewModel
class AiSetupViewModel @Inject constructor(
    private val ai: AiRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
    private val consent: ConsentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AiSetupUiState(
            step = savedState.get<String>(KEY_STEP)?.let { runCatching { SetupStep.valueOf(it) }.getOrNull() } ?: SetupStep.CHOOSE,
            provider = savedState.get<String>(KEY_PROVIDER)?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() } ?: ProviderId.GEMINI,
        ).let { if (it.step == SetupStep.DONE) it.copy(step = SetupStep.PASTE) else it },
    )
    val state: StateFlow<AiSetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { consent.aiConsents.collect { c -> _state.update { it.copy(allowedRecipients = c.keys) } } }
        // Only the step and the service survive a process restart; never the key.
        viewModelScope.launch {
            state.collect { s ->
                savedState[KEY_STEP] = s.step.name
                savedState[KEY_PROVIDER] = s.provider.name
            }
        }
    }

    fun onEvent(event: AiSetupEvent) {
        when (event) {
            is AiSetupEvent.Choose -> _state.update { it.copy(provider = event.provider, step = SetupStep.ABOUT, key = "", error = null) }
            AiSetupEvent.Next -> _state.update { s ->
                val next = when (s.step) {
                    SetupStep.CHOOSE -> SetupStep.ABOUT
                    SetupStep.ABOUT -> SetupStep.GET_KEY
                    SetupStep.GET_KEY -> SetupStep.PASTE
                    else -> s.step
                }
                s.copy(step = next)
            }
            AiSetupEvent.AllowAndNext -> viewModelScope.launch {
                consent.allowAi(AiRecipient.of(_state.value.provider, null))
                onEvent(AiSetupEvent.Next)
            }
            AiSetupEvent.Back -> _state.update { s ->
                if (s.checking) s else s.copy(step = SetupStep.entries[(s.step.ordinal - 1).coerceAtLeast(0)], error = null)
            }
            is AiSetupEvent.SetKey -> _state.update { it.copy(key = event.key, error = null) }
            is AiSetupEvent.Pasted -> {
                _state.update { it.copy(key = ApiKeyFormat.clean(event.key), error = null) }
                if (_state.value.keyCheck in AUTO_CHECK) check()
            }
            AiSetupEvent.Check -> check()
            AiSetupEvent.SaveAnyway -> viewModelScope.launch { finish(SetupOutcome.UNCHECKED, report = null) }
        }
    }

    private fun check() {
        val s = _state.value
        if (!s.canCheck) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val result = ai.testKey(s.provider, model(s.provider), ApiKeyFormat.clean(s.key))
            val error = result.exceptionOrNull()
            when {
                error == null -> finish(SetupOutcome.WORKS, result.getOrNull())
                // Google answered "busy" or "today's free limit": the key itself is fine.
                error is AiError.RateLimited || (error is AiError.QuotaExhausted && s.provider == ProviderId.GEMINI) ->
                    finish(SetupOutcome.BUSY, report = null)
                else -> _state.update { it.copy(checking = false, error = error) }
            }
        }
    }

    private suspend fun finish(outcome: SetupOutcome, report: app.forkcast.core.network.ai.ConnectionReport?) {
        val s = _state.value
        val model = model(s.provider)
        val name = ai.defaults.displayName(model)
        // The guide's Gemini is the free one; Claude keys are always paid (AI_PROVIDERS.md §2.5).
        val tier = if (s.provider == ProviderId.GEMINI) TierLabel.FREE else TierLabel.PAID
        val id = ai.saveProfile(AiProfileDraft(null, name, s.provider, model, null, tier), ApiKeyFormat.clean(s.key))
        if (report != null) settings.recordCheck(id, TestState.Ok(report), clock.millis())
        val backupName = if (s.provider == ProviderId.GEMINI) ai.backupOffer()?.takeIf { it.fromProfileId == id }?.name else null
        if (backupName != null) ai.addBackupModel()
        settings.updateAi { it.copy(enabled = true) }
        _state.update {
            it.copy(checking = false, error = null, key = "", step = SetupStep.DONE, outcome = outcome, modelName = name, backupName = backupName)
        }
    }

    private fun model(provider: ProviderId): String = ai.defaults.provider(provider).recommendedModel.orEmpty()

    private companion object {
        const val KEY_STEP = "setup_step"
        const val KEY_PROVIDER = "setup_provider"
        /** Pasted keys that look usable are checked right away; anything odd waits for "Check key". */
        val AUTO_CHECK = setOf(KeyCheck.LOOKS_RIGHT, KeyCheck.UNFAMILIAR)
    }
}
