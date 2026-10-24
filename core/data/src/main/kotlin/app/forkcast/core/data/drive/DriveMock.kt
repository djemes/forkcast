package app.forkcast.core.data.drive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Emulator testing only: a debuggable build whose `files/drive-mock` holds a URL (written with
 * `adb shell run-as`) talks to a local Drive stand-in there, signed in as a test account, instead
 * of Google. The emulator has no Google account to sign in with. Release builds aren't
 * debuggable, so this never applies on a phone.
 */
object DriveMock {
    private const val FILE = "drive-mock"

    /** The stand-in's base URL, or null (always null in a release build). */
    fun baseUrl(context: Context): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.ifEmpty { null }
    }

    /** Access is always granted; the stand-in accepts any token. */
    object Auth : DriveAuth {
        override suspend fun authorize(): DriveAccess = DriveAccess.Granted(TOKEN)
        override fun fromConsent(data: Intent?): DriveAccess = DriveAccess.Granted(TOKEN)
        override suspend fun forget(token: String) = Unit
        override suspend fun revoke(email: String) = Unit
        private const val TOKEN = "mock"
    }
}
