package app.forkcast.widget

import android.content.Context
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WaterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the home-screen widgets current: whenever today's log, water, targets, settings or
 * activity bonus change (from the app, a worker, or a widget button), and when the day rolls over.
 * Widgets also refresh every 30 minutes on their own (appwidget-provider), which covers midnight
 * while the app isn't running.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LogRepository,
    private val water: WaterRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val today = flow {
            while (true) {
                emit(LocalDate.now(clock))
                delay(DAY_CHECK_MS)
            }
        }.distinctUntilChanged()
        // Picker previews (Android 15+), published once per app version (the system rate-limits it).
        scope.launch { runCatching { publishPreviews() } }
        scope.launch {
            today.flatMapLatest { day ->
                combine(
                    log.observeDay(day),
                    water.observeDay(day),
                    profiles.observeDailyTargets(),
                    settings.settings,
                    maintenance.observeActivityBonus(day),
                ) { a, b, c, d, e -> listOf(day, a, b, c, d, e) }
            }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { updateAllWidgets(context) }
        }
    }

    private suspend fun publishPreviews() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = app.forkcast.BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_VERSION, -1) == version) return
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val ok = listOf(CaloriesWidgetReceiver::class, WaterWidgetReceiver::class).all {
            manager.setWidgetPreviews(it) == androidx.glance.appwidget.GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
        }
        if (ok) prefs.edit().putInt(KEY_VERSION, version).apply()
    }

    private companion object {
        const val PREFS = "widgets"
        const val KEY_VERSION = "previews_version"
        const val DAY_CHECK_MS = 60_000L
        const val DEBOUNCE_MS = 400L
    }
}
