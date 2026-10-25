package app.forkcast.ui.recipes

import app.forkcast.ui.format.unitLabels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.RecipeRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.RecipeMath
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Recipe
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.settings.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

data class RecipesUiState(val recipes: List<Recipe>, val units: UnitPrefs, val mealNames: Map<String, String>)

@HiltViewModel
class RecipesViewModel @Inject constructor(
    recipes: RecipeRepository,
    settings: SettingsRepository,
    meals: MealSlotRepository,
) : ViewModel() {
    val state: StateFlow<RecipesUiState?> = combine(recipes.recipes, settings.settings, meals.observeMealSlots()) { list, s, slots ->
        RecipesUiState(list, s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()), slots.associate { it.id to it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun RecipesRoute(
    mealSlotId: String?,
    onBack: () -> Unit,
    onLog: (Recipe) -> Unit,
    onEdit: (Recipe) -> Unit,
    onNew: () -> Unit,
    viewModel: RecipesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecipesScreen(state, mealSlotId?.let { state?.mealNames?.get(it) }, onBack, onLog, onEdit, onNew)
}

/**
 * Recipes list, opened from the Log sheet: tap a recipe to log it (its food detail, serving
 * chosen there), the pencil to edit it. Without a meal (not logging), a tap edits.
 */
@Composable
fun RecipesScreen(
    state: RecipesUiState?,
    mealName: String?,
    onBack: () -> Unit,
    onLog: (Recipe) -> Unit,
    onEdit: (Recipe) -> Unit,
    onNew: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.recipes_title), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val f = UnitFormatter(s.units, LocalLocale.current.platformLocale, labels = unitLabels())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.recipes.isEmpty()) {
                Text(stringResource(R.string.recipes_empty), style = MaterialTheme.typography.bodyLarge)
            } else if (mealName != null) {
                Text(stringResource(R.string.recipes_log_hint, mealName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.recipes_new), modifier = Modifier.padding(start = 6.dp))
            }
            Column {
                s.recipes.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecipeRow(r, f, onClick = { if (mealName != null) onLog(r) else onEdit(r) }, onEdit = { onEdit(r) })
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(recipe: Recipe, f: UnitFormatter, onClick: () -> Unit, onEdit: () -> Unit) {
    val result = RecipeMath.compute(recipe.ingredients, recipe.servings, recipe.cookedWeightG)
    val kcal = result?.perServing?.get(NutrientKey.ENERGY_KCAL) ?: 0.0
    val servings = UnitFormatter.trimNumber(recipe.servings, 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                pluralStringResource(R.plurals.recipes_row, if (recipe.servings == 1.0) 1 else 2, f.energy(kcal), servings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.recipes_edit, recipe.name)) }
    }
}
