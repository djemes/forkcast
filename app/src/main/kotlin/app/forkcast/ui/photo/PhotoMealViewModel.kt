package app.forkcast.ui.photo

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.MealSlot
import app.forkcast.core.network.ai.AiChain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class PhotoStep { CAPTURE, DETAILS }

data class PhotoMealUiState(
    val loaded: Boolean = false,
    val step: PhotoStep = PhotoStep.CAPTURE,
    val photos: List<Uri> = emptyList(),
    val description: String = "",
    val mealSlotId: String = "",
    val slots: List<MealSlot> = emptyList(),
    val aiEnabled: Boolean = true,
    /** At least one enabled profile exists. */
    val hasProfile: Boolean = false,
    /** The first profile in the chain is Gemini free (or not sure): show the privacy hint. */
    val firstIsFreeTier: Boolean = false,
    val notificationPermissionAsked: Boolean = true,
    val submitting: Boolean = false,
) {
    val canAddPhoto: Boolean get() = photos.size < MAX_PHOTOS
    val canEstimate: Boolean get() = photos.isNotEmpty() && aiEnabled && hasProfile && mealSlotId.isNotBlank() && !submitting

    companion object {
        const val MAX_PHOTOS = 3
    }
}

sealed interface PhotoMealEvent {
    data class AddPhotos(val uris: List<Uri>) : PhotoMealEvent
    data class RemovePhoto(val index: Int) : PhotoMealEvent
    data object Next : PhotoMealEvent
    data object BackToCamera : PhotoMealEvent
    data class SetDescription(val text: String) : PhotoMealEvent
    data class SetMeal(val id: String) : PhotoMealEvent
    data object Estimate : PhotoMealEvent
    data object NotificationPermissionAsked : PhotoMealEvent
}

sealed interface PhotoMealEffect {
    data class Queued(val mealName: String) : PhotoMealEffect
}

@HiltViewModel
class PhotoMealViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val estimates: AiEstimateRepository,
    ai: AiRepository,
    slots: MealSlotRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val date: LocalDate = savedState.get<String>("date")?.let(LocalDate::parse) ?: LocalDate.now(clock)
    private val draft = MutableStateFlow(
        PhotoMealUiState(mealSlotId = savedState.get<String>("mealSlotId").orEmpty()),
    )
    private val effects = Channel<PhotoMealEffect>(Channel.BUFFERED)
    val effect: Flow<PhotoMealEffect> = effects.receiveAsFlow()

    val state: StateFlow<PhotoMealUiState> = combine(draft, slots.observeMealSlots(), ai.profiles, settings.settings) { d, s, profiles, app ->
        val usable = AiChain.usable(profiles.map { it.profile }, clock.instant()).ifEmpty { profiles.map { it.profile }.filter { it.enabled } }
        d.copy(
            loaded = true,
            slots = s,
            mealSlotId = d.mealSlotId.ifBlank { s.firstOrNull()?.id.orEmpty() },
            aiEnabled = app.ai.enabled,
            hasProfile = profiles.any { it.profile.enabled },
            firstIsFreeTier = usable.firstOrNull()?.showsFreeTierNote == true,
            notificationPermissionAsked = app.notifications.permissionAsked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), draft.value)

    fun onEvent(event: PhotoMealEvent) {
        when (event) {
            is PhotoMealEvent.AddPhotos -> draft.update { it.copy(photos = (it.photos + event.uris).take(PhotoMealUiState.MAX_PHOTOS)) }
            is PhotoMealEvent.RemovePhoto -> draft.update { s -> s.copy(photos = s.photos.filterIndexed { i, _ -> i != event.index }) }
            PhotoMealEvent.Next -> draft.update { if (it.photos.isNotEmpty()) it.copy(step = PhotoStep.DETAILS) else it }
            PhotoMealEvent.BackToCamera -> draft.update { it.copy(step = PhotoStep.CAPTURE) }
            is PhotoMealEvent.SetDescription -> draft.update { it.copy(description = event.text.take(500)) }
            is PhotoMealEvent.SetMeal -> draft.update { it.copy(mealSlotId = event.id) }
            PhotoMealEvent.NotificationPermissionAsked -> viewModelScope.launch {
                settings.updateNotifications { it.copy(permissionAsked = true) }
            }
            PhotoMealEvent.Estimate -> estimate()
        }
    }

    private fun estimate() {
        val s = state.value
        if (!s.canEstimate) return
        draft.update { it.copy(submitting = true) }
        viewModelScope.launch {
            estimates.create(date, s.mealSlotId, s.photos, s.description)
            // Camera shots were copied (downscaled, EXIF-free) into app storage; drop the originals.
            withContext(Dispatchers.IO) { s.photos.filter { it.scheme == "file" }.forEach { uri -> uri.path?.let { java.io.File(it).delete() } } }
            val meal = s.slots.firstOrNull { it.id == s.mealSlotId }?.name.orEmpty()
            effects.send(PhotoMealEffect.Queued(meal))
        }
    }
}
