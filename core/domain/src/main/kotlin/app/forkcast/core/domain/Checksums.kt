package app.forkcast.core.domain

/**
 * The updater's checksum checks (roadmap 2.0, task 1.5): `SHA256SUMS` as `sha256sum` writes it
 * ("<64 hex>  <name>", or "<hex> *<name>" in binary mode), and GitHub's asset digest "sha256:<hex>".
 */
object Checksums {
    private val LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""")

    /** The hash listed for [fileName] in a SHA256SUMS text, lower-case; null if it isn't listed. */
    fun fromSums(sums: String, fileName: String): String? =
        sums.lineSequence().mapNotNull { LINE.find(it.trim()) }.firstOrNull { it.groupValues[2] == fileName }?.groupValues?.get(1)?.lowercase()

    /** The hex of a GitHub digest "sha256:<hex>"; null for any other algorithm or shape. */
    fun fromDigest(digest: String?): String? =
        digest?.removePrefix("sha256:")?.takeIf { digest.startsWith("sha256:") && it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.lowercase()
}
