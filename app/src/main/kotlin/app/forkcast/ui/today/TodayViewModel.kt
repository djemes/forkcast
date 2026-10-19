package app.forkcast.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.db.LoggedDay
import app.forkcast.core.data.health.HcAvailability
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SavedMealRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WaterRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.AdaptiveTdee
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DayMath
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.DrinkWater
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.domain.Forecast
import app.forkcast.core.domain.MealSlotPicker
import app.forkcast.core.domain.NutritionConstants
import app.forkcast.core.domain.UnitConversions
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.domain.WeightTrend
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.model.Food
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WaterEntry
import app.forkcast.core.model.WaterUnit
import app.forkcast.core.model.WeightEntry
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
import kotlinx.coroutines.flow.flowOf
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

data class MealSection(
    val slot: MealSlot,
    val entries: List<LogEntry>,
    val kcal: Double,
    /** Photo estimates in this meal that aren't logged yet (running, waiting, question, failed, or awaiting review). */
    val pending: List<PendingEstimate> = emptyList(),
)

/** A photo estimate shown as its own row until it becomes a log entry (SPEC §4.6 step 4). */
data class PendingEstimate(
    val id: String,
    val status: EstimateStatus,
    val kcalExpected: Double?,
    /** Waiting for quota until then (epoch ms). */
    val retryAfter: Long?,
    val errorCode: String?,
)

data class WaterSummary(
    val ml: Double = 0.0,
    val targetMl: Double = NutritionConstants.WATER_TARGET_MALE_ML,
    /** One tap on "+ glass": 8 fl oz for US units, 250 ml for metric. */
    val glassMl: Double = 250.0,
    val lastEntryId: String? = null,
    /** Part of [ml] that came from logged drinks. */
    val fromDrinksMl: Double = 0.0,
)

/** Latest weigh-in and the trend's weekly change (negative = losing). */
data class WeightSummary(val latestKg: Double, val trendKg: Double, val weeklyChangeKg: Double?)

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
    /** [recents] are favorites (nothing logged yet). */
    val recentsAreFavorites: Boolean = false,
    val savedMeals: List<SavedMeal> = emptyList(),
    /** Meals that had entries yesterday: these get a "Same as yesterday" button when empty. */
    val yesterdayMealIds: Set<String> = emptySet(),
    val water: WaterSummary = WaterSummary(),
    val weight: WeightSummary? = null,
    /** Estimates logged by auto-accept and not reviewed yet: their entries show "AI·review". */
    val unreviewedEstimateIds: Set<String> = emptySet(),
    /** Today only, after 7+ logged days (SPEC §4.2). */
    val forecast: Forecast.Outlook? = null,
    /** Minute of day the forecast counts from ("what you usually eat after 4 pm"). */
    val forecastFromMinute: Int = 0,
    /** Most recent other day with entries, shown on an empty day so a log is never "lost". */
    val lastLogged: LoggedDay? = null,
    /** Health Connect activity for the day (Phase 3). */
    val activity: ActivitySummary = ActivitySummary(false, false, null),
    /** The user's mark for the day, for the maintenance estimate (SPEC §5.6). */
    val completeness: DayCompleteness = DayCompleteness.AUTO,
    /** The day meets the automatic rule (2 meals, 1,000 kcal). */
    val autoComplete: Boolean = false,
    /** Keys lost in a restore or a move to a new phone (backups never carry keys). */
    val missingKeys: List<app.forkcast.core.data.repository.MissingKey> = emptyList(),
) {
    val isToday: Boolean get() = date == today
    val isSelecting: Boolean get() = selection.isNotEmpty()
    val allEntries: List<LogEntry> get() = meals.flatMap { it.entries }
    /** Nothing logged and nothing estimating on the day on screen. */
    val isEmptyDay: Boolean get() = meals.all { it.entries.isEmpty() && it.pending.isEmpty() }
}

