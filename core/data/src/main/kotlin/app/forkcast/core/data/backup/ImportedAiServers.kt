package app.forkcast.core.data.backup

import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.ai.AiDefaults
import java.net.URI

/**
 * AI servers a file brings in (REVIEW-2 S4). An OpenAI-compatible profile can point anywhere and
 * may need no key, so a file from someone else could send future meal photos to their server.
 * The import preview names those servers, and an imported profile with one arrives turned off,
 * unless this phone already has that same profile on that same server.
 */
internal object ImportedAiServers {
    private val defaultHost by lazy { AiDefaults.load().provider(ProviderId.OPENAI_COMPAT).defaultBaseUrl?.let(::hostOf) }

    /** A server the user (or whoever made the file) typed in, rather than the provider's own. */
    fun customServer(profile: AiProfileEntity): String? {
        if (profile.provider != ProviderId.OPENAI_COMPAT || profile.deletedAt != null) return null
        val host = profile.baseUrl?.let(::hostOf) ?: return null
        return host.takeIf { it != defaultHost }
    }

    /** The custom servers in [profiles], each once, for the preview. */
    fun names(profiles: List<AiProfileEntity>): List<String> = profiles.mapNotNull(::customServer).distinct().sorted()

    /** [incoming] as it should be stored, given what this phone has now ([local]). */
    fun guard(incoming: List<AiProfileEntity>, local: List<AiProfileEntity>): List<AiProfileEntity> {
        val known = local.filter { it.deletedAt == null }.associate { it.id to it.baseUrl?.trim()?.trimEnd('/') }
        return incoming.map { p ->
            val same = known[p.id] != null && known[p.id] == p.baseUrl?.trim()?.trimEnd('/')
            if (p.enabled && customServer(p) != null && !same) p.copy(enabled = false) else p
        }
    }

    /** "192.168.1.20:11434", "api.openai.com": the part of the address people recognize. */
    private fun hostOf(url: String): String? = runCatching {
        val uri = URI(url.trim())
        uri.host?.lowercase()?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host }
    }.getOrNull()?.ifEmpty { null } ?: url.trim().takeIf { it.isNotEmpty() }
}
