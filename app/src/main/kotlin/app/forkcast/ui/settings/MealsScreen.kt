package app.forkcast.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.domain.MealSlotRules
import app.forkcast.core.model.MealSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A meal being added (id null) or edited. Window minutes are null when the meal has none. */
data class MealDraft(val id: String?, val name: String, val start: Int?, val end: Int?)

data class MealDeleted(val id: String, val name: String)

@HiltViewModel
class MealsViewModel @Inject constructor(private val repo: MealSlotRepository) : ViewModel() {
    val slots: StateFlow<List<MealSlot>?> = repo.observeMealSlots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val deletedChannel = Channel<MealDeleted>(Channel.BUFFERED)
    val deleted: Flow<MealDeleted> = deletedChannel.receiveAsFlow()

    /** Saves when valid; returns false (and changes nothing) otherwise. */
    fun save(d: MealDraft): Boolean {
        val all = slots.value.orEmpty()
        if (MealSlotRules.nameProblem(d.name, d.id, all) != null) return false
        if (d.start != null && d.end != null && !MealSlotRules.windowValid(d.start, d.end)) return false
        viewModelScope.launch {
            if (d.id == null) {
                if (MealSlotRules.canAdd(all)) repo.add(d.name, d.start, d.end)
            } else {
                val old = all.firstOrNull { it.id == d.id } ?: return@launch
                repo.update(old.copy(name = d.name.trim(), startMinute = d.start, endMinute = d.end))
            }
        }
        return true
    }

    fun move(id: String, delta: Int) = viewModelScope.launch { repo.reorder(MealSlotRules.moved(slots.value.orEmpty(), id, delta)) }

    fun delete(slot: MealSlot) {
        if (!MealSlotRules.canDelete(slots.value.orEmpty())) return
        viewModelScope.launch {
            repo.delete(slot.id)
            deletedChannel.send(MealDeleted(slot.id, slot.name))
        }
    }

    fun undo(id: String) = viewModelScope.launch { repo.restore(id) }
}

@Composable
fun MealsRoute(onBack: () -> Unit, viewModel: MealsViewModel = hiltViewModel()) {
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { d ->
            val r = snackbar.showSnackbar(res.getString(R.string.meals_deleted, d.name), res.getString(R.string.undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) viewModel.undo(d.id)
        }
    }
    Box(Modifier.fillMaxSize()) {
        MealsScreen(slots ?: return@Box, viewModel::save, viewModel::move, viewModel::delete, onBack)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
fun MealsScreen(
    slots: List<MealSlot>,
    onSave: (MealDraft) -> Boolean,
    onMove: (String, Int) -> Unit,
    onDelete: (MealSlot) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<MealDraft?>(null) }
    var confirmDelete by remember { mutableStateOf<MealSlot?>(null) }
    SettingsScaffold(title = stringResource(R.string.settings_meals), onBack = onBack) {
        Text(
            stringResource(R.string.meals_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        slots.forEachIndexed { i, slot ->
            MealRow(
                slot = slot,
                canMoveUp = i > 0,
                canMoveDown = i < slots.lastIndex,
                canDelete = MealSlotRules.canDelete(slots),
                onClick = { editing = MealDraft(slot.id, slot.name, slot.startMinute, slot.endMinute) },
                onMove = { onMove(slot.id, it) },
                onDelete = { confirmDelete = slot },
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { editing = MealDraft(null, "", null, null) },
                enabled = MealSlotRules.canAdd(slots),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.meals_add), modifier = Modifier.padding(start = 8.dp))
            }
            if (!MealSlotRules.canAdd(slots)) {
                Text(stringResource(R.string.meals_max, MealSlotRules.MAX_MEALS), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { draft -> MealDialog(draft, slots, onSave = { if (onSave(it)) editing = null }, onDismiss = { editing = null }) }
    confirmDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.meals_delete)) },
            text = { Text(stringResource(R.string.meals_delete_body, slot.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(slot) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MealRow(
    slot: MealSlot,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(slot.name, style = MaterialTheme.typography.bodyLarge)
            Text(windowText(slot.startMinute, slot.endMinute), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.meals_more, slot.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canMoveUp) DropdownMenuItem(text = { Text(stringResource(R.string.meals_move_up)) }, onClick = { menu = false; onMove(-1) })
                if (canMoveDown) DropdownMenuItem(text = { Text(stringResource(R.string.meals_move_down)) }, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(
                    text = { Text(stringResource(if (canDelete) R.string.meals_delete else R.string.meals_last_one)) },
                    enabled = canDelete,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun windowText(start: Int?, end: Int?): String =
    if (start == null || end == null) stringResource(R.string.meals_no_window)
    else stringResource(R.string.meals_window, timeText(start), timeText(end))

@Composable
private fun MealDialog(initial: MealDraft, slots: List<MealSlot>, onSave: (MealDraft) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var hasWindow by remember { mutableStateOf(initial.start != null && initial.end != null) }
    var start by remember { mutableStateOf(initial.start ?: (12 * 60)) }
    var end by remember { mutableStateOf(initial.end ?: (13 * 60)) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var touched by remember { mutableStateOf(false) }

    val nameProblem = MealSlotRules.nameProblem(name, initial.id, slots)
    val windowOk = !hasWindow || MealSlotRules.windowValid(start, end)
    val overlaps = if (hasWindow && windowOk) MealSlotRules.overlaps(start, end, initial.id, slots) else emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == null) R.string.meals_new_title else R.string.meals_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MealSlotRules.MAX_NAME + 5); touched = true },
                    label = { Text(stringResource(R.string.meals_name)) },
                    singleLine = true,
                    isError = touched && nameProblem != null,
                    supportingText = if (touched && nameProblem != null) {
                        {
                            Text(
                                when (nameProblem) {
                                    MealSlotRules.NameProblem.BLANK -> stringResource(R.string.meals_err_blank)
                                    MealSlotRules.NameProblem.TOO_LONG -> stringResource(R.string.meals_err_long, MealSlotRules.MAX_NAME)
                                    MealSlotRules.NameProblem.DUPLICATE -> stringResource(R.string.meals_err_duplicate)
                                },
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = hasWindow, role = Role.Switch) { hasWindow = it },
                ) {
                    Text(stringResource(R.string.meals_has_window), modifier = Modifier.weight(1f))
                    Switch(checked = hasWindow, onCheckedChange = null)
                }
                if (hasWindow) {
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.meals_starts, timeText(start))) }
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.meals_ends, timeText(end))) }
                    if (!windowOk) Text(stringResource(R.string.meals_err_window), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (overlaps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.meals_overlap, overlaps.joinToString { it.name }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { touched = true; if (nameProblem == null && windowOk) onSave(MealDraft(initial.id, name, start.takeIf { hasWindow }, end.takeIf { hasWindow })) },
                enabled = windowOk,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    picking?.let { isStart ->
        MealTimeDialog(
            minute = if (isStart) start else end,
            onPick = { if (isStart) start = it else end = it; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealTimeDialog(minute: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
