package app.forkcast.ui.photo

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.MealTimes
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.MealSlot
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
    val notificationPermissionAsked: Boolean = true,
    val submitting: Boolean = false,
    /** A photo couldn't be read (not an image, or too large); cleared when the photos change. */
    val photoUnreadable: Boolean = false,
    /** "Describe" (SPEC §4.6.2): no photo, the description is required. */
    val describe: Boolean = false,
    /** When it was eaten, minutes after midnight; null until the user picks one (then the default below). */
    val eatenMinute: Int? = null,
    /** Now on today, else the selected meal's typical time. */
    val defaultMinute: Int = 12 * 60,
    /** Now, on today: no later time can be picked. */
    val latestMinute: Int? = null,
    /**
     * No enabled AI service may receive meals yet: the first one in the order, whose permission is
     * asked before the camera opens (roadmap 2.0 task 4.2).
     */
    val askPermission: AiProfile? = null,
) {
    val canAddPhoto: Boolean get() = photos.size < MAX_PHOTOS
    /** An AI can take this estimate; otherwise the screen explains AI instead (roadmap 2.0 task 3.1). */
    val aiReady: Boolean get() = aiEnabled && hasProfile
    val canEstimate: Boolean
        get() = (if (describe) description.isNotBlank() else photos.isNotEmpty()) && aiReady && mealSlotId.isNotBlank() && !submitting
    val minute: Int get() = eatenMinute ?: defaultMinute

    companion object {
        const val MAX_PHOTOS = 3
        const val MAX_DESCRIPTION = 500
    }
}

sealed interface PhotoMealEvent {
    data class AddPhotos(val uris: List<Uri>) : PhotoMealEvent
    data class RemovePhoto(val index: Int) : PhotoMealEvent
    data object Next : PhotoMealEvent
    data object BackToCamera : PhotoMealEvent
    data class SetDescription(val text: String) : PhotoMealEvent
    data class SetMeal(val id: String) : PhotoMealEvent
    data class SetTime(val minute: Int) : PhotoMealEvent
    data object Estimate : PhotoMealEvent
    data object NotificationPermissionAsked : PhotoMealEvent
    data object TurnOnAi : PhotoMealEvent
    /** "Allow" on the permission page, for [PhotoMealUiState.askPermission]'s recipient. */
    data object AllowAi : PhotoMealEvent
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
    private val consent: ConsentRepository,
) : ViewModel() {

    private val date: LocalDate = savedState.get<String>("date")?.let(LocalDate::parse) ?: LocalDate.now(clock)
    private val describe: Boolean = savedState.get<Boolean>("describe") == true
    private val draft = MutableStateFlow(
        PhotoMealUiState(
            mealSlotId = savedState.get<String>("mealSlotId").orEmpty(),
            describe = describe,
            step = if (describe) PhotoStep.DETAILS else PhotoStep.CAPTURE,
            description = savedState.get<String>("text").orEmpty().take(PhotoMealUiState.MAX_DESCRIPTION),
        ),
    )
    private val effects = Channel<PhotoMealEffect>(Channel.BUFFERED)
    val effect: Flow<PhotoMealEffect> = effects.receiveAsFlow()

    val state: StateFlow<PhotoMealUiState> = combine(draft, slots.observeMealSlots(), ai.profiles, settings.settings, consent.aiConsents) { d, s, profiles, app, consents ->
        val enabled = profiles.map { it.profile }.filter { it.enabled }.sortedBy { it.order }
        val mealId = d.mealSlotId.ifBlank { s.firstOrNull()?.id.orEmpty() }
        val slot = s.firstOrNull { it.id == mealId }
        val nowMinute = MealTimes.minuteOn(date, clock.instant(), clock.zone)
        d.copy(
            loaded = true,
            slots = s,
            mealSlotId = mealId,
            defaultMinute = nowMinute ?: MealTimes.typicalMinute(slot?.startMinute, slot?.endMinute, date, clock.instant(), clock.zone),
            latestMinute = nowMinute,
            aiEnabled = app.ai.enabled,
            hasProfile = enabled.isNotEmpty(),
            askPermission = enabled.firstOrNull().takeIf { enabled.none { AiRecipient.of(it) in consents } },
            notificationPermissionAsked = app.notifications.permissionAsked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), draft.value)

    fun onEvent(event: PhotoMealEvent) {
        when (event) {
            is PhotoMealEvent.AddPhotos -> draft.update { it.copy(photos = (it.photos + event.uris).take(PhotoMealUiState.MAX_PHOTOS), photoUnreadable = false) }
            is PhotoMealEvent.RemovePhoto -> draft.update { s -> s.copy(photos = s.photos.filterIndexed { i, _ -> i != event.index }, photoUnreadable = false) }
            PhotoMealEvent.Next -> draft.update { if (it.photos.isNotEmpty()) it.copy(step = PhotoStep.DETAILS) else it }
            PhotoMealEvent.BackToCamera -> draft.update { it.copy(step = PhotoStep.CAPTURE) }
            is PhotoMealEvent.SetDescription -> draft.update { it.copy(description = event.text.take(PhotoMealUiState.MAX_DESCRIPTION)) }
            is PhotoMealEvent.SetMeal -> draft.update { it.copy(mealSlotId = event.id) }
            is PhotoMealEvent.SetTime -> draft.update { it.copy(eatenMinute = event.minute) }
            PhotoMealEvent.NotificationPermissionAsked -> viewModelScope.launch {
                settings.updateNotifications { it.copy(permissionAsked = true) }
            }
            PhotoMealEvent.TurnOnAi -> viewModelScope.launch { settings.updateAi { it.copy(enabled = true) } }
            PhotoMealEvent.Estimate -> estimate()
            PhotoMealEvent.AllowAi -> viewModelScope.launch { state.value.askPermission?.let { consent.allowAi(AiRecipient.of(it)) } }
        }
    }

    private fun estimate() {
        val s = state.value
        if (!s.canEstimate) return
        draft.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                if (s.describe) {
                    estimates.createDescribed(date, s.mealSlotId, s.description, s.minute)
                } else {
                    estimates.create(date, s.mealSlotId, s.photos, s.description, s.minute)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A picked file that isn't a readable image (or is far too large) is said, not a crash (REVIEW-2 S5).
                draft.update { it.copy(submitting = false, photoUnreadable = true) }
                return@launch
            }
            // Camera shots were copied (downscaled, EXIF-free) into app storage; drop the originals.
            withContext(Dispatchers.IO) { s.photos.filter { it.scheme == "file" }.forEach { uri -> uri.path?.let { java.io.File(it).delete() } } }
            val meal = s.slots.firstOrNull { it.id == s.mealSlotId }?.name.orEmpty()
            effects.send(PhotoMealEffect.Queued(meal))
        }
    }
}