sealed interface TodayEvent {
    data object PreviousDay : TodayEvent
    data object NextDay : TodayEvent
    data object GoToToday : TodayEvent
    /** The screen came back to the foreground: if it was showing today, follow the clock past midnight. */
    data object Resumed : TodayEvent
    data class PickDate(val date: LocalDate) : TodayEvent
    data class Delete(val ids: List<String>) : TodayEvent
    data class Undo(val ids: List<String>) : TodayEvent
    data class ToggleSelect(val id: String) : TodayEvent
    data object ClearSelection : TodayEvent
    data object DeleteSelected : TodayEvent
    data class UpdateEntry(
        val entry: LogEntry,
        val quantity: Double,
        val mealSlotId: String,
        val countsAsWater: Boolean = entry.waterMl != null,
        /** The serving chosen in the edit sheet; null keeps the entry's. */
        val serving: ServingSize? = null,
    ) : TodayEvent
    /** The edit sheet opened: load the food's servings for it. */
    data class LoadEditServings(val entryId: String) : TodayEvent
    data class QuickAdd(val mealSlotId: String, val label: String, val kcal: Double, val protein: Double?, val carbs: Double?, val fat: Double?) : TodayEvent
    data class AddRecent(val food: Food, val mealSlotId: String) : TodayEvent
    /** Copies entries to [toDate], into [toMealSlotId] or (null) each entry's own meal. */
    data class Copy(val ids: List<String>, val toDate: LocalDate, val toMealSlotId: String?) : TodayEvent
    data class SaveAsMeal(val ids: List<String>, val name: String) : TodayEvent
    data class SameAsYesterday(val mealSlotId: String) : TodayEvent
    data class LogSavedMeal(val savedMealId: String, val mealSlotId: String) : TodayEvent
    data class DeleteSavedMeal(val savedMealId: String) : TodayEvent
    data class AddWater(val ml: Double) : TodayEvent
    data object RemoveLastWater : TodayEvent
    data class SetCompleteness(val completeness: DayCompleteness) : TodayEvent
    /** "Not now" on the missing-keys card. */
    data object DismissMissingKeys : TodayEvent
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
    private val foods: FoodRepository,
    private val savedMealRepository: SavedMealRepository,
    private val waterRepository: WaterRepository,
    weightRepository: WeightRepository,
    settingsRepository: SettingsRepository,
    estimateRepository: AiEstimateRepository,
    health: HealthRepository,
    private val maintenance: MaintenanceRepository,
    private val missingKeys: app.forkcast.core.data.repository.MissingKeysRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    /** True while the screen shows today, so it moves to the new day after midnight (see [TodayEvent.Resumed]). */
    private var followingToday = true
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    /** Servings for the entry open in the edit sheet (entry id → servings). */
    private val editServings = MutableStateFlow<Pair<String, List<ServingSize>>?>(null)
    val editServingsFor: StateFlow<Pair<String, List<ServingSize>>?> = editServings
    private val effectChannel = Channel<TodayEffect>(Channel.BUFFERED)
    val effects: Flow<TodayEffect> = effectChannel.receiveAsFlow()

    /**
     * Everything that belongs to one date, built together so a new date never shows with the
     * previous date's entries for a frame (the date and its data always arrive as one value).
     */
    private data class DayData(
        val date: LocalDate,
        val entries: List<LogEntry>,
        val yesterdayMeals: Set<String>,
        val water: List<WaterEntry>,
        val estimates: List<AiEstimateEntity>,
        val history: List<LogEntry>,
        val lastLogged: LoggedDay?,
        val activity: ActivityDay?,
        val bonus: ActivityBonus,
        val completeness: DayCompleteness,
    ) {
        val exerciseBonus: Double get() = bonus.bonusKcal
    }

    private data class DayHealth(val activity: ActivityDay?, val bonus: ActivityBonus, val completeness: DayCompleteness)

