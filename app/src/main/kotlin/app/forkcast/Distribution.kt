package app.forkcast

/**
 * Where this build is distributed (roadmap 2.0, task 1.2), from the `distribution` product flavor.
 * Features that exist only outside Google Play ask here, never [BuildConfig] directly.
 */
enum class Distribution(val id: String) {
    /** Signed APKs from GitHub Releases: the only channel that ships. */
    GITHUB("github"),

    /** Prepared for a later Google Play build: Play updates the app and its payments policy rules out donation links. */
    PLAY("play"),
    ;

    /** The in-app updater (Settings › Updates) and its background check. */
    val hasUpdater: Boolean get() = this == GITHUB

    /** "Support Forkcast" in About (roadmap Phase 4). */
    val hasDonationLink: Boolean get() = this == GITHUB

    companion object {
        val current: Distribution = fromId(BuildConfig.DISTRIBUTION)

        fun fromId(id: String): Distribution = entries.first { it.id == id }
    }
}
