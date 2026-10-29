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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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
    /** 2 (1.8.2): how many saved keys `secrets.json` holds. */
    val keys: Int = 0,
) {
    companion object {
        const val FORMAT = "forkcast-backup"
        /** 2 (1.8.2): keys inside, photos always, Argon2id when encrypted. 1 (1.5 to 1.8.1) still restores. */
        const val FORMAT_VERSION = 2
    }
}

/** A backup file, opened as far as the password allows. */
sealed interface OpenedBackupFile {
    /**
     * [json] still has to pass [BackupRepository.parse]; [photos] were staged by [PhotoArchive];
     * [secrets] are the saved keys (1.8.2+ backups), applied by [BackupSecrets] after the import.
     */
    data class Ok(
        val json: String,
        val manifest: BackupManifest?,
        val photos: Int,
        val encrypted: Boolean,
        val secrets: Map<String, String> = emptyMap(),
    ) : OpenedBackupFile
    /** Password-protected, and no password was given. */
    data object NeedsPassword : OpenedBackupFile
    /** Wrong password, or a protected file that was changed or cut short. */
    data object WrongPassword : OpenedBackupFile
}

/**
 * Forkcast's backup file (owner, 2026-10-29): `2026-10-29_0300_forkcast-backup.forkcast`, a zip
 * of [BackupManifest], the JSON export (current schema), the saved keys and the meal photos;
 * encrypted by [BackupCrypto] with the backup password, or `…_unencrypted.forkcast` when the user
 * asks for a copy without encryption. Opening also accepts what older versions made: 1.5 to
 * 1.8.1's `.forkcast` (no keys), the "Include photos" `.zip` and a plain `.json` export.
 */
@Singleton
class BackupFile @Inject constructor(
    private val backups: BackupRepository,
    private val archive: PhotoArchive,
    private val secrets: BackupSecrets,
    @Named(APP_VERSION) private val appVersion: String,
    private val clock: Clock,
) {
    /** How backup passwords become keys; tests (here and in the app module) use lighter settings. */
    var kdf = Kdf()

    /** Writes a complete backup (encrypted with [password], or readable when null) to [out] and closes it; returns the bytes written. */
    suspend fun write(out: OutputStream, password: CharArray?): Long = withContext(Dispatchers.IO) {
        val json = backups.exportJson()
        val keys = secrets.forBackup()
        val manifest = BackupManifest(
            appVersion = appVersion,
            schemaVersion = ForkcastExport.SCHEMA_VERSION,
            createdAt = clock.instant().toString(),
            photos = true,
            keys = keys.size,
        )
        val counted = CountingStream(out)
        try {
            val target = if (password != null) BackupCrypto.encrypt(counted, password, kdf) else counted
            archive.write(target, json, BackupJson.encodeToString(BackupManifest.serializer(), manifest), includePhotos = true, secrets = BackupSecrets.encode(keys))
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Any failure before the zip took over the stream (the encryption header) would leave the file open; rethrown.
            runCatching { out.close() }
            throw e
        }
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
            return OpenedBackupFile.Ok(contents.json, manifest, contents.photos, encrypted, BackupSecrets.decode(contents.secrets))
        }
        if (encrypted) throw IOException("protected backup without a zip inside")
        archive.discardStaged()
        return OpenedBackupFile.Ok(input.readText(PhotoArchive.MAX_JSON_BYTES), null, 0, encrypted = false)
    }

    /**
     * `2026-10-29_0300_forkcast-backup.forkcast` (owner, 2026-10-29: date first), or
     * `…_forkcast-backup_unencrypted.forkcast`. The time keeps several in a day apart and in order
     * by name, for folders whose app doesn't report file times.
     */
    fun fileName(encrypted: Boolean = true): String =
        STAMP.format(clock.instant().atZone(clock.zone)) + "_" + NAME + (if (encrypted) "" else UNENCRYPTED) + EXTENSION

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
        const val NAME = "forkcast-backup"
        const val UNENCRYPTED = "_unencrypted"
        const val EXTENSION = ".forkcast"
        /** What the file picker and the folder get; ".forkcast" has no registered type. */
        const val MIME_TYPE = "application/octet-stream"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

        /** 1.8.2: `2026-10-29_0300_forkcast-backup[_unencrypted][ (1)].forkcast`. */
        private val CURRENT = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{4})_forkcast-backup(_unencrypted)?( \(\d+\))?\.forkcast$""")
        /** 1.5 to 1.8.1: `forkcast-backup-2026-10-24[-0300][ (1)].forkcast`. */
        private val OLDER = Regex("""^forkcast-backup-(\d{4}-\d{2}-\d{2})(?:-(\d{4}))?( \(\d+\))?\.forkcast$""")

        /** Whether [name] looks like a backup Forkcast made (also "… (1).forkcast" from a picker). */
        fun isBackupName(name: String): Boolean = CURRENT.matches(name) || OLDER.matches(name)

        /** An unencrypted copy (only 1.8.2+ names say so). */
        fun isUnencryptedName(name: String): Boolean = CURRENT.matchEntire(name)?.groupValues?.get(3)?.isNotEmpty() == true

        /** When a backup was made, read from its name (local time in [zone]); null if the name doesn't say. */
        fun createdAt(name: String, zone: ZoneId): Instant? {
            val m = CURRENT.matchEntire(name) ?: OLDER.matchEntire(name) ?: return null
            val time = m.groupValues[2].ifEmpty { "0000" }
            return runCatching {
                LocalDateTime.of(LocalDate.parse(m.groupValues[1]), LocalTime.of(time.take(2).toInt(), time.takeLast(2).toInt())).atZone(zone).toInstant()
            }.getOrNull()
        }
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
