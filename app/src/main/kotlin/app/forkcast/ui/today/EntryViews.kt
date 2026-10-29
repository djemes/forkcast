package app.forkcast.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.ServingSize
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.TextButton
import app.forkcast.ui.common.MealTimeField
import app.forkcast.ui.common.PortionChips
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.heading
import app.forkcast.R
import app.forkcast.ui.theme.Motion
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.kcal
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.photo.EstimateStage
import app.forkcast.ui.photo.attemptReason
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        FoodSource.AI_TEXT -> R.string.badge_ai_text
    },
)

/** Tiny textual source badge (DESIGN.md §5). An unreviewed AI entry reads "AI·review" (apricot outline). */
@Composable
fun SourceBadge(source: FoodSource, needsReview: Boolean = false) {
    val outline = if (source.isAi && needsReview) ForkcastTheme.colors.apricot else MaterialTheme.colorScheme.outlineVariant
    Text(
        when {
            source == FoodSource.AI && needsReview -> stringResource(R.string.badge_ai_review)
            source == FoodSource.AI_TEXT && needsReview -> stringResource(R.string.badge_ai_text_review)
            else -> sourceBadge(source)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, outline, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "1 cup", "1.5 × 1 cup", "180 g", "2 oz". One serving shows just its label. */
@Composable
fun servingText(quantity: Double, label: String): String {
    val qty = UnitFormatter.trimNumber(quantity, 2)
    return when {
        label == "g" || label == "oz" || label == "ml" || label == "fl oz" -> "$qty\u2009$label"
        quantity == 1.0 -> servingLabel(label)
        else -> "$qty × ${servingLabel(label)}"
    }
}

/**
 * The labels Forkcast itself stores ("serving" for quick adds and recipes, "meal" for photo
 * estimates) read in the app's language; labels from food databases ("1 cup") stay as they are.
 */
@Composable
fun servingLabel(label: String): String = when (label) {
    "serving" -> stringResource(R.string.serving_label_serving)
    "meal" -> stringResource(R.string.serving_label_meal)
    else -> label
}

@Composable
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
    /** Snacks: each item shows when it was eaten. */
    showTime: Boolean = false,
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
                        listOfNotNull(entry.eatenMinute?.takeIf { showTime }?.let { app.forkcast.ui.common.mealTimeText(it) }, amountText(entry, f)).joinToString(" · "),
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
 * An estimate that isn't a log entry yet (DESIGN.md §5): the same one-line status as the review
 * screen (owner, 2026-10-23), with a thin progress line while it runs. Tap opens the review screen.
 */
@Composable
fun PendingEstimateRow(p: PendingEstimate, f: UnitFormatter, onClick: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    // Re-read every 30 s so "next try at" turns into "Starting" without waiting for a database change.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(p.estimate.updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val stage = EstimateStage.of(p.estimate, now)
    val text = when (stage) {
        EstimateStage.Starting -> stringResource(R.string.estimate_running)
        is EstimateStage.Asking -> stage.profile?.let { stringResource(R.string.estimate_trying, it) } ?: stringResource(R.string.estimate_running)
        EstimateStage.Matching -> stringResource(R.string.stage_matching)
        is EstimateStage.RetryWaiting -> stringResource(R.string.estimate_waiting_retry, timeOf(stage.nextAtMs))
        is EstimateStage.QuotaWaiting -> stringResource(R.string.estimate_waiting_quota, timeOf(stage.nextAtMs))
        EstimateStage.Offline -> stringResource(R.string.estimate_waiting_network)
        EstimateStage.NeedsAnswer -> stringResource(R.string.estimate_needs_answer)
        EstimateStage.Done -> stringResource(R.string.estimate_ready_review, f.energy(p.kcalExpected ?: 0.0))
        is EstimateStage.Failed -> stringResource(R.string.estimate_failed)
    }
    val failed = stage is EstimateStage.Failed
    val working = stage.isWorking
    val text2 = (stage as? EstimateStage.Failed)?.tried?.lastOrNull()?.let { attemptReason(res, it) }
    val isText = p.estimate.kind == app.forkcast.core.data.db.EstimateKind.TEXT
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
                painterResource(if (failed) R.drawable.ic_error else if (isText) R.drawable.ic_edit else R.drawable.ic_photo_camera),
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            // Status changes (waiting → estimating → ready) cross-fade instead of jumping.
            AnimatedContent(
                targetState = text,
                transitionSpec = { fadeIn(Motion.enter(Motion.SHORT)) togetherWith fadeOut(Motion.exit()) },
                label = "estimateStatus",
                modifier = Modifier.weight(1f),
            ) { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            // "AI·review" only when there's an estimate to review; a failed one has nothing yet.
            SourceBadge(if (isText) FoodSource.AI_TEXT else FoodSource.AI, needsReview = !failed)
        }
        text2?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp))
        }
        AnimatedVisibility(working, enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit())) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp, start = 28.dp))
        }
    }
}

