package app.forkcast.core.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Readable facts about a `.forkcast` file, written first in its zip. */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    /** The app version that made it. */
    val appVersion: String,
    /** The export JSON's [ForkcastExport.schemaVersion]. */
    val schemaVersion: Int,
    /** ISO-8601 instant. */
    val createdAt: String,
    val photos: Boolean,
) {
    companion object {
        const val FORMAT = "forkcast-backup"
        const val FORMAT_VERSION = 1
    }
}

/** A backup file, opened as far as the password allows. */
sealed interface OpenedBackupFile {
    /** [json] still has to pass [BackupRepository.parse]; [photos] were staged by [PhotoArchive]. */
    data class Ok(val json: String, val manifest: BackupManifest?, val photos: Int, val encrypted: Boolean) : OpenedBackupFile
    /** Password-protected, and no password was given. */
    data object NeedsPassword : OpenedBackupFile
    /** Wrong password, or a protected file that was changed or cut short. */
    data object WrongPassword : OpenedBackupFile
}

/**
 * Forkcast's backup file (roadmap 2.0, task 1.4): `forkcast-backup-YYYY-MM-DD.forkcast`, a zip
 * of [BackupManifest], the JSON export (current schema) and, if chosen, meal photos; with a
 * password, that zip encrypted by [BackupCrypto]. Opening also accepts the files older versions
 * made: the "Include photos" `.zip` and a plain `.json` export. No API keys are ever in it.
 */
@Singleton
class BackupFile @Inject constructor(
    private val backups: BackupRepository,
    private val archive: PhotoArchive,
    @Named(APP_VERSION) private val appVersion: String,
    private val clock: Clock,
) {
    /** Writes a complete backup to [out] and closes it; returns the bytes written. */
    suspend fun write(out: OutputStream, includePhotos: Boolean, password: CharArray?): Long = withContext(Dispatchers.IO) {
        val json = backups.exportJson()
        val manifest = BackupManifest(
            appVersion = appVersion,
            schemaVersion = ForkcastExport.SCHEMA_VERSION,
            createdAt = clock.instant().toString(),
            photos = includePhotos,
        )
        val counted = CountingStream(out)
        val target = if (password != null) BackupCrypto.encrypt(counted, password) else counted
        archive.write(target, json, BackupJson.encodeToString(BackupManifest.serializer(), manifest), includePhotos)
        counted.count
    }

    /** Reads [input] (any backup format this version restores). Unreadable files throw [IOException]. */
    suspend fun open(input: InputStream, password: CharArray?): OpenedBackupFile = withContext(Dispatchers.IO) {
        val buffered = input.buffered()
        val head = ByteArray(BackupCrypto.MAGIC.size)
        buffered.mark(head.size)
        val n = buffered.readNBytesCompat(head)
        buffered.reset()
        if (n == head.size && BackupCrypto.isEncrypted(head)) {
            if (password == null) return@withContext OpenedBackupFile.NeedsPassword
            try {
                readPlain(BufferedInputStream(BackupCrypto.decrypt(buffered, password)), encrypted = true)
            } catch (e: BackupDecryptException) {
                archive.discardStaged()
                OpenedBackupFile.WrongPassword
            }
        } else {
            readPlain(buffered, encrypted = false)
        }
    }

    private suspend fun readPlain(input: BufferedInputStream, encrypted: Boolean): OpenedBackupFile {
        if (archive.isZip(input)) {
            val contents = archive.read(input)
            val manifest = contents.manifest?.let { text ->
                runCatching { BackupJson.decodeFromString(BackupManifest.serializer(), text) }.getOrNull()
                    ?.takeIf { it.format == BackupManifest.FORMAT }
            }
            return OpenedBackupFile.Ok(contents.json, manifest, contents.photos, encrypted)
        }
        if (encrypted) throw IOException("protected backup without a zip inside")
        archive.discardStaged()
        return OpenedBackupFile.Ok(input.readText(PhotoArchive.MAX_JSON_BYTES), null, 0, encrypted = false)
    }

    /** `forkcast-backup-2026-10-24.forkcast` for [day] (default today). */
    fun fileName(day: LocalDate = LocalDate.now(clock)): String = PREFIX + DAY.format(day) + EXTENSION

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) {
            out.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        const val PREFIX = "forkcast-backup-"
        const val EXTENSION = ".forkcast"
        /** What the file picker and the folder get; ".forkcast" has no registered type. */
        const val MIME_TYPE = "application/octet-stream"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** Whether [name] looks like a backup Forkcast made (also "… (1).forkcast" from a picker). */
        fun isBackupName(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(EXTENSION)
    }
}

/** InputStream.readNBytes needs API 33; reads until [buf] is full or the stream ends. */
private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
