package app.forkcast.ui.food

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
    forRecipe: Boolean = false,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it is SearchEffect.OpenFood) onOpenFood(it.foodId) }
    }
    SearchScreen(state, viewModel::onQueryChange, viewModel::open, onBack, onCreateFood, onCreateRecipe, forRecipe)
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
) {
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(if (forRecipe) R.string.search_ingredient_hint else R.string.search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
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
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!state.isSearching) {
                    item { Hint(stringResource(R.string.search_empty_start)) }
                    foodSection(R.string.search_favorites, state.favorites, f, onOpen)
                    foodSection(R.string.search_recent, state.recents.take(15), f, onOpen)
                    foodSection(R.string.search_my_foods, state.custom, f, onOpen)
                } else {
                    foodSection(R.string.search_your_foods, state.local, f, onOpen)
                    remoteSection(R.string.search_usda, state.usda, f, onOpen, showDemoNote = state.usingDemoKey)
                    remoteSection(R.string.search_off, state.off, f, onOpen, showDemoNote = false)
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

private fun LazyListScope.foodSection(title: Int, foods: List<Food>, f: UnitFormatter, onOpen: (Food) -> Unit) {
    if (foods.isEmpty()) return
    item(key = "h$title") { SectionTitle(title) }
    items(foods, key = { "$title-${it.id.ifEmpty { "${it.source}:${it.sourceId}" }}" }) { FoodRow(it, f) { onOpen(it) } }
}

private fun LazyListScope.remoteSection(
    title: Int,
    results: RemoteResults,
    f: UnitFormatter,
    onOpen: (Food) -> Unit,
    showDemoNote: Boolean,
) {
    if (results is RemoteResults.Idle) return
    item(key = "h$title") { SectionTitle(title) }
    if (showDemoNote) item(key = "demo$title") { Hint(stringResource(R.string.search_demo_key)) }
    when (results) {
        RemoteResults.Loading -> item(key = "l$title") {
            Column {
                Hint(stringResource(R.string.search_loading))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is RemoteResults.Failed -> item(key = "e$title") {
            val name = stringResource(title)
            Hint(
                when (results.error) {
                    is FoodApiError.Offline -> stringResource(R.string.search_offline)
                    is FoodApiError.RateLimited -> stringResource(R.string.search_rate_limited, name)
                    is FoodApiError.InvalidKey -> stringResource(R.string.search_invalid_key)
                    else -> stringResource(R.string.search_failed, name)
                },
            )
        }
        is RemoteResults.Done ->
            if (results.foods.isEmpty()) item(key = "n$title") { Hint(stringResource(R.string.search_no_results)) }
            else items(results.foods, key = { "$title-${it.source}:${it.sourceId}" }) { FoodRow(it, f) { onOpen(it) } }
        RemoteResults.Idle -> Unit
    }
}

/** Name, brand, serving, kcal per serving, source badge (SPEC §4.3). */
@Composable
fun FoodRow(food: Food, f: UnitFormatter, onClick: () -> Unit) {
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
    }
}
