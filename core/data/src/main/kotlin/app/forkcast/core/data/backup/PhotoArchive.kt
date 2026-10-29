package app.forkcast.core.data.backup

import android.content.Context
import app.forkcast.core.data.ai.PhotoStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What a backup zip held: the export JSON, its manifest (`.forkcast` files only) and how many photos were staged. */
/** [secrets]: the saved keys (1.8.2+ backups), as written by [BackupSecrets]. */
data class ArchiveContents(val json: String, val manifest: String?, val photos: Int, val secrets: String? = null)

/**
 * The zip inside a backup (SPEC §4.11): `manifest.json` (`.forkcast` files, 1.5+), `forkcast.json`,
 * `secrets.json` (the saved keys, 1.8.2+) and optionally `photos/<estimate>/<n>.jpg`, the same relative paths the JSON references. 1.0–1.4's
 * "Include photos" `.zip` is the same without the manifest. Photos from an imported zip are
 * staged first and only moved into place once the import is confirmed.
 */
@Singleton
class PhotoArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photos: PhotoStore,
) {
    private val staging get() = File(context.cacheDir, "import-photos")

    /** Limits for a file being read; tests lower them. */
    internal var maxJsonBytes = MAX_JSON_BYTES
    internal var maxPhotoBytes = MAX_PHOTO_BYTES

    suspend fun write(out: OutputStream, json: String, manifest: String? = null, includePhotos: Boolean = true, secrets: String? = null) = withContext(Dispatchers.IO) {
        ZipOutputStream(out.buffered()).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.encodeToByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToByteArray())
            zip.closeEntry()
            if (secrets != null) {
                zip.putNextEntry(ZipEntry(SECRETS_NAME))
                zip.write(secrets.encodeToByteArray())
                zip.closeEntry()
            }
            if (includePhotos) {
                val root = photos.root
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(PHOTOS_PREFIX + f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** True for a zip file (starts with "PK\u0003\u0004"). The stream must support mark/reset. */
    fun isZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val head = ByteArray(4)
        val n = input.read(head)
        input.reset()
        return n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
    }

    /**
     * Extracts photos to staging and returns the JSON and manifest. Rejects path tricks ("../"),
     * oversized parts and archives with no export. Unknown entries are skipped, never written.
     */
    suspend fun read(input: InputStream): ArchiveContents = withContext(Dispatchers.IO) {
        discardStaged()
        val stage = staging.apply { mkdirs() }
        var json: String? = null
        var manifest: String? = null
        var secrets: String? = null
        var photoCount = 0
        try {
            ZipInputStream(input).use { zip ->
                var total = 0L
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IOException("too many entries")
                    when {
                        entry.isDirectory -> Unit
                        entry.name == JSON_NAME -> json = zip.readText(maxJsonBytes)
                        entry.name == MANIFEST_NAME -> manifest = zip.readText(MAX_MANIFEST_BYTES)
                        entry.name == SECRETS_NAME -> secrets = zip.readText(MAX_MANIFEST_BYTES)
                        entry.name.startsWith(PHOTOS_PREFIX) && entry.name.endsWith(".jpg") -> {
                            val relative = entry.name.removePrefix(PHOTOS_PREFIX)
                            val target = File(stage, relative)
                            if (!target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw IOException("bad path")
                            // Only the "<estimate>/<n>.jpg" files Forkcast writes; anything else is skipped.
                            if (app.forkcast.core.data.ai.PhotoStore.isValidPath(relative)) {
                                target.parentFile?.mkdirs()
                                // Stops as soon as the limit is passed, so a crafted file can't fill the phone.
                                target.outputStream().use { out -> total += zip.copyAtMost(out, maxPhotoBytes - total) }
                                photoCount++
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            stage.deleteRecursively()
            throw e
        }
        ArchiveContents(json ?: throw IOException("no $JSON_NAME in archive"), manifest, photoCount, secrets)
    }

    /** After a confirmed import: staged photos join the app's photos (existing files are kept). */
    suspend fun commitStaged() = withContext(Dispatchers.IO) {
        val stage = staging
        if (!stage.exists()) return@withContext
        stage.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(photos.root, f.relativeTo(stage).path)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                f.copyTo(target)
            }
        }
        stage.deleteRecursively()
    }

    suspend fun discardStaged() = withContext(Dispatchers.IO) { staging.deleteRecursively() }

    companion object {
        const val JSON_NAME = "forkcast.json"
        const val MANIFEST_NAME = "manifest.json"
        const val SECRETS_NAME = "secrets.json"
        const val PHOTOS_PREFIX = "photos/"
        /** Years of daily logging is a few MB; anything this big isn't a Forkcast export. */
        const val MAX_JSON_BYTES = 64 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_PHOTO_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ENTRIES = 200_000
    }
}

/** Reads the rest of this stream as UTF-8, refusing more than [max] bytes. */
/** Copies at most [max] bytes; more than that throws. */
private fun InputStream.copyAtMost(out: OutputStream, max: Long): Long {
    val chunk = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) return copied
        copied += n
        if (copied > max) throw IOException("archive too large")
        out.write(chunk, 0, n)
    }
}

internal fun InputStream.readText(max: Int): String {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        out.write(chunk, 0, n)
        if (out.size() > max) throw IOException("part too large")
    }
    return out.toString(Charsets.UTF_8.name())
}
