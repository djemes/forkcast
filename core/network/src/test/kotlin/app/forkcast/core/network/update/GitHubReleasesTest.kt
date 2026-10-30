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
        val r = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast").latest()
        assertThat(r.tag).isEqualTo("v1.0.0")
        assertThat(r.apk?.name).isEqualTo("forkcast-1.0.0.apk")
        assertThat(r.apk?.sizeBytes).isEqualTo(5)
        assertThat(auth).isNull()
    }

    @Test fun refusedAndMissingRelease() = runTest {
        for ((code, type) in listOf(401 to UpdateError.Http::class.java, 403 to UpdateError.Http::class.java, 404 to UpdateError.NoRelease::class.java)) {
            val engine = MockEngine { respond("{}", HttpStatusCode.fromValue(code), json) }
            val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
            val e = runCatching { client.latest() }.exceptionOrNull()
            assertThat(e).isInstanceOf(type)
        }
    }

    /** The asset answers with a redirect to signed storage, fetched as a second plain request. */
    @Test fun downloadFollowsTheRedirect() = runTest {
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
        client.download(ReleaseAsset("a.apk", "https://api.github.com/repos/djemes/forkcast/releases/assets/7", 5), out) { done, _ -> last = done }
        assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(last).isEqualTo(5)
        assertThat(seen).containsExactly("api.github.com" to null, "objects.githubusercontent.com" to null).inOrder()
    }

    /**
     * Owner's report (2026-10-24): with GitHub's JSON type also in Accept, the API sent the asset's
     * JSON details (200), saved as the APK. The asset request carries exactly one Accept.
     */
    @Test fun assetRequestAsksForTheBinaryOnly() = runTest {
        var accepts: List<String>? = null
        val engine = MockEngine { req ->
            if (req.url.host == "api.github.com") {
                accepts = req.headers.getAll(HttpHeaders.Accept)
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/signed?x=1"))
            } else {
                respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
            }
        }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        client.download(ReleaseAsset("a.apk", "https://api.github.com/repos/djemes/forkcast/releases/assets/7", 5), ByteArrayOutputStream()) { _, _ -> }
        assertThat(accepts!!.flatMap { it.split(",") }.map { it.trim() }).containsExactly("application/octet-stream")
    }

    /** JSON (or a short body) instead of the file is named, not saved as an APK. */
    @Test fun jsonInsteadOfTheFileIsRefused() = runTest {
        val engine = MockEngine { respond("{\"id\":7,\"name\":\"a.apk\"}", HttpStatusCode.OK, json) }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        val e = runCatching {
            client.download(ReleaseAsset("a.apk", "https://api.github.com/repos/djemes/forkcast/releases/assets/7", 28_000_000), ByteArrayOutputStream()) { _, _ -> }
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(UpdateError.WrongDownload::class.java)
    }

    /** No Authorization header anywhere. */
    @Test fun nothingIsSentButTheRequest() = runTest {
        val auths = mutableListOf<String?>()
        val engine = MockEngine { req ->
            auths += req.headers[HttpHeaders.Authorization]
            when {
                req.url.encodedPath.endsWith("/releases/latest") -> respond(latestJson, HttpStatusCode.OK, json)
                req.url.host == "api.github.com" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/s"))
                else -> respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
            }
        }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        val r = client.latest()
        client.download(r.apk!!, ByteArrayOutputStream()) { _, _ -> }
        assertThat(auths).containsExactly(null, null, null)
    }

    @Test fun a403IsAnHttpError() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.Forbidden, json) }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        assertThat(runCatching { client.latest() }.exceptionOrNull()).isInstanceOf(UpdateError.Http::class.java)
    }

    @Test fun rateLimitIsNamedWithItsResetTime() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("1793000000")))
        }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        val e = runCatching { client.latest() }.exceptionOrNull()
        assertThat(e).isInstanceOf(UpdateError.RateLimited::class.java)
        assertThat((e as UpdateError.RateLimited).resetAt).isEqualTo(1_793_000_000L)
    }

    @Test fun recentListsBetasWithTheirChecksumsAndSkipsDrafts() = runTest {
        val list = """
            [{"tag_name":"v2.0.0-beta.1","html_url":"h1","prerelease":true,"draft":false,
              "assets":[{"name":"Forkcast-v2.0.0-beta.1.apk","url":"u1","size":9,"digest":"sha256:abc"},{"name":"SHA256SUMS","url":"u2","size":80}]},
             {"tag_name":"v2.0.0-beta.2","html_url":"h2","prerelease":true,"draft":true,"assets":[]},
             {"tag_name":"v1.9.0","html_url":"h3","assets":[]}]
        """.trimIndent()
        var url: String? = null
        val engine = MockEngine { req -> url = req.url.toString(); respond(list, HttpStatusCode.OK, json) }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        val releases = client.recent()
        assertThat(url).isEqualTo("https://api.github.com/repos/djemes/forkcast/releases?per_page=20")
        assertThat(releases.map { it.tag }).containsExactly("v2.0.0-beta.1", "v1.9.0").inOrder()
        val beta = releases.first()
        assertThat(beta.prerelease).isTrue()
        assertThat(beta.apk).isEqualTo(ReleaseAsset("Forkcast-v2.0.0-beta.1.apk", "u1", 9, "sha256:abc"))
        assertThat(beta.checksums?.name).isEqualTo("SHA256SUMS")
    }

    @Test fun offlineIsNamed() = runTest {
        val engine = MockEngine { throw java.io.IOException("no route") }
        val client = GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast")
        assertThat(runCatching { client.latest() }.exceptionOrNull()).isInstanceOf(UpdateError.Offline::class.java)
    }
}