    private val day: Flow<DayData> = selectedDate.flatMapLatest { d ->
        combine(
            logRepository.observeDay(d),
            logRepository.observeDay(d.minusDays(1)).map { list -> list.map { it.mealSlotId }.toSet() },
            waterRepository.observeDay(d),
            estimateRepository.observeDay(d),
            // Earlier days for the forecast (today only); 60 calendar days comfortably hold the last
            // 14 logged days. Other days skip the query: it re-reads every entry on each change.
            combine(
                if (d == LocalDate.now(clock)) logRepository.observeRange(d.minusDays(60), d.minusDays(1)) else flowOf(emptyList()),
                logRepository.observeLatestDay(d),
                combine(health.observeActivity(d), maintenance.observeActivityBonus(d), maintenance.observeCompleteness(d), ::DayHealth),
            ) { h, l, dh -> Triple(h, l, dh) },
        ) { entries, yesterday, water, estimates, (history, last, dh) ->
            DayData(d, entries, yesterday, water, estimates, history, last, dh.activity, dh.bonus, dh.completeness)
        }
    }

    private val extras = combine(
        profileRepository.observeDailyTargets(),
        settingsRepository.settings,
        // One-tap foods: recents, or favorites before anything has been logged.
        combine(foods.observeRecents(5), foods.observeFavorites()) { r, fav -> if (r.isNotEmpty()) r to false else fav.take(5) to true },
        savedMealRepository.meals,
        missingKeys.missing,
    ) { t, s, (r, favorites), saved, missing -> Extras(t, s, r, saved, favorites, missing) }

    private val body = combine(weightRepository.entries, profileRepository.profile) { weights, profile -> weights to profile }

    /** Connected with activity reads on, and whether Health Connect exists here at all. */
    private val healthState = health.status.map { s ->
        (s.connected && s.settings.readActivity) to (s.availability != HcAvailability.UNAVAILABLE)
    }

    private data class Extras(
        val targets: DailyTargets?,
        val settings: AppSettings,
        val recents: List<Food>,
        val saved: List<SavedMeal>,
        val recentsAreFavorites: Boolean = false,
        val missingKeys: List<app.forkcast.core.data.repository.MissingKey> = emptyList(),
    )

