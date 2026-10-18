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
 * 429/5xx and I/O errors only (never for 4xx auth errors). No request logging, so keys
 * in headers can never reach logs.
 */
object HttpClientFactory {
    fun create(userAgent: String, engine: HttpClientEngine = OkHttp.create()): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(NetworkJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response -> response.status.value == 429 || response.status.value >= 500 }
            retryOnExceptionIf { _, cause -> cause is java.io.IOException }
            exponentialDelay(base = 2.0, maxDelayMs = 8_000)
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
