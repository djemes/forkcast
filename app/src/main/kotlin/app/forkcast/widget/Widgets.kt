package app.forkcast.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.forkcast.MainActivity
import app.forkcast.R
import app.forkcast.core.data.notify.Notifier
import app.forkcast.core.domain.BudgetState
import app.forkcast.ui.format.UnitFormatter

/** Opens Forkcast at [open] (a route hint, see ForkcastNavHost), or on Today when null. */
internal fun openApp(context: Context, open: String? = null): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setFlags(OPEN_FLAGS)
        .apply { if (open != null) putExtra(Notifier.EXTRA_OPEN, open) },
)

/**
 * One UI-matching frame (DESIGN.md §11): the system widget corner radius and the glass background
 * (tinted fill at the chosen opacity, then sheen and rim), with the content on top.
 */
@Composable
private fun WidgetFrame(palette: WidgetPalette, padding: Dp, onClick: Action?, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius),
    ) {
        if (palette.opacity > 0f) {
            Glass(palette.glassFill, palette.opacity)
            Glass(palette.glassRim, palette.rimAlpha)
        }
        var inner = GlanceModifier.fillMaxSize()
        if (onClick != null) inner = inner.clickable(onClick)
        Box(modifier = inner.padding(padding), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Glass(drawable: Int, alpha: Float) {
    Image(
        ImageProvider(drawable),
        contentDescription = null,
        alpha = alpha,
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.fillMaxSize(),
    )
}

/** An alpha mask tinted with [color]. */
@Composable
private fun Mask(bitmap: Bitmap, color: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    Image(ImageProvider(bitmap), contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = modifier)
}

/** Pixels per dp for widget art, capped so resent bitmaps stay small. */
private fun Context.artScale(): Float = resources.displayMetrics.density.coerceAtMost(3f)

/** The main number in Manrope with tabular figures, [height] tall, shrunk to fit [maxWidth]. */
@Composable
private fun BigNumber(text: String, height: Dp, color: ColorProvider, maxWidth: Dp = Dp.Infinity) {
    val context = LocalContext.current
    val scale = context.artScale()
    val bmp = remember(text, height, maxWidth) {
        val first = WidgetArt.number(context, text, (height.value * scale).toInt().coerceAtLeast(4))
        val limit = maxWidth.value * scale
        if (first.width <= limit) first
        else WidgetArt.number(context, text, (height.value * scale * limit / first.width).toInt().coerceAtLeast(4))
    }
    Mask(bmp, color, GlanceModifier.width((bmp.width / scale).dp).height((bmp.height / scale).dp))
}

/** The budget ring (fill, track and the apricot "now" dot), with content in the middle. */
@Composable
private fun Gauge(size: Dp, fraction: Float, fill: ColorProvider, palette: WidgetPalette, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scale = context.artScale()
    val geometry = WidgetArt.Gauge((size.value * scale).toInt().coerceAtLeast(8), STROKE)
    val track = remember(geometry) { WidgetArt.track(geometry) }
    val arc = remember(geometry, fraction) { WidgetArt.fill(geometry, fraction) }
    val dot = remember(geometry, fraction) { WidgetArt.dot(geometry, fraction) }
    Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(size)) {
        Mask(track, palette.text, GlanceModifier.fillMaxSize())
        Mask(arc, fill, GlanceModifier.fillMaxSize())
        Mask(dot, palette.apricot, GlanceModifier.fillMaxSize())
        content()
    }
}

private const val STROKE = 0.085f

// ---- Calories ----

/**
 * Calories left today (SPEC §3 Phase 5, DESIGN.md §11). Resizable from 1×1 to full width:
 * small sizes = ring and number; 4×1 strip = ring, number and Log; wide = ring + protein, carbs
 * and fat; tall adds Log / Scan / Photo. [frameOf]: render this snapshot once (WidgetPush)
 * instead of reading and watching the data.
 */
class CaloriesWidget(private val frameOf: TodaySnapshot? = null) : GlanceAppWidget() {
    // Exact: layouts follow the real size and re-render on every resize.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = frameOf ?: WidgetData.today(context)
        val updates = if (frameOf != null) kotlinx.coroutines.flow.flowOf(frameOf) else WidgetData.observeToday(context)
        provideContent {
            val snapshot by updates.collectAsState(initial)
            CaloriesContent(snapshot, WidgetStyle.from(currentState()))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { CaloriesContent(sampleSnapshot(), WidgetStyle()) }
    }

    companion object {
        val TINY = DpSize(57.dp, 57.dp)
        val SMALL = DpSize(110.dp, 110.dp)
        val STRIP = DpSize(220.dp, 57.dp)
        val WIDE = DpSize(220.dp, 110.dp)
        val LARGE = DpSize(220.dp, 170.dp)
    }
}

class CaloriesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaloriesWidget()

    // Glance renders these in a background job One UI can hold back; render right away too.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetPush.soon(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetPush.soon(context)
    }
}

