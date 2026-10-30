package app.forkcast.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.PortionScale
import app.forkcast.core.model.EnergyUnit
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val INSET = 24.dp
private val THUMB = 12.dp

/**
 * "How much did you eat?" (roadmap 3.9, owner 2026-10-29): a slider with 1× in the middle, fractions
 * to the left and multipliers to the right ([PortionScale]), a tick at every snap, "¼", "1×" and
 * "3×" as labels (tap one to jump there), and the value in a balloon under the thumb. Double-tap
 * goes back to 1×; a haptic tick marks each snap. The fill runs from 1× to the thumb in pine either
 * way: more or less food isn't good or bad (DESIGN §9). Custom… takes a multiplier, or grams when
 * [gramsAtOne] (the 1× weight) is known. TalkBack hears "Half portion, 320 kilocalories" and can
 * step with the usual adjust gestures.
 */
@Composable
fun PortionSlider(
    portion: Double,
    kcalAtOne: Double,
    f: UnitFormatter,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    gramsAtOne: Double? = null,
) {
    val haptics = LocalHapticFeedback.current
    val locale = LocalLocale.current.platformLocale
    val current by rememberUpdatedState(portion)
    val change by rememberUpdatedState(onChange)
    val pick = { p: Double ->
        if (abs(p - current) > 1e-6) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            change(p)
        }
    }
    var custom by rememberSaveable { mutableStateOf(false) }
    val label = portionLabel(portion, locale)
    val energy = f.energy(kcalAtOne * portion)
    val snap = PortionScale.indexOf(portion)
    val headline = if (snap != null && snap < PortionScale.ONE) {
        stringResource(R.string.portion_headline_fraction, label, energy)
    } else {
        stringResource(R.string.portion_headline, label, energy)
    }
    val spoken = stringResource(R.string.portion_spoken_state, spokenPortion(portion, locale), spokenEnergy(f, kcalAtOne * portion))
    val sliderName = stringResource(R.string.portion_slider_label)
    val resetName = stringResource(R.string.portion_reset)
    val position = PortionScale.positionOf(portion).toFloat()
    val pine = ForkcastTheme.colors.pine
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val onPine = MaterialTheme.colorScheme.surface

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The headline repeats what the slider says; TalkBack reads the slider instead.
        Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { hideFromAccessibility() })
        // A few labels, enough to show what each way does (owner): tap one to jump there.
        AtFractions(
            fractions = listOf(0f, 0.5f, 1f),
            inset = INSET,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(0, PortionScale.ONE, PortionScale.SNAPS.lastIndex).forEach { i ->
                val p = PortionScale.SNAPS[i]
                val text = portionLabel(p, locale)
                val jump = stringResource(R.string.portion_jump, text)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClickLabel = jump) { pick(p) }
                        .semantics { contentDescription = jump },
                ) { Text(text, style = MaterialTheme.typography.labelLarge, color = tick) }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(Unit) {
                    val inset = INSET.toPx()
                    fun at(x: Float) = PortionScale.SNAPS[PortionScale.nearestIndex(((x - inset) / (size.width - 2 * inset)).toDouble())]
                    detectTapGestures(onDoubleTap = { pick(1.0) }, onTap = { o -> pick(at(o.x)) })
                }
                .pointerInput(Unit) {
                    val inset = INSET.toPx()
                    detectHorizontalDragGestures { c, _ ->
                        c.consume()
                        pick(PortionScale.SNAPS[PortionScale.nearestIndex(((c.position.x - inset) / (size.width - 2 * inset)).toDouble())])
                    }
                }
                .semantics {
                    contentDescription = sliderName
                    stateDescription = spoken
                    val at = snap ?: PortionScale.nearestIndex(position.toDouble())
                    progressBarRangeInfo = ProgressBarRangeInfo(at.toFloat(), 0f..PortionScale.SNAPS.lastIndex.toFloat(), PortionScale.SNAPS.size - 2)
                    setProgress { v ->
                        val target = v.roundToInt().coerceIn(PortionScale.SNAPS.indices)
                        pick(if (target == at && snap == null) PortionScale.step(current, v > at) else PortionScale.SNAPS[target])
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction(resetName) { pick(1.0); true })
                },
        ) {
            val inset = INSET.toPx()
            val w = size.width - 2 * inset
            val y = size.height / 2
            fun x(fraction: Double) = inset + w * fraction.toFloat()
            drawLine(track, Offset(inset, y), Offset(inset + w, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(pine, Offset(x(0.5), y), Offset(x(position.toDouble()), y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            PortionScale.SNAPS.indices.forEach { i ->
                val tx = x(PortionScale.position(i))
                val filled = (tx - x(0.5)) * (x(position.toDouble()) - tx) >= 0
                drawLine(if (filled) onPine else tick, Offset(tx, y - 5.dp.toPx()), Offset(tx, y + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            val thumbX = x(position.toDouble())
            drawCircle(pine, THUMB.toPx(), Offset(thumbX, y))
            drawCircle(onPine, THUMB.toPx() - 3.dp.toPx(), Offset(thumbX, y))
            drawCircle(pine, THUMB.toPx() - 6.dp.toPx(), Offset(thumbX, y))
        }
        ValueBalloon(label, position, Modifier.semantics { hideFromAccessibility() })
        TextButton(onClick = { custom = true }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.portion_custom))
        }
    }
    if (custom) {
        CustomPortionDialog(portion, gramsAtOne, locale, onDismiss = { custom = false }) { p -> custom = false; pick(p) }
    }
}

/** The value under the thumb: a rounded rectangle in the surface color with an outline, its pointer up to the thumb. */
@Composable
private fun ValueBalloon(text: String, position: Float, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                val tip = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(tip, surface)
                val edge = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                }
                drawPath(edge, outline, style = Stroke(1.dp.toPx()))
            }
            Box(
                Modifier
                    .widthIn(min = 56.dp)
                    .background(surface, RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, outline), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val tip = measurables[0].measure(loose)
        val box = measurables[1].measure(loose)
        val inset = INSET.roundToPx()
        val width = constraints.maxWidth
        val thumbX = inset + ((width - 2 * inset) * position).roundToInt()
        val overlap = 1.dp.roundToPx()
        layout(width, tip.height + box.height - overlap) {
            box.place((thumbX - box.width / 2).coerceIn(0, (width - box.width).coerceAtLeast(0)), tip.height - overlap)
            // Drawn after the box so the pointer covers the box's top edge where they meet.
            tip.place(thumbX - tip.width / 2, 0)
        }
    }
}

/** Children centered at fractions of the track (between the insets), each kept inside the row. */
@Composable
private fun AtFractions(fractions: List<Float>, inset: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val start = inset.roundToPx()
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p ->
                val center = start + ((width - 2 * start) * fractions[i]).roundToInt()
                p.place((center - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

@Composable
private fun CustomPortionDialog(portion: Double, gramsAtOne: Double?, locale: Locale, onDismiss: () -> Unit, onPick: (Double) -> Unit) {
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; isGroupingUsed = false } }
    var grams by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(grams) {
        mutableStateOf(if (grams && gramsAtOne != null) (gramsAtOne * portion).roundToInt().toString() else format.format(portion))
    }
    val number = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val value = number?.let { if (grams && gramsAtOne != null) it / gramsAtOne else it }
        ?.takeIf { it in PortionScale.MIN..PortionScale.MAX }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portion_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(7) },
                    label = { Text(stringResource(if (grams) R.string.portion_grams else R.string.portion_multiplier)) },
                    supportingText = if (grams) null else ({ Text(stringResource(R.string.portion_multiplier_hint)) }),
                    suffix = { Text(if (grams) "g" else "×") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                // Multiplier first; grams one tap away when the entry has a weight (owner, 2026-10-29).
                if (gramsAtOne != null && gramsAtOne > 0) {
                    TextButton(onClick = { grams = !grams }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (grams) R.string.portion_use_multiplier else R.string.portion_use_grams))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let { onPick(PortionScale.custom(it)) } }, enabled = value != null) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "¼", "½", "¾", "1×", "1¼×", "1½×", "2×", "3×"; a custom portion as "1.75×" in the app's language. */
fun portionLabel(p: Double, locale: Locale = Locale.getDefault()): String = when (PortionScale.indexOf(p)?.let { PortionScale.SNAPS[it] }) {
    0.25 -> "¼"
    0.5 -> "½"
    0.75 -> "¾"
    1.0 -> "1×"
    1.25 -> "1¼×"
    1.5 -> "1½×"
    2.0 -> "2×"
    3.0 -> "3×"
    else -> NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(p) + "×"
}

@Composable
private fun spokenPortion(p: Double, locale: Locale): String = when (PortionScale.indexOf(p)?.let { PortionScale.SNAPS[it] }) {
    0.25 -> stringResource(R.string.portion_spoken_quarter)
    0.5 -> stringResource(R.string.portion_spoken_half)
    0.75 -> stringResource(R.string.portion_spoken_three_quarters)
    1.0 -> stringResource(R.string.portion_spoken_one)
    1.25 -> stringResource(R.string.portion_spoken_one_quarter)
    1.5 -> stringResource(R.string.portion_spoken_one_half)
    2.0 -> stringResource(R.string.portion_spoken_two)
    3.0 -> stringResource(R.string.portion_spoken_three)
    else -> stringResource(R.string.portion_spoken_custom, NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(p))
}

/** "320 kilocalories" (or kilojoules): the unit spelled out, so TalkBack doesn't say "k-cal". */
@Composable
private fun spokenEnergy(f: UnitFormatter, kcal: Double): String {
    val n = f.energyValue(kcal).roundToInt()
    val res = LocalResources.current
    val plural = if (f.prefs.energy == EnergyUnit.KJ) R.plurals.energy_spoken_kj else R.plurals.energy_spoken_kcal
    return res.getQuantityString(plural, n, f.energyNumber(kcal))
}
