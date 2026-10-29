package app.forkcast.ui.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Back up and share" (owner, 2026-10-29): the encrypted backup is written to the app's cache
 * (`cache/share/`, never in Android's backup) and handed to the share sheet through a
 * FileProvider uri. Only the latest one is kept there.
 */
@Singleton
class BackupShares @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir get() = File(context.cacheDir, DIR)

    suspend fun write(name: String, block: suspend (OutputStream) -> Unit): Uri = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("share folder")
        val file = File(dir, name)
        try {
            file.outputStream().use { block(it) }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    companion object {
        const val DIR = "share"
        /** Matches the provider in the manifest. */
        const val AUTHORITY_SUFFIX = ".files"
    }
}
