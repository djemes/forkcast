package app.forkcast.ui.today

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.data.db.LoggedDay
import app.forkcast.ui.format.UnitFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
    val openHealthConnect: () -> Unit = {},
    /** Opens the Activity detail on [date]. */
    val openActivity: (date: java.time.LocalDate) -> Unit = {},
    val openTrends: () -> Unit = {},
    /** Opens the recipe list to log into [mealSlotId] on [date]. */
    val openRecipes: ((mealSlotId: String, date: java.time.LocalDate) -> Unit)? = null,
)

@Composable
fun TodayRoute(
    actions: TodayActions,
    addedToMeal: String? = null,
    onAddedShown: () -> Unit = {},
    /** A day to show, from Trends' "Open day" (ISO date). */
    openDate: String? = null,
    onOpenDateShown: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(openDate) {
        openDate?.let { iso -> runCatching { LocalDate.parse(iso) }.getOrNull()?.let { viewModel.onEvent(TodayEvent.PickDate(it)) } }
        if (openDate != null) onOpenDateShown()
    }
    val editServings by viewModel.editServingsFor.collectAsStateWithLifecycle()
    // Left open overnight, the screen moves on to the new day (unless you were looking at another one).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(TodayEvent.Resumed) }
    state?.let {
        TodayScreen(
            state = it,
            onEvent = viewModel::onEvent,
            actions = actions,
            effects = viewModel.effects,
            addedToMeal = addedToMeal,
            onAddedShown = onAddedShown,
            editServings = editServings,
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
    /** Servings loaded for the entry being edited (entry id → servings). */
    editServings: Pair<String, List<app.forkcast.core.model.ServingSize>>? = null,
) {
    // Null = sheet closed; otherwise the meal the sheet logs to.
    var logSheetMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showNutrients by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(editingId) { editingId?.let { onEvent(TodayEvent.LoadEditServings(it)) } }
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

    // Back clears a selection first, then returns to today from another day, then leaves the app.
    BackHandler(enabled = state.isSelecting) { onEvent(TodayEvent.ClearSelection) }
    BackHandler(enabled = !state.isSelecting && !state.isToday) { onEvent(TodayEvent.GoToToday) }

    // Horizontal drag offset while swiping between days: the page follows the finger a little.
    var dragX by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val canGoForward = rememberUpdatedState(state.date < state.today)

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
            else DateHeader(state, onEvent, onPickDate = { showDatePicker = true }, onOpenSettings = actions.openSettings, onOpenTrends = actions.openTrends)
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
        // The new day slides in from the side you swiped toward; the arrows and Back use the same motion.
        AnimatedContent(
            targetState = state,
            contentKey = { it.date },
            transitionSpec = {
                val forward = targetState.date > initialState.date
                val slide = tween<IntOffset>(DAY_SLIDE_MS, easing = FastOutSlowInEasing)
                (slideInHorizontally(slide) { w -> if (forward) w else -w } + fadeIn(tween(DAY_SLIDE_MS))) togetherWith
                    (slideOutHorizontally(slide) { w -> if (forward) -w else w } + fadeOut(tween(DAY_SLIDE_MS / 2)))
            },
            label = "day",
            modifier = Modifier
                .clipToBounds()
                .pointerInput(state.isSelecting) {
                    if (state.isSelecting) return@pointerInput
                    val threshold = DAY_SWIPE_THRESHOLD.toPx()
                    val velocity = VelocityTracker()
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            total = 0f
                            velocity.resetTracking()
                        },
                        onDragEnd = {
                            val fling = abs(velocity.calculateVelocity().x) > DAY_FLING_VELOCITY && abs(total) > threshold / 3
                            val blocked = total < 0 && !canGoForward.value
                            if (!blocked && (abs(total) > threshold || fling)) {
                                dragX = 0f
                                currentOnEvent(if (total < 0) TodayEvent.NextDay else TodayEvent.PreviousDay)
                            } else {
                                dragScope.launch { animate(dragX, 0f) { v, _ -> dragX = v } }
                            }
                        },
                        onDragCancel = { dragScope.launch { animate(dragX, 0f) { v, _ -> dragX = v } } },
                    ) { change, dx ->
                        total += dx
                        velocity.addPosition(change.uptimeMillis, change.position)
                        // Today is the last day: swiping toward the future doesn't move the page.
                        dragX = if (total < 0 && !canGoForward.value) 0f else total * DAY_DRAG_FOLLOW
                    }
                },
        ) { day ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = if (day.date == state.date) dragX else 0f },
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
                    item(key = "ring") { BudgetRing(totals, f, Modifier.fillMaxWidth(), day.forecast, day.forecastFromMinute) }
                    item(key = "macros") { MacroBars(totals, targets, f) { showNutrients = true } }
                }
                val last = day.lastLogged
                if (day.isEmptyDay && last != null) {
                    item(key = "last-logged") { LastLoggedRow(last, day.today) { onEvent(TodayEvent.PickDate(last.date)) } }
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
                        onAddAmount = { ml -> onEvent(TodayEvent.AddWater(ml)) },
                    )
                }
                item(key = "weight") { WeightCard(day.weight, f, actions.openWeight) }
                item(key = "activity") { ActivityCard(day.activity, f, actions.openHealthConnect) { actions.openActivity(day.date) } }
                if (!day.date.isAfter(day.today) && !day.isEmptyDay) {
                    item(key = "completeness") {
                        DayCompletenessRow(day.completeness, day.autoComplete) { onEvent(TodayEvent.SetCompleteness(it)) }
                    }
                }
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
                recipes = actions.openRecipes?.let { open -> { logSheetMealId = null; open(mealId, state.date) } },
            ),
            recents = state.recents,
            recentsAreFavorites = state.recentsAreFavorites,
            f = f,
            onAddRecent = { food ->
                logSheetMealId = null
                onEvent(TodayEvent.AddRecent(food, mealId))
            },
            dayLabel = if (state.isToday) null else shortDayLabel(state.date, state.today),
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
                onSave = { q, meal, water, serving -> onEvent(TodayEvent.UpdateEntry(entry, q, meal, water, serving)); editingId = null },
                servings = editServings?.takeIf { it.first == entry.id }?.second ?: FoodLogging.editServings(entry, null),
                onDelete = { onEvent(TodayEvent.Delete(listOf(entry.id))); editingId = null },
                onDismiss = { editingId = null },
            )
        }
    }
    if (showDatePicker) {
        DatePickerDialogFor(
            initial = state.date,
            latest = state.today,
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

/**
 * Day header: ‹ [day ▾] › with the date under the name. On any day but today the name sits on a
 * tinted pill and a "Today" button appears, so it's always obvious which day is on screen.
 * The height never changes between days, so the page doesn't jump.
 */
@Composable
private fun DateHeader(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onPickDate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrends: () -> Unit,
) {
    val (title, subtitle) = dateLabels(state.date, state.today)
    val pickLabel = stringResource(R.string.pick_date)
    val otherDay = !state.isToday
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = { onEvent(TodayEvent.PreviousDay) }) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.previous_day))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (otherDay) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(onClickLabel = pickLabel, role = Role.Button, onClick = onPickDate)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    // TalkBack announces the new day after an arrow, a swipe or Back.
                    .semantics(mergeDescendants = true) {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (otherDay) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = if (otherDay) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // No future days: the arrow stops at today (the swipe and the picker do too).
        IconButton(onClick = { onEvent(TodayEvent.NextDay) }, enabled = state.date < state.today) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.next_day))
        }
        // At large font sizes the "Today" button drops its word so the day name keeps one line.
        val roomy = LocalDensity.current.fontScale <= 1.3f
        if (otherDay && !roomy) {
            IconButton(onClick = { onEvent(TodayEvent.GoToToday) }) {
                Icon(painterResource(R.drawable.ic_today), stringResource(R.string.go_to_today))
            }
        } else if (otherDay) {
            val goLabel = stringResource(R.string.go_to_today)
            TextButton(
                onClick = { onEvent(TodayEvent.GoToToday) },
                modifier = Modifier.semantics { contentDescription = goLabel },
            ) {
                Icon(painterResource(R.drawable.ic_today), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.today))
            }
        } else {
            // On today the day name already opens the calendar, so this slot is Trends (owner, 2026-10-19).
            IconButton(onClick = onOpenTrends) {
                Icon(painterResource(R.drawable.ic_trends), stringResource(R.string.open_trends))
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.open_settings))
        }
    }
}

