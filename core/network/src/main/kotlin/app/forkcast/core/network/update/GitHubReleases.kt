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

/** A release on GitHub, its APK and its `SHA256SUMS` (the Release workflow attaches both). */
data class Release(
    val tag: String,
    val name: String?,
    val notes: String?,
    val pageUrl: String,
    val apk: ReleaseAsset?,
    /** `SHA256SUMS` (releases from 1.5.0 on). */
    val checksums: ReleaseAsset? = null,
    /** Marked as a pre-release on GitHub (betas): only offered with "Beta updates" on. */
    val prerelease: Boolean = false,
)

/** [digest] is GitHub's own `sha256:<hex>` for the file, when it reports one. */
data class ReleaseAsset(val name: String, val apiUrl: String, val sizeBytes: Long, val digest: String? = null)

/** What can go wrong checking for an update; the Updates page names each. */
sealed class UpdateError(message: String) : Exception(message) {
    class Offline : UpdateError("No connection")
    /** 401/403 with a token: it's wrong, expired, or can't read the repository. */
    class TokenRejected(val code: Int) : UpdateError("GitHub refused the token (HTTP $code)")
    /** 404: no release yet, or (without a token) the repository is private. */
    class NoRelease : UpdateError("No release found")
    /** GitHub's hourly limit for this network (60 requests without a token); [resetAt] in epoch seconds. */
    class RateLimited(val resetAt: Long?) : UpdateError("GitHub rate limit")
    class Http(val code: Int) : UpdateError("GitHub HTTP $code")
    /** GitHub answered with something that isn't the file (the asset's JSON details, a short body). */
    class WrongDownload : UpdateError("GitHub sent something other than the APK")
}

/**
 * GitHub's REST API for updates (owner, 2026-10-20; roadmap 2.0 task 1.5). A public repository
 * needs no token; while it's private, every call carries the user's own fine-grained token
 * (read-only Contents on that repository), never stored here or logged. Nothing else is sent.
 * Shapes from docs.github.com/rest/releases (2022-11-28 API version).
 *
 * [http] must not follow redirects: an asset download answers 302 to a signed storage URL, which
 * is fetched without the token (the token must only ever go to api.github.com).
 */
class GitHubReleases(private val http: HttpClient, private val repo: String, private val api: String = API) {

    /** The newest full release (GitHub's "latest" skips drafts and pre-releases). */
    suspend fun latest(token: String?): Release {
        val r = call { http.get("$api/repos/$repo/releases/latest") { github(token) } }
        check(r, token)
        return NetworkJson.decodeFromString(ReleaseJson.serializer(), r.bodyAsText()).toRelease()
    }

    /** The most recent releases, pre-releases included (drafts are only visible to maintainers and skipped). */
    suspend fun recent(token: String?): List<Release> {
        val r = call { http.get("$api/repos/$repo/releases?per_page=$RECENT") { github(token) } }
        check(r, token)
        val list = NetworkJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ReleaseJson.serializer()), r.bodyAsText())
        return list.filterNot { it.draft }.map { it.toRelease() }
    }

    /**
     * Streams [asset] into [out], reporting (bytes so far, total). The request asks for
     * `application/octet-stream` **only**: with GitHub's JSON type also in Accept, the API answers
     * 200 with the asset's JSON details instead of the 302 to the file, and 1.1/1.2.0 saved those
     * 1.8 KB as the "APK" (owner's report 2026-10-24: "The download isn't a valid APK").
     */
    suspend fun download(token: String?, asset: ReleaseAsset, out: OutputStream, progress: (Long, Long) -> Unit) {
        val redirect = call { http.get(asset.apiUrl) { binary(token) } }
        val location = when (redirect.status.value) {
            in 300..399 -> redirect.headers[HttpHeaders.Location] ?: throw UpdateError.Http(redirect.status.value)
            else -> { check(redirect, token); asset.apiUrl }
        }
        // The signed storage URL needs no token, and must not get one.
        call {
            http.prepareGet(location) {
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
                if (location == asset.apiUrl) binary(token)
            }.execute { response ->
                check(response, token)
                if (response.headers[HttpHeaders.ContentType]?.contains("json", ignoreCase = true) == true) throw UpdateError.WrongDownload()
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
                // A cut-off or wrong body would only fail later as "not a valid APK".
                if (asset.sizeBytes > 0 && done != asset.sizeBytes) throw UpdateError.WrongDownload()
                response
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.github(token: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /** The asset itself: token (if any) and API version, and exactly one Accept, the binary one. */
    private fun io.ktor.client.request.HttpRequestBuilder.binary(token: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header("X-GitHub-Api-Version", "2022-11-28")
        headers.remove(HttpHeaders.Accept)
        header(HttpHeaders.Accept, "application/octet-stream")
    }

    private fun check(r: HttpResponse, token: String?) {
        val code = r.status.value
        // docs.github.com/rest/using-the-rest-api/rate-limits-for-the-rest-api: 403 or 429 with no requests left.
        if ((code == 403 || code == 429) && r.headers["x-ratelimit-remaining"] == "0") {
            throw UpdateError.RateLimited(r.headers["x-ratelimit-reset"]?.toLongOrNull())
        }
        when (code) {
            in 200..299 -> Unit
            401, 403 -> throw if (token != null) UpdateError.TokenRejected(code) else UpdateError.Http(code)
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
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<AssetJson> = emptyList(),
    ) {
        fun toRelease(): Release {
            fun AssetJson.asset() = ReleaseAsset(name, url, size, digest)
            return Release(
                tag = tagName,
                name = name,
                notes = body,
                pageUrl = htmlUrl,
                apk = assets.firstOrNull { it.name.endsWith(".apk") }?.asset(),
                checksums = assets.firstOrNull { it.name == CHECKSUMS }?.asset(),
                prerelease = prerelease,
            )
        }
    }

    @Serializable
    private data class AssetJson(val name: String, val url: String, val size: Long = 0, val digest: String? = null)

    companion object {
        const val API = "https://api.github.com"
        /** What the Release workflow names the checksum file. */
        const val CHECKSUMS = "SHA256SUMS"
        private const val RECENT = 20
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
