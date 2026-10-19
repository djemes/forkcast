package app.forkcast.ui.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.RecipeRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.domain.RecipeMath
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Recipe
import app.forkcast.core.model.RecipeIngredient
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.forGrams
import app.forkcast.navigation.RecipeEditDestination
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject

/** The recipe being edited; kept in SavedStateHandle so a trip to search (or process death) loses nothing. */
@Serializable
data class RecipeDraft(
    val id: String = "",
    val foodId: String? = null,
    val name: String = "",
    val servings: Double? = 4.0,
    val cookedWeightG: Double? = null,
    val ingredients: List<DraftIngredient> = emptyList(),
    /** Changed since it was opened (Back asks before discarding). */
    val dirty: Boolean = false,
)

@Serializable
data class DraftIngredient(
    val id: String,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double,
    val nutrients: Map<NutrientKey, Double>,
) {
    fun toModel() = RecipeIngredient(id, foodId, displayName, brand, quantity, servingLabel, grams, nutrients)
}

data class RecipeEditState(
    val draft: RecipeDraft,
    val isNew: Boolean,
    val units: UnitPrefs,
) {
    val result: RecipeMath.Result? get() =
        RecipeMath.compute(draft.ingredients.map { it.toModel() }, draft.servings ?: 0.0, draft.cookedWeightG)
    val canSave: Boolean get() = draft.name.isNotBlank() && draft.ingredients.isNotEmpty() && (draft.servings ?: 0.0) > 0
}

sealed interface RecipeEditEffect {
    data class Saved(val recipeId: String, val foodId: String, val name: String, val wasNew: Boolean) : RecipeEditEffect
    data object Deleted : RecipeEditEffect
    data class Added(val name: String) : RecipeEditEffect
    data object NotItself : RecipeEditEffect
}

@HiltViewModel
class RecipeEditViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val recipes: RecipeRepository,
    private val foods: FoodRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val args: RecipeEditDestination = savedState.toRoute()
    private val draft = MutableStateFlow<RecipeDraft?>(savedState.get<String>(DRAFT)?.let { runCatching { json.decodeFromString<RecipeDraft>(it) }.getOrNull() })
    private val effectChannel = Channel<RecipeEditEffect>(Channel.BUFFERED)
    val effects: Flow<RecipeEditEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<RecipeEditState?> = combine(draft, settings.settings) { d, s ->
        d?.let { RecipeEditState(it, it.id.isEmpty(), s.units ?: UnitFormatter.defaultsFor(Locale.getDefault())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (draft.value == null) {
            viewModelScope.launch {
                val recipe = args.recipeId?.let { recipes.get(it) } ?: args.foodId?.let { recipes.forFood(it) }
                set(recipe?.toDraft() ?: RecipeDraft(name = args.name.orEmpty()), dirty = false)
            }
        }
    }

    private fun set(d: RecipeDraft, dirty: Boolean = true) {
        val next = d.copy(dirty = d.dirty || dirty)
        draft.value = next
        savedState[DRAFT] = json.encodeToString(RecipeDraft.serializer(), next)
    }

    private fun edit(change: (RecipeDraft) -> RecipeDraft) = draft.value?.let { set(change(it)) }

    fun setName(name: String) = edit { it.copy(name = name) }
    fun setServings(servings: Double?) = edit { it.copy(servings = servings) }
    fun setCookedWeight(grams: Double?) = edit { it.copy(cookedWeightG = grams?.takeIf { g -> g > 0 }) }

    /** A food picked in search or the scanner ("Add to recipe"), [quantity] × the serving named [servingLabel]. */
    fun addPicked(pick: RecipePick) = viewModelScope.launch {
        val food = foods.get(pick.foodId) ?: return@launch
        val current = draft.value ?: return@launch
        if (food.source == FoodSource.RECIPE && food.id == current.foodId) {
            effectChannel.send(RecipeEditEffect.NotItself)
            return@launch
        }
        val serving = FoodLogging.servingOptions(food).firstOrNull { it.label == pick.servingLabel } ?: return@launch
        val grams = serving.grams * pick.quantity
        val ingredient = DraftIngredient(
            id = "",
            foodId = food.id,
            displayName = food.name,
            brand = food.brand,
            quantity = pick.quantity,
            servingLabel = serving.label,
            grams = grams,
            nutrients = food.nutrientsPer100g.forGrams(grams),
        )
        set(current.copy(ingredients = current.ingredients + ingredient))
        effectChannel.send(RecipeEditEffect.Added(food.name))
    }

    /** New amount for ingredient [index], in its own serving; nutrients scale with it. */
    fun setQuantity(index: Int, quantity: Double) = edit { d ->
        val list = d.ingredients.toMutableList()
        val old = list.getOrNull(index) ?: return@edit d
        if (quantity <= 0 || old.quantity <= 0) return@edit d
        val ratio = quantity / old.quantity
        list[index] = old.copy(quantity = quantity, grams = old.grams * ratio, nutrients = old.nutrients.mapValues { (_, v) -> v * ratio })
        d.copy(ingredients = list)
    }

    fun remove(index: Int) = edit { d -> d.copy(ingredients = d.ingredients.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val s = state.value ?: return
        if (!s.canSave) return
        val d = s.draft
        viewModelScope.launch {
            val id = recipes.save(
                Recipe(d.id, d.name, d.servings ?: return@launch, d.cookedWeightG, d.ingredients.map { it.toModel() }, d.foodId),
            )
            val saved = recipes.get(id) ?: return@launch
            set(saved.toDraft(), dirty = false)
            savedState[DRAFT] = null
            effectChannel.send(RecipeEditEffect.Saved(id, saved.foodId.orEmpty(), saved.name, wasNew = d.id.isEmpty()))
        }
    }

    fun delete() {
        val id = draft.value?.id?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            recipes.delete(id)
            effectChannel.send(RecipeEditEffect.Deleted)
        }
    }

    private fun Recipe.toDraft() = RecipeDraft(
        id = id,
        foodId = foodId,
        name = name,
        servings = servings,
        cookedWeightG = cookedWeightG,
        ingredients = ingredients.map { DraftIngredient(it.id, it.foodId, it.displayName, it.brand, it.quantity, it.servingLabel, it.grams, it.nutrients) },
    )

    private companion object {
        const val DRAFT = "draft"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What "Add to recipe" hands back to the editor. */
data class RecipePick(val foodId: String, val servingLabel: String, val quantity: Double) {
    fun encode(): String = listOf(foodId, servingLabel, quantity.toString()).joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"

        fun decode(text: String): RecipePick? {
            val parts = text.split(SEP)
            if (parts.size != 3) return null
            return RecipePick(parts[0], parts[1], parts[2].toDoubleOrNull() ?: return null)
        }
    }
}
