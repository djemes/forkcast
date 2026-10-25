package app.forkcast.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.ai.ComponentEdits
import app.forkcast.core.data.ai.EstimateDetail
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateKind
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.BackupOffer
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.Food
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.network.FoodApiError
import app.forkcast.core.network.ai.AiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Edits on the review screen are a draft (owner, 2026-10-23: nothing is saved until Save; Back or
 * Cancel with changes asks "Discard changes?"). [baseAttempts] ties the draft to the result it was
 * made on: a new result (after "Correct it") starts a fresh draft.
 */
data class ReviewDraft(val baseAttempts: Int, val components: List<AiComponentEntity>, val portion: Double)

data class ReviewUiState(
    val loaded: Boolean = false,
    /** Null once loaded = the estimate was deleted. */
    val detail: EstimateDetail? = null,
    val draft: ReviewDraft? = null,
    val photoFiles: List<File> = emptyList(),
    val units: UnitPrefs? = null,
    /** The open "Change match" search, if any. */
    val match: MatchSearchState? = null,
    /** Enabled profiles, for "Try with another profile". */
    val profiles: List<AiProfile> = emptyList(),
    /** "Add backup model" (one Gemini profile only). */
    val backupOffer: BackupOffer? = null,
) {
    /** What the screen shows: the draft when there is one, else what's stored. */
    val components: List<AiComponentEntity> get() = draft?.components ?: detail?.components.orEmpty()
    val portion: Double get() = draft?.portion ?: detail?.estimate?.portion ?: 1.0
    /** Unsaved edits. */
    val dirty: Boolean
        get() {
            val d = draft ?: return false
            val stored = detail ?: return false
            return d.portion != stored.estimate.portion || d.components != stored.components
        }
    val isText: Boolean get() = detail?.estimate?.kind == EstimateKind.TEXT
}

sealed interface ReviewEvent {
    data class SetGrams(val componentId: String, val grams: Double) : ReviewEvent
    data class SetSource(val componentId: String, val source: ComponentSource) : ReviewEvent
    /** Open the "Change match" search for a component. */
    data class ChangeMatch(val componentId: String) : ReviewEvent
    data class SearchMatch(val query: String) : ReviewEvent
    data class PickMatch(val food: Food) : ReviewEvent
    data object CloseMatch : ReviewEvent
    data class Remove(val componentId: String) : ReviewEvent
    data object RemoveAllAdded : ReviewEvent
    /** "How much did you eat?": share of the plate (0.25, 0.5, …, or a custom percent / 100). */
    data class SetPortion(val portion: Double) : ReviewEvent
    /** "Correct it", or the answer to the model's question. */
    data class Correct(val text: String) : ReviewEvent
    data object AcceptAsIs : ReviewEvent
    data object RetryNow : ReviewEvent
    data class RetryWith(val profileId: String) : ReviewEvent
    data class EditDescription(val text: String) : ReviewEvent
    data object LogManually : ReviewEvent
    data object AddBackupModel : ReviewEvent
    /** [saveAsFood]: a described meal becomes a food named [foodName] (on by default). */
    data class Save(val saveAsFood: Boolean = false, val foodName: String = "") : ReviewEvent
    /** Cancel / "Discard changes": drop the draft and leave. */
    data object Discard : ReviewEvent
    data object Delete : ReviewEvent
}

