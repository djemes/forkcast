package app.forkcast.core.network.ai

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When provider quota windows end (AI_PROVIDERS.md §2.5–2.6). */
object QuotaWindows {
    val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Gemini daily quotas reset at midnight Pacific. */
    fun nextMidnightPacific(now: Instant): Instant {
        val today = now.atZone(PACIFIC).toLocalDate()
        return today.plusDays(1).atStartOfDay(PACIFIC).toInstant()
    }

    /** The Pacific calendar day [now] falls in, for "Used today" counters on Gemini profiles. */
    fun pacificDay(now: Instant): LocalDate = now.atZone(PACIFIC).toLocalDate()

    /** Default pause after a short-window 429 with no Retry-After. */
    const val SHORT_COOLDOWN_MS = 60_000L
}
