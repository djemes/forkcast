package app.forkcast.ui.today

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.MealSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TodayRoute(
    onOpenSettings: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    state?.let { TodayScreen(state = it, onEvent = viewModel::onEvent, onOpenSettings = onOpenSettings) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Null = sheet closed; otherwise the meal the sheet logs to.
    var logSheetMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            DateHeader(
                state = state,
                onEvent = onEvent,
                onPickDate = { showDatePicker = true },
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            val logLabel = stringResource(R.string.log)
            ExtendedFloatingActionButton(
                onClick = { logSheetMealId = state.defaultMealId ?: state.meals.firstOrNull()?.id },
                // The M3 extended FAB doesn't merge its text into the button node; label it for TalkBack.
                modifier = Modifier.semantics { contentDescription = logLabel },
                // Pine, like every primary action (DESIGN.md §2), rather than the pale M3 default.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.log)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                // Room for the FAB so it never covers the last meal.
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item { BudgetPlaceholder() }
            items(state.meals, key = { it.id }) { meal ->
                MealSection(meal = meal, onAdd = { logSheetMealId = meal.id })
            }
        }
    }

    logSheetMealId?.let { mealId ->
        LogSheet(
            meals = state.meals,
            selectedMealId = mealId,
            onSelectMeal = { logSheetMealId = it },
            onDismiss = { logSheetMealId = null },
        )
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
private fun BudgetPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.budget_placeholder_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.budget_placeholder_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MealSection(meal: MealSlot, onAdd: () -> Unit) {
    val addDescription = stringResource(R.string.add_to_meal_description, meal.name)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = meal.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onAdd, modifier = Modifier.semantics { contentDescription = addDescription }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_to_meal))
            }
        }
        Text(
            text = stringResource(R.string.meal_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
