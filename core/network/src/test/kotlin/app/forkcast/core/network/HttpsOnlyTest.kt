package app.forkcast.core.network

import app.forkcast.core.network.ai.AiHttpClientFactory
import app.forkcast.core.network.ai.OpenAiCompatEstimator
import app.forkcast.core.network.update.UpdateHttpClientFactory
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** REVIEW-2 S3: only the user's own OpenAI-compatible server may be reached over plain http. */
class HttpsOnlyTest {

    private val calls = AtomicInteger()
    private val engine = MockEngine { _ -> calls.incrementAndGet(); respond("""{"data":[]}""") }

    @Test fun foodAndAiClientsRefusePlainHttpWithoutSendingIt() = runTest {
        for (client in listOf(HttpClientFactory.create("t", engine), AiHttpClientFactory.create("t", engine))) {
            assertThrows(InsecureRequestException::class.java) { kotlinx.coroutines.runBlocking { client.get("http://example.org") } }
            assertThat(client.get("https://example.org").bodyAsText()).isNotEmpty()
        }
        // Refused before the engine: nothing went out over http, and nothing was retried.
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test fun updateClientAllowsOnlyTheLoopbackStandIn() = runTest {
        val client = UpdateHttpClientFactory.create("t", engine)
        assertThrows(InsecureRequestException::class.java) { kotlinx.coroutines.runBlocking { client.get("http://api.github.com/x") } }
        assertThat(client.get("http://127.0.0.1:8099/x").bodyAsText()).isNotEmpty()
    }

    @Test fun usersOwnServerMayBePlainHttp() = runTest {
        val compat = OpenAiCompatEstimator(AiHttpClientFactory.create("t", engine), { null }, "m", "http://192.168.1.20:11434")
        assertThat(compat.listModels().isSuccess).isTrue()
        assertThat(calls.get()).isEqualTo(1)
    }
}
