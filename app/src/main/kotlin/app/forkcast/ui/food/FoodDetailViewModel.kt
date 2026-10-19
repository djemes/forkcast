package app.forkcast.ui.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.DrinkWater
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.Food
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.forGrams
import app.forkcast.navigation.FoodDetailDestination
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class FoodDetailState(
    val food: Food,
    val servings: List<ServingSize>,
    val serving: ServingSize,
    val quantity: Double?,
    val meals: List<MealSlot>,
    val mealSlotId: String,
    val units: UnitPrefs,
    /** Day the entry goes to, relative to today (0 = today, -1 = yesterday). */
    val daysFromToday: Long = 0,
    val date: LocalDate? = null,
    /** Picking an ingredient for a recipe: no meal, the button says "Add to recipe". */
    val forRecipe: Boolean = false,
) {
    val grams: Double? get() = quantity?.let { it * serving.grams }
    val preview: Nutrients get() = food.nutrientsPer100g.forGrams(grams ?: 0.0)
    val canAdd: Boolean get() = (quantity ?: 0.0) > 0
    val mealName: String get() = meals.firstOrNull { it.id == mealSlotId }?.name.orEmpty()
    /** The user's choice, or the drink rule (SPEC §4.9). */
    val countsAsWater: Boolean get() = DrinkWater.countsAsWater(food)
    val isDrink: Boolean get() = DrinkWater.isDrink(food)
}

private data class Selection(val servingLabel: String? = null, val quantity: Double? = null, val quantityTouched: Boolean = false, val mealSlotId: String? = null)

sealed interface FoodDetailEffect {
    data class Added(val mealName: String) : FoodDetailEffect
    data class Picked(val pick: app.forkcast.ui.recipes.RecipePick) : FoodDetailEffect
}

@HiltViewModel
class FoodDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
    private val log: LogRepository,
    mealSlots: MealSlotRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val args: FoodDetailDestination = savedState.toRoute()
    private val logDate: LocalDate? = runCatching { LocalDate.parse(args.date) }.getOrNull()
    private val selection = MutableStateFlow(Selection(mealSlotId = args.mealSlotId))
    private val effectChannel = Channel<FoodDetailEffect>(Channel.BUFFERED)
    val effects: Flow<FoodDetailEffect> = effectChannel.receiveAsFlow()

    /** Null while loading; [missing] if the food was deleted. */
    val state: StateFlow<FoodDetailState?> = combine(
        foods.observe(args.foodId), selection, mealSlots.observeMealSlots(), settings.settings,
    ) { food, sel, meals, s ->
        food ?: return@combine null
        val options = FoodLogging.servingOptions(food)
        val (defaultServing, defaultQty) = FoodLogging.defaultServing(food)
        val serving = options.firstOrNull { it.label == sel.servingLabel } ?: defaultServing
        FoodDetailState(
            food = food,
            servings = options,
            serving = serving,
            quantity = if (sel.quantityTouched) sel.quantity else defaultQty,
            meals = meals,
            mealSlotId = sel.mealSlotId ?: meals.firstOrNull()?.id.orEmpty(),
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            daysFromToday = logDate?.let { ChronoUnit.DAYS.between(LocalDate.now(clock), it) } ?: 0,
            date = logDate,
            forRecipe = args.forRecipe,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Better data (portions, clean nutrients) when online; harmless offline.
        viewModelScope.launch { foods.refreshDetails(args.foodId) }
    }

    fun selectServing(serving: ServingSize) {
        val current = state.value ?: return
        // Keep the same amount of food when switching units (e.g. 1 cup → 158 g).
        val grams = current.grams
        val newQty = when {
            grams == null -> null
            serving.label == FoodLogging.GRAM_LABEL -> Math.round(grams).toDouble()
            current.serving.label == FoodLogging.GRAM_LABEL || current.serving.label == FoodLogging.OUNCE_LABEL ->
                (grams / serving.grams * 4).let { Math.round(it) / 4.0 }.coerceAtLeast(0.25)
            else -> 1.0
        }
        selection.update { it.copy(servingLabel = serving.label, quantity = newQty, quantityTouched = true) }
    }

    fun setQuantity(q: Double?) = selection.update { it.copy(quantity = q, quantityTouched = true) }

    fun step(delta: Int) {
        val s = state.value ?: return
        val stepSize = when (s.serving.label) {
            FoodLogging.GRAM_LABEL, FoodLogging.ML_LABEL -> 10.0
            FoodLogging.FL_OZ_LABEL -> 1.0
            else -> 0.25
        }
        val next = ((s.quantity ?: 0.0) + delta * stepSize).coerceAtLeast(stepSize)
        setQuantity(next)
    }

    fun selectMeal(id: String) = selection.update { it.copy(mealSlotId = id) }

    fun setCountsAsWater(counts: Boolean) {
        val f = state.value?.food ?: return
        viewModelScope.launch { foods.setCountsAsWater(f.id, counts) }
    }

    fun toggleFavorite() {
        val f = state.value?.food ?: return
        viewModelScope.launch { foods.setFavorite(f.id, !f.isFavorite) }
    }

    fun add() {
        val s = state.value ?: return
        val q = s.quantity ?: return
        if (args.forRecipe) {
            viewModelScope.launch { effectChannel.send(FoodDetailEffect.Picked(app.forkcast.ui.recipes.RecipePick(s.food.id, s.serving.label, q))) }
            return
        }
        viewModelScope.launch {
            log.add(listOf(FoodLogging.entry(s.food, s.serving, q, LocalDate.parse(args.date), s.mealSlotId, clock.instant())))
            effectChannel.send(FoodDetailEffect.Added(s.mealName))
        }
    }
}
