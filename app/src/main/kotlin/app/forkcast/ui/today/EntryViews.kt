package app.forkcast.ui.today

import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.kcal
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.theme.ForkcastTheme

@Composable
fun sourceBadge(source: FoodSource): String = stringResource(
    when (source) {
        FoodSource.USDA -> R.string.badge_usda
        FoodSource.OFF -> R.string.badge_off
        FoodSource.LABEL -> R.string.badge_label
        FoodSource.CUSTOM -> R.string.badge_custom
        FoodSource.QUICK_ADD -> R.string.badge_quick
        FoodSource.RECIPE -> R.string.badge_recipe
        FoodSource.AI -> R.string.badge_ai
    },
)

/** Tiny textual source badge (DESIGN.md §5). An unreviewed AI entry reads "AI·review" (apricot outline). */
@Composable
fun SourceBadge(source: FoodSource, needsReview: Boolean = false) {
    val outline = if (source == FoodSource.AI && needsReview) ForkcastTheme.colors.apricot else MaterialTheme.colorScheme.outlineVariant
    Text(
        if (source == FoodSource.AI && needsReview) stringResource(R.string.badge_ai_review) else sourceBadge(source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, outline, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "1 cup", "1.5 × 1 cup", "180 g", "2 oz". One serving shows just its label. */
fun servingText(quantity: Double, label: String): String {
    val qty = UnitFormatter.trimNumber(quantity, 2)
    return when {
        label == "g" || label == "oz" || label == "ml" || label == "fl oz" -> "$qty\u2009$label"
        quantity == 1.0 -> label
        else -> "$qty × $label"
    }
}

fun amountText(entry: LogEntry, f: UnitFormatter): String {
    val serving = servingText(entry.quantity, entry.servingLabel)
    // Grams add nothing to an amount already in g or in ml/fl oz.
    return entry.grams?.takeIf { entry.servingLabel !in setOf("g", "ml", "fl oz") }?.let { "$serving · ${f.grams(it)}" } ?: serving
}

/** A single-line entry row: swipe left to delete, tap to edit, long-press to select. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: LogEntry,
    f: UnitFormatter,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    needsReview: Boolean = false,
) {
    val dismiss = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.action_delete)
    val selectLabel = stringResource(R.string.select_entry)
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selecting,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(end = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_delete), deleteLabel, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp),
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
                .semantics {
                    this.selected = selected
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (selecting) {
                Icon(
                    painterResource(if (selected) R.drawable.ic_check else R.drawable.ic_remove),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(entry.displayName, entry.brand).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        amountText(entry, f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SourceBadge(entry.source, needsReview)
                }
            }
            // kcal is never truncated (DESIGN.md §5).
            Text(f.energyNumber(entry.nutrients.kcal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * A photo estimate that isn't a log entry yet (DESIGN.md §5): an indeterminate progress line
 * while it runs, otherwise one line saying what it's waiting for. Tap opens the review screen.
 */
@Composable
fun PendingEstimateRow(p: PendingEstimate, f: UnitFormatter, onClick: () -> Unit) {
    val text = when (p.status) {
        EstimateStatus.PENDING -> when {
            p.retryAfter != null && p.retryAfter > System.currentTimeMillis() ->
                stringResource(R.string.estimate_waiting_quota, timeOf(p.retryAfter))
            p.errorCode == "Network" -> stringResource(R.string.estimate_waiting_network)
            else -> stringResource(R.string.estimate_running)
        }
        EstimateStatus.RUNNING -> stringResource(R.string.estimate_running)
        EstimateStatus.NEEDS_INPUT -> stringResource(R.string.estimate_needs_answer)
        EstimateStatus.DONE -> stringResource(R.string.estimate_ready_review, f.energy(p.kcalExpected ?: 0.0))
        EstimateStatus.FAILED -> stringResource(R.string.estimate_failed)
    }
    val working = p.status == EstimateStatus.RUNNING || (p.status == EstimateStatus.PENDING && p.retryAfter == null && p.errorCode == null)
    // Same shape as an entry row (DESIGN.md §5): rounded surface, 52 dp min, 12 dp sides.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                painterResource(if (p.status == EstimateStatus.FAILED) R.drawable.ic_error else R.drawable.ic_photo_camera),
                contentDescription = null,
                tint = if (p.status == EstimateStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (p.status == EstimateStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // "AI·review" only when there's an estimate to review; a failed one has nothing yet.
            SourceBadge(FoodSource.AI, needsReview = p.status != EstimateStatus.FAILED)
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp, start = 28.dp))
    }
}

private fun timeOf(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))

/** Change the amount or meal of a logged entry. Nutrients rescale from the entry's own snapshot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(
    entry: LogEntry,
    meals: List<MealSlot>,
    f: UnitFormatter,
    onSave: (quantity: Double, mealSlotId: String, countsAsWater: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var quantity by rememberSaveable(entry.id) { mutableStateOf<Double?>(entry.quantity) }
    var mealId by rememberSaveable(entry.id) { mutableStateOf(entry.mealSlotId) }
    var countsWater by rememberSaveable(entry.id) { mutableStateOf(entry.waterMl != null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.edit_entry_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SourceBadge(entry.source)
            }
            NumberField(
                initial = UnitFormatter.trimNumber(entry.quantity, 2),
                label = stringResource(R.string.edit_quantity),
                suffix = if (entry.servingLabel in setOf("g", "oz", "ml", "fl oz")) entry.servingLabel else "× ${entry.servingLabel}",
                onValue = { quantity = it },
                key = entry.id,
            )
            val q = quantity
            if (q != null && q > 0 && entry.quantity > 0) {
                Text(
                    f.energy(entry.nutrients.kcal * q / entry.quantity),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                meals.forEach { m ->
                    FilterChip(selected = m.id == mealId, onClick = { mealId = m.id }, label = { Text(m.name) })
                }
            }
            // A drink's water counts toward the day (needs its amount in grams/ml).
            if (entry.grams != null && (entry.waterMl != null || app.forkcast.core.domain.DrinkWater.isDrinkName(entry.displayName))) {
                WaterToggle(countsWater) { countsWater = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_delete))
                }
                Button(
                    onClick = { quantity?.let { onSave(it, mealId, countsWater) } },
                    enabled = (quantity ?: 0.0) > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

/** "Counts toward water" switch, shared by the edit sheet and food detail. */
@Composable
fun WaterToggle(checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.water_counts), style = MaterialTheme.typography.bodyLarge)
            Text(
                detail ?: stringResource(R.string.water_counts_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}
