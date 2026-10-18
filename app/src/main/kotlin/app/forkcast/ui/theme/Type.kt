package app.forkcast.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.forkcast.R

// Manrope is bundled (variable font, SIL OFL) so it works offline and without Google Play services.
private fun manrope(weight: Int) = Font(
    resId = R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Tabular figures, so digits that update don't jitter (DESIGN.md §3). */
const val TABULAR_NUMBERS = "tnum"

private fun style(size: Int, line: Int, weight: FontWeight, tnum: Boolean = false, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = if (tnum) TABULAR_NUMBERS else null,
)

/**
 * DESIGN.md §3 roles mapped onto Material roles:
 * ring number = displayLarge, screen title = headlineSmall/titleLarge,
 * meal headers = titleMedium, body = bodyLarge, secondary = bodyMedium,
 * small numbers in rows = labelLarge (tabular).
 */
val ForkcastTypography = Typography(
    displayLarge = style(56, 60, FontWeight.ExtraBold, tnum = true, tracking = -0.02),
    displayMedium = style(45, 52, FontWeight.ExtraBold, tnum = true),
    displaySmall = style(36, 44, FontWeight.ExtraBold, tnum = true),
    headlineLarge = style(32, 40, FontWeight.ExtraBold),
    headlineMedium = style(28, 36, FontWeight.ExtraBold),
    headlineSmall = style(24, 30, FontWeight.ExtraBold),
    titleLarge = style(24, 30, FontWeight.ExtraBold),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold, tnum = true),
    labelMedium = style(12, 16, FontWeight.SemiBold, tnum = true),
    labelSmall = style(11, 16, FontWeight.SemiBold, tnum = true),
)
