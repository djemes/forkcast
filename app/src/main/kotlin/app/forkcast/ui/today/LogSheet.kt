package app.forkcast.ui.today

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    val search: (() -> Unit)? = null,
    val quickAdd: (() -> Unit)? = null,
    val savedMeals: (() -> Unit)? = null,
)

/**
 * The "Log" bottom sheet (DESIGN.md §6): Scan · Photo · Search tiles, then Quick add,
 * Saved meals, and the first 5 recent foods for one-tap add (≤ 3 taps from Today, SPEC §4.3).
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
    f: UnitFormatter? = null,
    onAddRecent: (Food) -> Unit = {},
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
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                meals.forEach { meal ->
                    FilterChip(selected = meal.id == selected?.id, onClick = { onSelectMeal(meal.id) }, label = { Text(meal.name) })
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                LogTile(R.drawable.ic_barcode, R.string.log_scan, actions.scan, Modifier.weight(1f))
                LogTile(R.drawable.ic_photo_camera, R.string.log_photo, actions.photo, Modifier.weight(1f))
                LogTile(R.drawable.ic_search, R.string.log_search, actions.search, Modifier.weight(1f))
            }
            LogRow(R.drawable.ic_bolt, R.string.log_quick_add, actions.quickAdd)
            LogRow(R.drawable.ic_bookmark, R.string.log_saved_meals, actions.savedMeals)
            if (recents.isNotEmpty() && f != null) {
                Text(
                    stringResource(R.string.log_recent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                recents.take(5).forEach { food -> RecentRow(food, f, selected?.name.orEmpty()) { onAddRecent(food) } }
            }
        }
    }
}

@Composable
private fun RecentRow(food: Food, f: UnitFormatter, mealName: String, onAdd: () -> Unit) {
    val (serving, qty) = FoodLogging.defaultServing(food)
    val kcal = food.nutrientsPer100g.forGrams(serving.grams * qty).kcal
    val amount = servingText(qty, serving.label)
    val addLabel = stringResource(R.string.food_add_to, mealName)
    ListItem(
        headlineContent = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(amount, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.energy(kcal), style = MaterialTheme.typography.labelLarge)
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(start = 8.dp))
            }
        },
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
            if (onClick == null) {
                Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun LogRow(@DrawableRes icon: Int, @StringRes label: Int, onClick: (() -> Unit)?) {
    val base = Modifier.heightIn(min = 56.dp)
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = if (onClick == null) ({ Text(stringResource(R.string.coming_soon)) }) else null,
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base.alpha(0.6f).semantics { disabled() },
    )
}
