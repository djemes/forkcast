package app.forkcast.ui.report

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.forkcast.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The made report, full screen (owner, 2026-10-29): the PDF's pages, or what the spreadsheet
 * holds, with Save, Share and Print (PDF only). Closing it deletes the file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPreview(
    ready: ReportStep.Ready,
    snackbar: SnackbarHostState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.report_section_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.report_close)) }
                        },
                    )
                },
                bottomBar = { ReportActions(pdf = ready.options.format == ReportFormat.PDF, onSave, onShare, onPrint) },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                if (ready.options.format == ReportFormat.PDF) PdfPreview(ready, Modifier.padding(padding))
                else SheetPreview(ready, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun ReportActions(pdf: Boolean, onSave: () -> Unit, onShare: () -> Unit, onPrint: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_save)) }
            OutlinedButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_share)) }
            if (pdf) OutlinedButton(onClick = onPrint, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_print)) }
        }
    }
}

/** Dates, days and entries: the lines both previews start with. */
@Composable
private fun ReportFacts(ready: ReportStep.Ready) {
    val locale = LocalLocale.current.platformLocale
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    Text(stringResource(R.string.report_range, dates.format(ready.options.from), dates.format(ready.options.to)), style = MaterialTheme.typography.titleMedium)
    val count = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Text(
        pluralStringResource(R.plurals.report_preview_days, ready.days, count.format(ready.days), count.format(ready.loggedDays)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(pluralStringResource(R.plurals.report_preview_entries, ready.entries, count.format(ready.entries)), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SheetPreview(ready: ReportStep.Ready, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.report_format_sheet), style = MaterialTheme.typography.headlineSmall)
        ReportFacts(ready)
        Text(stringResource(R.string.report_sheet_contents), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ready.file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pages, drawn as they scroll into view (a long report has hundreds). */
@Composable
private fun PdfPreview(ready: ReportStep.Ready, modifier: Modifier) {
    val pages = remember(ready.path) { PdfPages.open(ready.file) }
    DisposableEffect(pages) { onDispose { pages?.close() } }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReportFacts(ready)
                    val count = pages?.count ?: 0
                    Text(pluralStringResource(R.plurals.report_pages, count, count), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (pages == null) {
                item { Text(stringResource(R.string.report_failed), color = MaterialTheme.colorScheme.error) }
            } else {
                items((0 until pages.count).toList()) { i -> PdfPage(pages, i, widthPx) }
            }
        }
    }
}

@Composable
private fun PdfPage(pages: PdfPages, index: Int, widthPx: Int) {
    val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) { value = pages.render(index, widthPx)?.asImageBitmap() }
    val label = stringResource(R.string.report_page_of, index + 1, pages.count)
    Surface(
        Modifier.fillMaxWidth().aspectRatio(A4_RATIO).border(1.dp, MaterialTheme.colorScheme.outlineVariant).semantics { contentDescription = label },
        color = androidx.compose.ui.graphics.Color.White,
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

/** Android's own PdfRenderer over the made file; one page at a time (it isn't thread-safe). */
private class PdfPages private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
    private val lock = Any()
    private var closed = false
    val count: Int = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed || widthPx <= 0) return@synchronized null
            val page = renderer.openPage(index)
            try {
                val bitmap = Bitmap.createBitmap(widthPx, (widthPx.toLong() * page.height / page.width).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            renderer.close()
            fd.close()
        }
    }

    companion object {
        /** Null when the file can't be read (it was deleted, or the PDF is damaged). */
        @Suppress("SwallowedException")
        fun open(file: File): PdfPages? = try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfPages(fd, PdfRenderer(fd))
            } catch (e: IOException) {
                fd.close()
                throw e
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}

/** The report's pages are A4, 595 × 842 pt. */
private const val A4_RATIO = 595f / 842f
