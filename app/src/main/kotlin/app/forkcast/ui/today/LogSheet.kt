package app.forkcast.ui.today

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.model.MealSlot

/**
 * The "Log" bottom sheet (DESIGN.md §6). In Phase 0 every action is a placeholder;
 * Phase 1 wires Scan, Search, Quick add, Saved meals and Recent; Phase 2 wires Photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(
    meals: List<MealSlot>,
    selectedMealId: String,
    onSelectMeal: (String) -> Unit,
    onDismiss: () -> Unit,
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
                    FilterChip(
                        selected = meal.id == selected?.id,
                        onClick = { onSelectMeal(meal.id) },
                        label = { Text(meal.name) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                LogTile(R.drawable.ic_barcode, R.string.log_scan, Modifier.weight(1f))
                LogTile(R.drawable.ic_photo_camera, R.string.log_photo, Modifier.weight(1f))
                LogTile(R.drawable.ic_search, R.string.log_search, Modifier.weight(1f))
            }
            LogRow(R.drawable.ic_bolt, R.string.log_quick_add)
            LogRow(R.drawable.ic_bookmark, R.string.log_saved_meals)
            LogRow(R.drawable.ic_history, R.string.log_recent)
        }
    }
}

@Composable
private fun LogTile(@DrawableRes icon: Int, @StringRes label: Int, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = {},
        enabled = false,
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
            Text(
                stringResource(R.string.coming_soon),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LogRow(@DrawableRes icon: Int, @StringRes label: Int) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(R.string.coming_soon)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .alpha(0.6f)
            .semantics { disabled() },
    )
}
