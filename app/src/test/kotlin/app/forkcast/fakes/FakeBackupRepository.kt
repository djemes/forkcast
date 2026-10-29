package app.forkcast.fakes

import android.net.Uri
import app.forkcast.core.data.backup.BackupCounts
import app.forkcast.core.data.backup.BackupPreview
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.backup.ImportSummary
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.ui.backup.DocumentIo
import java.io.IOException
import java.time.LocalDate

/** Files are plain strings: "good" parses, "newer" is rejected, anything else is unreadable. */
class FakeBackupRepository : BackupRepository {
    val imports = mutableListOf<ImportMode>()
    var safetyBackup: String? = null
    var failImport = false

    override suspend fun exportJson() = "{json}"

    private val aliases = mutableMapOf<String, String>()

    /** [text] parses the way [like] does (e.g. the exported "{json}" as "good"). */
    fun parseAs(text: String, like: String) { aliases[text] = like }

    override fun parse(text: String): ParsedBackup = when (aliases[text] ?: text) {
        "good" -> ParsedBackup.Ok(EXPORT, PREVIEW)
        "newer" -> ParsedBackup.Rejected(RejectReason.NEWER_VERSION)
        else -> ParsedBackup.Rejected(RejectReason.UNREADABLE)
    }

    override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary {
        if (failImport) throw IllegalStateException("disk full")
        imports += mode
        if (mode == ImportMode.REPLACE) safetyBackup = "good"
        return ImportSummary(mode, added = 3, updated = 1, unchanged = 2)
    }

    override suspend fun readSafetyBackup() = safetyBackup

    companion object {
        val EXPORT = ForkcastExport(appVersion = "0.1.0", exportedAt = "2026-10-18T16:00:00Z", timeZone = "UTC")
        val PREVIEW = BackupPreview(
            exportedAt = null,
            appVersion = "0.1.0",
            counts = BackupCounts(foods = 12, logEntries = 340, savedMeals = 2, weights = 30, water = 90, goals = 1, hasProfile = true),
            firstDate = LocalDate.of(2026, 9, 1),
            lastDate = LocalDate.of(2026, 10, 18),
        )
    }
}

class FakeDocumentIo : DocumentIo {
    val written = mutableMapOf<Uri, String>()
    val files = mutableMapOf<Uri, String>()
    var failWrites = false

    override suspend fun writeText(uri: Uri, text: String) {
        if (failWrites) throw IOException("read-only")
        written[uri] = text
    }

    override suspend fun readText(uri: Uri) = files[uri] ?: throw IOException("missing")

    val writtenBytes = mutableMapOf<Uri, ByteArray>()

    override suspend fun writeStream(uri: Uri, block: suspend (java.io.OutputStream) -> Unit) {
        if (failWrites) throw IOException("read-only")
        val out = java.io.ByteArrayOutputStream()
        block(out)
        writtenBytes[uri] = out.toByteArray()
    }

    /** Binary files (a real backup) win over text ones. */
    val fileBytes = mutableMapOf<Uri, ByteArray>()

    override suspend fun <T> readStream(uri: Uri, block: suspend (java.io.InputStream) -> T): T =
        block(fileBytes[uri]?.inputStream() ?: (files[uri] ?: throw IOException("missing")).byteInputStream())

    override suspend fun displayName(uri: Uri): String? = uri.lastPathSegment
}