/** ("Today", "Mon, Oct 19"), ("Yesterday", "Sun, Oct 18"), ("Sat, Oct 17", "2 days ago"). */
@Composable
private fun dateLabels(date: LocalDate, today: LocalDate): Pair<String, String> {
    val locale = LocalLocale.current.platformLocale
    val skeleton = if (date.year == today.year) "EEEMMMd" else "EEEMMMdy"
    val formatted = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when (days) {
        0 -> stringResource(R.string.today) to formatted
        -1 -> stringResource(R.string.yesterday) to formatted
        1 -> stringResource(R.string.tomorrow) to formatted
        else -> formatted to if (days < 0) {
            pluralStringResource(R.plurals.days_ago, -days, -days)
        } else {
            pluralStringResource(R.plurals.days_ahead, days, days)
        }
    }
}

/** "Yesterday" / "Today" / "Sat, Oct 17", for the last-logged hint. */
@Composable
private fun shortDayLabel(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
    0L -> stringResource(R.string.today)
    -1L -> stringResource(R.string.yesterday)
    1L -> stringResource(R.string.tomorrow)
    else -> shortDate(date, LocalLocale.current.platformLocale)
}

/**
 * On an empty day: where the last log is ("Last logged yesterday · 6 entries", Show). Today
 * only shows the day on screen, so a morning after logging late looks empty without this.
 */
@Composable
private fun LastLoggedRow(last: LoggedDay, today: LocalDate, onShow: () -> Unit) {
    val dayText = shortDayLabel(last.date, today)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onShow)
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_history),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(stringResource(R.string.day_empty), style = MaterialTheme.typography.bodyMedium)
            Text(
                pluralStringResource(R.plurals.last_logged, last.entries, dayText, last.entries),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onShow) { Text(stringResource(R.string.last_logged_show)) }
    }
}

private const val DAY_SLIDE_MS = 280
private val DAY_SWIPE_THRESHOLD = 96.dp
/** Pixels per second; a quick flick changes the day even if it's short. */
private const val DAY_FLING_VELOCITY = 1_200f
/** How far the page follows the finger while swiping (fraction of the drag). */
private const val DAY_DRAG_FOLLOW = 0.35f

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
private fun DatePickerDialogFor(initial: LocalDate, latest: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material date picker works in UTC midnight millis.
    val latestMillis = latest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 2000..latest.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latestMillis
            override fun isSelectableYear(year: Int) = year <= latest.year
        },
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
