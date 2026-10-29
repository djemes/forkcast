package app.forkcast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.Food
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal
import app.forkcast.ui.food.FavoriteStar
import app.forkcast.ui.format.UnitFormatter

/**
 * Log sheet › Favorites (owner, 2026-10-29): tap logs the usual amount into the meal, the pencil
 * opens the food to change the amount first (or edit a food you made), the star unstars it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesSheet(
    favorites: List<Food>,
    mealName: String,
    f: UnitFormatter,
    onLog: (Food) -> Unit,
    onOpen: ((Food) -> Unit)?,
    onUnstar: (Food) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.log_favorites), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
            Text(
                stringResource(R.string.log_sheet_title, mealName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (favorites.isEmpty()) {
                Text(stringResource(R.string.favorites_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            }
            favorites.forEach { food ->
                val (serving, qty) = FoodLogging.defaultServing(food)
                val kcal = food.nutrientsPer100g.forGrams(serving.grams * qty).kcal
                ListItem(
                    headlineContent = { Text(food.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(food.brand, servingText(qty, serving.label), f.energy(kcal)).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onOpen != null) {
                                IconButton(onClick = { onOpen(food) }) {
                                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.favorites_open, food.name))
                                }
                            }
                            FavoriteStar(favorite = true) { onUnstar(food) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.food_add_to, mealName)) { onLog(food) },
                )
            }
        }
    }
}