private fun timeOf(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))

/** What the edit sheet saves (all at once, on Save only). */
data class EntryEdit(
    val quantity: Double,
    val mealSlotId: String,
    val countsAsWater: Boolean,
    /** The serving chosen; null keeps the entry's. */
    val serving: ServingSize?,
    /** Moves the entry to another day (never a future one). */
    val date: java.time.LocalDate,
    /** When it was eaten, minutes after midnight. */
    val eatenMinute: Int,
)

/**
 * Change the amount, serving, meal, day or time of a logged entry. Nutrients rescale from the
 * entry's own snapshot by grams, so switching cup → g keeps the numbers right and never re-reads
 * the food. Nothing is saved until Save; Cancel, Back or a swipe with changes asks "Discard
 * changes?" (owner, 2026-10-23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(
    entry: LogEntry,
    meals: List<MealSlot>,
    f: UnitFormatter,
    onSave: (EntryEdit) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Servings to switch between (empty: the amount can only be scaled). */
    servings: List<ServingSize> = emptyList(),
    today: java.time.LocalDate = java.time.LocalDate.now(),
    /** Now, for today's time limit. */
    nowMinute: Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute },
    /** The entry's time when it has none yet. */
    defaultMinute: Int = 12 * 60,
) {
    var quantity by rememberSaveable(entry.id) { mutableStateOf<Double?>(entry.quantity) }
    var servingLabel by rememberSaveable(entry.id) { mutableStateOf(entry.servingLabel) }
    var reseed by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    val serving = servings.firstOrNull { it.label == servingLabel }
    var mealId by rememberSaveable(entry.id) { mutableStateOf(entry.mealSlotId) }
    var countsWater by rememberSaveable(entry.id) { mutableStateOf(entry.waterMl != null) }
    var date by rememberSaveable(entry.id) { mutableStateOf(entry.date) }
    val initialMinute = entry.eatenMinute ?: defaultMinute
    var minute by rememberSaveable(entry.id) { mutableStateOf(initialMinute) }
    val dirty = quantity != entry.quantity || servingLabel != entry.servingLabel || mealId != entry.mealSlotId ||
        countsWater != (entry.waterMl != null) || date != entry.date || minute != initialMinute
    var confirmDiscard by rememberSaveable(entry.id) { mutableStateOf(false) }
    val dirtyNow by androidx.compose.runtime.rememberUpdatedState(dirty)
    // Swiping the sheet down or tapping outside with changes asks first instead of closing.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == androidx.compose.material3.SheetValue.Hidden && dirtyNow) {
                confirmDiscard = true
                false
            } else {
                true
            }
        },
    )
    val leave = { if (dirty) confirmDiscard = true else onDismiss() }
    ModalBottomSheet(onDismissRequest = leave, sheetState = sheetState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .navigationBarsPadding()
                // The keyboard (amount field) must not hide what's being typed.
                .imePadding()
                // Serving chips, the water switch, day and time can make it taller than a small screen.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.edit_entry_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SourceBadge(entry.source)
            }
            if (servings.size > 1) {
                Text(stringResource(R.string.food_serving), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    servings.forEach { s ->
                        FilterChip(
                            selected = s.label == servingLabel,
                            onClick = {
                                // Same amount in the new serving: 1 cup (158 g) → 158 g.
                                val from = serving
                                val q = quantity
                                if (from != null && q != null && s.label != servingLabel) {
                                    // Whole grams and ml, tenths of oz, hundredths of servings.
                                    val decimals = when (s.label) {
                                        "g", "ml" -> 0
                                        "oz", "fl oz" -> 1
                                        else -> 2
                                    }
                                    quantity = UnitFormatter.trimNumber(FoodLogging.convertQuantity(q, from, s), decimals).toDouble()
                                }
                                servingLabel = s.label
                            },
                            label = { Text(servingLabel(s.label)) },
                        )
                    }
                }
            }
            // "How much did you eat?" on every entry (owner, 2026-10-29): a share or multiple of what was logged.
            Text(stringResource(R.string.portion_title), style = MaterialTheme.typography.titleSmall)
            PortionChips(
                portion = quantity?.takeIf { servingLabel == entry.servingLabel && entry.quantity > 0 }?.let { it / entry.quantity },
                percentLabel = R.string.portion_percent_entry,
                onPick = { p ->
                    servingLabel = entry.servingLabel
                    quantity = UnitFormatter.trimNumber(entry.quantity * p, 2).toDouble()
                    reseed++
                },
            )
            NumberField(
                initial = UnitFormatter.trimNumber(quantity ?: entry.quantity, 2),
                label = stringResource(R.string.edit_quantity),
                suffix = if (servingLabel in setOf("g", "oz", "ml", "fl oz")) servingLabel else "× ${servingLabel(servingLabel)}",
                onValue = { quantity = it },
                // Re-seed when the serving or a portion chip changes the number.
                key = "${entry.id}|$servingLabel|$reseed",
            )
            val q = quantity
            if (q != null && q > 0 && entry.quantity > 0) {
                // The snapshot, scaled by grams when a serving is known, else by quantity.
                val factor = if (serving != null && entry.grams != null && entry.grams!! > 0) {
                    q * serving.grams / entry.grams!!
                } else {
                    q / entry.quantity
                }
                Text(f.energy(entry.nutrients.kcal * factor), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                NutrientsExpander(entry.nutrients.mapValues { (_, v) -> v * factor }, f)
            }
            Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                meals.forEach { m ->
                    FilterChip(selected = m.id == mealId, onClick = { mealId = m.id }, label = { Text(m.name) })
                }
            }
            // Day and time: move it to another (past) day, or say when it was eaten.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.entry_day), style = MaterialTheme.typography.titleSmall)
                app.forkcast.ui.onboarding.DateField(date, "", date, { date = it }, selectableTo = today)
            }
            MealTimeField(minute = minute, onChange = { minute = it }, latestMinute = nowMinute.takeIf { date == today })
            // A drink's water counts toward the day (needs its amount in grams/ml).
            if (entry.grams != null && (entry.waterMl != null || app.forkcast.core.domain.DrinkWater.isDrinkName(entry.displayName))) {
                WaterToggle(countsWater) { countsWater = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = leave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val m = if (date == today) minOf(minute, nowMinute) else minute
                        quantity?.let { onSave(EntryEdit(it, mealId, countsWater, serving, date, m)) }
                    },
                    enabled = (quantity ?: 0.0) > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
            TextButton(
                onClick = onDelete,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_delete)) }
        }
    }
    if (confirmDiscard) {
        app.forkcast.ui.common.DiscardChangesDialog(
            onDiscard = { confirmDiscard = false; onDismiss() },
            onKeepEditing = { confirmDiscard = false },
        )
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
