package app.forkcast.core.network.update

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class GitHubReleasesTest {

    private val latestJson = """
        {"tag_name":"v1.0.0","name":"Forkcast 1.0.0","body":"What's new","html_url":"https://github.com/djemes/forkcast/releases/tag/v1.0.0",
         "assets":[{"name":"forkcast-1.0.0.apk","url":"https://api.github.com/repos/djemes/forkcast/releases/assets/7","size":5,"browser_download_url":"x"}],
         "author":{"login":"djemes"}}
    """.trimIndent()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun latestReleaseWithItsApk() = runTest {
        var auth: String? = null
        val engine = MockEngine { req ->
            auth = req.headers[HttpHeaders.Authorization]
            assertThat(req.url.toString()).isEqualTo("https://api.github.com/repos/djemes/forkcast/releases/latest")
            respond(latestJson, HttpStatusCode.OK, json)
        }
        val r = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast").latest("tok")
        assertThat(r.tag).isEqualTo("v1.0.0")
        assertThat(r.apk?.name).isEqualTo("forkcast-1.0.0.apk")
        assertThat(r.apk?.sizeBytes).isEqualTo(5)
        assertThat(auth).isEqualTo("Bearer tok")
    }

    @Test fun refusedTokenAndMissingRelease() = runTest {
        for ((code, type) in listOf(401 to UpdateError.TokenRejected::class.java, 403 to UpdateError.TokenRejected::class.java, 404 to UpdateError.NoRelease::class.java)) {
            val engine = MockEngine { respond("{}", HttpStatusCode.fromValue(code), json) }
            val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
            val e = runCatching { client.latest("tok") }.exceptionOrNull()
            assertThat(e).isInstanceOf(type)
        }
    }

    /** The asset answers with a redirect to signed storage; the token must not go there. */
    @Test fun downloadFollowsTheRedirectWithoutTheToken() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { req ->
            seen += req.url.host to req.headers[HttpHeaders.Authorization]
            if (req.url.host == "api.github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/signed?x=1"))
            } else {
                respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
            }
        }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        val out = ByteArrayOutputStream()
        var last = 0L
        client.download("tok", ReleaseAsset("a.apk", "https://api.github.com/repos/djemes/forkcast/releases/assets/7", 5), out) { done, _ -> last = done }
        assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(last).isEqualTo(5)
        assertThat(seen).containsExactly("api.github.com" to "Bearer tok", "objects.githubusercontent.com" to null).inOrder()
    }

    @Test fun offlineIsNamed() = runTest {
        val engine = MockEngine { throw java.io.IOException("no route") }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        assertThat(runCatching { client.latest("tok") }.exceptionOrNull()).isInstanceOf(UpdateError.Offline::class.java)
    }
}
