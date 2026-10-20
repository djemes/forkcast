package app.forkcast.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import app.forkcast.ui.theme.DarkForkcastColors
import app.forkcast.ui.theme.ForkcastColors
import app.forkcast.ui.theme.LightForkcastColors

/** Text and icon color, like Samsung's widgets: follow the system theme, or force light or dark. */
enum class TextMode { AUTO, LIGHT, DARK }

/**
 * Per-widget look (DESIGN.md §11), chosen when the widget is added and changeable later from the
 * widget's Settings: background opacity 0 (transparent) to 1 (solid) and the text color.
 */
data class WidgetStyle(val background: Float = 1f, val text: TextMode = TextMode.AUTO) {
    companion object {
        val BACKGROUND = floatPreferencesKey("background")
        val TEXT = stringPreferencesKey("text")

        fun from(prefs: Preferences) = WidgetStyle(
            background = prefs[BACKGROUND] ?: 1f,
            text = prefs[TEXT]?.let { runCatching { TextMode.valueOf(it) }.getOrNull() } ?: TextMode.AUTO,
        )
    }
}

/**
 * Colors for one widget. Light text sits on the dark palette (for dark wallpapers), dark text on
 * the light palette; Auto switches with the system theme. Tracks are the text color at low alpha,
 * so arcs and bars stay visible on any wallpaper even with a fully transparent background.
 */
class WidgetPalette(private val style: WidgetStyle) {
    private val light = LightForkcastColors
    private val dark = DarkForkcastColors

    private fun pick(f: (ForkcastColors) -> Color, alpha: Float = 1f): ColorProvider = when (style.text) {
        TextMode.AUTO -> ColorProvider(day = f(light).copy(alpha = alpha), night = f(dark).copy(alpha = alpha))
        TextMode.DARK -> ColorProvider(day = f(light).copy(alpha = alpha), night = f(light).copy(alpha = alpha))
        TextMode.LIGHT -> ColorProvider(day = f(dark).copy(alpha = alpha), night = f(dark).copy(alpha = alpha))
    }

    val background = pick({ it.surface }, style.background.coerceIn(0f, 1f))
    val text = pick({ it.ink })
    /** Secondary text: the muted gray on a background, the text color itself (slightly soft) when see-through. */
    val muted = if (style.background < SEE_THROUGH) pick({ it.ink }, 0.8f) else pick({ it.muted })
    val track = pick({ it.ink }, TRACK_ALPHA)
    val pine = pick({ it.pine })
    val apricot = pick({ it.apricot })
    val plum = pick({ it.plum })
    val protein = pick({ it.protein })
    val carbs = pick({ it.carbs })
    val fat = pick({ it.fat })
    /** Water uses the protein blue, as on Today's water bar. */
    val water = protein
    /** Quiet buttons: the text color at low strength, so they suit any wallpaper. */
    val button = pick({ it.ink }, BUTTON_ALPHA)

    private companion object {
        const val TRACK_ALPHA = 0.18f
        const val BUTTON_ALPHA = 0.12f
        /** Below this background opacity the wallpaper shows through enough to need stronger labels. */
        const val SEE_THROUGH = 0.5f
    }
}
