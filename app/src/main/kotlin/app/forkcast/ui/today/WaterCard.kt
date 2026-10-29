package app.forkcast.ui.today

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.forkcast.R
import app.forkcast.core.model.WaterContainer
import app.forkcast.ui.common.rememberFill
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long Undo stays after the last addition (owner, 2026-10-23: about 6 s). */
const val WATER_UNDO_MS = 6_000L

@DrawableRes
fun containerIcon(c: WaterContainer): Int = when (c) {
    WaterContainer.GLASS -> R.drawable.ic_water_glass
    WaterContainer.CAN -> R.drawable.ic_container_can
    WaterContainer.BOTTLE -> R.drawable.ic_container_bottle
    WaterContainer.TUMBLER -> R.drawable.ic_container_tumbler
}

@StringRes
fun containerName(c: WaterContainer): Int = when (c) {
    WaterContainer.GLASS -> R.string.water_glass
    WaterContainer.CAN -> R.string.water_can
    WaterContainer.BOTTLE -> R.string.water_bottle
    WaterContainer.TUMBLER -> R.string.water_tumbler
}

/**
 * Water card (owner, 2026-10-23), compact: the total (tap: add another amount; long-press: type the
 * day's total), the bar, and the water drop, which adds the last-used container in one tap
 * ("+ Bottle · 24 oz"). The arrow next to it (or a long-press on the drop) opens the four
 * containers; picking one adds it and makes it the drop's. After any addition, Undo removes the
 * newest one, one tap each, for about 6 s after the last addition or until the app is left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaterCard(
    water: WaterSummary,
    f: UnitFormatter,
    onAddContainer: (WaterContainer) -> Unit,
    onAddAmount: (Double) -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: () -> Unit,
    onSetTotal: (Double) -> Unit,
) {
    var askAmount by remember { mutableStateOf(false) }
    var editTotal by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val expire by rememberUpdatedState(onUndoExpired)
    // Undo goes ~6 s after the last addition (a new one restarts the wait), and when the app is left.
    LaunchedEffect(water.undoToken, water.undoCount) {
        if (water.undoCount > 0) {
            delay(WATER_UNDO_MS)
            expire()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (water.undoCount > 0) expire() }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.water_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            // The total: tap adds another amount, long-press types the day's total.
            val addLabel = stringResource(R.string.water_other_amount)
            val editLabel = stringResource(R.string.water_total_edit)
            val total = stringResource(R.string.water_progress, f.waterLiters(water.ml), f.waterLiters(water.targetMl))
            Text(
                total,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClickLabel = addLabel,
                        onLongClickLabel = editLabel,
                        role = Role.Button,
                        onLongClick = { editTotal = true },
                        onClick = { askAmount = true },
                    )
                    .semantics { customActions = listOf(CustomAccessibilityAction(editLabel) { editTotal = true; true }) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        // Fills in on first sight; an addition or Undo eases the bar to its new length.
        val fill = rememberFill(if (water.targetMl > 0) (water.ml / water.targetMl).toFloat().coerceIn(0f, 1f) else 0f, "water")
        LinearProgressIndicator(
            progress = { fill.fraction },
            color = ForkcastTheme.colors.protein,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp).then(fill.modifier),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (water.fromDrinksMl > 0) {
                Text(
                    stringResource(R.string.water_from_drinks, f.waterLiters(water.fromDrinksMl)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            AnimatedVisibility(
                visible = water.undoCount > 0,
                enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.9f),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.9f),
            ) {
                TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.undo)) }
            }
            // The drop and its arrow stay together when the row wraps (large fonts).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    WaterDrop(water, f, onAdd = { onAddContainer(water.lastUsed) }, onPick = { picking = true })
                    if (picking) ContainerPicker(water, f, onPick = { c -> picking = false; onAddContainer(c) }, onDismiss = { picking = false })
                }
                val pickLabel = stringResource(R.string.water_pick_container)
                IconButton(onClick = { picking = !picking }, modifier = Modifier.semantics { contentDescription = pickLabel }) {
                    Icon(painterResource(if (picking) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
                }
            }
        }
    }
    if (askAmount) {
        WaterAmountDialog(f, onDismiss = { askAmount = false }) { ml -> askAmount = false; onAddAmount(ml) }
    }
    if (editTotal) {
        WaterTotalDialog(water, f, onDismiss = { editTotal = false }) { ml -> editTotal = false; onSetTotal(ml) }
    }
}

/** The quick-add button, showing the last-used container: one tap adds it; long-press opens the picker. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WaterDrop(water: WaterSummary, f: UnitFormatter, onAdd: () -> Unit, onPick: () -> Unit) {
    val name = stringResource(containerName(water.lastUsed))
    val amount = f.waterServing(water.lastUsedMl)
    val label = stringResource(R.string.water_drop_add, name, amount)
    val pickLabel = stringResource(R.string.water_pick_container)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(onClick = onAdd, onLongClick = onPick, onLongClickLabel = pickLabel, role = Role.Button)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                customActions = listOf(CustomAccessibilityAction(pickLabel) { onPick(); true })
            }
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(painterResource(containerIcon(water.lastUsed)), contentDescription = null, tint = ForkcastTheme.colors.protein, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The four containers with their sizes, over the card. Picking one adds it and makes it the
 * drop's; a tap anywhere else closes it without adding. Rises out of the drop like the Log sheet.
 */
