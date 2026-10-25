package app.forkcast.core.domain

/** Which service a pasted key is for. */
enum class KeyService { GEMINI, CLAUDE, OPENAI, USDA }

/** What a quick look at a pasted key says, before the connection test has the last word. */
enum class KeyCheck {
    EMPTY,
    /** Looks like this service's key (a known prefix, or nothing known to compare with). */
    LOOKS_RIGHT,
    /** Doesn't start the way this service's keys do; still worth a test. */
    UNFAMILIAR,
    /** A space or line break inside: probably copied with surrounding text. */
    HAS_SPACE,
    TOO_SHORT,
    /** A web address or an email address, not a key. */
    NOT_A_KEY,
    /** Looks like a Gemini key (in another service's field). */
    GEMINI_KEY,
    /** Looks like a Claude key (in another service's field). */
    CLAUDE_KEY,
    /** Looks like an OpenAI key (in another service's field). */
    OPENAI_KEY,
}

/**
 * A soft format check for the setup guide (roadmap 2.0 task 3.3): it only catches obvious paste
 * mistakes and never blocks, because providers add key formats without notice (Google issues
 * both "AIza…" and "AQ.…" keys and documents neither prefix; checked 2026-10-25). The connection
 * test decides.
 */
object ApiKeyFormat {

    private const val MIN_LENGTH = 20

    /** Trims what copying tends to add: surrounding spaces, quotes and invisible characters. */
    fun clean(raw: String): String =
        raw.replace(INVISIBLE, "").trim().trim('"', '\'', '`').trim()

    fun check(service: KeyService, raw: String): KeyCheck {
        val key = clean(raw)
        return pasteMistake(key) ?: wrongService(service, key) ?: when {
            key.length < MIN_LENGTH -> KeyCheck.TOO_SHORT
            service == KeyService.CLAUDE && !key.startsWith(CLAUDE_PREFIX) -> KeyCheck.UNFAMILIAR
            service == KeyService.GEMINI && GEMINI_PREFIXES.none { key.startsWith(it) } -> KeyCheck.UNFAMILIAR
            else -> KeyCheck.LOOKS_RIGHT
        }
    }

    private fun pasteMistake(key: String): KeyCheck? = when {
        key.isEmpty() -> KeyCheck.EMPTY
        key.contains("://") || key.startsWith("www.") || key.contains('@') -> KeyCheck.NOT_A_KEY
        key.any { it.isWhitespace() } -> KeyCheck.HAS_SPACE
        else -> null
    }

    /** Another service's key. An OpenAI-compatible server may take anyone's (Gemini and others offer such endpoints). */
    private fun wrongService(service: KeyService, key: String): KeyCheck? {
        val other = otherService(key)
        if (other == null || other == service || service == KeyService.OPENAI) return null
        return when (other) {
            KeyService.GEMINI -> KeyCheck.GEMINI_KEY
            KeyService.CLAUDE -> KeyCheck.CLAUDE_KEY
            else -> KeyCheck.OPENAI_KEY
        }
    }

    /** The service whose prefix [key] has, if any (Claude's "sk-ant-" before OpenAI's "sk-"). */
    private fun otherService(key: String): KeyService? = when {
        key.startsWith(CLAUDE_PREFIX) -> KeyService.CLAUDE
        GEMINI_PREFIXES.any { key.startsWith(it) } -> KeyService.GEMINI
        key.startsWith("sk-") -> KeyService.OPENAI
        else -> null
    }

    private const val CLAUDE_PREFIX = "sk-ant-"
    private val GEMINI_PREFIXES = listOf("AIza", "AQ.")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
}
