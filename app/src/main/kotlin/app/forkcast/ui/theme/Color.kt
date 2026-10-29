package app.forkcast.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Forkcast brand tokens (DESIGN.md §2). Material roles are derived from these below. */
@Immutable
data class ForkcastColors(
    val pine: Color,
    val pineDeep: Color,
    val apricot: Color,
    val plum: Color,
    val ink: Color,
    val porcelain: Color,
    val surface: Color,
    val muted: Color,
    val error: Color,
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val fiber: Color,
    /**
     * The macro and apricot hues as text (roadmap 2.0 task 3.6): the light-theme bar colors fall
     * below 4.5:1 on the light surfaces, so words and numbers use these darker shades (5:1 or more on
     * porcelain, white and the containers). Bars and dots keep the brighter hues.
     */
    val proteinText: Color,
    val carbsText: Color,
    val fatText: Color,
    val apricotText: Color,
    /**
     * The ring's forecast band (owner, 2026-10-29): a neutral grey a little darker than the
     * background in light theme and a little lighter in dark, so it reads as a guess and doesn't
     * compete with what was eaten.
     */
    val forecast: Color,
)

val LightForkcastColors = ForkcastColors(
    pine = Color(0xFF0F7B5F),
    pineDeep = Color(0xFF0A5A46),
    apricot = Color(0xFFF08A3E),
    plum = Color(0xFF8C4A8E),
    ink = Color(0xFF14201B),
    porcelain = Color(0xFFF3F6F4),
    surface = Color(0xFFFFFFFF),
    muted = Color(0xFF5E6B65),
    error = Color(0xFFB3261E),
    protein = Color(0xFF4C6FFF),
    carbs = Color(0xFFD99A1E),
    fat = Color(0xFFD45D79),
    fiber = Color(0xFF3E9B62),
    proteinText = Color(0xFF3B57D9),
    carbsText = Color(0xFF8A5A00),
    fatText = Color(0xFFA8324F),
    apricotText = Color(0xFFA3500F),
    forecast = Color(0xFFAEB2B0),
)

val DarkForkcastColors = ForkcastColors(
    pine = Color(0xFF5FCFA9),
    pineDeep = Color(0xFF3FAF8A),
    apricot = Color(0xFFFFB27A),
    plum = Color(0xFFD69AD8),
    ink = Color(0xFFE6EEEA),
    porcelain = Color(0xFF0E1512),
    surface = Color(0xFF16201C),
    muted = Color(0xFF9AA8A1),
    error = Color(0xFFF2B8B5),
    protein = Color(0xFF9DB0FF),
    carbs = Color(0xFFF2C46B),
    fat = Color(0xFFF29AAE),
    fiber = Color(0xFF86D3A3),
    // The dark-theme hues already read at 7:1 or more on the dark surfaces.
    proteinText = Color(0xFF9DB0FF),
    carbsText = Color(0xFFF2C46B),
    fatText = Color(0xFFF29AAE),
    apricotText = Color(0xFFFFB27A),
    forecast = Color(0xFF636866),
)

val LocalForkcastColors = staticCompositionLocalOf { LightForkcastColors }

// App background is porcelain; sheets and meal groups use the white/dark "surface" token,
// which maps to surfaceContainerLow (the M3 bottom-sheet default) and surfaceContainerLowest.
internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightForkcastColors.pine,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8EBDD),
    onPrimaryContainer = Color(0xFF00382A),
    inversePrimary = DarkForkcastColors.pine,
    secondary = Color(0xFF4E635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E7DD),
    onSecondaryContainer = Color(0xFF0C1F17),
    tertiary = Color(0xFF9A4A0E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC4),
    onTertiaryContainer = Color(0xFF331200),
    error = LightForkcastColors.error,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = LightForkcastColors.porcelain,
    onBackground = LightForkcastColors.ink,
    surface = LightForkcastColors.porcelain,
    onSurface = LightForkcastColors.ink,
    surfaceVariant = Color(0xFFDDE5E0),
    onSurfaceVariant = LightForkcastColors.muted,
    surfaceTint = LightForkcastColors.pine,
    inverseSurface = Color(0xFF29332E),
    inverseOnSurface = Color(0xFFECF2EE),
    outline = Color(0xFF75827C),
    outlineVariant = Color(0xFFC9D3CE),
    scrim = Color(0xFF000000),
    surfaceBright = LightForkcastColors.porcelain,
    surfaceDim = Color(0xFFD6DDD9),
    surfaceContainerLowest = LightForkcastColors.surface,
    surfaceContainerLow = LightForkcastColors.surface,
    surfaceContainer = Color(0xFFEBF0ED),
    surfaceContainerHigh = Color(0xFFE5EBE8),
    surfaceContainerHighest = Color(0xFFDFE6E2),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkForkcastColors.pine,
    onPrimary = Color(0xFF003828),
    primaryContainer = LightForkcastColors.pineDeep,
    onPrimaryContainer = Color(0xFFB9F0DA),
    inversePrimary = LightForkcastColors.pine,
    secondary = Color(0xFFB5CCC1),
    onSecondary = Color(0xFF20352C),
    secondaryContainer = Color(0xFF364B42),
    onSecondaryContainer = Color(0xFFD1E8DC),
    tertiary = DarkForkcastColors.apricot,
    onTertiary = Color(0xFF4F2500),
    tertiaryContainer = Color(0xFF713700),
    onTertiaryContainer = Color(0xFFFFDCC4),
    error = DarkForkcastColors.error,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = DarkForkcastColors.porcelain,
    onBackground = DarkForkcastColors.ink,
    surface = DarkForkcastColors.porcelain,
    onSurface = DarkForkcastColors.ink,
    surfaceVariant = Color(0xFF3A4741),
    onSurfaceVariant = DarkForkcastColors.muted,
    surfaceTint = DarkForkcastColors.pine,
    inverseSurface = DarkForkcastColors.ink,
    inverseOnSurface = Color(0xFF29332E),
    outline = Color(0xFF7D8A84),
    outlineVariant = Color(0xFF3A4741),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333D38),
    surfaceDim = DarkForkcastColors.porcelain,
    surfaceContainerLowest = Color(0xFF09100D),
    surfaceContainerLow = DarkForkcastColors.surface,
    surfaceContainer = Color(0xFF1A2420),
    surfaceContainerHigh = Color(0xFF242E2A),
    surfaceContainerHighest = Color(0xFF2F3934),
)
