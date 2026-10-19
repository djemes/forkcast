package app.forkcast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.DateField
import java.time.LocalDate

/** Pick a day and (optionally) a different meal to copy entries into. */
@Composable
fun CopyDialog(
    initialDate: LocalDate,
    meals: List<MealSlot>,
    allowSameMeal: Boolean,
    onCopy: (date: LocalDate, mealSlotId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var mealId by rememberSaveable { mutableStateOf<String?>(if (allowSameMeal) null else meals.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.copy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.copy_day), style = MaterialTheme.typography.titleSmall)
                DateField(date, "", date, { date = it })
                Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allowSameMeal) {
                        FilterChip(selected = mealId == null, onClick = { mealId = null }, label = { Text(stringResource(R.string.copy_same_meal)) })
                    }
                    meals.forEach { m -> FilterChip(selected = mealId == m.id, onClick = { mealId = m.id }, label = { Text(m.name) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(date, mealId) }) { Text(stringResource(R.string.action_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SaveMealDialog(suggestedName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_meal_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.save_meal_name)) },
                placeholder = { Text(stringResource(R.string.save_meal_hint)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Saved meals for one-tap logging into [mealName]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedMealsSheet(
    meals: List<SavedMeal>,
    mealName: String,
    f: UnitFormatter,
    onLog: (SavedMeal) -> Unit,
    onDelete: (SavedMeal) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.saved_meals_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.log_sheet_title, mealName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (meals.isEmpty()) {
                Text(
                    stringResource(R.string.saved_meals_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            meals.forEach { meal ->
                val kcal = meal.items.map { it.nutrients }.sumNutrients().kcal
                val logLabel = stringResource(R.string.saved_meal_log, meal.name)
                ListItem(
                    headlineContent = { Text(meal.name) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.saved_meal_items, meal.items.size, meal.items.size) + " · " + f.energy(kcal))
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(meal) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.saved_meal_delete, meal.name))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable(onClickLabel = logLabel) { onLog(meal) },
                )
            }
        }
    }
}
