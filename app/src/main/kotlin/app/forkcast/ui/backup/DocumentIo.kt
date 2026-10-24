package app.forkcast.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Reads and writes files the user picked with the system file picker (Storage Access Framework). */
interface DocumentIo {
    suspend fun writeText(uri: Uri, text: String)
    suspend fun readText(uri: Uri): String
    /** For large or binary files (the photo .zip). */
    suspend fun writeStream(uri: Uri, block: suspend (OutputStream) -> Unit)
    suspend fun <T> readStream(uri: Uri, block: suspend (InputStream) -> T): T
    /** The file's name as its app shows it (a picker may have changed what was suggested). */
    suspend fun displayName(uri: Uri): String?
}

class ContentResolverDocumentIo @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentIo {

    override suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        // "wt" truncates, so re-using an existing file never leaves old bytes at the end.
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    override suspend fun writeStream(uri: Uri, block: suspend (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        out.use { block(it) }
    }

    override suspend fun <T> readStream(uri: Uri, block: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No input stream")
        input.use { block(it) }
    }

    override suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    override suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No input stream")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("File too large")
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        /** Years of daily logging is a few MB; anything this big isn't a Forkcast backup. */
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentIoModule {
    @Binds
    abstract fun bindDocumentIo(impl: ContentResolverDocumentIo): DocumentIo
}
