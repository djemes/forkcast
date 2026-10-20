package app.forkcast.core.network.update

import app.forkcast.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.OutputStream

/** The newest release on GitHub and its APK (the Release workflow attaches `forkcast-X.Y.Z.apk`). */
data class Release(
    val tag: String,
    val name: String?,
    val notes: String?,
    val pageUrl: String,
    val apk: ReleaseAsset?,
)

data class ReleaseAsset(val name: String, val apiUrl: String, val sizeBytes: Long)

/** What can go wrong checking for an update; About names each. */
sealed class UpdateError(message: String) : Exception(message) {
    class Offline : UpdateError("No connection")
    /** 401/403: the token is wrong, expired, or can't read the repository. */
    class TokenRejected(val code: Int) : UpdateError("GitHub refused the token (HTTP $code)")
    /** 404: no release yet, or the token can't see the repository. */
    class NoRelease : UpdateError("No release found")
    class Http(val code: Int) : UpdateError("GitHub HTTP $code")
}

/**
 * GitHub's REST API for "Check for updates" (owner, 2026-10-20). The repository is private, so
 * every call carries the user's own fine-grained token (read-only Contents on that repository);
 * it is never stored here or logged. Nothing else is sent. Shapes from
 * docs.github.com/rest/releases (2022-11-28 API version).
 *
 * [http] must not follow redirects: an asset download answers 302 to a signed storage URL, which
 * is fetched without the token (the token must only ever go to api.github.com).
 */
class GitHubReleases(private val http: HttpClient, private val repo: String) {

    suspend fun latest(token: String): Release {
        val r = call { http.get("$API/repos/$repo/releases/latest") { github(token) } }
        check(r)
        val body = NetworkJson.decodeFromString(ReleaseJson.serializer(), r.bodyAsText())
        val apk = body.assets.firstOrNull { it.name.endsWith(".apk") }
        return Release(body.tagName, body.name, body.body, body.htmlUrl, apk?.let { ReleaseAsset(it.name, it.url, it.size) })
    }

    /** Streams [asset] into [out], reporting (bytes so far, total). */
    suspend fun download(token: String, asset: ReleaseAsset, out: OutputStream, progress: (Long, Long) -> Unit) {
        val redirect = call {
            http.get(asset.apiUrl) {
                github(token)
                header(HttpHeaders.Accept, "application/octet-stream")
            }
        }
        val location = when (redirect.status.value) {
            in 300..399 -> redirect.headers[HttpHeaders.Location] ?: throw UpdateError.Http(redirect.status.value)
            else -> { check(redirect); asset.apiUrl }
        }
        // The signed storage URL needs no token, and must not get one.
        call {
            http.prepareGet(location) {
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
                if (location == asset.apiUrl) { github(token); header(HttpHeaders.Accept, "application/octet-stream") }
            }.execute { response ->
                check(response)
                val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: asset.sizeBytes
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) { if (channel.isClosedForRead) break else continue }
                    out.write(buffer, 0, n)
                    done += n
                    progress(done, total)
                }
                response
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.github(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun check(r: HttpResponse) {
        when (val code = r.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw UpdateError.TokenRejected(code)
            404 -> throw UpdateError.NoRelease()
            else -> throw UpdateError.Http(code)
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: UpdateError) {
        throw e
    } catch (e: java.io.IOException) {
        throw UpdateError.Offline()
    }

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val assets: List<AssetJson> = emptyList(),
    )

    @Serializable
    private data class AssetJson(val name: String, val url: String, val size: Long = 0)

    companion object {
        const val API = "https://api.github.com"
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
    }
}

/**
 * Client for [GitHubReleases]: never follows redirects itself (neither Ktor nor OkHttp), so the
 * token can't travel to the storage host; long reads for a ~30 MB APK; no logging.
 */
object UpdateHttpClientFactory {
    fun create(
        userAgent: String,
        engine: io.ktor.client.engine.HttpClientEngine = io.ktor.client.engine.okhttp.OkHttp.create {
            config { followRedirects(false); followSslRedirects(false) }
        },
    ): HttpClient = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(io.ktor.client.plugins.HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
    }
}
