package app.forkcast.ui.charts

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forkcast.core.domain.TrendPoint
import app.forkcast.core.domain.Trends
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared chart pieces (DESIGN.md §7: one chart per section, no chart junk, tap-and-hold
 * scrubbing with a value label). Built on Vico; values arrive already in the user's units.
 */

/** A rounded step that gives about [lines] gridlines over [range] (1, 2, 2.5, 5 × 10ⁿ). */
internal fun niceStep(range: Double, lines: Int = 4): Double {
    if (range <= 0) return 1.0
    val raw = range / lines
    val magnitude = Math.pow(10.0, floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
    return step
}

@Composable
private fun markerLabel(): TextComponent {
    val text = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, bg) {
        TextComponent(
            textStyle = TextStyle(color = text, fontSize = 13.sp),
            lineCount = 2,
            padding = Insets(horizontal = 8.dp, vertical = 4.dp),
            background = ShapeComponent(Fill(bg), RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun axisLabel(): TextComponent {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(color) { TextComponent(textStyle = TextStyle(color = color, fontSize = 12.sp)) }
}

/** Calls [onSelect] with the x of the bar or point under the finger, and null when the marker hides. */
private fun selectionListener(onSelect: (Double?) -> Unit) = object : CartesianMarkerVisibilityListener {
    override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { onSelect(targets.firstOrNull()?.x) }
    override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { onSelect(targets.firstOrNull()?.x) }
    override fun onHidden(marker: CartesianMarker) { onSelect(null) }
}

/** Every [spacing]th label, counted back from the last day so today is always labeled. */
private fun labelSpacing(days: Int): Int = when {
    days <= 7 -> 1
    days <= 31 -> 7
    else -> 14
}

/**
 * Daily calories as bars against the allowance (budget + activity) as a dashed line (SPEC §4.10).
 * Bars over their day's allowance use the "over" color of the ring and today (not over yet) is
 * lighter. Tapping a bar shows its value; [onSelect] gets that day so the screen can offer to open it.
 */
@Composable
fun CaloriesChart(
    days: List<Trends.Day>,
    today: LocalDate,
    f: UnitFormatter,
    description: String,
    onSelect: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val producer = remember { CartesianChartModelProducer() }
    val kcal = days.map { f.energyValue(it.kcal ?: 0.0) }
    val allowance = days.map { f.energyValue(it.allowanceKcal) }
    LaunchedEffect(days, f.prefs) {
        producer.runTransaction {
            columnSeries { series(days.indices.toList(), kcal) }
            lineSeries { series(days.indices.toList(), allowance) }
        }
    }
    val c = ForkcastTheme.colors
    val under = remember(c.pine) { LineComponent(Fill(c.pine), 10.dp, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)) }
    val over = remember(c.plum) { LineComponent(Fill(c.plum), 10.dp, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)) }
    val inProgress = remember(c.pine) { LineComponent(Fill(c.pine.copy(alpha = 0.4f)), 10.dp, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)) }
    val dates = days.map { it.date }
    val provider = remember(days, today, under, over, inProgress) {
        object : ColumnCartesianLayer.ColumnProvider {
            override fun getColumn(entry: ColumnCartesianLayerModel.Entry, seriesIndex: Int, extraStore: ExtraStore): LineComponent {
                val d = days.getOrNull(entry.x.roundToInt()) ?: return under
                return when {
                    (d.kcal ?: 0.0) > d.allowanceKcal -> over
                    d.date == today -> inProgress
                    else -> under
                }
            }
            override fun getWidestSeriesColumn(seriesIndex: Int, extraStore: ExtraStore): LineComponent = under
        }
    }
    val top = max(kcal.maxOrNull() ?: 0.0, allowance.maxOrNull() ?: 0.0).coerceAtLeast(1.0) * 1.08
    val step = niceStep(top)
    val maxY = ceil(top / step) * step
    val range = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxY) }
    val locale = Locale.getDefault()
    val dayFormat = remember(locale, days.size) {
        DateTimeFormatter.ofPattern(if (days.size <= 7) "EEE" else android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    }
    val spacing = labelSpacing(days.size)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentDates by rememberUpdatedState(dates)
    val markerText = remember(days, f) {
        DefaultCartesianMarker.ValueFormatter { _: CartesianDrawingContext, targets ->
            val d = targets.firstOrNull()?.x?.roundToInt()?.let { days.getOrNull(it) } ?: return@ValueFormatter ""
            val date = d.date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale))
            "$date\n" + (d.kcal?.let { f.energy(it) } ?: "—")
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = provider,
                columnCollectionSpacing = if (days.size > 31) 2.dp else 6.dp,
                rangeProvider = range,
            ),
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.onSurfaceVariant)),
                        stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 2.dp),
                        areaFill = null,
                    ),
                ),
                rangeProvider = range,
            ),
            startAxis = VerticalAxis.rememberStart(
                label = axisLabel(),
                valueFormatter = remember(f) { CartesianValueFormatter { _, y, _ -> f.number(y) } },
                itemPlacer = remember(step) { VerticalAxis.ItemPlacer.step({ step }) },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel(),
                guideline = null,
                valueFormatter = remember(dates, dayFormat) {
                    CartesianValueFormatter { _, x, _ -> dates.getOrNull(x.roundToInt())?.format(dayFormat) ?: " " }
                },
                itemPlacer = remember(spacing, days.size) {
                    HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }, offset = { (days.size - 1) % spacing })
                },
            ),
            marker = rememberDefaultCartesianMarker(label = markerLabel(), valueFormatter = markerText),
            markerVisibilityListener = remember { selectionListener { x -> currentOnSelect(x?.roundToInt()?.let { currentDates.getOrNull(it) }) } },
            markerController = CartesianMarkerController.rememberToggleOnTap(),
        ),
        modelProducer = producer,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = Zoom.Content),
        modifier = modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description },
    )
}