@Composable
internal fun CaloriesContent(s: TodaySnapshot, style: WidgetStyle) {
    val context = LocalContext.current
    val palette = remember(style) { WidgetPalette(style) }
    val size = LocalSize.current
    val f = s.format
    val totals = s.totals
    val over = totals?.state == BudgetState.OVER
    val number = totals?.let { f.energyNumber(if (over) it.overKcal else it.remainingKcal) } ?: "—"
    val label = when {
        totals == null -> context.getString(R.string.widget_set_up)
        over -> context.getString(R.string.ring_over, f.energyUnitLabel)
        else -> context.getString(R.string.ring_left, f.energyUnitLabel)
    }
    val stateColor = when (totals?.state) {
        BudgetState.APPROACHING -> palette.apricot
        BudgetState.OVER -> palette.plum
        else -> palette.pine
    }
    val fraction = totals?.fraction?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    val numberColor = if (over) palette.plum else palette.text
    val description = "$number $label"
    val shortest = min(size.width, size.height)
    val pad = if (shortest < 100.dp) 8.dp else 12.dp
    val innerW = size.width - pad * 2
    val innerH = size.height - pad * 2
    val strip = size.height < 100.dp && size.width >= 200.dp
    val square = !strip && size.width < 200.dp

    WidgetFrame(palette, padding = pad, onClick = openApp(context)) {
        when {
            strip -> Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxSize()) {
                val ring = min(innerH, 44.dp)
                Gauge(ring, fraction, stateColor, palette) {}
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight().semantics { contentDescription = description }) {
                    BigNumber(number, 22.dp, numberColor)
                    Text(label, style = TextStyle(fontSize = 11.sp, color = palette.muted), maxLines = 1)
                }
                ActionButton(R.drawable.ic_add, context.getString(R.string.log), openApp(context, OPEN_LOG), palette, GlanceModifier.width(48.dp))
            }
            square -> {
                val ring = min(innerW, innerH)
                val tiny = ring < 72.dp
                Gauge(ring, fraction, stateColor, palette) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.semantics { contentDescription = description }) {
                        BigNumber(number, ring * 0.26f, numberColor, maxWidth = ring * 0.66f)
                        if (!tiny) Text(label, style = TextStyle(fontSize = 11.sp, color = palette.muted, textAlign = TextAlign.Center), maxLines = 1)
                    }
                }
            }
            // One row and up: ring on the left; macros with Log · Scan · Photo under them on the right.
            // Everything keeps its size; extra room only centers it, so the default 4x1 has no empty band.
            else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxSize()) {
                val ring = min(min(innerH, innerW * 0.36f), 112.dp)
                Gauge(ring, fraction, stateColor, palette) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.semantics { contentDescription = description }) {
                        BigNumber(number, ring * 0.24f, numberColor, maxWidth = ring * 0.68f)
                        if (ring >= 80.dp) Text(label, style = TextStyle(fontSize = 10.sp, color = palette.muted), maxLines = 1)
                    }
                }
                Spacer(GlanceModifier.width(14.dp))
                Column(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.defaultWeight()) {
                    MacroLine(context.getString(R.string.macro_protein), s.proteinG, s.proteinTargetG, palette.protein, palette, f)
                    MacroLine(context.getString(R.string.macro_carbs), s.carbsG, s.carbsTargetG, palette.carbs, palette, f)
                    MacroLine(context.getString(R.string.macro_fat), s.fatG, s.fatTargetG, palette.fat, palette, f)
                    if (innerH >= 98.dp) {
                        Spacer(GlanceModifier.height(6.dp))
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            ActionButton(R.drawable.ic_add, context.getString(R.string.log), openApp(context, OPEN_LOG), palette, GlanceModifier.defaultWeight())
                            Spacer(GlanceModifier.width(6.dp))
                            ActionButton(R.drawable.ic_barcode, context.getString(R.string.shortcut_scan), openApp(context, OPEN_SCAN), palette, GlanceModifier.defaultWeight())
                            Spacer(GlanceModifier.width(6.dp))
                            ActionButton(R.drawable.ic_photo_camera, context.getString(R.string.shortcut_photo), openApp(context, OPEN_PHOTO), palette, GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }
}

/**
 * A quiet button: a soft tint of the text color with the icon in the text color, so it suits
 * any wallpaper and any background opacity. [label] is what TalkBack reads.
 */
@Composable
private fun ActionButton(icon: Int, label: String, onClick: Action, palette: WidgetPalette, modifier: GlanceModifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(36.dp)
            .background(palette.button)
            .cornerRadius(18.dp)
            .clickable(onClick)
            .semantics { contentDescription = label },
    ) {
        Image(ImageProvider(icon), contentDescription = null, colorFilter = ColorFilter.tint(palette.text), modifier = GlanceModifier.size(18.dp))
    }
}

