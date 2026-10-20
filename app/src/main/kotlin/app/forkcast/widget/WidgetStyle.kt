package app.forkcast.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import app.forkcast.R
import app.forkcast.ui.theme.DarkForkcastColors
import app.forkcast.ui.theme.ForkcastColors
import app.forkcast.ui.theme.LightForkcastColors

/**
 * The widget's color, named for how the widget looks (as in Samsung's widget settings): Light is
 * light glass with dark text, Dark is dark glass with light text, Auto follows the phone's dark mode.
 */
enum class WidgetTheme { AUTO, LIGHT, DARK }

/**
 * Per-widget look (DESIGN.md §11), chosen when the widget is added and changeable later from the
 * widget's Settings: how strong the glass background is, 0 (transparent) to 1 (solid), and the
 * widget color.
 */
data class WidgetStyle(val background: Float = DEFAULT_GLASS, val theme: WidgetTheme = WidgetTheme.AUTO) {
    companion object {
        val BACKGROUND = floatPreferencesKey("background")
        val THEME = stringPreferencesKey("theme")
        /** The first build stored the text color instead: light text = Dark widget, and back. */
        private val OLD_TEXT = stringPreferencesKey("text")
        /** Frosted, like One UI's own widgets: the wallpaper shows through, the text stays easy to read. */
        const val DEFAULT_GLASS = 0.75f

        fun from(prefs: Preferences) = WidgetStyle(
            background = prefs[BACKGROUND] ?: DEFAULT_GLASS,
            theme = prefs[THEME]?.let { runCatching { WidgetTheme.valueOf(it) }.getOrNull() }
                ?: when (prefs[OLD_TEXT]) {
                    "LIGHT" -> WidgetTheme.DARK
                    "DARK" -> WidgetTheme.LIGHT
                    else -> WidgetTheme.AUTO
                },
        )
    }
}

/**
 * Colors for one widget: the light palette for a Light widget, the dark one for Dark; Auto
 * switches with the system theme. Tracks are the text color at low alpha,
 * so arcs and bars stay visible on any wallpaper even with a fully transparent background.
 */
class WidgetPalette(private val style: WidgetStyle) {
    private val light = LightForkcastColors
    private val dark = DarkForkcastColors

    private fun pick(f: (ForkcastColors) -> Color, alpha: Float = 1f): ColorProvider = when (style.theme) {
        WidgetTheme.AUTO -> ColorProvider(day = f(light).copy(alpha = alpha), night = f(dark).copy(alpha = alpha))
        WidgetTheme.LIGHT -> ColorProvider(day = f(light).copy(alpha = alpha), night = f(light).copy(alpha = alpha))
        WidgetTheme.DARK -> ColorProvider(day = f(dark).copy(alpha = alpha), night = f(dark).copy(alpha = alpha))
    }

    /**
     * The glass: a fill in the phone's wallpaper-tinted neutral (Android 12+) at [opacity], with a
     * sheen and a thin bright rim on top.
     * Apps can't blur what's behind a widget (only the launcher can), so this is how it reads as glass.
     */
    val opacity = style.background.coerceIn(0f, 1f)
    val glassFill: Int = when (style.theme) {
        WidgetTheme.AUTO -> R.drawable.widget_glass_fill_auto
        WidgetTheme.LIGHT -> R.drawable.widget_glass_fill_light
        WidgetTheme.DARK -> R.drawable.widget_glass_fill_dark
    }
    val glassRim: Int = when (style.theme) {
        WidgetTheme.AUTO -> R.drawable.widget_glass_rim_auto
        WidgetTheme.LIGHT -> R.drawable.widget_glass_rim_light
        WidgetTheme.DARK -> R.drawable.widget_glass_rim_dark
    }
    /** The rim fades in with the glass: none at 0 %, full from about 60 %. */
    val rimAlpha = (opacity / RIM_FULL_AT).coerceAtMost(1f)
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
        /** Same as the ring tracks (WidgetArt), so bars and rings match. */
        const val TRACK_ALPHA = WidgetArt.TRACK_ALPHA
        const val BUTTON_ALPHA = 0.12f
        /** Below this background opacity the wallpaper shows through enough to need stronger labels. */
        const val SEE_THROUGH = 0.5f
        const val RIM_FULL_AT = 0.6f
    }
}
