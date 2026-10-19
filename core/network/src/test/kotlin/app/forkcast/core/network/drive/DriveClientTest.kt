package app.forkcast.core.network.drive

import app.forkcast.core.network.HttpClientFactory
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Drive v3 request shapes (SPEC §4.11); responses follow the documented JSON. */
class DriveClientTest {

    private class Seen(val req: HttpRequestData, val body: String)
    private val seen = mutableListOf<Seen>()

    private fun drive(handler: (HttpRequestData) -> Triple<HttpStatusCode, String, Map<String, String>>) = DriveClient(
        HttpClientFactory.create(
            "Forkcast/test",
            MockEngine { req ->
                seen += Seen(req, String(req.body.toByteArray()))
                val (status, body, headers) = handler(req)
                respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
            },
        ),
    )

    private fun ok(body: String) = Triple(HttpStatusCode.OK, body, emptyMap<String, String>())

    @Test fun findsTheFolderOrCreatesIt() = runTest {
        val found = drive { ok("""{"files":[{"id":"F1","name":"Forkcast Backups"}]}""") }
        assertThat(found.folder("tok")).isEqualTo("F1")
        val q = seen.single().req.url.parameters["q"]!!
        assertThat(q).contains("name = 'Forkcast Backups'")
        assertThat(q).contains("mimeType = 'application/vnd.google-apps.folder'")
        assertThat(seen.single().req.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")

        seen.clear()
        val created = drive { req -> if (req.method == HttpMethod.Get) ok("""{"files":[]}""") else ok("""{"id":"F2"}""") }
        assertThat(created.folder("tok")).isEqualTo("F2")
        assertThat(seen.last().req.method).isEqualTo(HttpMethod.Post)
        assertThat(seen.last().body).contains("\"mimeType\":\"application/vnd.google-apps.folder\"")
    }

    @Test fun uploadsMultipartWithMetadataFirst() = runTest {
        val d = drive { ok("""{"id":"B1","name":"forkcast-20261019-0300.json.gz","size":"42","createdTime":"2026-10-19T07:00:00.000Z"}""") }
        val file = d.upload("tok", "F1", "forkcast-20261019-0300.json.gz", byteArrayOf(1, 2, 3), mapOf("forkcast" to "backup"))
        assertThat(file.id).isEqualTo("B1")
        val r = seen.single()
        assertThat(r.req.url.toString()).startsWith("https://www.googleapis.com/upload/drive/v3/files")
        assertThat(r.req.url.parameters["uploadType"]).isEqualTo("multipart")
        val type = r.req.body.contentType?.toString() ?: r.req.headers[HttpHeaders.ContentType]
        assertThat(type).startsWith("multipart/related; boundary=")
        assertThat(r.body.indexOf("\"parents\":[\"F1\"]")).isLessThan(r.body.indexOf("Content-Type: application/gzip"))
        assertThat(r.body).contains("\"appProperties\":{\"forkcast\":\"backup\"}")
    }

    @Test fun bigFilesUseAResumableSession() = runTest {
        val d = drive { req ->
            if (req.url.parameters["uploadType"] == "resumable") Triple(HttpStatusCode.OK, "", mapOf(HttpHeaders.Location to "https://upload.example/session1"))
            else ok("""{"id":"B2","name":"big.json.gz"}""")
        }
        val file = d.upload("tok", "F1", "big.json.gz", ByteArray(DriveClient.MULTIPART_MAX + 1))
        assertThat(file.id).isEqualTo("B2")
        assertThat(seen.map { it.req.method }).containsExactly(HttpMethod.Post, HttpMethod.Put).inOrder()
        assertThat(seen[0].req.headers["X-Upload-Content-Length"]).isEqualTo((DriveClient.MULTIPART_MAX + 1).toString())
        assertThat(seen[1].req.url.host).isEqualTo("upload.example")
    }

    @Test fun listsNewestFirstAcrossPages() = runTest {
        val d = drive { req ->
            if (req.url.parameters["pageToken"] == null) ok("""{"nextPageToken":"p2","files":[{"id":"a","name":"x","createdTime":"2026-10-19T07:00:00Z"}]}""")
            else ok("""{"files":[{"id":"b","name":"y"}]}""")
        }
        assertThat(d.list("tok", "F1").map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(seen.first().req.url.parameters["orderBy"]).isEqualTo("createdTime desc")
        assertThat(seen.first().req.url.parameters["q"]).isEqualTo("'F1' in parents and trashed = false")
    }

    @Test fun errorsAreNamed() = runTest {
        assertThrows(DriveError.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { drive { Triple(HttpStatusCode.Unauthorized, "{}", emptyMap()) }.accountEmail("tok") }
        }
        assertThrows(DriveError.QuotaFull::class.java) {
            kotlinx.coroutines.runBlocking {
                drive { Triple(HttpStatusCode.Forbidden, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", emptyMap()) }
                    .upload("tok", "F1", "a", byteArrayOf(1))
            }
        }
        // Deleting something already gone is fine.
        drive { Triple(HttpStatusCode.NotFound, "{}", emptyMap()) }.delete("tok", "gone")
    }
}