sealed interface ReviewEffect {
    data object Close : ReviewEffect
    /** "Log manually instead": search on the estimate's day and meal, prefilled with the description. */
    data class OpenSearch(val mealSlotId: String, val date: String, val query: String, val photo: String?) : ReviewEffect
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val estimates: AiEstimateRepository,
    private val photos: PhotoStore,
    settings: SettingsRepository,
    private val foods: FoodRepository,
    private val ai: AiRepository,
) : ViewModel() {

    private val match = MutableStateFlow<MatchSearchState?>(null)
    private val draft = MutableStateFlow<ReviewDraft?>(null)
    private val backupOffer = MutableStateFlow<BackupOffer?>(null)

    private val id: String = checkNotNull(savedState["estimateId"])
    private val effects = Channel<ReviewEffect>(Channel.BUFFERED)
    val effect: Flow<ReviewEffect> = effects.receiveAsFlow()

    val state: StateFlow<ReviewUiState> = combine(
        estimates.observeDetail(id),
        settings.settings,
        combine(match, draft, backupOffer) { m, d, b -> Triple(m, d, b) },
        ai.profiles,
    ) { d, s, (m, dr, offer), profiles ->
        ReviewUiState(
            loaded = true,
            detail = d,
            // A draft made on an older result (before "Correct it" came back) no longer applies.
            draft = dr?.takeIf { d != null && it.baseAttempts == d.estimate.attempts },
            photoFiles = d?.estimate?.photoPaths.orEmpty().map(photos::file),
            units = s.units,
            match = m,
            profiles = profiles.map { it.profile }.filter { it.enabled && !it.needsAttention },
            backupOffer = offer,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    init {
        viewModelScope.launch { backupOffer.value = runCatching { ai.backupOffer() }.getOrNull() }
    }

    /** Applies [change] to a draft of the current components and portion. */
    private fun edit(change: (ReviewDraft) -> ReviewDraft) {
        val s = state.value
        val stored = s.detail ?: return
        val base = s.draft ?: ReviewDraft(stored.estimate.attempts, stored.components, stored.estimate.portion)
        draft.value = change(base)
    }

    private fun editComponent(componentId: String, change: (AiComponentEntity) -> AiComponentEntity) =
        edit { d -> d.copy(components = d.components.map { if (it.id == componentId) change(it) else it }) }

    private suspend fun search(query: String) {
        match.update { it?.copy(query = query, loading = true, error = null) }
        val (results, error) = searchFoods(foods, query)
        match.update { m -> m?.takeIf { it.query == query }?.copy(loading = false, results = results, error = error) ?: m }
    }

    fun onEvent(event: ReviewEvent) {
        viewModelScope.launch {
            when (event) {
                is ReviewEvent.SetGrams -> editComponent(event.componentId) { ComponentEdits.withGrams(it, event.grams) }
                is ReviewEvent.SetSource -> editComponent(event.componentId) { ComponentEdits.withSource(it, event.source) }
                is ReviewEvent.ChangeMatch -> {
                    val c = state.value.components.firstOrNull { it.id == event.componentId } ?: return@launch
                    val query = c.usdaQuery.ifBlank { c.name }
                    match.value = MatchSearchState(c.id, c.name, query)
                    search(query)
                }
                is ReviewEvent.SearchMatch -> search(event.query)
                is ReviewEvent.PickMatch -> {
                    val m = match.value ?: return@launch
                    match.value = null
                    val local = estimates.localFood(event.food)
                    editComponent(m.componentId) { ComponentEdits.withMatch(it, local) }
                }
                ReviewEvent.CloseMatch -> match.value = null
                is ReviewEvent.Remove -> edit { d -> d.copy(components = d.components.filterNot { it.id == event.componentId }) }
                ReviewEvent.RemoveAllAdded -> edit { d -> d.copy(components = d.components.filterNot { it.isHiddenIngredient }) }
                is ReviewEvent.SetPortion -> edit { d -> d.copy(portion = event.portion.coerceIn(0.01, 10.0)) }
                is ReviewEvent.Correct -> {
                    draft.value = null
                    estimates.correct(id, event.text)
                }
                ReviewEvent.AcceptAsIs -> {
                    estimates.acceptAsIs(id)
                    estimates.saveReview(id, state.value.components, state.value.portion)
                    effects.send(ReviewEffect.Close)
                }
                ReviewEvent.RetryNow -> estimates.retry(id)
                is ReviewEvent.RetryWith -> estimates.retryWith(id, event.profileId)
                is ReviewEvent.EditDescription -> estimates.editDescriptionAndRetry(id, event.text)
                ReviewEvent.AddBackupModel -> {
                    ai.addBackupModel()
                    backupOffer.value = null
                }
                ReviewEvent.LogManually -> {
                    val e = state.value.detail?.estimate ?: return@launch
                    estimates.abandon(id)
                    effects.send(ReviewEffect.OpenSearch(e.mealSlotId, e.date.toString(), e.description.orEmpty(), e.photoPaths.firstOrNull()))
                }
                is ReviewEvent.Save -> {
                    val s = state.value
                    estimates.saveReview(id, s.components, s.portion)
                    if (event.saveAsFood && s.isText) estimates.saveAsFood(id, event.foodName)
                    draft.value = null
                    effects.send(ReviewEffect.Close)
                }
                ReviewEvent.Discard -> {
                    draft.value = null
                    effects.send(ReviewEffect.Close)
                }
                ReviewEvent.Delete -> {
                    estimates.delete(id)
                    effects.send(ReviewEffect.Close)
                }
            }
        }
    }
}

/** Your own foods first, then USDA (generic and branded). Offline still shows your foods. */
private suspend fun searchFoods(foods: FoodRepository, query: String): Pair<List<Food>, FoodApiError?> {
    val local = runCatching { foods.searchLocal(query, limit = 10) }.getOrDefault(emptyList())
    return try {
        (local + foods.searchUsda(query)).distinctBy { it.source to (it.sourceId ?: it.id) } to null
    } catch (e: FoodApiError) {
        local to e
    }
}

/** Rebuilds the error an estimate stopped on, for [app.forkcast.ui.ai.aiErrorMessage]. */
fun errorFromCode(code: String?, detail: String?): Throwable? = when (code) {
    null -> null
    "InvalidKey" -> AiError.InvalidKey(detail)
    "RateLimited" -> AiError.RateLimited(null)
    "QuotaExhausted" -> AiError.QuotaExhausted(null)
    "ModelNotFound" -> AiError.ModelNotFound(detail?.substringAfter(": ").orEmpty())
    "ContentRefused" -> AiError.ContentRefused()
    "BadResponse" -> AiError.BadResponse(null)
    "InvalidRequest" -> AiError.InvalidRequest(detail)
    "Network" -> AiError.Network()
    "Timeout" -> AiError.Timeout()
    "Server" -> AiError.Server(detail?.filter { it.isDigit() }?.toIntOrNull() ?: 500)
    AiErrorCodes.NO_CONSENT -> AiError.NoConsent()
    AiErrorCodes.NO_PROFILE, AiErrorCodes.DISABLED, AiErrorCodes.STOPPED -> null
    else -> AiError.BadResponse(null)
}
