package app.forkcast.core.data.drive

import android.content.Intent
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.network.HttpClientFactory
import app.forkcast.core.network.drive.DriveClient
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class DriveBackupRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T07:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-drive-${System.nanoTime()}.json") },
    )
    private val device = DevicePrefs(context).also { d -> d.update { DeviceStateReset } }

    private class FakeAuth : DriveAuth {
        var access: DriveAccess = DriveAccess.Granted("tok1")
        val forgotten = mutableListOf<String>()
        override suspend fun authorize() = access
        override fun fromConsent(data: Intent?) = access
        override suspend fun forget(token: String) { forgotten += token; access = DriveAccess.Granted("tok2") }
        override suspend fun revoke(email: String) = Unit
    }

    private class FakeBackup : BackupRepository {
        override suspend fun exportJson() = """{"app":"Forkcast","schemaVersion":5}"""
        override suspend fun foodLogCsv() = ""
        override suspend fun weightsCsv() = ""
        /** Reads only the version: 6 restores, 5 is an older export, anything else is unreadable. */
        override fun parse(text: String): ParsedBackup {
            val version = Regex("\"schemaVersion\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
                ?: return ParsedBackup.Rejected(app.forkcast.core.data.backup.RejectReason.UNREADABLE)
            val export = ForkcastExport(schemaVersion = version, appVersion = "1.3.0", exportedAt = "2026-10-19T07:00:00Z", timeZone = "UTC")
            return ParsedBackup.Ok(export, app.forkcast.core.data.backup.preview(export))
        }
        override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary = throw UnsupportedOperationException()
        override suspend fun readSafetyBackup(): String? = null
    }

    private class Call(val req: HttpRequestData, val body: ByteArray)
    private val calls = mutableListOf<Call>()
    private val auth = FakeAuth()
    /** What downloading any backup returns (gzipped). */
    private var downloaded = """{"app":"Forkcast"}"""

    /** 20 daily backups already in the folder (the retention keeps 14 + firsts of months). */
    private val existing = (1..20).joinToString(",") { d ->
        """{"id":"old$d","name":"forkcast-202610${"%02d".format(d)}-0300.json.gz","createdTime":"2026-10-${"%02d".format(d)}T07:00:00Z"}"""
    }

    private fun repo(unauthorizedFirst: Boolean = false, files: String = existing): DefaultDriveBackupRepository {
        var refused = !unauthorizedFirst
        val http = HttpClientFactory.create("t", MockEngine { req ->
            calls += Call(req, req.body.toByteArray())
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (!refused) {
                refused = true
                return@MockEngine respond("{}", HttpStatusCode.Unauthorized, json)
            }
            val url = req.url.toString()
            when {
                "/about" in url -> respond("""{"user":{"emailAddress":"gabe@example.com"}}""", HttpStatusCode.OK, json)
                req.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                "uploadType=multipart" in url -> respond("""{"id":"new","name":"x"}""", HttpStatusCode.OK, json)
                "alt=media" in url -> respond(gzip(downloaded), HttpStatusCode.OK)
                "in+parents" in url || "in%20parents" in url || req.url.parameters["q"]?.contains("in parents") == true ->
                    respond("""{"files":[$files]}""", HttpStatusCode.OK, json)
                else -> respond("""{"files":[{"id":"F1","name":"Forkcast Backups"}]}""", HttpStatusCode.OK, json)
            }
        })
        return DefaultDriveBackupRepository(auth, DriveClient(http), FakeBackup(), device, settings, clock)
    }

    @Test fun connectRemembersAccountAndFolder() = runTest {
        assertThat(repo().connect("tok1")).isNull()
        assertThat(device.state.value.driveAccount).isEqualTo("gabe@example.com")
        assertThat(device.state.value.driveFolderId).isEqualTo("F1")
    }

    @Test fun backupUploadsGzippedExportAndPrunes() = runTest {
        val r = repo()
        r.connect("tok1")
        calls.clear()
        assertThat(r.backupNow()).isNull()

        val upload = calls.single { "uploadType=multipart" in it.req.url.toString() }
        val text = String(upload.body, Charsets.ISO_8859_1)
        assertThat(text).contains("\"name\":\"forkcast-20261019-0300.json.gz\"")
        assertThat(text).contains("\"parents\":[\"F1\"]")
        // The media part is gzip (magic bytes 1f 8b).
        val media = upload.body.toList().windowed(2).indexOfFirst { it[0] == 0x1f.toByte() && it[1] == 0x8b.toByte() }
        assertThat(media).isGreaterThan(0)
        // 20 old backups: 14 newest kept, plus Oct 1 (first of the month); 5 deleted.
        assertThat(calls.count { it.req.method == HttpMethod.Delete }).isEqualTo(5)
        assertThat(device.state.value.lastDriveBackupAt).isEqualTo(clock.millis())
        assertThat(device.state.value.lastDriveError).isNull()
    }

    @Test fun refusedTokenIsDroppedAndRetriedOnce() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        assertThat(repo(unauthorizedFirst = true, files = "").backupNow()).isNull()
        assertThat(auth.forgotten).containsExactly("tok1")
        assertThat(calls.last { "uploadType" in it.req.url.toString() }.req.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok2")
    }

    @Test fun lostAccessAsksToReconnect() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        auth.access = DriveAccess.NeedsConsent(android.app.PendingIntent.getActivity(context, 0, Intent(), android.app.PendingIntent.FLAG_IMMUTABLE))
        assertThat(repo().backupNow()).isEqualTo(DriveProblem.RECONNECT)
        assertThat(device.state.value.lastDriveError).isEqualTo("RECONNECT")
    }

    @Test fun notConnectedDoesNothing() = runTest {
        assertThat(repo().backupNow()).isEqualTo(DriveProblem.NOT_CONNECTED)
        assertThat(calls).isEmpty()
    }

    @Test fun downloadUnzips() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        assertThat(repo().download("old1")).isEqualTo("""{"app":"Forkcast"}""")
    }

    @Test fun cleanupKeepsTheNewestOnceItRestores() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        downloaded = """{"app":"Forkcast","schemaVersion":${ForkcastExport.SCHEMA_VERSION}}"""
        val r = repo()
        val plan = r.cleanupPlan() as CleanupPlan.Ready
        assertThat(plan.keep.id).isEqualTo("old20")
        assertThat(plan.delete.map { it.id }).containsExactlyElementsIn((1..19).map { "old$it" })
        // Checking deleted nothing and downloaded only the newest.
        assertThat(calls.count { it.req.method == HttpMethod.Delete }).isEqualTo(0)
        assertThat(calls.single { "alt=media" in it.req.url.toString() }.req.url.encodedPath).endsWith("/old20")

        calls.clear()
        // A file that isn't in the folder any more (or never was) is never touched.
        val result = r.deleteOld(plan.copy(delete = plan.delete + DriveBackup("elsewhere", "x", null, null))).getOrThrow()
        assertThat(result).isEqualTo(CleanupResult(deleted = 19, failed = 0, kept = plan.keep))
        val gone = calls.filter { it.req.method == HttpMethod.Delete }.map { it.req.url.encodedPath.substringAfterLast('/') }
        assertThat(gone).containsExactlyElementsIn((1..19).map { "old$it" })
    }

    @Test fun cleanupDeletesNothingWhenTheNewestIsOldOrUnreadable() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        downloaded = """{"app":"Forkcast","schemaVersion":5}"""
        assertThat((repo().cleanupPlan() as CleanupPlan.LatestNotRestorable).reason).isEqualTo(NotRestorable.OLD_FORMAT)
        downloaded = """{"app":"Forkcast","logEntries":[{"id":"cut sh"""
        assertThat((repo().cleanupPlan() as CleanupPlan.LatestNotRestorable).reason).isEqualTo(NotRestorable.UNREADABLE)
        assertThat(calls.count { it.req.method == HttpMethod.Delete }).isEqualTo(0)
    }

    @Test fun cleanupWithOneBackupHasNothingToDelete() = runTest {
        device.update { it.copy(driveAccount = "gabe@example.com", driveFolderId = "F1") }
        downloaded = """{"app":"Forkcast","schemaVersion":${ForkcastExport.SCHEMA_VERSION}}"""
        val one = """{"id":"only","name":"forkcast-20261019-0300.json.gz","createdTime":"2026-10-19T07:00:00Z"}"""
        assertThat((repo(files = one).cleanupPlan() as CleanupPlan.NothingToDelete).keep?.id).isEqualTo("only")
        assertThat(repo(files = "").cleanupPlan()).isEqualTo(CleanupPlan.NothingToDelete(null))
    }

    @Test fun cleanupNeedsDrive() = runTest {
        assertThat(repo().cleanupPlan()).isEqualTo(CleanupPlan.Failed(DriveProblem.NOT_CONNECTED))
        assertThat(calls).isEmpty()
    }

    private companion object {
        val DeviceStateReset = app.forkcast.core.data.settings.DeviceState()

        fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
    }
}
