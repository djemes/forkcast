package app.forkcast.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.GlanceId
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
 * stopped as soon as Forkcast is in the background and come back minutes later, so a tap on the
 * water widget saved the glass but the widget didn't change (owner, 2026-10-20). Rendering here
 * works wherever we are: in the app, in a widget tap, or in a worker.
 */
object WidgetPush {

    /**
     * Every Forkcast widget, from [snapshot] (read once here when the caller doesn't have it).
     * Falls back to Glance's own update if rendering fails. Also plans the refresh at midnight.
     */
    suspend fun all(context: Context, snapshot: TodaySnapshot? = null) {
        val ids = GlanceAppWidgetManager(context)
        val any = runCatching { ids.getGlanceIds(CaloriesWidget::class.java).isNotEmpty() || ids.getGlanceIds(WaterWidget::class.java).isNotEmpty() }.getOrDefault(true)
        if (!any) return
        val s = snapshot ?: WidgetData.today(context)
        renderLock.withLock {
            push(context, CaloriesWidget::class.java) { CaloriesWidget(frameOf = s) }
            push(context, WaterWidget::class.java) { WaterWidget(frameOf = s) }
        }
        refreshAtMidnight(context)
    }

    /**
     * The widgets show today, so they re-render when the day changes. A non-wakeup alarm: it
     * fires when the phone next wakes after midnight, which is before anyone can see the widget.
     * Replaces `updatePeriodMillis`, which woke the phone and started Forkcast every 30 minutes.
     */
    fun refreshAtMidnight(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST,
            Intent(context, UndoExpiredReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = java.time.LocalDate.now().plusDays(1).atTime(0, 1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.set(AlarmManager.RTC, next, intent)
    }

    /**
     * The water arc filling from [from] to [to] on one widget, as a few quick frames (widgets
     * can't animate on their own; DESIGN.md §11), eased out.
     */
    suspend fun fillWater(context: Context, glanceId: GlanceId, from: Float, after: TodaySnapshot) {
        val to = after.waterFraction
        if (to <= from) return
        val ids = GlanceAppWidgetManager(context)
        val manager = AppWidgetManager.getInstance(context)
        val appWidgetId = runCatching { ids.getAppWidgetId(glanceId) }.getOrNull() ?: return
        val options = manager.getAppWidgetOptions(appWidgetId)
        // One frame at a time (Glance renders one composition per widget at once); a frame that
        // takes longer than FILL_FRAME_MS to render just shows for that long. The lock keeps a
        // data-change render from landing between frames.
        renderLock.withLock { fillFrames(context, glanceId, appWidgetId, options, from, to, after) }
    }

    private suspend fun fillFrames(context: Context, glanceId: GlanceId, appWidgetId: Int, options: android.os.Bundle, from: Float, to: Float, after: TodaySnapshot) {
        val manager = AppWidgetManager.getInstance(context)
        for (i in 1..FILL_FRAMES) {
            val started = SystemClock.uptimeMillis()
            val t = i / FILL_FRAMES.toFloat()
            val fill = from + (to - from) * (1 - (1 - t) * (1 - t))
            runCatching { manager.updateAppWidget(appWidgetId, WaterWidget(fill, after).compose(context, glanceId, options)) }
            delay((FILL_FRAME_MS - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
        }
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

    /** Takes "Undo" off the water widget once its window closes, even if Forkcast isn't running. */
    fun hideUndoLater(context: Context, afterMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, UndoExpiredReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Inexact is fine: Undo just stays a few seconds longer if Android batches the alarm.
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + afterMs + 250, intent)
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
    /** One render pass at a time, so a fill animation and a data-change render don't interleave. */
    private val renderLock = kotlinx.coroutines.sync.Mutex()
    private const val MIDNIGHT_REQUEST = 1
    private const val SOON_MS = 300L
    private const val FILL_FRAMES = 6
    private const val FILL_FRAME_MS = 55L
}

/** Re-renders the widgets when the water widget's "Undo" window closes, and after midnight. */
class UndoExpiredReceiver : BroadcastReceiver() {
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
