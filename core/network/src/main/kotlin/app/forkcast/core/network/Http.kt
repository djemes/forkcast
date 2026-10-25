package app.forkcast.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Shared JSON settings: tolerate fields we don't model, never fail on extras. */
val NetworkJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * The one HTTP client (SPEC §7): connect 10 s, request 15 s, retries with backoff for
 * 429/5xx and I/O errors only (never for 4xx auth errors), at most a few seconds apart
 * whatever Retry-After says. Before each retry it also waits (up to 10 s) for the phone to be
 * online again, so a network switch mid-request doesn't surface as "No connection".
 * No request logging, so keys in headers can never reach logs.
 */
object HttpClientFactory {
    fun create(
        userAgent: String,
        engine: HttpClientEngine = OkHttp.create(),
        network: NetworkWaiter = NetworkWaiter.None,
    ): HttpClient = HttpClient(engine) {
        expectSuccess = false
        // Food databases are https only.
        install(httpsOnly())
        install(ContentNegotiation) { json(NetworkJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response -> response.status.value == 429 || response.status.value >= 500 }
            // A request timeout means a slow server, not a lost connection: retrying would only double the wait.
            retryOnExceptionIf { _, cause -> cause is java.io.IOException && cause !is io.ktor.client.plugins.HttpRequestTimeoutException }
            // Never wait out the server's Retry-After: USDA sends hours for an exhausted key, which
            // stalled photo estimates in component matching (found on the emulator 2026-10-19).
            exponentialDelay(base = 2.0, maxDelayMs = 8_000, respectRetryAfterHeader = false)
            delay { millis -> kotlinx.coroutines.delay(millis); network.awaitOnline(NetworkWaiter.WAIT_MS) }
        }
        defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
    }
}

/** Errors food lookups can report; the UI shows a specific message for each. */
sealed class FoodApiError(message: String) : Exception(message) {
    class Offline : FoodApiError("No connection")
    class RateLimited : FoodApiError("Rate limited")
    class InvalidKey : FoodApiError("API key rejected")
    class Server(code: Int) : FoodApiError("Server error $code")
    class BadResponse(cause: Throwable?) : FoodApiError("Unexpected response: ${cause?.message}")
}
