package app.forkcast

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.core.model.ThemeMode
import app.forkcast.navigation.ForkcastNavHost
import app.forkcast.ui.theme.ForkcastTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.start.value == null }
        enableEdgeToEdge()

        setContent {
            val start by viewModel.start.collectAsStateWithLifecycle()
            val current = start ?: return@setContent
            val appearance = current.settings.appearance
            val darkTheme = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Status/navigation bar icons follow the app theme, not just the system theme.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }

            ForkcastTheme(darkTheme = darkTheme, useWallpaperColors = appearance.useWallpaperColors) {
                // Decided once per launch, so finishing onboarding doesn't rebuild the graph mid-transition.
                val needsOnboarding = remember { current.needsOnboarding }
                ForkcastNavHost(startWithOnboarding = needsOnboarding)
            }
        }
    }
}
