package app.forkcast.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.domain.MealSlotPicker
import app.forkcast.core.model.MealSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class TodayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val meals: List<MealSlot>,
    /** Meal pre-selected in the Log sheet, by current time of day. */
    val defaultMealId: String?,
) {
    val isToday: Boolean get() = date == today
}

sealed interface TodayEvent {
    data object PreviousDay : TodayEvent
    data object NextDay : TodayEvent
    data object GoToToday : TodayEvent
    data class PickDate(val date: LocalDate) : TodayEvent
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    mealSlotRepository: MealSlotRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))

    val uiState: StateFlow<TodayUiState?> =
        combine(mealSlotRepository.observeMealSlots(), selectedDate) { slots, date ->
            val now = LocalTime.now(clock)
            TodayUiState(
                date = date,
                today = LocalDate.now(clock),
                meals = slots,
                defaultMealId = MealSlotPicker.defaultFor(slots, now.hour * 60 + now.minute)?.id,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onEvent(event: TodayEvent) {
        when (event) {
            TodayEvent.PreviousDay -> selectedDate.update { it.minusDays(1) }
            TodayEvent.NextDay -> selectedDate.update { it.plusDays(1) }
            TodayEvent.GoToToday -> selectedDate.value = LocalDate.now(clock)
            is TodayEvent.PickDate -> selectedDate.value = event.date
        }
    }
}
