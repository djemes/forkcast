package app.forkcast.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.ModelInfo
import app.forkcast.ui.common.SecretText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A profile that already has a key for the same provider, offered as "Use the key from …". */
data class KeySource(val id: String, val name: String)

data class AiProfileEditUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val provider: ProviderId = ProviderId.GEMINI,
    val name: String = "",
    /** True once the user typed a name, so changing model stops renaming. */
    val nameEdited: Boolean = false,
    val model: String = "",
    val baseUrl: String = "",
    /** Not asked any more (owner, 2026-10-19): new Gemini profiles are UNKNOWN unless made by the free setup. */
    val tier: TierLabel = TierLabel.UNKNOWN,
    val keyInput: String = "",
    val hasSavedKey: Boolean = false,
    /** The saved key for the masked field with show/hide and copy (null while replacing or unknown). */
    val savedKey: SecretText? = null,
    /** True while typing a new key over a saved one. */
    val replacingKey: Boolean = false,
    val copyKeyFrom: KeySource? = null,
    val keySources: List<KeySource> = emptyList(),
    val models: List<ModelInfo>? = null,
    val loadingModels: Boolean = false,
    val modelsError: Throwable? = null,
    val recommendedModel: String? = null,
    val saving: Boolean = false,
    val test: TestState? = null,
    /** Set when the profile is saved and its test passed: the screen closes. */
    val done: Boolean = false,
) {
    val keyReady: Boolean get() = keyInput.isNotBlank() || hasSavedKey || copyKeyFrom != null || provider == ProviderId.OPENAI_COMPAT
    /** Show the saved key read-only (masked) instead of the entry field. */
    val showSavedKey: Boolean get() = savedKey != null && !replacingKey && copyKeyFrom == null
    val canSave: Boolean get() = model.isNotBlank() && keyReady && (provider != ProviderId.OPENAI_COMPAT || baseUrl.isNotBlank()) && !saving
    val isCleartext: Boolean get() = provider == ProviderId.OPENAI_COMPAT && baseUrl.trim().startsWith("http://", ignoreCase = true)
}

sealed interface AiProfileEditEvent {
    data class SetProvider(val provider: ProviderId) : AiProfileEditEvent
    data class SetName(val name: String) : AiProfileEditEvent
    data class SetModel(val model: String) : AiProfileEditEvent
    data class SetBaseUrl(val url: String) : AiProfileEditEvent
    data class SetKey(val key: String) : AiProfileEditEvent
    data class UseKeyFrom(val source: KeySource?) : AiProfileEditEvent
    data object LoadModels : AiProfileEditEvent
    data object SaveAndTest : AiProfileEditEvent
    /** Re-runs the test on the saved profile: the same call as Test on the profile list. */
    data object TestAgain : AiProfileEditEvent
    data class ReplaceKey(val replacing: Boolean) : AiProfileEditEvent
}

