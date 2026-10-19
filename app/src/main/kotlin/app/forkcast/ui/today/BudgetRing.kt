package app.forkcast.ui.today

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
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
import app.forkcast.core.domain.Forecast
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The budget ring (DESIGN.md §1, §5): solid arc = eaten, apricot dot = "now".
 * Color follows state (pine → apricot at 80 % → plum when over), and the label text
 * changes too ("left" → "over") so color is never the only signal.
 * After 7+ logged days a dashed pale arc continues from "now" to where the day will likely
 * end (the forecast, SPEC §4.2), with a status line under the ring; tapping explains it.
 */
/** Material 3 emphasized easing. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun BudgetRing(
    totals: DayTotals,
    units: UnitFormatter,
    modifier: Modifier = Modifier,
    forecast: Forecast.Outlook? = null,
    forecastFromMinute: Int = 0,
) {
    var explain by remember { mutableStateOf(false) }
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

    val forecastSweep by animateFloatAsState(
        targetValue = forecast?.let { (it.forecastTotalKcal / totals.allowanceKcal).toFloat().coerceIn(0f, 1f) } ?: 0f,
        animationSpec = tween(400, easing = EmphasizedEasing),
        label = "forecast",
    )
    val forecastColor = arcColor.copy(alpha = 0.35f)
    val status = forecast?.let { forecastStatus(it, units) }

    val over = totals.state == BudgetState.OVER
    val bigNumber = units.energyNumber(if (over) totals.overKcal else totals.remainingKcal)
    val label = stringResource(if (over) R.string.ring_over else R.string.ring_left, units.energyUnitLabel)
    val description = stringResource(
        R.string.ring_description,
        units.energy(totals.eatenKcal),
        units.energy(totals.allowanceKcal),
        "$bigNumber $label",
    ) + (status?.let { " $it." } ?: "")

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(if (forecast != null) Modifier.clickable { explain = !explain } else Modifier)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            Canvas(Modifier.matchParentSize().padding(12.dp)) {
                val stroke = size.minDimension * 0.075f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                // Forecast: dashed, paler, from "now" to the likely end of the day.
                if (forecastSweep > sweep) {
                    val dash = stroke * 0.9f
                    drawArc(
                        forecastColor, -90f + 360f * sweep, 360f * (forecastSweep - sweep), false, topLeft, arcSize,
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.6f))),
                    )
                }
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
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.titleSmall,
                color = when (forecast?.status) {
                    Forecast.Status.OVER, Forecast.Status.LIKELY_OVER -> colors.plum
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (explain && forecast != null) {
            Text(
                stringResource(R.string.forecast_explain, timeLabel(forecastFromMinute), units.energy(forecast.forecastTotalKcal)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun forecastStatus(f: Forecast.Outlook, units: UnitFormatter): String = when (f.status) {
    Forecast.Status.ON_COURSE -> stringResource(R.string.forecast_on_course)
    Forecast.Status.TIGHT -> stringResource(R.string.forecast_tight)
    Forecast.Status.LIKELY_OVER -> stringResource(R.string.forecast_likely_over, units.energyNumber(f.overByKcal))
    Forecast.Status.OVER -> stringResource(R.string.forecast_over, units.energyNumber(f.overByKcal))
}

private fun timeLabel(minuteOfDay: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
