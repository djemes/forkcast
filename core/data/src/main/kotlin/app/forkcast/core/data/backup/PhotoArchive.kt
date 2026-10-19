package app.forkcast.core.data.backup

import android.content.Context
import app.forkcast.core.data.ai.PhotoStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Include photos" export (SPEC §4.11): a .zip with `forkcast.json` plus `photos/<estimate>/<n>.jpg`,
 * the same relative paths the JSON references. Photos from an imported zip are staged first and
 * only moved into place once the import is confirmed.
 */
@Singleton
class PhotoArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photos: PhotoStore,
) {
    private val staging get() = File(context.cacheDir, "import-photos")

    suspend fun write(out: OutputStream, json: String) = withContext(Dispatchers.IO) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToByteArray())
            zip.closeEntry()
            val root = photos.root
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(PHOTOS_PREFIX + f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
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

    /** Extracts photos to staging and returns the JSON text. Rejects path tricks ("../"). */
    suspend fun read(input: InputStream): String = withContext(Dispatchers.IO) {
        discardStaged()
        val stage = staging.apply { mkdirs() }
        var json: String? = null
        ZipInputStream(input).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name == JSON_NAME -> json = zip.readBytes().decodeToString()
                    entry.name.startsWith(PHOTOS_PREFIX) && entry.name.endsWith(".jpg") -> {
                        val target = File(stage, entry.name.removePrefix(PHOTOS_PREFIX))
                        if (!target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw IOException("bad path")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> total += zip.copyTo(out) }
                        if (total > MAX_PHOTO_BYTES) throw IOException("archive too large")
                    }
                }
                zip.closeEntry()
            }
        }
        json ?: throw IOException("no $JSON_NAME in archive")
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
        const val PHOTOS_PREFIX = "photos/"
        private const val MAX_PHOTO_BYTES = 2L * 1024 * 1024 * 1024
    }
}