@HiltViewModel
class AiProfileEditViewModel @Inject constructor(
    private val ai: AiRepository,
    savedState: SavedStateHandle,
    private val settings: app.forkcast.core.data.repository.SettingsRepository,
    private val clock: java.time.Clock,
) : ViewModel() {

    private val profileId: String? = savedState["profileId"]
    /** "openai": a new profile that starts on OpenAI-compatible (the setup guide's Advanced). */
    private val preset: String? = savedState["preset"]
    private var savedId: String? = profileId
    /** The saved profile's id once it exists, for Delete (which the profile list carries out, with Undo). */
    val savedProfileId: String? get() = savedId
    private var allProfiles: List<AiProfile> = emptyList()
    private var keysPresent: Set<String> = emptySet()

    private val _state = MutableStateFlow(AiProfileEditUiState())
    val state: StateFlow<AiProfileEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val statuses = ai.profiles.first()
            allProfiles = statuses.map { it.profile }
            keysPresent = statuses.filter { it.hasKey }.map { it.profile.id }.toSet()
            val existing = profileId?.let { id -> allProfiles.firstOrNull { it.id == id } }
            if (existing != null) {
                _state.value = AiProfileEditUiState(
                    loaded = true,
                    isNew = false,
                    provider = existing.provider,
                    name = existing.name,
                    nameEdited = true,
                    model = existing.model,
                    baseUrl = existing.baseUrl.orEmpty(),
                    tier = existing.tierLabel,
                    hasSavedKey = existing.id in keysPresent,
                    savedKey = ai.savedKey(existing.id)?.let(::SecretText),
                ).withProviderInfo()
            } else {
                val provider = if (preset == OPENAI) ProviderId.OPENAI_COMPAT else ProviderId.GEMINI
                _state.value = AiProfileEditUiState(loaded = true, provider = provider, tier = newTier(provider))
                    .withProviderInfo()
                    .let { s -> s.copy(model = s.recommendedModel.orEmpty(), name = ai.defaults.displayName(s.recommendedModel.orEmpty())) }
            }
        }
    }

    fun onEvent(event: AiProfileEditEvent) {
        when (event) {
            is AiProfileEditEvent.SetProvider -> _state.update { s ->
                val next = s.copy(provider = event.provider, models = null, modelsError = null, copyKeyFrom = null,
                    tier = if (s.isNew) newTier(event.provider) else s.tier).withProviderInfo()
                val model = if (s.isNew) next.recommendedModel.orEmpty() else next.model
                next.copy(model = model, name = if (s.nameEdited) s.name else ai.defaults.displayName(model))
            }
            is AiProfileEditEvent.SetName -> _state.update { it.copy(name = event.name, nameEdited = true) }
            is AiProfileEditEvent.SetModel -> _state.update {
                it.copy(model = event.model.trim(), name = if (it.nameEdited) it.name else ai.defaults.displayName(event.model.trim()))
            }
            is AiProfileEditEvent.SetBaseUrl -> _state.update { it.copy(baseUrl = event.url.trim()) }
            is AiProfileEditEvent.SetKey -> _state.update { it.copy(keyInput = event.key.trim(), copyKeyFrom = null) }
            is AiProfileEditEvent.ReplaceKey -> _state.update { it.copy(replacingKey = event.replacing, keyInput = "") }
            is AiProfileEditEvent.UseKeyFrom -> _state.update { it.copy(copyKeyFrom = event.source, keyInput = "") }
            AiProfileEditEvent.LoadModels -> loadModels()
            AiProfileEditEvent.SaveAndTest -> saveAndTest()
            AiProfileEditEvent.TestAgain -> testAgain()
        }
    }

    /** The setup guide is the only place that knows a key is free; Claude/OpenAI keys are paid. */
    private fun newTier(provider: ProviderId): TierLabel = if (provider != ProviderId.GEMINI) TierLabel.PAID else TierLabel.UNKNOWN

    private fun AiProfileEditUiState.withProviderInfo(): AiProfileEditUiState {
        val d = ai.defaults.provider(provider)
        val sources = allProfiles
            .filter { it.provider == provider && it.id != profileId && it.id in keysPresent }
            .map { KeySource(it.id, it.name) }
        return copy(
            recommendedModel = d.recommendedModel,
            keySources = sources,
            baseUrl = if (provider == ProviderId.OPENAI_COMPAT && baseUrl.isBlank()) d.defaultBaseUrl.orEmpty() else baseUrl,
        )
    }

    private fun loadModels() {
        val s = _state.value
        _state.update { it.copy(loadingModels = true, modelsError = null) }
        viewModelScope.launch {
            val draftKey = s.keyInput.takeIf { it.isNotBlank() }
            val keyProfile = s.copyKeyFrom?.id ?: savedId
            val result = ai.listModels(s.provider, s.baseUrl.takeIf { it.isNotBlank() }, keyProfile, draftKey)
            _state.update { it.copy(loadingModels = false, models = result.getOrNull(), modelsError = result.exceptionOrNull()) }
        }
    }

    private fun saveAndTest() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, test = TestState.Running) }
        viewModelScope.launch {
            val draft = AiProfileDraft(savedId, s.name, s.provider, s.model, s.baseUrl.takeIf { it.isNotBlank() }, s.tier)
            val id = ai.saveProfile(draft, s.keyInput.takeIf { it.isNotBlank() })
            savedId = id
            s.copyKeyFrom?.let { ai.copyKey(it.id, id) }
            val result = ai.runTest(id)
            settings.recordCheck(id, result, clock.millis())
            val key = ai.savedKey(id)?.let(::SecretText)
            _state.update {
                it.copy(
                    saving = false,
                    isNew = false,
                    hasSavedKey = key != null,
                    savedKey = key,
                    replacingKey = false,
                    copyKeyFrom = null,
                    keyInput = "",
                    test = result,
                    done = result is TestState.Ok,
                )
            }
        }
    }

    private fun testAgain() {
        val id = savedId ?: return
        _state.update { it.copy(test = TestState.Running) }
        viewModelScope.launch {
            val result = ai.runTest(id)
            settings.recordCheck(id, result, clock.millis())
            _state.update { it.copy(test = result) }
        }
    }

    companion object {
        const val OPENAI = "openai"
    }
}
