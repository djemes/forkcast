package app.forkcast.core.network.ai

import app.forkcast.core.network.NetworkWaiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.util.Base64
import kotlin.time.TimeSource

/**
 * HTTP client for AI providers (SPEC §7): read timeout 60 s. A 429 is never retried here: the
 * fallback chain moves to the next profile instead, so a rate-limited key isn't hammered.
 * One quick retry on a 5xx/overload, two on a dropped connection, each after waiting (up to 10 s)
 * for the phone to be online again ([NetworkWaiter]). No logging of any kind.
 */
object AiHttpClientFactory {
    fun create(
        userAgent: String,
        engine: HttpClientEngine = OkHttp.create(),
        network: NetworkWaiter = NetworkWaiter.None,
    ): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            // Only one retry for a 5xx (retryCount is 1 on the first failure): the fallback chain is the
            // better answer to a struggling provider.
            retryIf { _, response -> response.status.value >= 500 && retryCount < 2 }
            retryOnExceptionIf { _, cause -> cause is java.io.IOException && cause !is SocketTimeoutException && cause !is HttpRequestTimeoutException }
            // A 503's Retry-After can be long; the chain and the worker's backoff handle waiting.
            constantDelay(millis = 1_500, respectRetryAfterHeader = false)
            delay { millis -> kotlinx.coroutines.delay(millis); network.awaitOnline(NetworkWaiter.WAIT_MS) }
        }
        defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
    }
}

/** Response body plus wall-clock latency of a successful exchange (any HTTP status). */
internal class Timed(val response: HttpResponse, val body: String, val latencyMs: Long)

/** Runs [block], mapping transport failures to [AiError]. HTTP status mapping is per provider. */
internal suspend fun timedCall(block: suspend () -> HttpResponse): Timed {
    val mark = TimeSource.Monotonic.markNow()
    val response = try {
        block()
    } catch (e: HttpRequestTimeoutException) {
        throw AiError.Timeout()
    } catch (e: SocketTimeoutException) {
        throw AiError.Timeout()
    } catch (e: ConnectTimeoutException) {
        throw AiError.Network()
    } catch (e: java.io.IOException) {
        throw AiError.Network()
    }
    val body = try { response.bodyAsText() } catch (e: java.io.IOException) { throw AiError.Network() }
    return Timed(response, body, mark.elapsedNow().inWholeMilliseconds)
}

internal fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

/** Retry-After header in seconds → ms. */
internal fun HttpResponse.retryAfterMs(): Long? = headers["retry-after"]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }

/** Wraps any unexpected failure so callers only ever see [AiError]. */
internal inline fun <T> aiResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: AiError) {
    Result.failure(e)
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(AiError.BadResponse(null, e.message))
}
