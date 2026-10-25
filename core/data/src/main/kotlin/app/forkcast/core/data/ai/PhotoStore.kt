package app.forkcast.core.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Meal photos in app-private storage (SPEC §4.6, §8). Every photo is downscaled (long edge
 * ≤ [maxEdge]), rotated per its EXIF orientation, and re-encoded as JPEG, which drops all
 * EXIF data including GPS. The stored file is the exact image sent to the AI provider; the
 * original never leaves the device. The folder is excluded from Android Auto Backup.
 */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {

    val root: File get() = File(context.filesDir, DIR)

    /** Processes [source] and stores it; returns the path relative to [root]. */
    suspend fun save(source: Uri, estimateId: String, index: Int, maxEdge: Int = 1568, quality: Int = 85): String = withContext(Dispatchers.IO) {
        // A picked file can be anything: past the limit it isn't read into memory at all (REVIEW-2 S5).
        val bytes = context.contentResolver.openInputStream(source)?.use { readLimited(it, MAX_SOURCE_BYTES) } ?: error("can't read photo")
        val jpeg = process(bytes, maxEdge, quality)
        val rel = "$estimateId/$index.jpg"
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeBytes(jpeg)
        rel
    }

    /**
     * The photo at [relativePath], which must be `<estimate>/<n>.jpg` inside [root]. Paths come from
     * the database and so from imported backups: anything else (`../`, absolute paths) would
     * reach other app files, which a retry would send to an AI provider and the clean-up would
     * delete (REVIEW-2 M1). Those resolve to a file that never exists.
     */
    fun file(relativePath: String): File {
        if (!isValidPath(relativePath)) return File(root, INVALID)
        val f = File(root, relativePath)
        return if (f.canonicalPath.startsWith(root.canonicalPath + File.separator)) f else File(root, INVALID)
    }

    suspend fun read(relativePath: String): ByteArray = withContext(Dispatchers.IO) { file(relativePath).readBytes() }

    suspend fun delete(relativePaths: List<String>) = withContext(Dispatchers.IO) {
        for (p in relativePaths.filter(::isValidPath)) {
            val f = file(p)
            f.delete()
            f.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
        }
    }

    companion object {
        /** Must match the exclusion in data_extraction_rules.xml / backup_rules.xml. */
        const val DIR = "photos"

        /** What save() writes: one folder per estimate, numbered JPEGs. Nothing else is a photo path. */
        private val PATH = Regex("""[A-Za-z0-9_-]{1,64}/\d{1,3}\.jpg""")

        /** A name no saved photo can have (it fails [PATH]), for paths that aren't photos. */
        private const val INVALID = "invalid/invalid"

        fun isValidPath(relativePath: String): Boolean = PATH.matches(relativePath)

        /** Phone photos are a few MB; 40 MB covers any real one and keeps a decode within memory. */
        const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

        /** Reads [input] fully, or throws once it passes [max] bytes. */
        internal fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                if (out.size() > max) throw java.io.IOException("photo too large")
            }
            return out.toByteArray()
        }

        /** Downscale + orientation + JPEG re-encode (no EXIF survives). */
        fun process(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("can't decode photo")
            val orientation = runCatching {
                ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val longEdge = max(decoded.width, decoded.height)
            val scale = if (longEdge > maxEdge) maxEdge.toFloat() / longEdge else 1f
            val matrix = Matrix().apply {
                postScale(scale, scale)
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                }
            }
            val out = if (scale == 1f && orientation == ExifInterface.ORIENTATION_NORMAL) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
            val buffer = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            if (out !== decoded) decoded.recycle()
            out.recycle()
            return buffer.toByteArray()
        }
    }
}
