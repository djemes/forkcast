package app.forkcast.ui.today

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
import app.forkcast.core.model.ExerciseMode
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
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.abs

/** Water card (DESIGN.md §5): progress toward the daily goal, + glass, and undo of the last glass. */
@Composable
fun WaterCard(water: WaterSummary, f: UnitFormatter, onAddGlass: () -> Unit, onRemoveLast: () -> Unit) {
    val glass = f.waterLiters(water.glassMl)
    val addDescription = stringResource(R.string.water_add_glass_description, glass)
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
            FilledTonalButton(
                onClick = onAddGlass,
                modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = addDescription },
            ) { Text(stringResource(R.string.water_add_glass)) }
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
        LinearProgressIndicator(
            progress = { if (water.targetMl > 0) (water.ml / water.targetMl).toFloat().coerceIn(0f, 1f) else 0f },
            color = ForkcastTheme.colors.protein,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp),
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
    val mode: ExerciseMode,
    val addBackPct: Int,
    /** What the day's active calories added to its budget. */
    val bonusKcal: Double,
)

/** Activity card: steps, active calories and workouts from Health Connect, and how they affect the budget. */
@Composable
fun ActivityCard(activity: ActivitySummary, f: UnitFormatter, onConnect: () -> Unit) {
    if (!activity.connected && !activity.available) return
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .then(if (!activity.connected) Modifier.clickable(onClickLabel = stringResource(R.string.activity_connect_action), role = Role.Button, onClick = onConnect) else Modifier)
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
                Text(
                    if (activity.mode == ExerciseMode.ADDED_BACK) {
                        stringResource(R.string.activity_added_back, f.energy(activity.bonusKcal), activity.addBackPct)
                    } else {
                        stringResource(R.string.activity_included)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activity.mode == ExerciseMode.ADDED_BACK && activity.bonusKcal > 0) ForkcastTheme.colors.pine else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun exerciseName(type: Int): String = stringResource(
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
