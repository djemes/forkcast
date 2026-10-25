package app.forkcast.core.network

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLProtocol
import io.ktor.util.AttributeKey

/**
 * A request was about to leave over plain http where only https is allowed. Not an IOException,
 * so it's never retried or reported as "No connection": it can only mean a bug.
 */
class InsecureRequestException(host: String) : IllegalStateException("refused plain http to $host")

/**
 * Only https leaves a client with this plugin (REVIEW-2 S3). The platform allows cleartext so a
 * user's own OpenAI-compatible server on their network can be reached over http, which Android
 * can't scope to an address typed at run time; the clients refuse it here instead, except for a
 * request marked [allowCleartext] (that server only). Ktor's redirect handling already refuses
 * https → http downgrades. [allowHost] lets a loopback test stand-in through (debug builds only
 * point there).
 */
fun httpsOnly(allowHost: (String) -> Boolean = { false }) = createClientPlugin("HttpsOnly") {
    onRequest { request, _ ->
        val url = request.url
        val allowed = url.protocol == URLProtocol.HTTPS || allowHost(url.host) || request.attributes.getOrNull(Cleartext) == true
        if (!allowed) throw InsecureRequestException(url.host)
    }
}

/** For the user's own OpenAI-compatible server, the one place plain http may be used. */
fun HttpRequestBuilder.allowCleartext() = attributes.put(Cleartext, true)

private val Cleartext = AttributeKey<Boolean>("ForkcastAllowCleartext")

/** 127.0.0.1 and localhost: the emulator's adb-reversed test servers. */
fun isLoopback(host: String): Boolean = host == "127.0.0.1" || host == "localhost"
