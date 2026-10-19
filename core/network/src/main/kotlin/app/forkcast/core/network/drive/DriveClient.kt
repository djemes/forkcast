package app.forkcast.core.network.drive

import app.forkcast.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject

/** A backup file in the user's Drive (only files this app created are visible with `drive.file`). */
@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    /** Bytes, as Drive reports it (a string in the API). */
    val size: String? = null,
    /** RFC 3339, e.g. 2026-10-19T07:02:11.000Z. */
    val createdTime: String? = null,
    val appProperties: Map<String, String> = emptyMap(),
)

/** What can go wrong talking to Drive; the backup screen and notification name each. */
sealed class DriveError(message: String) : Exception(message) {
    class Offline : DriveError("No connection")
    /** The access token was refused (expired or access removed): get a fresh one, or reconnect. */
    class Unauthorized : DriveError("Drive refused the sign-in")
    /** Drive's storage is full. */
    class QuotaFull : DriveError("Drive storage is full")
    class RateLimited : DriveError("Drive is busy")
    class Http(val code: Int, detail: String) : DriveError("Drive HTTP $code: $detail")
}

/**
 * Google Drive REST v3 over Ktor (SPEC §4.11, §7): no Google SDK. Every call takes the access
 * token from Google's AuthorizationClient (scope `drive.file`); tokens are never stored or logged.
 * Shapes checked against developers.google.com/workspace/drive/api/reference/rest/v3 (2026-10-19).
 */
class DriveClient @Inject constructor(private val http: HttpClient) {

    /** The signed-in account's email, for Settings ("Backing up to …"). */
    suspend fun accountEmail(token: String): String? {
        val r = call { http.get("$API/about") { auth(token); parameter("fields", "user(emailAddress)") } }
        return r.body<About>().user?.emailAddress
    }

    /** The app's "Forkcast Backups" folder, created when missing. */
    suspend fun folder(token: String, name: String = FOLDER_NAME): String {
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = call {
            http.get("$API/files") {
                auth(token)
                parameter("q", q)
                parameter("spaces", "drive")
                parameter("fields", "files(id,name)")
            }
        }.body<FileList>().files.firstOrNull()
        if (found != null) return found.id
        return call {
            http.post("$API/files") {
                auth(token)
                contentType(ContentType.Application.Json)
                setBody(NetworkJson.encodeToString(NewFile(name, FOLDER_MIME)))
                parameter("fields", "id")
            }
        }.body<DriveFile>().id
    }

    /** Backups in [folderId], newest first. */
    suspend fun list(token: String, folderId: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val list = call {
                http.get("$API/files") {
                    auth(token)
                    parameter("q", "'$folderId' in parents and trashed = false")
                    parameter("spaces", "drive")
                    parameter("orderBy", "createdTime desc")
                    parameter("pageSize", 100)
                    parameter("fields", "nextPageToken,files(id,name,size,createdTime,appProperties)")
                    page?.let { parameter("pageToken", it) }
                }
            }.body<FileList>()
            out += list.files
            page = list.nextPageToken
        } while (page != null)
        return out
    }

    /**
     * Uploads one gzipped backup into [folderId]: multipart for small files (Google's guidance is
     * up to 5 MB), resumable above that (one session, sent in one piece).
     */
    suspend fun upload(token: String, folderId: String, name: String, bytes: ByteArray, properties: Map<String, String> = emptyMap()): DriveFile {
        val meta = NetworkJson.encodeToString(NewFile(name, GZIP_MIME, listOf(folderId), properties))
        if (bytes.size > MULTIPART_MAX) return resumable(token, meta, bytes)
        val boundary = "forkcast-" + UUID.randomUUID()
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta)
            append("\r\n--$boundary\r\n")
            append("Content-Type: $GZIP_MIME\r\n\r\n")
        }.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        return call {
            http.post("$UPLOAD/files") {
                auth(token)
                parameter("uploadType", "multipart")
                parameter("fields", "id,name,size,createdTime,appProperties")
                header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
                setBody(body)
                timeout { requestTimeoutMillis = LONG_MS; socketTimeoutMillis = LONG_MS }
            }
        }.body()
    }

    private suspend fun resumable(token: String, meta: String, bytes: ByteArray): DriveFile {
        val start = call {
            http.post("$UPLOAD/files") {
                auth(token)
                parameter("uploadType", "resumable")
                header("X-Upload-Content-Type", GZIP_MIME)
                header("X-Upload-Content-Length", bytes.size.toString())
                contentType(ContentType.Application.Json)
                setBody(meta)
            }
        }
        val session = start.headers[HttpHeaders.Location] ?: throw DriveError.Http(start.status.value, "no upload session")
        return call {
            http.put(session) {
                parameter("fields", "id,name,size,createdTime,appProperties")
                header(HttpHeaders.ContentType, GZIP_MIME)
                setBody(bytes)
                timeout { requestTimeoutMillis = LONG_MS; socketTimeoutMillis = LONG_MS }
            }
        }.body()
    }

    suspend fun download(token: String, fileId: String): ByteArray = call {
        http.get("$API/files/$fileId") {
            auth(token)
            parameter("alt", "media")
            timeout { requestTimeoutMillis = LONG_MS; socketTimeoutMillis = LONG_MS }
        }
    }.readRawBytes()

    suspend fun delete(token: String, fileId: String) {
        val r = call(allow404 = true) { http.delete("$API/files/$fileId") { auth(token) } }
        check(r.status.value in 200..299 || r.status.value == 404)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private suspend fun call(allow404: Boolean = false, block: suspend () -> HttpResponse): HttpResponse {
        val r = try {
            block()
        } catch (e: java.io.IOException) {
            throw DriveError.Offline()
        }
        val code = r.status.value
        if (code in 200..299 || (allow404 && code == 404)) return r
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        throw when {
            code == 401 -> DriveError.Unauthorized()
            code == 403 && "storageQuotaExceeded" in text -> DriveError.QuotaFull()
            code == 429 || (code == 403 && ("rateLimitExceeded" in text || "userRateLimitExceeded" in text)) -> DriveError.RateLimited()
            else -> DriveError.Http(code, reason(text))
        }
    }

    /** Google's error `reason` (never the whole body). */
    private fun reason(text: String): String = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "error"

    @Serializable private data class About(val user: User? = null)
    @Serializable private data class User(val emailAddress: String? = null)
    @Serializable private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)
    @Serializable private data class NewFile(
        val name: String,
        val mimeType: String,
        val parents: List<String>? = null,
        val appProperties: Map<String, String>? = null,
    )

    companion object {
        const val FOLDER_NAME = "Forkcast Backups"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val GZIP_MIME = "application/gzip"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val LONG_MS = 120_000L
        const val MULTIPART_MAX = 5 * 1024 * 1024
    }
}
