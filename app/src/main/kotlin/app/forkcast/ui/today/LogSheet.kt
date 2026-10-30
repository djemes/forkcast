package app.forkcast.ui.today

import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.core.model.sumNutrients
import app.forkcast.core.model.SavedMeal
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.Food
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal
import app.forkcast.ui.format.UnitFormatter

/** What the Log sheet can open. Null = not built yet (shown as "Coming soon"). */
data class LogSheetActions(
    val scan: (() -> Unit)? = null,
    val photo: (() -> Unit)? = null,
    /** "Describe" (SPEC §4.6.2): a meal estimated from text alone. */
    val describe: (() -> Unit)? = null,
    val search: (() -> Unit)? = null,
    val quickAdd: (() -> Unit)? = null,
    val recipes: (() -> Unit)? = null,
    /** The Favorites list (owner, 2026-10-29). */
)

/** A favorite in the Log sheet: a food or a favorite meal (roadmap 3.10). */
sealed interface FavoriteItem {
    val name: String
    data class FoodItem(val food: Food) : FavoriteItem { override val name get() = food.name }
    data class MealItem(val meal: SavedMeal) : FavoriteItem { override val name get() = meal.name }
}

/**
 * The "Log" bottom sheet (DESIGN.md §6): Scan · Photo · Describe · Search tiles, Quick add and
 * Recipes, then two tabs (roadmap 3.10): Recent (the last 15 foods) and Favorites (All · Foods ·
 * Meals). One tap logs into the meal; ⋯ edits a favorite or unfavorites it (≤ 3 taps from Today, SPEC §4.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(
    meals: List<MealSlot>,
    selectedMealId: String,
    onSelectMeal: (String) -> Unit,
    onDismiss: () -> Unit,
    actions: LogSheetActions = LogSheetActions(),
    recents: List<Food> = emptyList(),
    favorites: List<Food> = emptyList(),
    favoriteMeals: List<SavedMeal> = emptyList(),
    f: UnitFormatter? = null,
    onAddRecent: (Food) -> Unit = {},
    onLogMeal: (SavedMeal) -> Unit = {},
    onEditFavorite: (FavoriteItem) -> Unit = {},
    onUnfavorite: (FavoriteItem) -> Unit = {},
    /** Set when the day on screen isn't today ("Yesterday"), so it's clear where the entry goes. */
    dayLabel: String? = null,
) {
    val selected = meals.firstOrNull { it.id == selectedMealId } ?: meals.firstOrNull()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.log_sheet_title, selected?.name.orEmpty()),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            if (dayLabel != null) {
                Text(
                    stringResource(R.string.log_sheet_day, dayLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Wraps rather than scrolls, so the selected meal is never off-screen at large font sizes.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                meals.forEach { meal ->
                    FilterChip(selected = meal.id == selected?.id, onClick = { onSelectMeal(meal.id) }, label = { Text(meal.name) })
                }
            }
            // Four tiles in a row; two rows of two at large font sizes, so the labels never squeeze.
            val tiles = listOf(
                Triple(R.drawable.ic_barcode, R.string.log_scan, actions.scan),
                Triple(R.drawable.ic_photo_camera, R.string.log_photo, actions.photo),
                Triple(R.drawable.ic_edit, R.string.describe_action, actions.describe),
                Triple(R.drawable.ic_search, R.string.log_search, actions.search),
            )
            val perRow = if (LocalDensity.current.fontScale > 1.3f) 2 else 4
            tiles.chunked(perRow).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    row.forEach { (icon, label, action) -> LogTile(icon, label, action, Modifier.weight(1f)) }
                }
            }
            LogRow(R.drawable.ic_bolt, R.string.log_quick_add, actions.quickAdd)
            LogRow(R.drawable.ic_recipe, R.string.log_recipes, actions.recipes)
            if (f != null) {
                val mealName = selected?.name.orEmpty()
                // Favorites first while nothing has been logged yet.
                var tab by rememberSaveable { mutableIntStateOf(if (recents.isEmpty() && (favorites.isNotEmpty() || favoriteMeals.isNotEmpty())) 1 else 0) }
                PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 12.dp)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.log_recent)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.log_favorites)) })
                }
                if (tab == 0) {
                    if (recents.isEmpty()) EmptyLine(R.string.recent_empty)
                    recents.forEach { food -> FoodRow(food, f, mealName, favorite = false, onAdd = { onAddRecent(food) }) }
                } else {
                    FavoritesTab(favorites, favoriteMeals, f, mealName, onAddRecent, onLogMeal, onEditFavorite, onUnfavorite)
                }
            }
        }
    }
}

