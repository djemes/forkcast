package app.forkcast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField

/**
 * Favorites › ⋯ › Edit (roadmap 3.10, owner 2026-10-29). A food: the amount it's logged with
 * (serving and quantity), and for a food you made, a link to its nutrition. A favorite meal: its
 * name, each item's amount, and items to remove. Only what's logged from now on changes; past
 * entries keep their own numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteEditSheet(
    item: FavoriteItem,
    f: UnitFormatter,
    onSaveFood: (quantity: Double, servingLabel: String) -> Unit,
    onSaveMeal: (name: String, quantities: Map<String, Double>, removed: Set<String>) -> Unit,
    onDismiss: () -> Unit,
    /** A food you made: its editor (null hides the link). */
    onEditNutrition: ((Food) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.favorite_edit_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when (item) {
                is FavoriteItem.FoodItem -> FoodFavoriteEditor(item.food, f, onSaveFood, onDismiss, onEditNutrition)
                is FavoriteItem.MealItem -> MealFavoriteEditor(item.meal, f, onSaveMeal, onDismiss)
            }
        }
    }
}

@Composable
private fun FoodFavoriteEditor(
    food: Food,
    f: UnitFormatter,
    onSave: (Double, String) -> Unit,
    onDismiss: () -> Unit,
    onEditNutrition: ((Food) -> Unit)?,
) {
    val options = remember(food) { FoodLogging.servingOptions(food) }
    val (initialServing, initialQuantity) = remember(food) { FoodLogging.defaultServing(food) }
    var servingLabel by rememberSaveable(food.id) { mutableStateOf(initialServing.label) }
    var quantity by rememberSaveable(food.id) { mutableStateOf<Double?>(initialQuantity) }
    val serving = options.firstOrNull { it.label == servingLabel } ?: initialServing
    Text(food.name, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.favorite_default_amount), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { s ->
            FilterChip(selected = s.label == servingLabel, onClick = { servingLabel = s.label }, label = { Text(servingLabel(s.label)) })
        }
    }
    NumberField(
        initial = UnitFormatter.trimNumber(quantity ?: initialQuantity, 2),
        label = stringResource(R.string.edit_quantity),
        suffix = if (servingLabel in setOf("g", "oz", "ml", "fl oz")) servingLabel else "× ${servingLabel(servingLabel)}",
        onValue = { quantity = it },
        key = "${food.id}|$servingLabel",
    )
    quantity?.takeIf { it > 0 }?.let { q ->
        Text(f.energy(food.nutrientsPer100g.forGrams(serving.grams * q).kcal), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Text(stringResource(R.string.favorite_future_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A food you made: its nutrition is edited in its own editor (owner, 2026-10-29).
    if (onEditNutrition != null && food.source in setOf(FoodSource.CUSTOM, FoodSource.LABEL)) {
        TextButton(onClick = { onEditNutrition(food) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.favorite_edit_nutrition)) }
    }
    SaveCancel(enabled = (quantity ?: 0.0) > 0, onDismiss = onDismiss) { quantity?.let { onSave(it, servingLabel) } }
}

@Composable
private fun MealFavoriteEditor(meal: SavedMeal, f: UnitFormatter, onSave: (String, Map<String, Double>, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(meal.id) { mutableStateOf(meal.name) }
    val quantities = remember(meal.id) { mutableStateMapOf<String, Double?>() }
    val removed = remember(meal.id) { mutableStateMapOf<String, Boolean>() }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(60) },
        label = { Text(stringResource(R.string.save_meal_name)) },
        singleLine = true,
        isError = name.isBlank(),
    )
    meal.items.filterNot { removed[it.id] == true }.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                NumberField(
                    initial = UnitFormatter.trimNumber(item.quantity, 2),
                    label = stringResource(R.string.edit_quantity),
                    suffix = if (item.servingLabel in setOf("g", "oz", "ml", "fl oz")) item.servingLabel else "× ${servingLabel(item.servingLabel)}",
                    onValue = { quantities[item.id] = it },
                    key = item.id,
                )
                val q = quantities[item.id] ?: item.quantity
                if (item.quantity > 0) Text(f.energy(item.nutrients.kcal * q / item.quantity), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { removed[item.id] = true }) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.favorite_remove_item, item.displayName))
            }
        }
    }
    Text(stringResource(R.string.favorite_future_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valid = name.isNotBlank() && quantities.values.all { it == null || it > 0 } && meal.items.any { removed[it.id] != true }
    SaveCancel(enabled = valid, onDismiss = onDismiss) {
        onSave(name.trim(), quantities.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(), removed.filterValues { it }.keys.toSet())
    }
}

@Composable
private fun SaveCancel(enabled: Boolean, onDismiss: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.action_save)) }
    }
}
