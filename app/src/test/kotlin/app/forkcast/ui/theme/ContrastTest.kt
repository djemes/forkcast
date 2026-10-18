package app.forkcast.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** WCAG AA contrast on the color tokens (DESIGN.md §2): 4.5:1 for text, 3:1 for graphics. */
class ContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertWithMessage("$name contrast %.2f < %.1f".format(ratio, min)).that(ratio).isAtLeast(min)
    }

    @Test fun lightTextMeetsAA() = textPairs(LightColorScheme.let { s ->
        listOf(
            Triple("ink on porcelain", s.onBackground, s.background),
            Triple("ink on surface", s.onSurface, s.surfaceContainerLow),
            Triple("muted on porcelain", s.onSurfaceVariant, s.background),
            Triple("muted on surface", s.onSurfaceVariant, s.surfaceContainerLow),
            Triple("onPrimary on pine", s.onPrimary, s.primary),
            Triple("pine text on porcelain", s.primary, s.background),
            Triple("pine text on surface", s.primary, s.surfaceContainerLow),
            Triple("tertiary on surface", s.tertiary, s.surfaceContainerLow),
            Triple("error on surface", s.error, s.surfaceContainerLow),
            Triple("plum on surface", LightForkcastColors.plum, LightForkcastColors.surface),
            Triple("plum on porcelain", LightForkcastColors.plum, LightForkcastColors.porcelain),
            Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
        )
    })

    @Test fun darkTextMeetsAA() = textPairs(DarkColorScheme.let { s ->
        listOf(
            Triple("ink on porcelain", s.onBackground, s.background),
            Triple("ink on surface", s.onSurface, s.surfaceContainerLow),
            Triple("muted on porcelain", s.onSurfaceVariant, s.background),
            Triple("muted on surface", s.onSurfaceVariant, s.surfaceContainerLow),
            Triple("onPrimary on pine", s.onPrimary, s.primary),
            Triple("pine text on porcelain", s.primary, s.background),
            Triple("pine text on surface", s.primary, s.surfaceContainerLow),
            Triple("tertiary on surface", s.tertiary, s.surfaceContainerLow),
            Triple("error on surface", s.error, s.surfaceContainerLow),
            Triple("plum on surface", DarkForkcastColors.plum, DarkForkcastColors.surface),
            Triple("plum on porcelain", DarkForkcastColors.plum, DarkForkcastColors.porcelain),
            Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
        )
    })

    /** Ring arcs and nutrient bars are graphics: 3:1 against the surface they sit on. */
    @Test fun graphicsMeetThreeToOne() {
        for (c in listOf(LightForkcastColors, DarkForkcastColors)) {
            for ((name, color) in listOf(
                "pine" to c.pine, "plum" to c.plum,
                "protein" to c.protein, "fat" to c.fat, "fiber" to c.fiber,
            )) {
                check("$name on surface", color, c.surface, 3.0)
            }
        }
    }

    private fun textPairs(pairs: List<Triple<String, Color, Color>>) =
        pairs.forEach { (name, fg, bg) -> check(name, fg, bg, 4.5) }
}
