package app.forkcast.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.NotificationRules
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NotificationSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class NotificationsUiState(val settings: NotificationSettings, val slots: List<MealSlot>) {
    val meals: List<NotificationRules.Meal>
        get() = slots.map { NotificationRules.Meal(it.id, it.name, it.endMinute ?: NotificationRules.DEFAULT_DINNER_MINUTE) }

    /** Reminder time per enabled meal: the user's setup, or the defaults (Lunch 13:30, Dinner 20:00). */
    val mealTimes: Map<String, Int>
        get() = NotificationRules.effectiveMeals(settings.mealReminderMinutes, settings.mealReminderOffMinutes, meals)

    /** A disabled meal's remembered time, else its window end. */
    fun timeFor(slot: MealSlot): Int =
        mealTimes[slot.id] ?: settings.mealReminderOffMinutes[slot.id] ?: slot.endMinute ?: NotificationRules.DEFAULT_DINNER_MINUTE

    val reminders: NotificationRules.MealReminders
        get() = NotificationRules.MealReminders(settings.mealRemindersEnabled, settings.mealReminderMinutes, settings.mealReminderOffMinutes)
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    slots: MealSlotRepository,
) : ViewModel() {
    val state: StateFlow<NotificationsUiState?> = combine(settings.settings, slots.observeMealSlots()) { s, m ->
        NotificationsUiState(s.notifications, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(change: (NotificationSettings) -> NotificationSettings) = viewModelScope.launch { settings.updateNotifications(change) }

    fun setMealTime(slotId: String, minute: Int) = update { s ->
        val st = state.value ?: return@update s
        s.copy(mealReminderMinutes = st.mealTimes + (slotId to minute))
    }

    /** One meal switched; switching off the last one turns the category off (owner, 2026-10-19). */
    fun setMeal(slotId: String, on: Boolean) = update { s ->
        val st = state.value ?: return@update s
        s.with(NotificationRules.setMeal(st.reminders, st.meals, slotId, on))
    }

    /** The category switched: off keeps each meal's setup, on restores it. */
    fun setMealCategory(on: Boolean) = update { s ->
        val st = state.value ?: return@update s
        s.with(NotificationRules.setCategory(st.reminders, st.meals, on))
    }

    private fun NotificationSettings.with(r: NotificationRules.MealReminders) =
        copy(mealRemindersEnabled = r.categoryOn, mealReminderMinutes = r.enabled, mealReminderOffMinutes = r.off)
}

private val THRESHOLD_CHOICES = listOf(50, 80, 90, 100, 110, 125)

@Composable
fun NotificationsRoute(onBack: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    NotificationsScreen(
        s,
        onUpdate = { viewModel.update(it) },
        onMealTime = viewModel::setMealTime,
        onMeal = viewModel::setMeal,
        onMealCategory = viewModel::setMealCategory,
        onBack = onBack,
    )
}

@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onUpdate: ((NotificationSettings) -> NotificationSettings) -> Unit,
    onMealTime: (String, Int) -> Unit,
    onMeal: (String, Boolean) -> Unit,
    onMealCategory: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val s = state.settings
    var systemEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemEnabled = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
        onUpdate { it.copy(permissionAsked = true) }
    }
    /** Turning a notification on asks for the permission in context, once (SPEC §4.8). */
    fun enable(on: Boolean, change: (NotificationSettings) -> NotificationSettings) {
        onUpdate(change)
        if (on && Build.VERSION.SDK_INT >= 33 && !s.permissionAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }

    SettingsScaffold(title = stringResource(R.string.settings_notifications), onBack = onBack) {
        if (!systemEnabled && s.permissionAsked) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nset_permission_off), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.nset_open_system)) }
            }
        }

        ToggleRow(R.string.nset_budget, R.string.nset_budget_body, s.budgetEnabled) { on -> enable(on) { it.copy(budgetEnabled = on) } }
        if (s.budgetEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 32.dp, end = 16.dp)) {
                for (t in THRESHOLD_CHOICES) {
                    FilterChip(
                        selected = t in s.budgetThresholds,
                        onClick = {
                            onUpdate { cur ->
                                val next = if (t in cur.budgetThresholds) cur.budgetThresholds - t else (cur.budgetThresholds + t).sorted()
                                cur.copy(budgetThresholds = next)
                            }
                        },
                        label = { Text(stringResource(R.string.nset_threshold, t)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Each reminder's time sits on its own row, next to its switch (owner, 2026-10-19).
        val eveningTitle = stringResource(R.string.nset_evening)
        ToggleRow(R.string.nset_evening, R.string.nset_evening_body, s.eveningEnabled, minute = s.eveningMinute,
            onTime = { editing = TimeEdit(eveningTitle, s.eveningMinute) { m -> onUpdate { c -> c.copy(eveningMinute = m) } } },
        ) { on -> enable(on) { it.copy(eveningEnabled = on) } }

        val proteinTitle = stringResource(R.string.nset_protein)
        ToggleRow(R.string.nset_protein, R.string.nset_protein_body, s.proteinEnabled, minute = s.proteinMinute,
            onTime = { editing = TimeEdit(proteinTitle, s.proteinMinute) { m -> onUpdate { c -> c.copy(proteinMinute = m) } } },
        ) { on -> enable(on) { it.copy(proteinEnabled = on) } }

        val weighTitle = stringResource(R.string.nset_weigh_in)
        ToggleRow(R.string.nset_weigh_in, R.string.nset_weigh_in_body, s.weighInEnabled, minute = s.weighInMinute,
            onTime = { editing = TimeEdit(weighTitle, s.weighInMinute) { m -> onUpdate { c -> c.copy(weighInMinute = m) } } },
        ) { on -> enable(on) { it.copy(weighInEnabled = on) } }

        val weeklyTitle = stringResource(R.string.nset_weekly)
        ToggleRow(R.string.nset_weekly, R.string.nset_weekly_body, s.weeklyEnabled, minute = s.weeklyMinute,
            onTime = { editing = TimeEdit(weeklyTitle, s.weeklyMinute) { m -> onUpdate { c -> c.copy(weeklyMinute = m) } } },
        ) { on -> enable(on) { it.copy(weeklyEnabled = on) } }
        if (s.weeklyEnabled) {
            var pickDay by remember { mutableStateOf(false) }
            val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
            val dayLabel = stringResource(R.string.nset_weekly_day)
            val dayName = java.time.DayOfWeek.of(s.weeklyDay.coerceIn(1, 7)).getDisplayName(java.time.format.TextStyle.FULL, locale)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 32.dp, end = 16.dp),
            ) {
                Text(dayLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = { pickDay = true }, label = { Text(dayName) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (pickDay) {
                AlertDialog(
                    onDismissRequest = { pickDay = false },
                    title = { Text(dayLabel) },
                    text = {
                        Column {
                            for (d in java.time.DayOfWeek.entries) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(selected = d.value == s.weeklyDay, role = Role.RadioButton) {
                                            onUpdate { c -> c.copy(weeklyDay = d.value) }
                                            pickDay = false
                                        },
                                ) {
                                    RadioButton(selected = d.value == s.weeklyDay, onClick = null)
                                    Text(d.getDisplayName(java.time.format.TextStyle.FULL, locale), modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { pickDay = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }

        ToggleRow(R.string.nset_meals, R.string.nset_meals_body, s.mealRemindersEnabled) { on ->
            onMealCategory(on)
            if (on) enable(true) { it }
        }
        if (s.mealRemindersEnabled) {
            for (slot in state.slots) {
                val minute = state.mealTimes[slot.id]
                MealRow(
                    name = slot.name,
                    on = minute != null,
                    minute = minute ?: state.timeFor(slot),
                    onToggle = { on -> onMeal(slot.id, on) },
                    onTime = { editing = TimeEdit(slot.name, minute ?: state.timeFor(slot)) { m -> onMealTime(slot.id, m) } },
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(R.string.nset_estimate_ready, R.string.nset_estimate_ready_body, s.estimateReadyEnabled) { on -> enable(on) { it.copy(estimateReadyEnabled = on) } }
        ToggleRow(R.string.nset_backup_failed, R.string.nset_backup_failed_body, s.backupFailedEnabled) { on -> enable(on) { it.copy(backupFailedEnabled = on) } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            stringResource(R.string.nset_quiet_hours),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.nset_quiet_body, timeText(s.quietStartMinute), timeText(s.quietEndMinute)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val startLabel = stringResource(R.string.nset_quiet_start)
        val endLabel = stringResource(R.string.nset_quiet_end)
        LabeledTime(startLabel, s.quietStartMinute) { editing = TimeEdit(startLabel, s.quietStartMinute) { m -> onUpdate { c -> c.copy(quietStartMinute = m) } } }
        LabeledTime(endLabel, s.quietEndMinute) { editing = TimeEdit(endLabel, s.quietEndMinute) { m -> onUpdate { c -> c.copy(quietEndMinute = m) } } }
    }

    editing?.let { e -> TimeDialog(e, onDismiss = { editing = null }) }
}

private class TimeEdit(val title: String, val minute: Int, val onPick: (Int) -> Unit)

/**
 * A notification with its switch and, when it has one, its time on the same row. At large font
 * sizes the time moves under the description, still inside the row.
 */
@Composable
private fun ToggleRow(
    title: Int,
    body: Int,
    checked: Boolean,
    minute: Int? = null,
    onTime: () -> Unit = {},
    onChange: (Boolean) -> Unit,
) {
    val roomy = LocalDensity.current.fontScale <= 1.3f
    val name = stringResource(title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (minute != null && checked && !roomy) TimeChip(name, minute, onTime)
        }
        if (minute != null && checked && roomy) TimeChip(name, minute, onTime)
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** One meal under "Meal reminders": name, time and switch on one row. */
@Composable
private fun MealRow(name: String, on: Boolean, minute: Int, onToggle: (Boolean) -> Unit, onTime: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .padding(start = 32.dp, end = 16.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (on) TimeChip(name, minute, onTime)
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The reminder's time; tapping it opens the time picker. */
@Composable
private fun TimeChip(what: String, minute: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.nset_change_time, what)
    AssistChip(
        onClick = onClick,
        label = { Text(timeText(minute)) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    )
}

/** A label and its time on one row (quiet hours). */
@Composable
private fun LabeledTime(label: String, minute: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TimeChip(label, minute, onClick)
    }
}

fun timeText(minute: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of((minute / 60) % 24, minute % 60))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(edit: TimeEdit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberTimePickerState(edit.minute / 60, edit.minute % 60, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.title) },
        text = { TimePicker(state = picker) },
        confirmButton = { TextButton(onClick = { edit.onPick(picker.hour * 60 + picker.minute); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
