package app.forkcast.ui.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.forkcast.core.data.ai.PhotoStore
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SavedMealRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.DescribedMatch
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.Food
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.network.FoodApiError
import app.forkcast.navigation.SearchDestination
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** One remote source's state. Errors keep their kind so the UI can say something specific. */
sealed interface RemoteResults {
    data object Idle : RemoteResults
    data object Loading : RemoteResults
    data class Done(val foods: List<Food>) : RemoteResults
    data class Failed(val error: FoodApiError) : RemoteResults
}

data class SearchUiState(
    val query: String = "",
    val local: List<Food> = emptyList(),
    val usda: RemoteResults = RemoteResults.Idle,
    val off: RemoteResults = RemoteResults.Idle,
    val recents: List<Food> = emptyList(),
    val favorites: List<Food> = emptyList(),
    val custom: List<Food> = emptyList(),
    val usingDemoKey: Boolean = false,
    val units: UnitPrefs = UnitPrefs.US,
    /** Saved meals matching the search: logged whole in one tap (owner, 2026-10-23). */
    val savedMeals: List<SavedMeal> = emptyList(),
    /** AI is on with a profile, so "Describe it to AI" can be offered. */
    val canDescribe: Boolean = false,
    /** "Log manually instead" from a photo estimate: the photo, to look at while searching. */
    val photo: java.io.File? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    /**
     * No good results: nothing of your own, and the databases are done with fewer than three
     * foods that match most of the words searched (USDA answers almost anything with something
     * loosely related). Then "Describe it to AI" moves to the top.
     */
    val fewResults: Boolean
        get() {
            if (!isSearching || local.isNotEmpty() || savedMeals.isNotEmpty()) return false
            fun good(r: RemoteResults) = (r as? RemoteResults.Done)?.foods.orEmpty()
                .count { DescribedMatch.score(query, listOfNotNull(it.name, it.brand)) >= DescribedMatch.THRESHOLD }
            val loading = usda is RemoteResults.Loading || off is RemoteResults.Loading || usda is RemoteResults.Idle
            return !loading && good(usda) + good(off) < 3
        }
}

sealed interface SearchEffect {
    data class OpenFood(val foodId: String) : SearchEffect
    /** A saved meal was logged: back to Today with "Added to Lunch". */
    data class LoggedMeal(val mealName: String) : SearchEffect
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
    secrets: SecretStore,
    settings: SettingsRepository,
    private val savedMeals: SavedMealRepository,
    private val log: LogRepository,
    private val slots: MealSlotRepository,
    ai: AiRepository,
    photos: PhotoStore,
) : ViewModel() {

    val args: SearchDestination = savedState.toRoute()

    private val search = MutableStateFlow(
        SearchUiState(query = args.query.orEmpty(), photo = args.photo?.let(photos::file)?.takeIf { it.exists() }),
    )
    private var job: Job? = null
    private val effectChannel = Channel<SearchEffect>(Channel.BUFFERED)
    val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<SearchUiState> = combine(
        search,
        combine(foods.observeRecents(), foods.observeFavorites(), foods.observeCustom()) { r, f, c -> Triple(r, f, c) },
        secrets.present,
        settings.settings,
        combine(savedMeals.meals, ai.profiles) { m, p -> m to p.any { it.profile.enabled } },
    ) { s, (recents, favorites, custom), present, appSettings, (meals, hasProfile) ->
        s.copy(
            recents = recents,
            favorites = favorites,
            custom = custom,
            usingDemoKey = SecretName.USDA_API_KEY !in present,
            units = appSettings.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            savedMeals = if (args.forRecipe) emptyList() else matchingMeals(meals, s.query),
            canDescribe = !args.forRecipe && appSettings.ai.enabled && hasProfile,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), search.value)

    init {
        viewModelScope.launch { secrets.refresh() }
        // "Log manually instead" arrives with the meal's description: search it right away.
        args.query?.takeIf { it.isNotBlank() }?.let(::onQueryChange)
    }

    /** Saved meals whose name or items match most words of the search. */
    private fun matchingMeals(meals: List<SavedMeal>, query: String): List<SavedMeal> {
        if (query.isBlank()) return emptyList()
        return meals.map { m -> m to DescribedMatch.score(query, listOf(m.name) + m.items.map { it.displayName }) }
            .filter { it.second >= DescribedMatch.THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** One tap logs the whole saved meal into the meal and day being logged (owner, 2026-10-23). */
    fun logSavedMeal(meal: SavedMeal) {
        viewModelScope.launch {
            val date = java.time.LocalDate.parse(args.date)
            val entries = savedMeals.toEntries(meal.id, date, args.mealSlotId)
            if (entries.isEmpty()) return@launch
            log.add(entries)
            val mealName = slots.observeMealSlots().first().firstOrNull { it.id == args.mealSlotId }?.name.orEmpty()
            effectChannel.send(SearchEffect.LoggedMeal(mealName))
        }
    }

    fun onQueryChange(query: String) {
        search.update { it.copy(query = query) }
        job?.cancel()
        if (query.isBlank()) {
            search.update { it.copy(local = emptyList(), usda = RemoteResults.Idle, off = RemoteResults.Idle) }
            return
        }
        job = viewModelScope.launch {
            // Your own foods show at once and work offline (SPEC §4.3).
            search.update { it.copy(local = foods.searchLocal(query)) }
            delay(DEBOUNCE_MS)
            search.update { it.copy(usda = RemoteResults.Loading, off = RemoteResults.Loading) }
            val usda = async { remote { foods.searchUsda(query) } }
            val off = async { remote { foods.searchOff(query) } }
            val usdaResult = usda.await()
            search.update { it.copy(usda = usdaResult) }
            val offResult = off.await()
            // Drop OFF items USDA already returned (same barcode).
            val usdaBarcodes = (usdaResult as? RemoteResults.Done)?.foods?.mapNotNull { it.barcode }?.toSet().orEmpty()
            search.update {
                it.copy(off = if (offResult is RemoteResults.Done) RemoteResults.Done(offResult.foods.filter { f -> f.barcode == null || f.barcode !in usdaBarcodes }) else offResult)
            }
        }
    }

    /** Remote results are cached on open, so a food you've looked at works offline later. */
    fun open(food: Food) {
        viewModelScope.launch {
            val id = if (food.id.isNotEmpty()) food.id else foods.cache(food)
            effectChannel.send(SearchEffect.OpenFood(id))
        }
    }

    private suspend fun remote(block: suspend () -> List<Food>): RemoteResults = try {
        RemoteResults.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: FoodApiError) {
        RemoteResults.Failed(e)
    } catch (e: Exception) {
        RemoteResults.Failed(FoodApiError.BadResponse(e))
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
