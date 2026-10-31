package app.forkcast.core.domain

import java.time.Duration

/**
 * The Ko-fi card at the top of Settings (owner, 2026-10-30). Hiding it saves the app's major
 * version and the time; it comes back [HIDDEN_FOR] later, or sooner when a later major version is
 * installed (1.x to 2.0), whichever comes first.
 */
object SupportCard {

    /** About three months (owner, 2026-10-30). */
    val HIDDEN_FOR: Duration = Duration.ofDays(90)

    /** The major version in [versionName]; 0 for a name that isn't a version (a "dev" build). */
    fun major(versionName: String): Int = AppVersion.parse(versionName)?.major ?: 0

    /**
     * Shown unless it was hidden at [hiddenAtMajor] and [hiddenAt] (epoch ms), the installed
     * [versionName] has the same major version (or an older one), and [HIDDEN_FOR] hasn't passed by
     * [now].
     */
    fun isShown(hiddenAtMajor: Int?, hiddenAt: Long?, versionName: String, now: Long): Boolean =
        hiddenAtMajor == null || hiddenAt == null || major(versionName) > hiddenAtMajor ||
            now - hiddenAt >= HIDDEN_FOR.toMillis()
}
