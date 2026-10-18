package app.forkcast.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SavedMealRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DayMath
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.domain.MealSlotPicker
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.Food
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.kcal
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

data class MealSection(val slot: MealSlot, val entries: List<LogEntry>, val kcal: Double)

data class TodayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val meals: List<MealSection>,
    /** Meal pre-selected in the Log sheet, by current time of day. */
    val defaultMealId: String?,
    val units: UnitPrefs = UnitPrefs.US,
    /** Null until the profile exists (it always does after onboarding). */
    val targets: DailyTargets? = null,
    val totals: DayTotals? = null,
    val selection: Set<String> = emptySet(),
    /** For one-tap add in the Log sheet. */
    val recents: List<Food> = emptyList(),
    val savedMeals: List<SavedMeal> = emptyList(),
    /** Meals that had entries yesterday: these get a "Same as yesterday" button when empty. */
    val yesterdayMealIds: Set<String> = emptySet(),
) {
    val isToday: Boolean get() = date == today
    val isSelecting: Boolean get() = selection.isNotEmpty()
    val allEntries: List<LogEntry> get() = meals.flatMap { it.entries }
}

sealed interface TodayEvent {
    data object PreviousDay : TodayEvent
    data object NextDay : TodayEvent
    data object GoToToday : TodayEvent
    data class PickDate(val date: LocalDate) : TodayEvent
    data class Delete(val ids: List<String>) : TodayEvent
    data class Undo(val ids: List<String>) : TodayEvent
    data class ToggleSelect(val id: String) : TodayEvent
    data object ClearSelection : TodayEvent
    data object DeleteSelected : TodayEvent
    data class UpdateEntry(val entry: LogEntry, val quantity: Double, val mealSlotId: String) : TodayEvent
    data class QuickAdd(val mealSlotId: String, val label: String, val kcal: Double, val protein: Double?, val carbs: Double?, val fat: Double?) : TodayEvent
    data class AddRecent(val food: Food, val mealSlotId: String) : TodayEvent
    /** Copies entries to [toDate], into [toMealSlotId] or (null) each entry's own meal. */
    data class Copy(val ids: List<String>, val toDate: LocalDate, val toMealSlotId: String?) : TodayEvent
    data class SaveAsMeal(val ids: List<String>, val name: String) : TodayEvent
    data class SameAsYesterday(val mealSlotId: String) : TodayEvent
    data class LogSavedMeal(val savedMealId: String, val mealSlotId: String) : TodayEvent
    data class DeleteSavedMeal(val savedMealId: String) : TodayEvent
}

/** One-shot UI effects. */
sealed interface TodayEffect {
    data class Deleted(val ids: List<String>, val label: String?) : TodayEffect
    data class Added(val mealName: String) : TodayEffect
    data class Copied(val count: Int, val toDate: LocalDate) : TodayEffect
    data class SavedMealCreated(val name: String) : TodayEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    mealSlotRepository: MealSlotRepository,
    private val logRepository: LogRepository,
    profileRepository: ProfileRepository,
    foodRepository: FoodRepository,
    private val savedMealRepository: SavedMealRepository,
    settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val effectChannel = Channel<TodayEffect>(Channel.BUFFERED)
    val effects: Flow<TodayEffect> = effectChannel.receiveAsFlow()

    private val dayEntries = selectedDate.flatMapLatest { logRepository.observeDay(it) }
    private val yesterdayMeals = selectedDate.flatMapLatest { d ->
        logRepository.observeDay(d.minusDays(1)).map { list -> list.map { it.mealSlotId }.toSet() }
    }
    private val extras = combine(
        profileRepository.observeDailyTargets(),
        settingsRepository.settings,
        foodRepository.observeRecents(5),
        savedMealRepository.meals,
        yesterdayMeals,
    ) { t, s, r, saved, yesterday -> Extras(t, s, r, saved, yesterday) }

    private data class Extras(
        val targets: DailyTargets?,
        val settings: AppSettings,
        val recents: List<Food>,
        val saved: List<SavedMeal>,
        val yesterday: Set<String>,
    )

