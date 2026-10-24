package app.forkcast.ui.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import app.forkcast.ui.common.LocalEntrance
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.Motion
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The budget ring (DESIGN.md §1, §5): solid arc = eaten, apricot dot = "now".
 * Color follows state (pine → apricot at 80 % → plum when over), and the label text
 * changes too ("left" → "over") so color is never the only signal.
 * After 7+ logged days a dotted band continues from "now" to where the day will likely end (the
 * forecast, SPEC §4.2): dense next to "now", thinning and fading toward the end, since the
 * further out, the less certain (owner, 2026-10-24). A status line sits under the ring.
 * Tapping the ring (or the line under it, which ends in a chevron) opens "Today's budget".
 */
@Composable
fun BudgetRing(
    totals: DayTotals,
    units: UnitFormatter,
    modifier: Modifier = Modifier,
    forecast: Forecast.Outlook? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ForkcastTheme.colors
    val arcColor = when (totals.state) {
        BudgetState.ON_TRACK -> colors.pine
        BudgetState.APPROACHING -> colors.apricot
        BudgetState.OVER -> colors.plum
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    // On app open and day change the ring fills from empty and the numbers count up (DESIGN.md §8);
    // afterwards it moves old → new. "Remove animations" makes all of it instant.
    val entrance = LocalEntrance.current
    val fillIn = remember(entrance) { entrance.claim("ring") }
    val sweepAnim = rememberRingValue(totals.fraction.toFloat().coerceIn(0f, 1f), fillIn)
    val forecastAnim = rememberRingValue(
        forecast?.let { (it.forecastTotalKcal / totals.allowanceKcal).toFloat().coerceIn(0f, 1f) } ?: 0f,
        fillIn,
    )
    val status = forecast?.let { forecastStatus(it, units) }

    val over = totals.state == BudgetState.OVER
    val bigNumber = units.energyNumber(if (over) totals.overKcal else totals.remainingKcal)
    val label = stringResource(if (over) R.string.ring_over else R.string.ring_left, units.energyUnitLabel)
    // Activity above a usual day (SPEC §5.2) is part of what's left; it's named so the number never surprises.
    val exercise = totals.exerciseKcal > 0
    val description = (
        if (exercise) {
            stringResource(R.string.ring_description_exercise, units.energy(totals.eatenKcal), units.energy(totals.allowanceKcal), units.energy(totals.exerciseKcal), "$bigNumber $label")
        } else {
            stringResource(R.string.ring_description, units.energy(totals.eatenKcal), units.energy(totals.allowanceKcal), "$bigNumber $label")
        }
        ) + (status?.let { " $it." } ?: "")

    val openLabel = stringResource(R.string.ring_open_budget)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(bottom = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            Canvas(Modifier.matchParentSize().padding(12.dp)) {
                val sweep = sweepAnim.value
                val forecastSweep = forecastAnim.value
                val stroke = size.minDimension * 0.075f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                // Forecast: a dotted band from "now" to the likely end of the day, thinning and fading out.
                if (forecastSweep > sweep) {
                    forecastDots(
                        center = Offset(size.width / 2, size.height / 2),
                        radius = arcSize.width / 2,
                        stroke = stroke,
                        startDeg = -90f + 360f * sweep,
                        sweepDeg = 360f * (forecastSweep - sweep),
                        color = arcColor,
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
                val shown = rememberRingValue((if (over) totals.overKcal else totals.remainingKcal).toFloat(), fillIn)
                CountingText(
                    { units.energyNumber(shown.value.toDouble()) },
                    style = MaterialTheme.typography.displayLarge,
                    color = if (over) colors.plum else MaterialTheme.colorScheme.onBackground,
                )
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // At large font sizes this would cross the ring; the line under the ring says it too.
                if (exercise && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f) {
                    Text(
                        stringResource(R.string.ring_exercise_included, units.energyNumber(totals.exerciseKcal)),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.pine,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        // The extra from activity sits inside the ring; at large font sizes it moves to this line instead.
        val bigFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        val eaten = rememberRingValue(totals.eatenKcal.toFloat(), fillIn)
        val eatenBudgetExercise = stringResource(R.string.ring_eaten_budget_exercise)
        val eatenBudget = stringResource(R.string.ring_eaten_budget)
        val budget = units.energyNumber(totals.budgetKcal.toDouble())
        val exerciseText = units.energyNumber(totals.exerciseKcal)
        // The chevron says the ring opens something (owner: a subtle cue that it can be tapped).
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountingText(
                {
                    val e = units.energyNumber(eaten.value.toDouble())
                    if (exercise && bigFont) eatenBudgetExercise.format(e, budget, exerciseText) else eatenBudget.format(e, budget)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onClick != null) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.titleSmall,
                color = when (forecast.status) {
                    Forecast.Status.OVER, Forecast.Status.LIKELY_OVER -> colors.plum
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 4.dp),
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

/**
 * The forecast as a stippled band: dots in two staggered rows across the stroke (one row in the
 * middle, then two at the edges), each step a little further apart, smaller and fainter than the
 * last, so it reads dense next to "now" and fades toward the likely end of the day.
 */
private fun DrawScope.forecastDots(center: Offset, radius: Float, stroke: Float, startDeg: Float, sweepDeg: Float, color: Color) {
    val arcLength = Math.toRadians(sweepDeg.toDouble()).toFloat() * radius
    if (arcLength <= 0f) return
    val baseStep = stroke * 0.42f
    var s = baseStep * 0.7f
    var row = 0
    while (s < arcLength) {
        val t = s / arcLength
        val angle = Math.toRadians((startDeg + sweepDeg * t).toDouble())
        val dot = stroke * 0.13f * (1f - 0.35f * t)
        val alpha = 0.85f - 0.7f * t
        val offsets = if (row % 2 == 0) floatArrayOf(0f) else floatArrayOf(-0.27f, 0.27f)
        for (o in offsets) {
            val r = radius + o * stroke
            drawCircle(
                color.copy(alpha = alpha),
                radius = dot,
                center = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat()),
            )
        }
        s += baseStep * (1f + 1.8f * t)
        row++
    }
}

/**
 * A ring value (sweep or number) that starts at 0 and fills over [Motion.RING_FILL] when [fillIn],
 * then follows later changes with [Motion.change].
 */
@Composable
private fun rememberRingValue(target: Float, fillIn: Boolean): Animatable<Float, AnimationVector1D> {
    val anim = remember { Animatable(if (fillIn) 0f else target) }
    val first = remember { booleanArrayOf(fillIn) }
    LaunchedEffect(target) {
        anim.animateTo(target, if (first[0]) Motion.enter(Motion.RING_FILL) else Motion.change(RING_CHANGE_MS))
        first[0] = false
    }
    return anim
}

/** Text that changes every frame while a number counts; only this small node recomposes. */
@Composable
private fun CountingText(
    text: () -> String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    Text(text(), style = style, color = color, textAlign = textAlign, modifier = modifier)
}

/** An edit moves the ring a little slower than other in-place changes, so the change reads. */
private const val RING_CHANGE_MS = 400
