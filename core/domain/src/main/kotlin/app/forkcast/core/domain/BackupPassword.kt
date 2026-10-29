package app.forkcast.core.domain

import java.security.SecureRandom
import java.util.Random

/**
 * The backup password's rules (owner, 2026-10-29): at least [MIN_LENGTH] characters, a strength
 * meter, and a generated one for people who'd rather not make one up.
 */
object BackupPassword {
    const val MIN_LENGTH = 10

    /** 0 = too short, 1 = weak, 2 = fair, 3 = good, 4 = strong. A meter only; any [MIN_LENGTH]+ characters are allowed. */
    fun strength(password: String): Int {
        if (password.length < MIN_LENGTH) return 0
        val classes = listOf(Regex("[a-z]"), Regex("[A-Z]"), Regex("[0-9]"), Regex("[^a-zA-Z0-9]")).count { it.containsMatchIn(password) }
        val words = password.trim().split(Regex("\\s+")).size
        // The same character over and over counts for little.
        val distinct = password.toSet().size
        return when {
            distinct < 5 -> 1
            password.length >= 20 || words >= 4 -> 4
            password.length >= 14 && classes >= 3 -> 4
            password.length >= 14 || classes >= 3 -> 3
            classes >= 2 -> 2
            else -> 1
        }
    }

    fun isAcceptable(password: String): Boolean = password.length >= MIN_LENGTH

    /**
     * 20 characters in groups of four ("k7Qm-Xp2r-…"), from letters and digits that can't be
     * mistaken for each other (no 0/O, 1/l/I), so it can be typed from paper too.
     */
    fun generate(random: Random = SecureRandom()): String =
        (0 until GROUPS).joinToString("-") { (0 until GROUP).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }

    private const val GROUPS = 5
    private const val GROUP = 4
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
