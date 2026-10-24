package app.forkcast.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Renders widgets in this process and hands the result straight to the launcher.
 *
 * Glance normally renders inside a WorkManager job (its "session"). On One UI those jobs get
 * stopped as soon as Forkcast is in the background and come back minutes later, so a widget
 * could lag behind the log (owner, 2026-10-20). Rendering here works wherever we are: in the
 * app, in a receiver, or in a worker.
 */
object WidgetPush {

    /**
     * Every Calories widget, from [snapshot] (read once here when the caller doesn't have it).
     * Falls back to Glance's own update if rendering fails. Also plans the refresh at midnight.
     */
    suspend fun all(context: Context, snapshot: TodaySnapshot? = null) {
        val ids = GlanceAppWidgetManager(context)
        val any = runCatching { ids.getGlanceIds(CaloriesWidget::class.java).isNotEmpty() }.getOrDefault(true)
        if (!any) return
        val s = snapshot ?: WidgetData.today(context)
        renderLock.withLock {
            push(context, CaloriesWidget::class.java) { CaloriesWidget(frameOf = s) }
        }
        refreshAtMidnight(context)
    }

    /**
     * The widget shows today, so it re-renders when the day changes. A non-wakeup alarm: it
     * fires when the phone next wakes after midnight, which is before anyone can see the widget.
     * Replaces `updatePeriodMillis`, which woke the phone and started Forkcast every 30 minutes.
     */
    fun refreshAtMidnight(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST,
            Intent(context, MidnightReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = java.time.LocalDate.now().plusDays(1).atTime(0, 1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.set(AlarmManager.RTC, next, intent)
    }

    /**
     * [all], from a receiver that can't wait for it (the widget was added, resized or is due its
     * periodic refresh). The short wait lets Glance record a newly added widget first.
     */
    fun soon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(SOON_MS)
            all(app)
        }
    }

    private suspend fun <T : GlanceAppWidget> push(context: Context, type: Class<T>, widget: () -> T) {
        val ids = GlanceAppWidgetManager(context)
        val manager = AppWidgetManager.getInstance(context)
        val glanceIds = runCatching { ids.getGlanceIds(type) }.getOrDefault(emptyList())
        val failed = glanceIds.count { glanceId ->
            runCatching {
                val appWidgetId = ids.getAppWidgetId(glanceId)
                manager.updateAppWidget(appWidgetId, widget().compose(context, glanceId, manager.getAppWidgetOptions(appWidgetId)))
            }.isFailure
        }
        if (failed > 0) runCatching { widget().updateAll(context) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** One render pass at a time, so two data-change renders don't interleave. */
    private val renderLock = kotlinx.coroutines.sync.Mutex()
    private const val MIDNIGHT_REQUEST = 1
    private const val SOON_MS = 300L
}

/** Re-renders the widget after midnight. */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetPush.all(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