@Composable
private fun MacroLine(label: String, eaten: Double, target: Double, color: ColorProvider, palette: WidgetPalette, f: UnitFormatter) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(label, style = TextStyle(fontSize = 11.sp, color = palette.muted), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(
                "${f.number(eaten)} / ${f.grams(target)}",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.text),
                maxLines = 1,
            )
        }
        LinearProgressIndicator(
            progress = if (target > 0) (eaten / target).toFloat().coerceIn(0f, 1f) else 0f,
            color = color,
            backgroundColor = palette.track,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        )
    }
}

/** A plausible afternoon for the widget picker's previews and the settings screen. */
internal fun sampleSnapshot(): TodaySnapshot {
    val units = UnitFormatter.defaultsFor(java.util.Locale.getDefault())
    return TodaySnapshot(
        totals = app.forkcast.core.domain.DayTotals(emptyMap(), eatenKcal = 1_240.0, budgetKcal = 2_100, exerciseKcal = 0.0),
        proteinG = 92.0, proteinTargetG = 140.0,
        carbsG = 130.0, carbsTargetG = 230.0,
        fatG = 41.0, fatTargetG = 70.0,
        units = units,
    )
}

/** Route hints the widgets, shortcuts and tiles pass to MainActivity (Notifier.EXTRA_OPEN). */
const val OPEN_LOG = "log"
const val OPEN_SCAN = "scan"
const val OPEN_PHOTO = "photo"
const val OPEN_SEARCH = "search"

/**
 * Launch flags for widget and tile taps. SINGLE_TOP matters: with CLEAR_TOP alone a running
 * MainActivity is finished and recreated (splash again, back stack and typed input lost)
 * instead of getting onNewIntent. Notifications use the same flags.
 */
const val OPEN_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

/**
 * A food-logging request from a widget, shortcut or tile, resolved in MainActivity as
 * "<kind>:<meal id>:<date>": the meal for the time of the tap, and that day.
 */
data class FoodOpen(val kind: String, val mealId: String?, val date: java.time.LocalDate?) {
    fun encode(): String = "$kind:${mealId.orEmpty()}:${date ?: ""}"

    companion object {
        val KINDS = setOf(OPEN_LOG, OPEN_SCAN, OPEN_PHOTO, OPEN_SEARCH)

        /** Without a meal, Scan, Photo and Search open the Log sheet instead, so the tap is never lost. */
        fun of(kind: String, mealId: String?, date: java.time.LocalDate): FoodOpen =
            if (mealId == null) FoodOpen(OPEN_LOG, null, date) else FoodOpen(kind, mealId, date)

        fun parse(request: String): FoodOpen? {
            val parts = request.split(':')
            if (parts.size != 3 || parts[0] !in KINDS) return null
            val meal = parts[1].ifEmpty { null }
            if (meal == null && parts[0] != OPEN_LOG) return null
            val date = parts[2].ifEmpty { null }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            return FoodOpen(parts[0], meal, date)
        }
    }
}
