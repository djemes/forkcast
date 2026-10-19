package app.forkcast.ui.food

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.SourceBadge
import app.forkcast.ui.today.formatNutrient
import app.forkcast.ui.today.nutrientName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun FoodDetailRoute(
    onBack: () -> Unit,
    onAdded: (mealName: String) -> Unit,
    onEdit: (foodId: String) -> Unit,
    viewModel: FoodDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it is FoodDetailEffect.Added) onAdded(it.mealName) }
    }
    state?.let {
        FoodDetailScreen(
            state = it,
            onBack = onBack,
            onServing = viewModel::selectServing,
            onQuantity = viewModel::setQuantity,
            onStep = viewModel::step,
            onMeal = viewModel::selectMeal,
            onFavorite = viewModel::toggleFavorite,
            onEdit = { onEdit(it.food.id) },
            onAdd = viewModel::add,
            onCountsAsWater = viewModel::setCountsAsWater,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodDetailScreen(
    state: FoodDetailState,
    onBack: () -> Unit,
    onServing: (app.forkcast.core.model.ServingSize) -> Unit,
    onQuantity: (Double?) -> Unit,
    onStep: (Int) -> Unit,
    onMeal: (String) -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onCountsAsWater: (Boolean) -> Unit = {},
) {
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale)
    val food = state.food
    var showAll by rememberSaveable { mutableStateOf(false) }
    val editable = food.source == FoodSource.CUSTOM || food.source == FoodSource.LABEL

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                actions = {
                    if (editable) {
                        IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.food_edit)) }
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(
                            painterResource(if (food.isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                            stringResource(if (food.isFavorite) R.string.food_favorite_remove else R.string.food_favorite_add),
                            tint = if (food.isFavorite) ForkcastTheme.colors.apricot else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Button(
                onClick = onAdd,
                enabled = state.canAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp)
                    .heightIn(min = 56.dp),
            ) {
                // Logging goes to the day on Today's screen; name it when it isn't today.
                val date = state.date
                val label = when {
                    state.daysFromToday == 0L || date == null -> stringResource(R.string.food_add_to, state.mealName)
                    state.daysFromToday == -1L -> stringResource(R.string.food_add_to_day, state.mealName, stringResource(R.string.yesterday))
                    state.daysFromToday == 1L -> stringResource(R.string.food_add_to_day, state.mealName, stringResource(R.string.tomorrow))
                    else -> stringResource(R.string.food_add_to_day, state.mealName, shortDate(date))
                }
                Text(label, textAlign = TextAlign.Center)
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Column {
                Text(food.name, style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    food.brand?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    SourceBadge(food.source)
                }
            }

            Text(stringResource(R.string.food_serving), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.servings.forEach { s ->
                    FilterChip(selected = s.label == state.serving.label, onClick = { onServing(s) }, label = { Text(s.label) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onStep(-1) }) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.food_decrease))
                }
                NumberField(
                    initial = state.quantity?.let { UnitFormatter.trimNumber(it, 2) }.orEmpty(),
                    label = stringResource(R.string.food_quantity),
                    suffix = state.serving.label.takeIf { it == "g" || it == "oz" } ?: "× ${state.serving.label}",
                    onValue = onQuantity,
                    // Re-seed the field when the serving or stepper changes the value.
                    key = "${state.serving.label}|${state.quantity}",
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = { onStep(+1) }) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.food_increase))
                }
            }
            state.grams?.takeIf { state.serving.label != "g" }?.let {
                Text(stringResource(R.string.food_grams_total, f.grams(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Four core values large (DESIGN.md §6).
            val n = state.preview
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Big(f.energyNumber(n[NutrientKey.ENERGY_KCAL] ?: 0.0), f.energyUnitLabel, MaterialTheme.colorScheme.primary)
                Big(f.number(n[NutrientKey.PROTEIN_G] ?: 0.0), stringResource(R.string.macro_protein), ForkcastTheme.colors.protein)
                Big(f.number(n[NutrientKey.CARBS_G] ?: 0.0), stringResource(R.string.macro_carbs), ForkcastTheme.colors.carbs)
                Big(f.number(n[NutrientKey.FAT_G] ?: 0.0), stringResource(R.string.macro_fat), ForkcastTheme.colors.fat)
            }

            TextButton(onClick = { showAll = !showAll }) {
                Icon(painterResource(if (showAll) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
                Text(stringResource(R.string.food_all_nutrients), modifier = Modifier.padding(start = 4.dp))
            }
            AnimatedVisibility(showAll) {
                Column {
                    NutrientKey.entries.filter { it in n }.forEach { key ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(nutrientName(key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(formatNutrient(key, n.getValue(key), f), style = MaterialTheme.typography.labelLarge)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.meals.forEach { m ->
                    FilterChip(selected = m.id == state.mealSlotId, onClick = { onMeal(m.id) }, label = { Text(m.name) })
                }
            }
            // Water, sparkling water and plain tea count toward the day's water; the user can override it per food.
            val counts = state.countsAsWater
            app.forkcast.ui.today.WaterToggle(
                checked = counts,
                detail = state.grams?.takeIf { counts }?.let { stringResource(R.string.water_counts_amount, f.waterLiters(it)) },
                onChange = onCountsAsWater,
            )
            if (food.source == FoodSource.OFF) {
                Text(stringResource(R.string.food_odbl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Big(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun shortDate(date: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(date)
}
