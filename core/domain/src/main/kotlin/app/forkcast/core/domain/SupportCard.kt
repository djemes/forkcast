package app.forkcast.core.domain

/**
 * The Ko-fi card at the top of Settings (owner, 2026-10-30). Hiding it saves the app's major
 * version; it comes back only when a later major version is installed (2.x → 3.0), never after a
 * minor or patch update.
 */
object SupportCard {

    /** The major version in [versionName]; 0 for a name that isn't a version (a "dev" build). */
    fun major(versionName: String): Int = AppVersion.parse(versionName)?.major ?: 0

    /** Shown unless it was hidden at [hiddenAtMajor] and the installed [versionName] has the same major version (or an older one). */
    fun isShown(hiddenAtMajor: Int?, versionName: String): Boolean = hiddenAtMajor == null || major(versionName) > hiddenAtMajor
}
