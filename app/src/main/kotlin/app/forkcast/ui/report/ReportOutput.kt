package app.forkcast.ui.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import app.forkcast.ui.backup.BackupShares
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The generated report, once, in the app's cache (`cache/report/`, never in Android's backup):
 * Save copies it, Share hands it out read-only through the FileProvider, Print sends it to
 * Android's print dialog (owner, 2026-10-29). It's deleted when the preview closes, and anything
 * left over (the app was closed meanwhile) when the app starts.
 */
object ReportCache {
    private const val DIR = "report"

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /** An empty file named [name]; any earlier report is deleted first. */
    fun newFile(context: Context, name: String): File {
        val dir = dir(context)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("report folder")
        return File(dir, name)
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + BackupShares.AUTHORITY_SUFFIX, file)
}

object ReportOutput {
    /** The share sheet, with read-only access to this one file. */
    fun share(context: Context, file: File, format: ReportFormat, chooserTitle: String) {
        val uri = ReportCache.uri(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /** Android's print dialog (which also offers "Save as PDF"); [context] must be the activity. */
    fun print(context: Context, file: File, jobName: String) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return
        manager.print(jobName, PdfFileAdapter(file), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}

/** Prints a finished PDF as it is: the print service asks for all pages and gets the file. */
private class PdfFileAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
        val info = PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }
}
