package app.forkcast.core.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import app.forkcast.core.data.R

/** One Android channel per notification type (SPEC §4.8), so each can be tuned in system settings. */
enum class Channel(val id: String, @StringRes val title: Int, val importance: Int) {
    BUDGET("budget", R.string.channel_budget, NotificationManager.IMPORTANCE_DEFAULT),
    EVENING("evening_outlook", R.string.channel_evening, NotificationManager.IMPORTANCE_DEFAULT),
    PROTEIN("protein_check", R.string.channel_protein, NotificationManager.IMPORTANCE_DEFAULT),
    MEAL_REMINDER("meal_reminder", R.string.channel_meal_reminder, NotificationManager.IMPORTANCE_DEFAULT),
    WEIGH_IN("weigh_in", R.string.channel_weigh_in, NotificationManager.IMPORTANCE_DEFAULT),
    ESTIMATE_READY("estimate_ready", R.string.channel_estimate_ready, NotificationManager.IMPORTANCE_DEFAULT),
    BACKUP_FAILED("backup_failed", R.string.channel_backup_failed, NotificationManager.IMPORTANCE_DEFAULT),
    /** Silent; only used for the foreground notice expedited work needs on Android 10–11. */
    WORKING("working", R.string.channel_working, NotificationManager.IMPORTANCE_MIN),
    ;

    companion object {
        fun createAll(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(entries.map { NotificationChannel(it.id, context.getString(it.title), it.importance) })
        }
    }
}
