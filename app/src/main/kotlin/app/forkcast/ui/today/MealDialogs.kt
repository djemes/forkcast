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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.forkcast.R
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.DateField
import java.time.LocalDate

/**
 * Pick a day and (optionally) a different meal to copy entries into. Never a future day (owner,
 * 2026-10-23: like the Today screen and its calendar), so the picker stops at [today].
 */
@Composable
fun CopyDialog(
    initialDate: LocalDate,
    meals: List<MealSlot>,
    allowSameMeal: Boolean,
    onCopy: (date: LocalDate, mealSlotId: String?) -> Unit,
    onDismiss: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    var date by rememberSaveable { mutableStateOf(minOf(initialDate, today)) }
    var mealId by rememberSaveable { mutableStateOf<String?>(if (allowSameMeal) null else meals.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.copy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.copy_day), style = MaterialTheme.typography.titleSmall)
                DateField(date, "", date, { date = minOf(it, today) }, selectableTo = today)
                Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allowSameMeal) {
                        FilterChip(selected = mealId == null, onClick = { mealId = null }, label = { Text(stringResource(R.string.copy_same_meal)) })
                    }
                    meals.forEach { m -> FilterChip(selected = mealId == m.id, onClick = { mealId = m.id }, label = { Text(m.name) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(minOf(date, today), mealId) }) { Text(stringResource(R.string.action_copy)) } },
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
