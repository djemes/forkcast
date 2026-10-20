package app.forkcast.ui.today

import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.health.connect.client.records.ExerciseSessionRecord
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.domain.NutritionConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.common.rememberFill
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.abs

/** Water card (DESIGN.md §5): progress toward the daily goal, + glass, and undo of the last glass. */
@Composable
fun WaterCard(water: WaterSummary, f: UnitFormatter, onAddGlass: () -> Unit, onRemoveLast: () -> Unit, onAddAmount: (Double) -> Unit = {}) {
    val glass = f.waterServing(water.glassMl)
    val addDescription = stringResource(R.string.water_add_glass_description, glass)
    val otherLabel = stringResource(R.string.water_other_amount)
    var askAmount by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        val title: @Composable (Modifier) -> Unit = { m ->
            Text(stringResource(R.string.water_title), style = MaterialTheme.typography.titleMedium, modifier = m.semantics { heading() })
        }
        val amount: @Composable () -> Unit = {
            Text(
                stringResource(R.string.water_progress, f.waterLiters(water.ml), f.waterLiters(water.targetMl)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        val buttons: @Composable () -> Unit = {
            if (water.lastEntryId != null) {
                IconButton(onClick = onRemoveLast) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.water_remove_last))
                }
            }
            // Tap adds a glass; long-press asks for another amount.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .combinedClickable(onClick = onAddGlass, onLongClick = { askAmount = true }, onLongClickLabel = otherLabel, role = Role.Button)
                    .semantics {
                        contentDescription = addDescription
                        customActions = listOf(CustomAccessibilityAction(otherLabel) { askAmount = true; true })
                    }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.water_add_glass), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        // One row normally; at large font sizes the buttons move to their own row so nothing is squeezed.
        if (LocalDensity.current.fontScale <= 1.3f) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                title(Modifier.weight(1f))
                amount()
                buttons()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)) {
                title(Modifier.weight(1f))
                amount()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                buttons()
            }
        }
        if (water.fromDrinksMl > 0) {
            Text(
                stringResource(R.string.water_from_drinks, f.waterLiters(water.fromDrinksMl)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (askAmount) {
            WaterAmountDialog(f, onDismiss = { askAmount = false }) { ml -> askAmount = false; onAddAmount(ml) }
        }
        // Fills in on first sight; a glass added or removed eases the bar to its new length.
        val fill = rememberFill(if (water.targetMl > 0) (water.ml / water.targetMl).toFloat().coerceIn(0f, 1f) else 0f, "water")
        LinearProgressIndicator(
            progress = { fill.fraction },
            color = ForkcastTheme.colors.protein,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp).then(fill.modifier),
        )
    }
}

