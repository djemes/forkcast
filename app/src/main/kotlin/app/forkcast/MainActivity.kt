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
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.model.ThemeMode
import app.forkcast.navigation.ForkcastNavHost
import app.forkcast.ui.common.ProvideLinkOpener
import app.forkcast.ui.legal.Agreement
import app.forkcast.ui.legal.AgreementScreen
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.widget.FoodOpen
import app.forkcast.widget.WidgetData
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // The app's own language on Android 10–12 (AppLanguage).
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.forkcast.AppLanguage.wrap(newBase))

    // Lazy and used off the main thread: the first frame shouldn't wait for Health Connect.
    @Inject lateinit var health: dagger.Lazy<HealthRepository>
    @Inject lateinit var maintenance: dagger.Lazy<MaintenanceRepository>
    @Inject lateinit var estimates: dagger.Lazy<app.forkcast.core.data.ai.AiEstimateRepository>
    @Inject lateinit var clock: java.time.Clock

    /** What a tapped notification asked to open ("estimate:<id>", "settings_ai"), consumed once. */
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.start.value == null }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleOpen(intent?.getStringExtra(Notifier.EXTRA_OPEN))

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

            ForkcastTheme(darkTheme = darkTheme, useWallpaperColors = appearance.useWallpaperColors) { ProvideLinkOpener(darkTheme) {
                // The terms come before anything else, onboarding included (roadmap 2.0, task 4.1).
                if (current.needsAgreement) {
                    AgreementScreen(
                        whatChanged = current.acceptedVersion?.let { Agreement.changes[Agreement.VERSION] },
                        onAccept = viewModel::acceptAgreement,
                        onDecline = ::finishAndRemoveTask,
                    )
                } else {
                    // Decided once per launch, so finishing onboarding doesn't rebuild the graph mid-transition.
                    val needsOnboarding = remember { current.needsOnboarding }
                    val open by openRequest.collectAsStateWithLifecycle()
                    ForkcastNavHost(
                        startWithOnboarding = needsOnboarding,
                        openRequest = open,
                        onOpenHandled = { openRequest.value = null },
                    )
                }
            } }
        }
    }

    /** Health Connect reads are allowed while the app is in the foreground (throttled in the repository). */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { health.get().sync(SyncReason.OPEN) }
            // Weekly adaptive maintenance (SPEC §5.6), after the scale's weigh-ins are in.
            runCatching { maintenance.get().updateIfDue() }
            // Old meal photos go even when the app stays in memory for days.
            runCatching { estimates.get().cleanUpPhotos() }
            // A retry Doze held back runs now that the app is open.
            runCatching { estimates.get().recover() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent.getStringExtra(Notifier.EXTRA_OPEN))
    }

    /**
     * Widget, shortcut and tile requests that log food (Log, Scan, Photo, Search) go to today and
     * the meal that fits the time of the tap; the rest pass through as they are.
     */
    private fun handleOpen(open: String?) {
        when (open) {
            null -> Unit
            in FoodOpen.KINDS -> lifecycleScope.launch {
                val meal = runCatching { WidgetData.mealNow(applicationContext) }.getOrNull()
                openRequest.value = FoodOpen.of(open, meal, java.time.LocalDate.now(clock)).encode()
            }
            else -> openRequest.value = open
        }
    }
}