    val uiState: StateFlow<TodayUiState?> = combine(
        mealSlotRepository.observeMealSlots(),
        day,
        extras,
        combine(body, healthState) { b, h -> b to h },
        selection,
    ) { slots, dayData, x, (b, healthFlags), selected ->
        val (weights, profile) = b
        val date = dayData.date
        val entries = dayData.entries
        val estimates = dayData.estimates
        val water = dayData.water
        val loggedIds = entries.map { it.id }.toSet()
        // Once logged, an estimate is its entry (deleting the entry hides it; Undo brings it back).
        val pending = estimates
            .filter { it.logEntryId == null }
            .map { PendingEstimate(it.id, it.status, it.kcalExpected, it.retryAfter, it.errorCode) to it.mealSlotId }
        val targets = x.targets
        val units = x.settings.units ?: UnitFormatter.defaultsFor(Locale.getDefault())
        val now = LocalTime.now(clock)
        val nowMinute = now.hour * 60 + now.minute
        val isToday = date == LocalDate.now(clock)
        val forecast = if (isToday && targets != null) {
            Forecast.expectedAfter(dayData.history.map { it.toLogged() }, date, nowMinute)?.let { expected ->
                val t = DayMath.totals(entries, targets.budgetKcal, dayData.exerciseBonus)
                Forecast.outlook(t.eatenKcal, t.allowanceKcal, expected)
            }
        } else {
            null
        }
        TodayUiState(
            date = date,
            today = LocalDate.now(clock),
            meals = slots.map { slot ->
                val list = entries.filter { it.mealSlotId == slot.id }
                MealSection(slot, list, DayMath.mealKcal(entries, slot.id), pending.filter { it.second == slot.id }.map { it.first })
            } + orphans(slots, entries),
            defaultMealId = MealSlotPicker.defaultFor(slots, now.hour * 60 + now.minute)?.id,
            units = units,
            targets = targets,
            totals = targets?.let { DayMath.totals(entries, it.budgetKcal, dayData.exerciseBonus) },
            selection = selected intersect entries.map { it.id }.toSet(),
            recents = x.recents,
            recentsAreFavorites = x.recentsAreFavorites,
            savedMeals = x.saved,
            yesterdayMealIds = dayData.yesterdayMeals,
            water = WaterSummary(
                // Drinks that count toward water add to it (SPEC §4.9).
                ml = water.sumOf { it.ml } + entries.sumOf { it.waterMl ?: 0.0 },
                fromDrinksMl = entries.sumOf { it.waterMl ?: 0.0 },
                targetMl = x.settings.waterTargetMl
                    ?: if (profile?.sex == Sex.FEMALE) NutritionConstants.WATER_TARGET_FEMALE_ML else NutritionConstants.WATER_TARGET_MALE_ML,
                glassMl = if (units.water == WaterUnit.FL_OZ) UnitConversions.flOzToMl(8.0) else 250.0,
                lastEntryId = water.maxByOrNull { it.timestamp }?.id,
            ),
            weight = weightSummary(weights),
            unreviewedEstimateIds = estimates.filter { it.logEntryId in loggedIds && it.reviewedAt == null }.map { it.id }.toSet(),
            forecast = forecast,
            forecastFromMinute = nowMinute,
            lastLogged = dayData.lastLogged,
            activity = ActivitySummary(
                connected = healthFlags.first,
                available = healthFlags.second,
                day = dayData.activity,
                bonus = dayData.bonus,
            ),
            completeness = dayData.completeness,
            missingKeys = x.missingKeys,
            autoComplete = AdaptiveTdee.isComplete(
                AdaptiveTdee.DayLog(date, entries.sumOf { it.nutrients.kcal }, entries.map { it.mealSlotId }.distinct().size),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun LogEntry.toLogged(): Forecast.Logged {
        val t = loggedAt.atZone(clock.zone).toLocalTime()
        return Forecast.Logged(date, t.hour * 60 + t.minute, nutrients.kcal)
    }

    private fun weightSummary(weights: List<WeightEntry>): WeightSummary? {
        val latest = weights.maxByOrNull { it.timestamp } ?: return null
        val points = WeightTrend.daily(weights.map { WeighIn(it.timestamp, it.kg) }, clock.zone, LocalDate.now(clock))
        return WeightSummary(latest.kg, points.last().trendKg, WeightTrend.weeklyRateKg(points))
    }

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
            TodayEvent.Resumed -> if (followingToday && selectedDate.value != LocalDate.now(clock)) changeDate { LocalDate.now(clock) }
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
            is TodayEvent.AddWater -> viewModelScope.launch { waterRepository.add(selectedDate.value, event.ml) }
            is TodayEvent.SetCompleteness -> viewModelScope.launch { maintenance.setCompleteness(selectedDate.value, event.completeness) }
            TodayEvent.DismissMissingKeys -> viewModelScope.launch {
                missingKeys.dismiss(uiState.value?.missingKeys.orEmpty().map { it.id })
            }
            TodayEvent.RemoveLastWater -> viewModelScope.launch {
                uiState.value?.water?.lastEntryId?.let { waterRepository.delete(it) }
            }
            is TodayEvent.UpdateEntry -> viewModelScope.launch {
                val e = event.entry
                val serving = event.serving?.takeIf { e.grams != null }
                val grams = if (serving != null) serving.grams * event.quantity else e.grams?.let { it * event.quantity / e.quantity.coerceAtLeast(1e-9) }
                logRepository.update(
                    e.copy(
                        quantity = event.quantity,
                        servingLabel = serving?.label ?: e.servingLabel,
                        grams = grams,
                        nutrients = DayMath.rescale(e, event.quantity, grams),
                        mealSlotId = event.mealSlotId,
                        waterMl = DrinkWater.waterMl(event.countsAsWater, grams),
                    ),
                )
            }
            is TodayEvent.LoadEditServings -> viewModelScope.launch {
                val entry = uiState.value?.allEntries?.firstOrNull { it.id == event.entryId } ?: return@launch
                val food = entry.foodId?.let { foods.get(it) }
                editServings.value = entry.id to FoodLogging.editServings(entry, food)
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
        // Never past today: nothing can be logged for a future day.
        val today = LocalDate.now(clock)
        selectedDate.update { minOf(change(it), today) }
        followingToday = selectedDate.value == LocalDate.now(clock)
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
