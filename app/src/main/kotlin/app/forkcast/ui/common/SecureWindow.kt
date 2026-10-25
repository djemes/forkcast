package app.forkcast.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * While [secure] is true, the window can't be screenshotted or recorded and shows blank in
 * Recents (FLAG_SECURE): used whenever a saved or typed API key is shown in plain text (REVIEW-2 S1).
 * Several callers can hold it at once; the flag goes when the last one lets go.
 */
@Composable
fun SecureWhile(secure: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(secure, activity) {
        if (secure) SecureCount.acquire(activity)
        onDispose { if (secure) SecureCount.release(activity) }
    }
}

private object SecureCount {
    private val holders = mutableMapOf<Activity, Int>()

    fun acquire(activity: Activity) {
        val n = (holders[activity] ?: 0) + 1
        holders[activity] = n
        if (n == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        val n = (holders[activity] ?: 1) - 1
        if (n <= 0) {
            holders.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[activity] = n
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