/**
 * Raw weigh-ins as dots and the smoothed trend as a line (SPEC §4.9, §5.5). The y-axis fits the
 * data with round steps in the user's unit. Press and hold to read a day's values.
 */
@Composable
fun WeightTrendChart(
    points: List<TrendPoint>,
    f: UnitFormatter,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val producer = remember { CartesianChartModelProducer() }
    val first = points.first().date
    val raw = points.filter { it.rawKg != null }
    LaunchedEffect(points, f.prefs) {
        producer.runTransaction {
            lineSeries {
                // Vico needs every series non-empty: with no weigh-ins in view, repeat the trend.
                if (raw.isNotEmpty()) {
                    series(x = raw.map { it.date.toEpochDay() - first.toEpochDay() }, y = raw.map { f.weightValue(it.rawKg!!) })
                } else {
                    series(x = listOf(0L), y = listOf(f.weightValue(points.first().trendKg)))
                }
                series(x = points.map { it.date.toEpochDay() - first.toEpochDay() }, y = points.map { f.weightValue(it.trendKg) })
            }
        }
    }
    val values = raw.map { f.weightValue(it.rawKg!!) } + points.map { f.weightValue(it.trendKg) }
    val lo = values.min()
    val hi = values.max()
    // At least 2 units of range so a flat week doesn't look like a cliff.
    val step = niceStep(maxOf(hi - lo, 2.0), lines = 4)
    val minY = floor((lo - step / 2) / step) * step
    val maxY = ceil((hi + step / 2) / step) * step
    val range = remember(minY, maxY) { CartesianLayerRangeProvider.fixed(minY = minY, maxY = maxY) }
    val decimals = if (step < 1) 1 else 0
    val pine = ForkcastTheme.colors.pine
    val dots = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = Locale.getDefault()
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val span = points.size
    val byOffset = remember(points) { points.associateBy { it.date.toEpochDay() - first.toEpochDay() } }
    val markerText = remember(points, f) {
        DefaultCartesianMarker.ValueFormatter { _: CartesianDrawingContext, targets ->
            val p = targets.firstOrNull()?.x?.roundToInt()?.toLong()?.let { byOffset[it] } ?: return@ValueFormatter ""
            val head = p.date.format(dateFormat) + " · " + f.weight(p.trendKg)
            p.rawKg?.let { "$head\n" + f.weight(it) } ?: head
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    // Weigh-ins: points only, no connecting stroke.
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                        areaFill = null,
                        pointProvider = LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(rememberShapeComponent(Fill(if (raw.isEmpty()) Color.Transparent else dots), CircleShape), 6.dp),
                        ),
                    ),
                    // Trend line.
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(pine)),
                        areaFill = null,
                    ),
                ),
                rangeProvider = range,
            ),
            startAxis = VerticalAxis.rememberStart(
                label = axisLabel(),
                valueFormatter = remember(f, decimals) { CartesianValueFormatter { _, y, _ -> f.number(y, decimals) } },
                itemPlacer = remember(step) { VerticalAxis.ItemPlacer.step({ step }) },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel(),
                guideline = null,
                valueFormatter = CartesianValueFormatter { _, x, _ -> first.plusDays(x.toLong()).format(dateFormat) },
                itemPlacer = remember(span) {
                    val spacing = maxOf(1, span / 4)
                    HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }, offset = { (span - 1) % spacing })
                },
            ),
            marker = rememberDefaultCartesianMarker(label = markerLabel(), valueFormatter = markerText),
        ),
        modelProducer = producer,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = Zoom.Content),
        modifier = modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description },
    )
}
