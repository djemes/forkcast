package app.forkcast.ui.today

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.BudgetState
import app.forkcast.core.domain.DayTotals
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The budget ring (DESIGN.md §1, §5): solid arc = eaten, apricot dot = "now".
 * Color follows state (pine → apricot at 80 % → plum when over), and the label text
 * changes too ("left" → "over") so color is never the only signal.
 * The forecast arc arrives in Phase 2.
 */
/** Material 3 emphasized easing. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun BudgetRing(totals: DayTotals, units: UnitFormatter, modifier: Modifier = Modifier) {
    val colors = ForkcastTheme.colors
    val arcColor = when (totals.state) {
        BudgetState.ON_TRACK -> colors.pine
        BudgetState.APPROACHING -> colors.apricot
        BudgetState.OVER -> colors.plum
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    // Ring animates old → new total (400 ms, emphasized); system "remove animations" makes it instant.
    val sweep by animateFloatAsState(
        targetValue = totals.fraction.toFloat().coerceIn(0f, 1f),
        animationSpec = tween(400, easing = EmphasizedEasing),
        label = "ring",
    )

    val over = totals.state == BudgetState.OVER
    val bigNumber = units.energyNumber(if (over) totals.overKcal else totals.remainingKcal)
    val label = stringResource(if (over) R.string.ring_over else R.string.ring_left, units.energyUnitLabel)
    val description = stringResource(
        R.string.ring_description,
        units.energy(totals.eatenKcal),
        units.energy(totals.allowanceKcal),
        "$bigNumber $label",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            Canvas(Modifier.matchParentSize().padding(12.dp)) {
                val stroke = size.minDimension * 0.075f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                if (sweep > 0f) {
                    drawArc(arcColor, -90f, 360f * sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                // "Now" dot at the end of the eaten arc.
                val angle = Math.toRadians((-90f + 360f * sweep).toDouble())
                val r = arcSize.width / 2
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    color = colors.apricot,
                    radius = stroke * 0.75f,
                    center = Offset(c.x + (r * cos(angle)).toFloat(), c.y + (r * sin(angle)).toFloat()),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    bigNumber,
                    style = MaterialTheme.typography.displayLarge,
                    color = if (over) colors.plum else MaterialTheme.colorScheme.onBackground,
                )
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            stringResource(
                R.string.ring_eaten_budget,
                units.energyNumber(totals.eatenKcal),
                units.energyNumber(totals.budgetKcal.toDouble()),
                units.energyNumber(abs(totals.exerciseKcal)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
