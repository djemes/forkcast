package app.forkcast.ui.today

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.ui.format.UnitFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Where the Log sheet and other Today actions lead. Filled in as Phase 1 features land. */
data class TodayActions(
    val openSettings: () -> Unit = {},
    /** Opens food search to log into [mealSlotId] on [date]. */
    val openSearch: ((mealSlotId: String, date: java.time.LocalDate) -> Unit)? = null,
    /** Opens the barcode scanner to log into [mealSlotId] on [date]. */
    val openScanner: ((mealSlotId: String, date: java.time.LocalDate) -> Unit)? = null,
    val openWeight: () -> Unit = {},
    /** Opens the photo-meal camera for [mealSlotId] on [date]. */
    val openPhoto: ((mealSlotId: String, date: java.time.LocalDate) -> Unit)? = null,
    /** Opens the review screen of a photo estimate. */
    val openEstimate: (estimateId: String) -> Unit = {},
)

@Composable
fun TodayRoute(
    actions: TodayActions,
    addedToMeal: String? = null,
    onAddedShown: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    state?.let {
        TodayScreen(
            state = it,
            onEvent = viewModel::onEvent,
            actions = actions,
            effects = viewModel.effects,
            addedToMeal = addedToMeal,
            onAddedShown = onAddedShown,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    actions: TodayActions = TodayActions(),
    effects: Flow<TodayEffect> = emptyFlow(),
    addedToMeal: String? = null,
    onAddedShown: () -> Unit = {},
) {
    // Null = sheet closed; otherwise the meal the sheet logs to.
    var logSheetMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showNutrients by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var quickAddMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedMealsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var copyRequest by remember { mutableStateOf<CopyRequest?>(null) }
    var saveRequest by remember { mutableStateOf<SaveRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale)
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val currentOnEvent by rememberUpdatedState(onEvent)

    // Confirmation after adding from food detail (DESIGN.md section 9: "Add to Lunch" then "Added to Lunch").
    // The snackbar runs in its own scope: clearing the trigger re-keys this effect, which would
    // otherwise cancel the message before it appears.
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(addedToMeal) {
        if (addedToMeal != null) {
            val message = resources.getString(R.string.food_added_to, addedToMeal)
            snackbarScope.launch { snackbar.showSnackbar(message) }
            onAddedShown()
        }
    }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is TodayEffect.Added -> snackbar.showSnackbar(resources.getString(R.string.food_added_to, effect.mealName))
                is TodayEffect.Copied -> snackbar.showSnackbar(
                    resources.getQuantityString(R.plurals.copied, effect.count, effect.count, shortDate(effect.toDate, locale)),
                )
                is TodayEffect.SavedMealCreated -> snackbar.showSnackbar(resources.getString(R.string.saved_meal_done, effect.name))
                is TodayEffect.Deleted -> {
                    val msg = effect.label?.let { resources.getString(R.string.deleted_one, it) }
                        ?: resources.getQuantityString(R.plurals.deleted_many, effect.ids.size, effect.ids.size)
                    val result = snackbar.showSnackbar(msg, resources.getString(R.string.undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) currentOnEvent(TodayEvent.Undo(effect.ids))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (state.isSelecting) {
                SelectionBar(
                    count = state.selection.size,
                    onEvent = onEvent,
                    onCopy = { copyRequest = CopyRequest(state.selection.toList(), allowSameMeal = true, from = state.date) },
                    onSave = { saveRequest = SaveRequest(state.selection.toList(), "") },
                )
            }
            else DateHeader(state, onEvent, onPickDate = { showDatePicker = true }, onOpenSettings = actions.openSettings)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.isSelecting) {
                val logLabel = stringResource(R.string.log)
                ExtendedFloatingActionButton(
                    onClick = { logSheetMealId = state.defaultMealId ?: state.meals.firstOrNull()?.slot?.id },
                    // The M3 extended FAB doesn't merge its text into the button node; label it for TalkBack.
                    modifier = Modifier.semantics { contentDescription = logLabel },
                    // Pine, like every primary action (DESIGN.md §2), rather than the pale M3 default.
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.log)) },
                )
            }
        },
    ) { padding ->
        // Swipe left/right anywhere not taken by an entry row changes the day (rows use swipe for delete).
        AnimatedContent(
            targetState = state,
            contentKey = { it.date },
            transitionSpec = {
                val forward = targetState.date > initialState.date
                slideInHorizontally { if (forward) it / 3 else -it / 3 } togetherWith
                    slideOutHorizontally { if (forward) -it / 3 else it / 3 }
            },
            label = "day",
            modifier = Modifier.pointerInput(state.isSelecting) {
                if (state.isSelecting) return@pointerInput
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > 120.dp.toPx()) currentOnEvent(if (total < 0) TodayEvent.NextDay else TodayEvent.PreviousDay)
                    },
                ) { _, dx -> total += dx }
            },
        ) { day ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    // Room for the FAB so it never covers the last meal.
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val totals = day.totals
                val targets = day.targets
                if (totals != null && targets != null) {
                    item(key = "ring") { BudgetRing(totals, f, Modifier.fillMaxWidth()) }
                    item(key = "macros") { MacroBars(totals, targets, f) { showNutrients = true } }
                }
                items(day.meals, key = { it.slot.id }) { meal ->
                    MealSectionView(
                        meal = meal,
                        f = f,
                        selection = day.selection,
                        onAdd = { logSheetMealId = meal.slot.id },
                        onEntryClick = { id ->
                            val estimateId = meal.entries.firstOrNull { it.id == id }?.aiEstimateId
                            when {
                                day.isSelecting -> onEvent(TodayEvent.ToggleSelect(id))
                                estimateId != null -> actions.openEstimate(estimateId)
                                else -> editingId = id
                            }
                        },
                        unreviewed = day.unreviewedEstimateIds,
                        onPendingClick = actions.openEstimate,
                        onEntryLongClick = { id -> onEvent(TodayEvent.ToggleSelect(id)) },
                        onDelete = { id -> onEvent(TodayEvent.Delete(listOf(id))) },
                        onCopyMeal = { copyRequest = CopyRequest(meal.entries.map { it.id }, allowSameMeal = true, from = day.date) },
                        onSaveMeal = { saveRequest = SaveRequest(meal.entries.map { it.id }, meal.slot.name) },
                        onCopyDay = { copyRequest = CopyRequest(day.allEntries.map { it.id }, allowSameMeal = true, from = day.date, wholeDay = true) },
                        dayHasEntries = day.allEntries.isNotEmpty(),
                        showSameAsYesterday = meal.slot.id in day.yesterdayMealIds,
                        onSameAsYesterday = { onEvent(TodayEvent.SameAsYesterday(meal.slot.id)) },
                    )
                }
                item(key = "water") {
                    WaterCard(
                        water = day.water,
                        f = f,
                        onAddGlass = { onEvent(TodayEvent.AddWater(day.water.glassMl)) },
                        onRemoveLast = { onEvent(TodayEvent.RemoveLastWater) },
                    )
                }
                item(key = "weight") { WeightCard(day.weight, f, actions.openWeight) }
            }
        }
    }

    logSheetMealId?.let { mealId ->
        LogSheet(
            meals = state.meals.map { it.slot },
            selectedMealId = mealId,
            onSelectMeal = { logSheetMealId = it },
            onDismiss = { logSheetMealId = null },
            actions = LogSheetActions(
                search = actions.openSearch?.let { open -> { logSheetMealId = null; open(mealId, state.date) } },
                scan = actions.openScanner?.let { open -> { logSheetMealId = null; open(mealId, state.date) } },
                photo = actions.openPhoto?.let { open -> { logSheetMealId = null; open(mealId, state.date) } },
                quickAdd = { logSheetMealId = null; quickAddMealId = mealId },
                savedMeals = { logSheetMealId = null; savedMealsFor = mealId },
            ),
            recents = state.recents,
            f = f,
            onAddRecent = { food ->
                logSheetMealId = null
                onEvent(TodayEvent.AddRecent(food, mealId))
            },
        )
    }
    quickAddMealId?.let { mealId ->
        QuickAddSheet(
            mealName = state.meals.firstOrNull { it.slot.id == mealId }?.slot?.name.orEmpty(),
            f = f,
            onAdd = { label, kcal, p, c, fat ->
                quickAddMealId = null
                onEvent(TodayEvent.QuickAdd(mealId, label, kcal, p, c, fat))
            },
            onDismiss = { quickAddMealId = null },
        )
    }
    savedMealsFor?.let { mealId ->
        SavedMealsSheet(
            meals = state.savedMeals,
            mealName = state.meals.firstOrNull { it.slot.id == mealId }?.slot?.name.orEmpty(),
            f = f,
            onLog = { saved -> savedMealsFor = null; onEvent(TodayEvent.LogSavedMeal(saved.id, mealId)) },
            onDelete = { saved -> onEvent(TodayEvent.DeleteSavedMeal(saved.id)) },
            onDismiss = { savedMealsFor = null },
        )
    }
    copyRequest?.let { req ->
        CopyDialog(
            initialDate = req.from.plusDays(1),
            meals = state.meals.map { it.slot },
            allowSameMeal = req.allowSameMeal,
            onCopy = { date, meal -> copyRequest = null; onEvent(TodayEvent.Copy(req.ids, date, meal)) },
            onDismiss = { copyRequest = null },
        )
    }
    saveRequest?.let { req ->
        SaveMealDialog(
            suggestedName = req.suggestedName,
            onSave = { name -> saveRequest = null; onEvent(TodayEvent.SaveAsMeal(req.ids, name)) },
            onDismiss = { saveRequest = null },
        )
    }
    if (showNutrients && state.totals != null && state.targets != null) {
        NutrientPanelSheet(state.totals, state.targets, f) { showNutrients = false }
    }
    editingId?.let { id ->
        val entry = state.allEntries.firstOrNull { it.id == id }
        if (entry == null) {
            editingId = null
        } else {
            EditEntrySheet(
                entry = entry,
                meals = state.meals.map { it.slot },
                f = f,
                onSave = { q, meal -> onEvent(TodayEvent.UpdateEntry(entry, q, meal)); editingId = null },
                onDelete = { onEvent(TodayEvent.Delete(listOf(entry.id))); editingId = null },
                onDismiss = { editingId = null },
            )
        }
    }
    if (showDatePicker) {
        DatePickerDialogFor(
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onPick = {
                showDatePicker = false
                onEvent(TodayEvent.PickDate(it))
            },
        )
    }
}

