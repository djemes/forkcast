package app.forkcast.core.network

import app.forkcast.core.network.ai.AiHttpClientFactory
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A dropped connection (e.g. a Wi-Fi ↔ mobile switch) is retried after the network is back. */
class HttpRetryTest {

    private val waits = AtomicInteger()
    private val network = NetworkWaiter { waits.incrementAndGet(); true }

    private fun failingThenOk(failures: Int, error: () -> Throwable = { IOException("connection reset") }): Pair<MockEngine, AtomicInteger> {
        val calls = AtomicInteger()
        return MockEngine { _ -> if (calls.incrementAndGet() <= failures) throw error() else respond("ok") } to calls
    }

    @Test fun droppedConnectionIsRetriedAfterWaitingForTheNetwork() = runTest {
        val (engine, calls) = failingThenOk(failures = 1)
        val body = HttpClientFactory.create("t", engine, network).get("https://example.org").bodyAsText()
        assertThat(body).isEqualTo("ok")
        assertThat(calls.get()).isEqualTo(2)
        assertThat(waits.get()).isEqualTo(1)
    }

    @Test fun aiClientAlsoWaitsForTheNetwork() = runTest {
        val (engine, calls) = failingThenOk(failures = 2)
        val body = AiHttpClientFactory.create("t", engine, network).get("https://example.org").bodyAsText()
        assertThat(body).isEqualTo("ok")
        assertThat(calls.get()).isEqualTo(3)
        assertThat(waits.get()).isEqualTo(2)
    }

    @Test fun slowServerTimeoutIsNotRetried() = runTest {
        val (engine, calls) = failingThenOk(failures = 5) { HttpRequestTimeoutException("https://example.org", 15_000) }
        val client = HttpClientFactory.create("t", engine, network)
        assertThrows(HttpRequestTimeoutException::class.java) { kotlinx.coroutines.runBlocking { client.get("https://example.org") } }
        assertThat(calls.get()).isEqualTo(1)
    }
}
