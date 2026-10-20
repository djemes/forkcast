package app.forkcast.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.forkcast.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shapes Glance can't draw (arcs, Manrope numbers), as alpha masks. Each mask is shown in an
 * Image tinted with a ColorProvider, so one bitmap follows light/dark and the widget's text mode.
 * ALPHA_8 keeps them small enough to resend several times for the water fill animation.
 */
object WidgetArt {

    /** Ring geometry shared by the masks of one gauge. [gapDegrees] 0 = full circle. */
    data class Gauge(val sizePx: Int, val strokeFraction: Float, val gapDegrees: Float) {
        val stroke get() = sizePx * strokeFraction
        /** A full ring starts at the top (like Today's); a gauge starts after its bottom gap. */
        val startAngle get() = if (gapDegrees <= 0f) -90f else 90f + gapDegrees / 2
        val span get() = 360f - gapDegrees
        val rect get() = RectF(stroke, stroke, sizePx - stroke, sizePx - stroke)
    }

    /**
     * The empty part, drawn at [TRACK_ALPHA] and tinted with the text color: launchers ignore a
     * see-through tint, so the transparency lives in the mask.
     */
    fun track(g: Gauge): Bitmap = arc(g, g.span, TRACK_ALPHA)

    /** The filled part, [fraction] 0..1 of the gauge. */
    fun fill(g: Gauge, fraction: Float): Bitmap = arc(g, g.span * fraction.coerceIn(0f, 1f))

    /** The "now" dot at the end of the fill (the ring's apricot dot, DESIGN.md §1). */
    fun dot(g: Gauge, fraction: Float): Bitmap {
        val bmp = mask(g.sizePx, g.sizePx)
        val angle = Math.toRadians((g.startAngle + g.span * fraction.coerceIn(0f, 1f)).toDouble())
        val r = g.rect.width() / 2
        val c = g.sizePx / 2f
        Canvas(bmp).drawCircle(c + (r * cos(angle)).toFloat(), c + (r * sin(angle)).toFloat(), g.stroke * 0.8f, paint())
        return bmp
    }

    private fun arc(g: Gauge, sweep: Float, alpha: Float = 1f): Bitmap {
        val bmp = mask(g.sizePx, g.sizePx)
        if (sweep > 0.5f) {
            val p = paint().apply { style = Paint.Style.STROKE; strokeWidth = g.stroke; strokeCap = Paint.Cap.ROUND; this.alpha = (alpha * 255).toInt() }
            Canvas(bmp).drawArc(g.rect, g.startAngle, sweep, false, p)
        }
        return bmp
    }

    /**
     * [text] in Manrope ExtraBold with tabular figures (DESIGN.md §3), [heightPx] tall. Widgets
     * can't load the app's font in text views, so the main number is drawn here; labels use the
     * system font, which matches One UI's own widgets.
     */
    fun number(context: Context, text: String, heightPx: Int, weight: Int = 800): Bitmap {
        val p = paint().apply {
            typeface = manrope(context)
            textSize = heightPx.toFloat()
            fontFeatureSettings = "tnum"
            fontVariationSettings = "'wght' $weight"
        }
        val fm = p.fontMetrics
        val w = max(1, p.measureText(text).toInt() + 2)
        val h = max(1, (fm.descent - fm.ascent).toInt())
        val bmp = mask(w, h)
        Canvas(bmp).drawText(text, 1f, -fm.ascent, p)
        return bmp
    }

    private fun manrope(context: Context): Typeface =
        runCatching { ResourcesCompat.getFont(context, R.font.manrope) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private fun mask(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    /** How strong the empty part of a ring or gauge is, relative to the text color. */
    const val TRACK_ALPHA = 0.2f
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)
}
