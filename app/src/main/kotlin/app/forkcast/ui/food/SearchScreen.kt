package app.forkcast.ui.food

import app.forkcast.ui.format.unitLabels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.sumNutrients
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.ui.help.HelpLink
import app.forkcast.ui.help.HelpLinks
import app.forkcast.core.model.Food
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal
import app.forkcast.core.network.FoodApiError
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.today.SourceBadge

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenFood: (foodId: String) -> Unit,
    onCreateFood: () -> Unit,
    /** Null while picking a recipe ingredient (no recipes inside recipes from here). */
    onCreateRecipe: ((query: String) -> Unit)? = null,
    /** "Describe it to AI" with the search text (SPEC §4.6.2); null while picking an ingredient. */
    onDescribe: ((query: String) -> Unit)? = null,
    /** A saved meal was logged whole: back to Today saying where. */
    onLoggedMeal: (mealName: String) -> Unit = {},
    forRecipe: Boolean = false,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is SearchEffect.OpenFood -> onOpenFood(it.foodId)
                is SearchEffect.LoggedMeal -> onLoggedMeal(it.mealName)
            }
        }
    }
    SearchScreen(
        state, viewModel::onQueryChange, viewModel::open, onBack, onCreateFood, onCreateRecipe, forRecipe,
        onDescribe = onDescribe.takeIf { state.canDescribe }, onLogSavedMeal = viewModel::logSavedMeal,
        onToggleFavorite = viewModel::toggleFavorite,
    )
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Food) -> Unit,
    onBack: () -> Unit,
    onCreateFood: () -> Unit,
    onCreateRecipe: ((query: String) -> Unit)? = null,
    forRecipe: Boolean = false,
    onDescribe: ((query: String) -> Unit)? = null,
    onLogSavedMeal: (SavedMeal) -> Unit = {},
    onToggleFavorite: ((Food) -> Unit)? = null,
) {
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale, labels = unitLabels())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The typed text lives here, not in the ViewModel's combined flow: that one arrives a frame
    // late, and fast typing then lost or reordered letters ("chicken" became "chinc").
    var query by rememberSaveable { mutableStateOf(state.query) }
    val setQuery: (String) -> Unit = { query = it; onQueryChange(it) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = setQuery,
                    placeholder = { Text(stringResource(if (forRecipe) R.string.search_ingredient_hint else R.string.search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { setQuery("") }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .focusRequester(focus),
                )
            }
            // The "Describe it to AI" card is added above results already on screen; bring it into view.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val offerDescribe = onDescribe != null && state.isSearching && state.fewResults
            LaunchedEffect(offerDescribe) { if (offerDescribe) listState.animateScrollToItem(0) }
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.photo?.let { photo ->
                    item(key = "photo") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            AsyncImage(
                                model = photo,
                                contentDescription = stringResource(R.string.search_manual_photo),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Text(stringResource(R.string.search_manual_photo), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                if (!state.isSearching) {
                    item { Hint(stringResource(R.string.search_empty_start)) }
                    foodSection(R.string.search_favorites, state.favorites, state, f, onOpen, onToggleFavorite)
                    foodSection(R.string.search_recent, state.recents.take(15), state, f, onOpen, onToggleFavorite)
                    foodSection(R.string.search_my_foods, state.custom, state, f, onOpen, onToggleFavorite)
                } else {
                    // Nothing good found: describing it to the AI is the next step (owner, 2026-10-23).
                    if (onDescribe != null && state.fewResults) {
                        item(key = "describe-top") { DescribeCard(state.query.trim()) { onDescribe(state.query.trim()) } }
                    }
                    savedMealSection(state.savedMeals, f, onLogSavedMeal)
                    resultsSection(state, f, onOpen, onToggleFavorite)
                    if (onDescribe != null && !state.fewResults) {
                        item(key = "describe-bottom") {
                            TextButton(onClick = { onDescribe(state.query.trim()) }, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                                Text(stringResource(R.string.describe_from_search), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = onCreateFood, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        Text(stringResource(R.string.search_create_food), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                onCreateRecipe?.let { create ->
                    item {
                        TextButton(onClick = { create(state.query.trim()) }) {
                            Icon(painterResource(R.drawable.ic_recipe), contentDescription = null)
                            Text(stringResource(R.string.search_create_recipe), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** "Not finding it? The AI can estimate “…”." with the Describe button. */
@Composable
private fun DescribeCard(query: String, onDescribe: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            stringResource(R.string.describe_from_search_body, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDescribe) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                Text(stringResource(R.string.describe_from_search), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Favorite meals that match, marked as meals, logged whole with one tap (owner, 2026-10-23; roadmap 3.10). */
private fun LazyListScope.savedMealSection(meals: List<SavedMeal>, f: UnitFormatter, onLog: (SavedMeal) -> Unit) {
    if (meals.isEmpty()) return
    item(key = "h-saved") { SectionTitle(R.string.favorite_meals_title) }
    items(meals, key = { "saved-${it.id}" }) { meal ->
        val kcal = meal.items.map { it.nutrients }.sumNutrients().kcal
        val logLabel = stringResource(R.string.saved_meal_log, meal.name)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = logLabel, onClick = { onLog(meal) })
                .heightIn(min = 56.dp)
                .padding(vertical = 6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(meal.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.saved_meal_items, meal.items.size, meal.items.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MealBadge()
                }
            }
            Text(f.energy(kcal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** "Saved meal" in the same tiny outlined style as the source badges. */
@Composable
private fun MealBadge() {
    Text(
        stringResource(R.string.badge_saved_meal),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 8.dp),
)

@Composable
private fun SectionTitle(title: Int) = Text(
    stringResource(title),
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier
        .padding(top = 16.dp, bottom = 4.dp)
        .semantics { heading() },
)

private fun LazyListScope.foodSection(
    title: Int,
    foods: List<Food>,
    state: SearchUiState,
    f: UnitFormatter,
    onOpen: (Food) -> Unit,
    onToggleFavorite: ((Food) -> Unit)?,
) {
    if (foods.isEmpty()) return
    item(key = "h$title") { SectionTitle(title) }
    items(foods, key = { "$title-${it.id.ifEmpty { "${it.source}:${it.sourceId}" }}" }) {
        FoodRow(it, f, favorite = state.isFavorite(it), onToggleFavorite = onToggleFavorite?.let { t -> { t(it) } }) { onOpen(it) }
    }
}

/**
 * Your foods, USDA and Open Food Facts as one ranked list with source tags (owner, 2026-10-29),
 * then what's still loading or didn't answer, per database.
 */
private fun LazyListScope.resultsSection(state: SearchUiState, f: UnitFormatter, onOpen: (Food) -> Unit, onToggleFavorite: ((Food) -> Unit)?) {
    if (state.results.isNotEmpty()) {
        item(key = "h-results") { SectionTitle(R.string.search_results) }
        items(state.results, key = { "r-${it.id.ifEmpty { "${it.source}:${it.sourceId}" }}" }) {
            FoodRow(it, f, favorite = state.isFavorite(it), onToggleFavorite = onToggleFavorite?.let { t -> { t(it) } }) { onOpen(it) }
        }
    }
    if (state.remoteLoading) {
        item(key = "loading") {
            Column(Modifier.padding(top = 8.dp)) {
                Hint(stringResource(R.string.search_loading))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    if (state.usingDemoKey && state.usda is RemoteResults.Done) item(key = "demo") { Hint(stringResource(R.string.search_demo_key)) }
    remoteProblem(R.string.search_usda, state.usda)
    remoteProblem(R.string.search_off, state.off)
    val bothDone = state.usda is RemoteResults.Done && state.off is RemoteResults.Done
    if (state.results.isEmpty() && bothDone) item(key = "none") { Hint(stringResource(R.string.search_no_results)) }
}

/** A database that didn't answer, in its own words, with the help link. */
private fun LazyListScope.remoteProblem(title: Int, results: RemoteResults) {
    if (results !is RemoteResults.Failed) return
    item(key = "e$title") {
        val name = stringResource(title)
        Column {
            Hint(
                when (results.error) {
                    is FoodApiError.Offline -> stringResource(R.string.search_offline)
                    is FoodApiError.RateLimited -> stringResource(R.string.search_rate_limited, name)
                    is FoodApiError.InvalidKey -> stringResource(R.string.search_invalid_key)
                    else -> stringResource(R.string.search_failed, name)
                },
            )
            HelpLink(HelpLinks.food(results.error))
        }
    }
}

/**
 * Name, brand, serving, kcal per serving, source badge (SPEC §4.3), and the favorite star
 * (owner, 2026-10-29): a button when [onToggleFavorite] is given, else shown only on favorites.
 */
@Composable
fun FoodRow(food: Food, f: UnitFormatter, favorite: Boolean = food.isFavorite, onToggleFavorite: (() -> Unit)? = null, onClick: () -> Unit) {
    val serving = food.servingSizes.firstOrNull()
    val kcal = food.nutrientsPer100g.forGrams(serving?.grams ?: 100.0).kcal
    val servingText = serving?.label ?: stringResource(R.string.per_100g)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(food.brand, servingText).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SourceBadge(food.source)
            }
        }
        Text(f.energy(kcal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
        FavoriteStar(favorite, onToggleFavorite)
    }
}
