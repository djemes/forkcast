package app.forkcast.core.data.update

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Emulator testing only: a debuggable build whose `files/releases-mock` holds a URL (written with
 * `adb shell run-as`) asks a local stand-in for GitHub's Releases API there instead of
 * api.github.com, to test updates (public repo, betas, tampered files) end to end. Release
 * builds aren't debuggable, so this never applies on a phone.
 */
object ReleasesMock {
    private const val FILE = "releases-mock"

    /** The stand-in's base URL, or null (always null in a release build). */
    fun baseUrl(context: Context): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.ifEmpty { null }
    }
}
