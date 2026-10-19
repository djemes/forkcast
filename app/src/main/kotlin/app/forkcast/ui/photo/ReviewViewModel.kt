package app.forkcast.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.ai.EstimateDetail
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.network.ai.AiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ReviewUiState(
    val loaded: Boolean = false,
    /** Null once loaded = the estimate was deleted. */
    val detail: EstimateDetail? = null,
    val photoFiles: List<File> = emptyList(),
    val units: UnitPrefs? = null,
)

sealed interface ReviewEvent {
    data class SetGrams(val componentId: String, val grams: Double) : ReviewEvent
    data class SetSource(val componentId: String, val source: ComponentSource) : ReviewEvent
    data class Remove(val componentId: String) : ReviewEvent
    data object RemoveAllAdded : ReviewEvent
    /** "Correct it", or the answer to the model's question. */
    data class Correct(val text: String) : ReviewEvent
    data object AcceptAsIs : ReviewEvent
    data object Retry : ReviewEvent
    data object Save : ReviewEvent
    data object Delete : ReviewEvent
}

sealed interface ReviewEffect {
    data object Close : ReviewEffect
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val estimates: AiEstimateRepository,
    private val photos: PhotoStore,
    settings: SettingsRepository,
) : ViewModel() {

    private val id: String = checkNotNull(savedState["estimateId"])
    private val effects = Channel<ReviewEffect>(Channel.BUFFERED)
    val effect: Flow<ReviewEffect> = effects.receiveAsFlow()

    val state: StateFlow<ReviewUiState> = combine(estimates.observeDetail(id), settings.settings) { d, s ->
        ReviewUiState(loaded = true, detail = d, photoFiles = d?.estimate?.photoPaths.orEmpty().map(photos::file), units = s.units)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun onEvent(event: ReviewEvent) {
        viewModelScope.launch {
            when (event) {
                is ReviewEvent.SetGrams -> estimates.setGrams(event.componentId, event.grams)
                is ReviewEvent.SetSource -> estimates.setSource(event.componentId, event.source)
                is ReviewEvent.Remove -> estimates.removeComponent(event.componentId)
                ReviewEvent.RemoveAllAdded -> state.value.detail?.components?.filter { it.isHiddenIngredient }?.forEach {
                    estimates.removeComponent(it.id)
                }
                is ReviewEvent.Correct -> estimates.correct(id, event.text)
                ReviewEvent.AcceptAsIs -> {
                    estimates.acceptAsIs(id)
                    estimates.save(id)
                    effects.send(ReviewEffect.Close)
                }
                ReviewEvent.Retry -> estimates.retry(id)
                ReviewEvent.Save -> {
                    estimates.save(id)
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
    AiErrorCodes.NO_PROFILE, AiErrorCodes.DISABLED -> null
    else -> AiError.BadResponse(null)
}
