package app.forkcast.ui.recipes

import app.forkcast.ui.format.unitLabels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.NutrientKey
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.servingText

@Composable
fun RecipeEditRoute(
    onBack: () -> Unit,
    onAddIngredient: () -> Unit,
    onScanIngredient: () -> Unit,
    onSaved: (RecipeEditEffect.Saved) -> Unit,
    onDeleted: () -> Unit,
    /** "Save and log" instead of "Save recipe": the editor was opened while logging a meal. */
    logAfterSave: Boolean,
    pick: String?,
    onPickHandled: () -> Unit,
    viewModel: RecipeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    LaunchedEffect(pick) {
        pick?.let { RecipePick.decode(it) }?.let { viewModel.addPicked(it) }
        if (pick != null) onPickHandled()
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { e ->
            when (e) {
                is RecipeEditEffect.Saved -> currentOnSaved(e)
                RecipeEditEffect.Deleted -> currentOnDeleted()
                is RecipeEditEffect.Added -> snackbar.showSnackbar(resources.getString(R.string.recipe_ingredient_added, e.name))
                RecipeEditEffect.NotItself -> snackbar.showSnackbar(resources.getString(R.string.recipe_not_itself))
            }
        }
    }
    state?.let {
        RecipeEditScreen(
            state = it,
            snackbar = snackbar,
            logAfterSave = logAfterSave,
            onBack = onBack,
            onName = viewModel::setName,
            onServings = viewModel::setServings,
            onCookedWeight = viewModel::setCookedWeight,
            onQuantity = viewModel::setQuantity,
            onRemove = viewModel::remove,
            onAddIngredient = onAddIngredient,
            onScanIngredient = onScanIngredient,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        )
    }
}

/** Recipe builder (SPEC §4.5): name, servings, optional cooked weight, ingredients, live per-serving values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    state: RecipeEditState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onServings: (Double?) -> Unit,
    onCookedWeight: (Double?) -> Unit,
    onQuantity: (Int, Double) -> Unit,
    onRemove: (Int) -> Unit,
    onAddIngredient: () -> Unit,
    onScanIngredient: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    logAfterSave: Boolean = false,
) {
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale, labels = unitLabels())
    val d = state.draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val back = { if (d.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = d.dirty) { confirmDiscard = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.recipe_new_title else R.string.recipe_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.recipe_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_delete)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                val missing = when {
                    d.name.isBlank() -> stringResource(R.string.recipe_need_name)
                    d.ingredients.isEmpty() -> stringResource(R.string.recipe_need_ingredient)
                    else -> null
                }
                if (missing != null) {
                    Text(
                        missing,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(if (logAfterSave && state.isNew) R.string.recipe_save_and_log else R.string.recipe_save))
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Held here so fast typing never waits for the ViewModel's combined flow.
            var name by rememberSaveable(d.id) { mutableStateOf(d.name) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onName(it) },
                label = { Text(stringResource(R.string.recipe_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(
                initial = d.servings?.let { UnitFormatter.trimNumber(it, 2) }.orEmpty(),
                label = stringResource(R.string.recipe_servings),
                onValue = onServings,
                isError = (d.servings ?: 0.0) <= 0,
                supporting = stringResource(R.string.recipe_servings_help),
                key = "servings-${d.id}",
                modifier = Modifier.fillMaxWidth(),
            )
            val raw = state.result?.rawGrams ?: d.ingredients.sumOf { it.grams }
            NumberField(
                initial = d.cookedWeightG?.let { UnitFormatter.trimNumber(it, 0) }.orEmpty(),
                label = stringResource(R.string.recipe_cooked_weight),
                suffix = "g",
                onValue = onCookedWeight,
                supporting = if (raw > 0) stringResource(R.string.recipe_cooked_weight_help, f.grams(raw)) else stringResource(R.string.recipe_cooked_weight_help_empty),
                key = "cooked-${d.id}",
                modifier = Modifier.fillMaxWidth(),
            )

            state.result?.let { r -> Summary(r, f) }

            Text(stringResource(R.string.recipe_ingredients), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            if (d.ingredients.isEmpty()) {
                Text(stringResource(R.string.recipe_no_ingredients), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                d.ingredients.forEachIndexed { i, ing ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    IngredientRow(ing, f, onEdit = { editing = i }, onRemove = { onRemove(i) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onAddIngredient, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    Text(stringResource(R.string.recipe_add_search), modifier = Modifier.padding(start = 6.dp), textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onScanIngredient, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_barcode), contentDescription = null)
                    Text(stringResource(R.string.recipe_add_scan), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    editing?.let { i ->
        val ing = d.ingredients.getOrNull(i)
        if (ing == null) editing = null else AmountDialog(ing, onDismiss = { editing = null }, onDone = { q -> onQuantity(i, q); editing = null })
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.recipe_discard_title)) },
            text = { Text(stringResource(R.string.recipe_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(stringResource(R.string.recipe_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.recipe_keep_editing)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recipe_delete_confirm_title, d.name)) },
            text = { Text(stringResource(R.string.recipe_delete_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Summary(r: app.forkcast.core.domain.RecipeMath.Result, f: UnitFormatter) {
    val c = ForkcastTheme.colors
    val n = r.perServing
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(R.string.recipe_per_serving), style = MaterialTheme.typography.titleSmall)
        Text(f.energy(n[NutrientKey.ENERGY_KCAL] ?: 0.0), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        // Wraps whole items at large font sizes instead of breaking "0.2 g" across lines.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(
                Triple(R.string.macro_protein, NutrientKey.PROTEIN_G, c.proteinText),
                Triple(R.string.macro_carbs, NutrientKey.CARBS_G, c.carbsText),
                Triple(R.string.macro_fat, NutrientKey.FAT_G, c.fatText),
            ).forEach { (label, key, color) ->
                Text(
                    stringResource(R.string.macro_amount, stringResource(label), f.grams(n[key] ?: 0.0)),
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(stringResource(R.string.recipe_per_serving_weight, f.grams(r.servingGrams)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.recipe_whole, f.energy(r.total[NutrientKey.ENERGY_KCAL] ?: 0.0)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IngredientRow(ing: DraftIngredient, f: UnitFormatter, onEdit: () -> Unit, onRemove: () -> Unit) {
    val editLabel = stringResource(R.string.recipe_ingredient_edit, ing.displayName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = editLabel, onClick = onEdit)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(ing.displayName, style = MaterialTheme.typography.bodyLarge)
            val amount = servingText(ing.quantity, ing.servingLabel)
            val withGrams = if (ing.servingLabel == "g") amount else "$amount · ${f.grams(ing.grams)}"
            Text(
                listOfNotNull(ing.brand, withGrams).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(f.energy(ing.nutrients[NutrientKey.ENERGY_KCAL] ?: 0.0), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_remove_circle), stringResource(R.string.recipe_ingredient_remove, ing.displayName))
        }
    }
}

@Composable
private fun AmountDialog(ing: DraftIngredient, onDismiss: () -> Unit, onDone: (Double) -> Unit) {
    var value by remember { mutableStateOf<Double?>(ing.quantity) }
    val unit = ing.servingLabel
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ing.displayName) },
        text = {
            NumberField(
                initial = UnitFormatter.trimNumber(ing.quantity, 2),
                label = stringResource(R.string.recipe_ingredient_amount),
                suffix = unit.takeIf { it == "g" || it == "oz" || it == "ml" || it == "fl oz" } ?: "× $unit",
                onValue = { value = it },
                isError = (value ?: 0.0) <= 0,
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.takeIf { it > 0 }?.let(onDone) }, enabled = (value ?: 0.0) > 0) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
