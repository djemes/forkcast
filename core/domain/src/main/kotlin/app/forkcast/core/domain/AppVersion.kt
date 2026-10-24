package app.forkcast.core.domain

/**
 * Release versions for "Check for updates" (owner, 2026-10-20). Releases are tagged `vX.Y.Z`,
 * betas `vX.Y.Z-beta.N` (roadmap 2.0; also `alpha.N`, `rc.N`), which come before `X.Y.Z`. Builds
 * made between releases are named like `1.0.0-3-gabc1234` (3 commits after 1.0.0), which makes
 * them newer than 1.0.0 and older than 1.0.1.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val commitsAfter: Int = 0,
    /** Null for a release; for a pre-release its stage and number. */
    val pre: Pre? = null,
) : Comparable<AppVersion> {

    data class Pre(val stage: Stage, val number: Int)

    /** In release order. */
    enum class Stage { ALPHA, BETA, RC }

    val isPrerelease: Boolean get() = pre != null

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(
            this,
            other,
            { it.major },
            { it.minor },
            { it.patch },
            // A release is past every pre-release of the same version.
            { it.pre == null },
            { it.pre?.stage },
            { it.pre?.number },
            { it.commitsAfter },
        )

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-${it.stage.name.lowercase()}.${it.number}" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?(?:-(\d+)-g[0-9a-f]+)?""", RegexOption.IGNORE_CASE)

        /** "v1.2.3", "1.2.3", "v2.0.0-beta.1", "1.2.3-4-gabc1234-dirty"; null for anything else ("dev"). */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.find(text.trim()) ?: return null
            val g = m.groupValues
            val pre = g[4].takeIf { it.isNotEmpty() }?.let { Pre(Stage.valueOf(it.uppercase()), g[5].toInt()) }
            return AppVersion(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[6].toIntOrNull() ?: 0, pre)
        }

        /** True when the release [tag] is newer than the installed [current] version name. */
        fun isNewer(tag: String, current: String): Boolean {
            val release = parse(tag) ?: return false
            val installed = parse(current) ?: return true
            return release > installed
        }
    }
}
