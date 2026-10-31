package app.forkcast.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri
import app.forkcast.R

/**
 * Every outside link the app opens (owner, 2026-10-30, v1.8.9): an app that handles the link itself
 * (the GitHub app for github.com) first, else a Custom Tab, the phone's browser on top of Forkcast
 * with the app's colors, which falls back to any browser when none supports Custom Tabs. No warm-up
 * or prefetch, so nothing connects before the tap. Inside [ForkcastTheme][app.forkcast.ui.theme.ForkcastTheme].
 */
@Composable
fun ProvideLinkOpener(darkTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.background.toArgb()
    val opener = remember(context, toolbar, darkTheme) { LinkOpener(context, toolbar, darkTheme) }
    CompositionLocalProvider(LocalUriHandler provides opener, content = content)
}

class LinkOpener(private val context: Context, private val toolbarColor: Int, private val darkTheme: Boolean) : UriHandler {

    override fun openUri(uri: String) {
        val parsed = uri.toUri()
        if (openInApp(parsed)) return
        val tab = customTab()
        // Outside an activity Android needs a new task; in one, the tab stays in Forkcast's.
        if (context.findActivity() == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tab.launchUrl(context, parsed)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.link_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    /** The tab's look: the app's background as the toolbar and the app's light or dark theme. */
    fun customTab(): CustomTabsIntent {
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).setNavigationBarColor(toolbarColor).build()
        return CustomTabsIntent.Builder()
            .setColorScheme(if (darkTheme) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .build()
    }

    /** Android 11+: only an app that isn't a browser, so a link nothing else claims goes to the tab. */
    private fun openInApp(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