/** Weight card: latest weigh-in and the trend's direction (arrow + words, not just color). Tap opens the log. */
@Composable
fun WeightCard(weight: WeightSummary?, f: UnitFormatter, onOpen: () -> Unit) {
    val openLabel = stringResource(R.string.weight_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.weight_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (weight == null) {
            Text(stringResource(R.string.weight_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(f.weight(weight.latestKg), style = MaterialTheme.typography.titleMedium)
                val change = weight.weeklyChangeKg
                Text(
                    when {
                        change == null || abs(f.weightValue(change)) < 0.05 -> stringResource(R.string.weight_trend_flat)
                        change < 0 -> stringResource(R.string.weight_trend_down, f.weight(-change))
                        else -> stringResource(R.string.weight_trend_up, f.weight(change))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the Activity card knows about the day on screen (SPEC §4.2 item 7). */
data class ActivitySummary(
    /** Health Connect is connected and activity reads are on. */
    val connected: Boolean,
    /** Health Connect exists on this phone (no prompt otherwise). */
    val available: Boolean,
    val day: ActivityDay?,
    /** Usual activity, the extra above it, and what that adds to the day's budget (SPEC §5.2). */
    val bonus: ActivityBonus = ActivityBonus.NONE,
)

/** Activity card: steps, active calories and workouts from Health Connect, and how they affect the budget. */
@Composable
fun ActivityCard(activity: ActivitySummary, f: UnitFormatter, onConnect: () -> Unit, onOpen: () -> Unit = {}) {
    if (!activity.connected && !activity.available) return
    // Like the Weight card: the whole card opens its detail (or, before connecting, Health Connect).
    val label = stringResource(if (activity.connected) R.string.activity_open else R.string.activity_connect_action)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = if (activity.connected) onOpen else onConnect)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.activity_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val day = activity.day
        when {
            !activity.connected -> Text(stringResource(R.string.activity_connect), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            day == null -> Text(stringResource(R.string.activity_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val parts = listOfNotNull(
                    day.steps?.let { stringResource(R.string.activity_steps, f.number(it.toDouble())) },
                    day.activeKcal?.let { stringResource(R.string.activity_active, f.energy(it)) },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                day.sessions.sortedBy { it.start }.forEach { s ->
                    val minutes = ((s.end - s.start) / 60_000).toInt()
                    Text(
                        listOfNotNull(
                            s.title?.takeIf { it.isNotBlank() } ?: exerciseName(s.exerciseType),
                            stringResource(R.string.activity_minutes, minutes),
                            s.activeKcal?.let { f.energy(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BonusLines(activity.bonus, f)
            }
        }
    }
}

/** Usual activity and what the day's activity adds, in plain words. */
@Composable
fun BonusLines(b: ActivityBonus, f: UnitFormatter) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val usual = b.usualKcal
    when {
        usual == null -> Text(stringResource(R.string.activity_usual_none, NutritionConstants.ACTIVITY_BASELINE_MIN_DAYS), style = MaterialTheme.typography.bodySmall, color = muted)
        b.partialBaseline -> Text(stringResource(R.string.activity_usual_partial, f.energy(usual), b.baselineDays), style = MaterialTheme.typography.bodySmall, color = muted)
        else -> Text(stringResource(R.string.activity_usual, f.energy(usual)), style = MaterialTheme.typography.bodySmall, color = muted)
    }
    when {
        !b.enabled -> Text(stringResource(R.string.activity_bonus_off), style = MaterialTheme.typography.bodySmall, color = muted)
        b.usualKcal == null || b.activeKcal == null -> Unit
        b.bonusKcal > 0 -> Text(stringResource(R.string.activity_bonus, f.energy(b.bonusKcal)), style = MaterialTheme.typography.bodySmall, color = ForkcastTheme.colors.pine)
        else -> Text(stringResource(R.string.activity_bonus_zero), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
fun exerciseName(type: Int): String = stringResource(
    when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.activity_running
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> R.string.activity_walking
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.activity_biking
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> R.string.activity_swimming
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> R.string.activity_strength
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> R.string.activity_yoga
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.activity_hiking
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.activity_hiit
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> R.string.activity_cardio_machine
        else -> R.string.activity_workout
    },
)

/**
 * Whether the day counts toward the adaptive maintenance estimate (SPEC §5.6), with the one
 * action that changes it. Past days and today only.
 */
@Composable
fun DayCompletenessRow(completeness: DayCompleteness, autoComplete: Boolean, onSet: (DayCompleteness) -> Unit) {
    val (text, action, next) = when (completeness) {
        DayCompleteness.COMPLETE -> Triple(R.string.day_marked_complete, R.string.day_mark_auto, DayCompleteness.AUTO)
        DayCompleteness.INCOMPLETE -> Triple(R.string.day_marked_incomplete, R.string.day_mark_auto, DayCompleteness.AUTO)
        DayCompleteness.AUTO -> if (autoComplete) {
            Triple(R.string.day_counts, R.string.day_mark_incomplete, DayCompleteness.INCOMPLETE)
        } else {
            Triple(R.string.day_not_counted, R.string.day_mark_complete, DayCompleteness.COMPLETE)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onSet(next) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(action)) }
    }
}

/** "Other amount" for water, in the user's unit. */
@Composable
private fun WaterAmountDialog(f: UnitFormatter, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
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
