package app.forkcast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ForkcastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useWallpaperColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        useWallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val brand = if (darkTheme) DarkForkcastColors else LightForkcastColors

    CompositionLocalProvider(LocalForkcastColors provides brand) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ForkcastTypography,
            content = content,
        )
    }
}

/** Brand tokens that Material roles don't cover (apricot, plum, nutrient colors). */
object ForkcastTheme {
    val colors: ForkcastColors
        @Composable @ReadOnlyComposable
        get() = LocalForkcastColors.current
}
