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
    /** How many saved keys `secrets.json` holds. */
    val keys: Int = 0,
) {
    companion object {
        const val FORMAT = "forkcast-backup"
        /** Keys inside, photos always, Argon2id when encrypted. */
        const val FORMAT_VERSION = 2
    }
}

/** A backup file, opened as far as the password allows. */
sealed interface OpenedBackupFile {
    /**
     * [json] still has to pass [BackupRepository.parse]; [photos] were staged by [PhotoArchive];
     * [secrets] are the saved keys, applied by [BackupSecrets] after the import.
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
 * asks for a copy without encryption.
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

    /** Reads [input]. Unreadable files (not a backup zip) throw [IOException]. */
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
        if (!archive.isZip(input)) throw IOException("not a backup file")
        val contents = archive.read(input)
        val manifest = contents.manifest?.let { text ->
            runCatching { BackupJson.decodeFromString(BackupManifest.serializer(), text) }.getOrNull()
                ?.takeIf { it.format == BackupManifest.FORMAT }
        }
        return OpenedBackupFile.Ok(contents.json, manifest, contents.photos, encrypted, BackupSecrets.decode(contents.secrets))
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

        /** `2026-10-29_0300_forkcast-backup[_unencrypted][ (1)].forkcast`. */
        private val NAME_PATTERN = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{4})_forkcast-backup(_unencrypted)?( \(\d+\))?\.forkcast$""")

        /** Whether [name] looks like a backup Forkcast made (also "… (1).forkcast" from a picker). */
        fun isBackupName(name: String): Boolean = NAME_PATTERN.matches(name)

        /** An unencrypted copy. */
        fun isUnencryptedName(name: String): Boolean = NAME_PATTERN.matchEntire(name)?.groupValues?.get(3)?.isNotEmpty() == true

        /** When a backup was made, read from its name (local time in [zone]); null if the name doesn't say. */
        fun createdAt(name: String, zone: ZoneId): Instant? {
            val m = NAME_PATTERN.matchEntire(name) ?: return null
            val time = m.groupValues[2]
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
