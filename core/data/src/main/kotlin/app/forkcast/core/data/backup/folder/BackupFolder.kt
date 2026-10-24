package app.forkcast.core.data.backup.folder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file in the backup folder. */
data class FolderFile(val uri: String, val name: String, val modifiedAt: Long?, val sizeBytes: Long?)

/**
 * The folder the user picked for automatic backups (roadmap 2.0, task 1.4): Android's
 * open-document-tree, so it can be on the phone, an SD card or any cloud app that offers a
 * folder (Google Drive, OneDrive, …) with no account or key in Forkcast. Forkcast keeps a
 * persisted permission to that one folder and sees nothing else.
 */
interface BackupFolder {
    /** Keeps access to [tree] across restarts and reboots (call right after the picker returns it). */
    fun keep(tree: String)
    /** Gives up access to [tree]. */
    fun release(tree: String)
    /** Whether Forkcast still may use [tree] and the folder still exists. */
    fun reachable(tree: String): Boolean
    /** The folder's name as its app shows it ("Forkcast", "Documents"). */
    fun displayName(tree: String): String?
    /** Files directly in the folder. Throws if it can't be read. */
    fun list(tree: String): List<FolderFile>
    /** Creates [name] in the folder and returns its uri (the provider may add " (1)"). */
    fun create(tree: String, name: String, mimeType: String): FolderFile
    fun openOutput(file: String): OutputStream
    fun openInput(file: String): InputStream
    fun delete(file: String): Boolean
}

@Singleton
class SafBackupFolder @Inject constructor(@ApplicationContext private val context: Context) : BackupFolder {
    private val resolver get() = context.contentResolver

    override fun keep(tree: String) {
        resolver.takePersistableUriPermission(Uri.parse(tree), FLAGS)
    }

    override fun release(tree: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(tree), FLAGS) }
    }

    override fun reachable(tree: String): Boolean {
        val uri = Uri.parse(tree)
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return granted && runCatching { query(folderDocument(uri)) { it.moveToFirst() } == true }.getOrDefault(false)
    }

    override fun displayName(tree: String): String? = runCatching {
        query(folderDocument(Uri.parse(tree)), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun list(tree: String): List<FolderFile> {
        val treeUri = Uri.parse(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return query(children, columns) { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    add(
                        FolderFile(
                            uri = uri.toString(),
                            name = c.getString(1) ?: continue,
                            modifiedAt = if (c.isNull(2)) null else c.getLong(2).takeIf { it > 0 },
                            sizeBytes = if (c.isNull(3)) null else c.getLong(3),
                        ),
                    )
                }
            }
        } ?: throw FileNotFoundException("folder can't be listed")
    }

    override fun create(tree: String, name: String, mimeType: String): FolderFile {
        val doc = DocumentsContract.createDocument(resolver, folderDocument(Uri.parse(tree)), mimeType, name)
            ?: throw FileNotFoundException("couldn't create $name")
        val shown = runCatching {
            query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: name
        return FolderFile(doc.toString(), shown, null, null)
    }

    // "wt" truncates, so a reused file never keeps old bytes at the end.
    override fun openOutput(file: String): OutputStream =
        resolver.openOutputStream(Uri.parse(file), "wt") ?: throw FileNotFoundException(file)

    override fun openInput(file: String): InputStream =
        resolver.openInputStream(Uri.parse(file)) ?: throw FileNotFoundException(file)

    override fun delete(file: String): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(file)) }.getOrDefault(false)

    private fun folderDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun <T> query(uri: Uri, columns: Array<String>? = null, block: (android.database.Cursor) -> T): T? =
        resolver.query(uri, columns, null, null, null)?.use(block)

    private companion object {
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
