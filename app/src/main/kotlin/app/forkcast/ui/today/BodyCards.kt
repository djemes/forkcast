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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.water_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            Text(
                stringResource(R.string.water_progress, f.waterLiters(water.ml), f.waterLiters(water.targetMl)),
                style = MaterialTheme.typography.labelLarge,
            )
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
