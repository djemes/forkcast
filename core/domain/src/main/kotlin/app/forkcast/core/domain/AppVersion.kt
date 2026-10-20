package app.forkcast.core.domain

/**
 * Release versions for "Check for updates" (owner, 2026-10-20). Releases are tagged `vX.Y.Z`;
 * builds made between releases are named like `1.0.0-3-gabc1234` (3 commits after 1.0.0), which
 * makes them newer than 1.0.0 and older than 1.0.1.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val commitsAfter: Int = 0) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.commitsAfter })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?""")

        /** "v1.2.3", "1.2.3", "1.2.3-4-gabc1234-dirty"; null for anything else ("0.0.0-dev"). */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.find(text.trim()) ?: return null
            val (a, b, c) = m.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt(), m.groupValues[4].toIntOrNull() ?: 0)
        }

        /** True when the release [tag] is newer than the installed [current] version name. */
        fun isNewer(tag: String, current: String): Boolean {
            val release = parse(tag) ?: return false
            val installed = parse(current) ?: return true
            return release > installed
        }
    }
}
