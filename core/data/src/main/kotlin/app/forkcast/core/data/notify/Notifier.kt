package app.forkcast.core.data.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.forkcast.core.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts Forkcast notifications. Tapping one opens the app at [EXTRA_OPEN] (a route hint). */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True while one of our activities is visible: no need to notify about what's on screen. */
    fun appInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // canPost() checks POST_NOTIFICATIONS first; lint can't follow that across functions.
    @SuppressLint("MissingPermission")
    fun post(channel: Channel, id: Int, title: String, text: String, open: String?) {
        if (!canPost()) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (open != null) putExtra(EXTRA_OPEN, open)
        }
        val pending = intent?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Silent notice for expedited work on Android 10–11 (WorkManager runs it in the foreground there). */
    fun workingNotification(): Notification = NotificationCompat.Builder(context, Channel.WORKING.id)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.estimating_notification))
        .setSilent(true)
        .setOngoing(true)
        .build()

    companion object {
        /** Intent extra naming what to open: "estimate:<id>", "settings_ai", "today". */
        const val EXTRA_OPEN = "app.forkcast.OPEN"
        const val WORKING_ID = 1001
    }
}