/** Favorites › All · Foods · Meals: one tap logs it, ⋯ has Edit and Unfavorite. */
@Composable
private fun FavoritesTab(
    foods: List<Food>,
    meals: List<SavedMeal>,
    f: UnitFormatter,
    mealName: String,
    onAdd: (Food) -> Unit,
    onLogMeal: (SavedMeal) -> Unit,
    onEdit: (FavoriteItem) -> Unit,
    onUnfavorite: (FavoriteItem) -> Unit,
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(R.string.favorites_all, R.string.favorites_foods, R.string.favorites_meals).forEachIndexed { i, label ->
            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(stringResource(label)) })
        }
    }
    val items = buildList {
        if (filter != 2) addAll(foods.map { FavoriteItem.FoodItem(it) })
        if (filter != 1) addAll(meals.map { FavoriteItem.MealItem(it) })
    }.sortedBy { it.name.lowercase() }
    if (items.isEmpty()) EmptyLine(R.string.favorites_empty)
    items.forEach { item ->
        val menu = @Composable { FavoriteMenu(item.name, onEdit = { onEdit(item) }, onUnfavorite = { onUnfavorite(item) }) }
        when (item) {
            is FavoriteItem.FoodItem -> FoodRow(item.food, f, mealName, favorite = true, onAdd = { onAdd(item.food) }, menu = menu)
            is FavoriteItem.MealItem -> MealRow(item.meal, f, onLog = { onLogMeal(item.meal) }, menu = menu)
        }
    }
}

@Composable
private fun EmptyLine(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
}

@Composable
private fun FavoriteMenu(name: String, onEdit: () -> Unit, onUnfavorite: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.favorite_more, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.favorite_edit)) }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.favorite_unfavorite)) }, onClick = { open = false; onUnfavorite() })
        }
    }
}

@Composable
private fun MealRow(meal: SavedMeal, f: UnitFormatter, onLog: () -> Unit, menu: @Composable () -> Unit) {
    val kcal = meal.items.map { it.nutrients }.sumNutrients().kcal
    val count = pluralStringResource(R.plurals.saved_meal_items, meal.items.size, meal.items.size)
    ListItem(
        headlineContent = { Text(meal.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.favorite_meal_summary, count, f.energy(kcal)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = ForkcastTheme.colors.pine) },
        trailingContent = menu,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.saved_meal_log, meal.name), onClick = onLog),
    )
}

/** A food to log in one tap with its default amount: a recent (clock) or a favorite (star, ⋯ menu). */
@Composable
private fun FoodRow(food: Food, f: UnitFormatter, mealName: String, favorite: Boolean, onAdd: () -> Unit, menu: (@Composable () -> Unit)? = null) {
    val (serving, qty) = FoodLogging.defaultServing(food)
    val kcal = food.nutrientsPer100g.forGrams(serving.grams * qty).kcal
    val amount = servingText(qty, serving.label)
    val addLabel = stringResource(R.string.food_add_to, mealName)
    ListItem(
        headlineContent = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("$amount · ${f.energy(kcal)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (favorite) {
                Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = ForkcastTheme.colors.pine)
            } else {
                Icon(painterResource(R.drawable.ic_history), contentDescription = null)
            }
        },
        trailingContent = menu ?: { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClickLabel = addLabel, onClick = onAdd),
    )
}

@Composable
private fun LogTile(@DrawableRes icon: Int, @StringRes label: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LogRow(@DrawableRes icon: Int, @StringRes label: Int, onClick: (() -> Unit)?) {
    val base = Modifier.heightIn(min = 56.dp)
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base.alpha(0.6f).semantics { disabled() },
    )
}
