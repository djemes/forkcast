package app.forkcast.widget

import androidx.core.content.edit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Calories widget current: whenever what it shows changes (today's log, targets, units,
 * activity bonus), from the app or a worker, and when the day rolls over while Forkcast runs.
 * When it isn't running, a midnight alarm re-renders it
 * (WidgetPush.refreshAtMidnight).
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // Picker previews (Android 15+), published once per app version (the system rate-limits it).
        scope.launch { runCatching { publishPreviews() } }
        scope.launch {
            // The widget's own snapshot, so unrelated settings writes (an AI test, a toggle) don't
            // re-render it, and each render uses this value instead of reading the day again.
            WidgetData.observeToday(context)
                .debounce(DEBOUNCE_MS)
                .collect { runCatching { WidgetPush.all(context, it) } }
        }
    }

    private suspend fun publishPreviews() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = app.forkcast.BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_VERSION, -1) == version) return
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val ok = manager.setWidgetPreviews(CaloriesWidgetReceiver::class) == androidx.glance.appwidget.GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
        if (ok) prefs.edit { putInt(KEY_VERSION, version) }
    }

    private companion object {
        const val PREFS = "widgets"
        const val KEY_VERSION = "previews_version"
        const val DEBOUNCE_MS = 400L
    }
}