    val uiState: StateFlow<TodayUiState?> = combine(
        mealSlotRepository.observeMealSlots(),
        selectedDate,
        dayEntries,
        extras,
        selection,
    ) { slots, date, entries, x, selected ->
        val targets = x.targets
        val now = LocalTime.now(clock)
        TodayUiState(
            date = date,
            today = LocalDate.now(clock),
            meals = slots.map { slot ->
                val list = entries.filter { it.mealSlotId == slot.id }
                MealSection(slot, list, DayMath.mealKcal(entries, slot.id))
            } + orphans(slots, entries),
            defaultMealId = MealSlotPicker.defaultFor(slots, now.hour * 60 + now.minute)?.id,
            units = x.settings.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            targets = targets,
            totals = targets?.let { DayMath.totals(entries, it.budgetKcal) },
            selection = selected intersect entries.map { it.id }.toSet(),
            recents = x.recents,
            savedMeals = x.saved,
            yesterdayMealIds = x.yesterday,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Entries whose meal slot was deleted still show (under their own section) so nothing is hidden. */
    private fun orphans(slots: List<MealSlot>, entries: List<LogEntry>): List<MealSection> {
        val known = slots.map { it.id }.toSet()
        return entries.filter { it.mealSlotId !in known }.groupBy { it.mealSlotId }.map { (id, list) ->
            MealSection(MealSlot(id, "Other", Int.MAX_VALUE, null, null), list, list.sumOf { it.nutrients.kcal })
        }
    }

    fun onEvent(event: TodayEvent) {
        when (event) {
            TodayEvent.PreviousDay -> changeDate { it.minusDays(1) }
            TodayEvent.NextDay -> changeDate { it.plusDays(1) }
            TodayEvent.GoToToday -> changeDate { LocalDate.now(clock) }
            is TodayEvent.PickDate -> changeDate { event.date }
            is TodayEvent.Delete -> delete(event.ids)
            is TodayEvent.Undo -> viewModelScope.launch { logRepository.restore(event.ids) }
            is TodayEvent.ToggleSelect -> selection.update { if (event.id in it) it - event.id else it + event.id }
            TodayEvent.ClearSelection -> selection.value = emptySet()
            TodayEvent.DeleteSelected -> {
                val ids = selection.value.toList()
                selection.value = emptySet()
                delete(ids)
            }
            is TodayEvent.QuickAdd -> addEntry(event.mealSlotId) { date ->
                FoodLogging.quickAdd(event.label, event.kcal, event.protein, event.carbs, event.fat, date, event.mealSlotId, clock.instant())
            }
            is TodayEvent.AddRecent -> addEntry(event.mealSlotId) { date ->
                val (serving, qty) = FoodLogging.defaultServing(event.food)
                FoodLogging.entry(event.food, serving, qty, date, event.mealSlotId, clock.instant())
            }
            is TodayEvent.Copy -> viewModelScope.launch {
                selection.value = emptySet()
                val ids = logRepository.copy(event.ids, event.toDate, event.toMealSlotId)
                effectChannel.send(TodayEffect.Copied(ids.size, event.toDate))
            }
            is TodayEvent.SaveAsMeal -> viewModelScope.launch {
                selection.value = emptySet()
                val entries = uiState.value?.allEntries?.filter { it.id in event.ids }.orEmpty()
                if (entries.isEmpty() || event.name.isBlank()) return@launch
                savedMealRepository.saveFrom(event.name, entries)
                effectChannel.send(TodayEffect.SavedMealCreated(event.name.trim()))
            }
            is TodayEvent.SameAsYesterday -> viewModelScope.launch {
                val date = selectedDate.value
                val yesterday = logRepository.entriesForMeal(date.minusDays(1), event.mealSlotId)
                if (yesterday.isEmpty()) return@launch
                logRepository.copy(yesterday.map { it.id }, date, event.mealSlotId)
            }
            is TodayEvent.LogSavedMeal -> viewModelScope.launch {
                val date = selectedDate.value
                val entries = savedMealRepository.toEntries(event.savedMealId, date, event.mealSlotId)
                if (entries.isEmpty()) return@launch
                logRepository.add(entries)
                val mealName = uiState.value?.meals?.firstOrNull { it.slot.id == event.mealSlotId }?.slot?.name.orEmpty()
                effectChannel.send(TodayEffect.Added(mealName))
            }
            is TodayEvent.DeleteSavedMeal -> viewModelScope.launch { savedMealRepository.delete(event.savedMealId) }
            is TodayEvent.UpdateEntry -> viewModelScope.launch {
                val e = event.entry
                val grams = e.grams?.let { it * event.quantity / e.quantity.coerceAtLeast(1e-9) }
                logRepository.update(
                    e.copy(
                        quantity = event.quantity,
                        grams = grams,
                        nutrients = DayMath.rescale(e, event.quantity, grams),
                        mealSlotId = event.mealSlotId,
                    ),
                )
            }
        }
    }

    /** Logs to the day on screen (so you can fill in yesterday), then confirms "Added to Lunch". */
    private fun addEntry(mealSlotId: String, build: (LocalDate) -> LogEntry) {
        val date = selectedDate.value
        val mealName = uiState.value?.meals?.firstOrNull { it.slot.id == mealSlotId }?.slot?.name.orEmpty()
        viewModelScope.launch {
            logRepository.add(listOf(build(date)))
            effectChannel.send(TodayEffect.Added(mealName))
        }
    }

    private fun changeDate(change: (LocalDate) -> LocalDate) {
        selection.value = emptySet()
        selectedDate.update(change)
    }

    private fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        val label = uiState.value?.allEntries?.singleOrNull { it.id == ids.singleOrNull() }?.displayName
        viewModelScope.launch {
            logRepository.delete(ids)
            effectChannel.send(TodayEffect.Deleted(ids, label))
        }
    }
}