private data class CopyRequest(val ids: List<String>, val allowSameMeal: Boolean, val from: LocalDate, val wholeDay: Boolean = false)
private data class SaveRequest(val ids: List<String>, val suggestedName: String)

private fun shortDate(date: LocalDate, locale: java.util.Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(date)

@Composable
private fun SelectionBar(count: Int, onEvent: (TodayEvent) -> Unit, onCopy: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = { onEvent(TodayEvent.ClearSelection) }) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_selection))
        }
        Text(
            pluralStringResource(R.plurals.selected_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) {
            Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.action_copy))
        }
        IconButton(onClick = onSave) {
            Icon(painterResource(R.drawable.ic_bookmark), stringResource(R.string.action_save_as_meal))
        }
        IconButton(onClick = { onEvent(TodayEvent.DeleteSelected) }) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
        }
    }
}

@Composable
private fun DateHeader(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onPickDate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val title = dateTitle(state.date, state.today)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onEvent(TodayEvent.PreviousDay) }) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.previous_day))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.pick_date), onClick = onPickDate)
                    .padding(vertical = 8.dp)
                    .semantics { heading() },
            )
            IconButton(onClick = { onEvent(TodayEvent.NextDay) }) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.next_day))
            }
            IconButton(onClick = onPickDate) {
                Icon(painterResource(R.drawable.ic_calendar), stringResource(R.string.pick_date))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.open_settings))
            }
        }
        if (!state.isToday) {
            TextButton(
                onClick = { onEvent(TodayEvent.GoToToday) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.go_to_today)) }
        }
    }
}