@Composable
private fun ContainerPicker(water: WaterSummary, f: UnitFormatter, onPick: (WaterContainer) -> Unit, onDismiss: () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { AboveAnchor(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(Motion.enter(Motion.LONG)) { it / 3 } + fadeIn(Motion.enter(Motion.LONG)) + expandVertically(Motion.enter(Motion.LONG), expandFrom = Alignment.Bottom),
            exit = slideOutVertically(Motion.exit()) { it / 3 } + fadeOut(Motion.exit()) + shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Bottom),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 8.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.water_pick_container),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                    water.containers.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (c, ml) ->
                                ContainerTile(c, f.waterServing(ml), selected = c == water.lastUsed, modifier = Modifier.weight(1f)) { onPick(c) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Above the drop, right edges lined up (below it if there's no room above). */
private class AboveAnchor(private val gapPx: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + gapPx)
    }
}

@Composable
private fun ContainerTile(c: WaterContainer, amount: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val name = stringResource(containerName(c))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, role = Role.Button, onClickLabel = stringResource(R.string.water_add_container, name, amount))
            .heightIn(min = 72.dp)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Icon(painterResource(containerIcon(c)), contentDescription = null, tint = ForkcastTheme.colors.protein, modifier = Modifier.size(28.dp))
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(amount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tap on the total: add another amount, in the user's water unit. */
@Composable
fun WaterAmountDialog(f: UnitFormatter, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
    var value by remember { mutableStateOf<Double?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_other_amount)) },
        text = {
            app.forkcast.ui.onboarding.NumberField(
                initial = "",
                label = stringResource(R.string.water_amount_field, f.waterUnitLabel),
                onValue = { value = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.takeIf { it > 0 }?.let { onAdd(f.waterToMl(it)) } }, enabled = (value ?: 0.0) > 0) {
                Text(stringResource(R.string.water_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Long-press on the total: type the day's total (numbers only). Drinks logged as food stay, so the
 * total can't go below them.
 */
@Composable
private fun WaterTotalDialog(water: WaterSummary, f: UnitFormatter, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(f.waterValue(water.ml).roundToInt().toString()) }
    val value = text.toIntOrNull()
    val minimum = f.waterValue(water.fromDrinksMl).roundToInt()
    val valid = value != null && value >= minimum
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_total_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.water_total_field, f.waterUnitLabel)) },
                    suffix = { Text(f.waterUnitLabel) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (water.fromDrinksMl > 0) {
                    Text(
                        stringResource(R.string.water_total_min, f.waterServing(water.fromDrinksMl)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let { onSet(f.waterToMl(it.toDouble())) } }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
