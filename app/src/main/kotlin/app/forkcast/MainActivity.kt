package app.forkcast

import android.content.Intent
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
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.health.SyncReason
import app.forkcast.core.data.notify.Notifier
import app.forkcast.core.model.ThemeMode
import app.forkcast.navigation.ForkcastNavHost
import app.forkcast.ui.theme.ForkcastTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var health: HealthRepository

    /** What a tapped notification asked to open ("estimate:<id>", "settings_ai"), consumed once. */
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.start.value == null }
        enableEdgeToEdge()
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)

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
                val open by openRequest.collectAsStateWithLifecycle()
                ForkcastNavHost(
                    startWithOnboarding = needsOnboarding,
                    openRequest = open,
                    onOpenHandled = { openRequest.value = null },
                )
            }
        }
    }

    /** Health Connect reads are allowed while the app is in the foreground (throttled in the repository). */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { runCatching { health.sync(SyncReason.OPEN) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_OPEN)?.let { openRequest.value = it }
    }
}