@Composable
private fun dateTitle(date: LocalDate, today: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    val sameYear = date.year == today.year
    val relative = when (date) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        today.plusDays(1) -> stringResource(R.string.tomorrow)
        else -> null
    }
    val skeleton = when {
        relative != null -> "MMMd"
        sameYear -> "EEEMMMd"
        else -> "EEEMMMdy"
    }
    val formatted = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    return if (relative != null) stringResource(R.string.today_date_header, relative, formatted) else formatted
}

@Composable
private fun MealSectionView(
    meal: MealSection,
    f: UnitFormatter,
    selection: Set<String>,
    onAdd: () -> Unit,
    onEntryClick: (String) -> Unit,
    onEntryLongClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCopyMeal: () -> Unit = {},
    onSaveMeal: () -> Unit = {},
    onCopyDay: () -> Unit = {},
    dayHasEntries: Boolean = false,
    showSameAsYesterday: Boolean = false,
    onSameAsYesterday: () -> Unit = {},
    unreviewed: Set<String> = emptySet(),
    onPendingClick: (String) -> Unit = {},
) {
    val addDescription = stringResource(R.string.add_to_meal_description, meal.slot.name)
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp).padding(start = 12.dp)) {
            Text(
                text = meal.slot.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (meal.entries.isNotEmpty()) {
                Text(f.energy(meal.kcal), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAdd, modifier = Modifier.semantics { contentDescription = addDescription }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_to_meal))
            }
            if (meal.entries.isNotEmpty() || dayHasEntries) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.meal_more, meal.slot.name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (meal.entries.isNotEmpty()) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.meal_copy)) }, onClick = { menuOpen = false; onCopyMeal() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.meal_save_as)) }, onClick = { menuOpen = false; onSaveMeal() })
                        }
                        if (dayHasEntries) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.day_copy)) }, onClick = { menuOpen = false; onCopyDay() })
                        }
                    }
                }
            }
        }
        if (meal.entries.isEmpty() && meal.pending.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.meal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (showSameAsYesterday) {
                    TextButton(onClick = onSameAsYesterday) { Text(stringResource(R.string.same_as_yesterday)) }
                }
            }
        }
        meal.entries.forEach { e ->
            androidx.compose.runtime.key(e.id) {
                EntryRow(
                    entry = e,
                    f = f,
                    selecting = selection.isNotEmpty(),
                    selected = e.id in selection,
                    onClick = { onEntryClick(e.id) },
                    onLongClick = { onEntryLongClick(e.id) },
                    onDelete = { onDelete(e.id) },
                    needsReview = e.aiEstimateId != null && e.aiEstimateId in unreviewed,
                )
            }
        }
        meal.pending.forEach { p ->
            androidx.compose.runtime.key(p.id) { PendingEstimateRow(p, f) { onPendingClick(p.id) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogFor(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material date picker works in UTC midnight millis.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis
                if (millis != null) onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) else onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}
