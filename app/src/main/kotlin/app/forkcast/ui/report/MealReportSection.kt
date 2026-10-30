package app.forkcast.ui.report

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WaterRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.MealReport
import app.forkcast.core.domain.WaterMath
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.backup.DocumentIo
import app.forkcast.ui.common.DeviceAuth
import app.forkcast.ui.common.NoScreenLockDialog
import app.forkcast.ui.common.ToggleRow
import app.forkcast.ui.common.findActivity
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.format.UnitLabels
import app.forkcast.ui.onboarding.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.concurrent.thread

/** Where the report is: being made, or made once and shown for Save, Share and Print. */
sealed interface ReportStep {
    /** [progress] 0..1 as days are written, null while the log is read. */
    data class Working(val progress: Float?) : ReportStep

    data class Ready(val path: String, val options: ReportOptions, val days: Int, val loggedDays: Int, val entries: Int) : ReportStep {
        val file: File get() = File(path)
    }
}

data class MealReportUiState(
    val step: ReportStep? = null,
    /** The first logged day, where "All time" starts. */
    val firstDate: LocalDate? = null,
    /** "Meal report saved", "Couldn't save the file" or "Couldn't make the report", until shown. */
    val notice: Int? = null,
)

/**
 * The meal report for a health professional (owner, 2026-10-29): load a date range, write the
 * chosen file once into the cache, off the main thread and cancellable, then Save, Share or Print it.
 */
@HiltViewModel
class MealReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LogRepository,
    private val slots: MealSlotRepository,
    private val profile: ProfileRepository,
    private val water: WaterRepository,
    private val weights: WeightRepository,
    settings: SettingsRepository,
    private val documents: DocumentIo,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(MealReportUiState())
    val state: StateFlow<MealReportUiState> = _state.asStateFlow()
    val units: StateFlow<UnitPrefs?> = settings.settings.map { it.units }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val first = log.firstDate()
            _state.update { it.copy(firstDate = first) }
        }
    }

    fun today(): LocalDate = LocalDate.now(clock)

    fun fileName(options: ReportOptions): String = "forkcast-report-${options.from}_to_${options.to}.${options.format.extension}"

    /** After the phone's unlock. A failure is said to the user; nothing is logged (no logs, CLAUDE.md). */
    @Suppress("SwallowedException")
    fun generate(options: ReportOptions, files: MealReportFiles) {
        job?.cancel()
        _state.update { it.copy(step = ReportStep.Working(null)) }
        job = viewModelScope.launch {
            val running = coroutineContext.job
            val ready = try {
                val report = load(options)
                withContext(Dispatchers.IO) {
                    val file = ReportCache.newFile(context, fileName(options))
                    file.outputStream().buffered().use { out ->
                        files.write(out, report, options) { p ->
                            running.ensureActive()
                            _state.update { s -> if (s.step is ReportStep.Working) s.copy(step = ReportStep.Working(p)) else s }
                        }
                    }
                    ReportStep.Ready(file.path, options, report.days.size, report.loggedDays, report.items.size)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { ReportCache.clear(context) }
                throw e
            } catch (e: IOException) {
                null
            }
            if (ready == null) {
                discard()
                _state.update { it.copy(step = null, notice = R.string.report_failed) }
            } else {
                _state.update { it.copy(step = ready) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(step = null) }
    }

    /** The preview closed: the file goes (an app it was shared with has had it since Share). */
    fun close() {
        cancel()
        discard()
    }

    /** The picker returned [uri] (null = canceled): a copy of the made file goes there. */
    @Suppress("SwallowedException")
    fun saveTo(uri: Uri?) {
        val ready = _state.value.step as? ReportStep.Ready ?: return
        if (uri == null) return
        viewModelScope.launch {
            val notice = try {
                documents.writeStream(uri) { out -> ready.file.inputStream().use { it.copyTo(out) } }
                R.string.report_saved
            } catch (e: IOException) {
                R.string.backup_save_failed
            } catch (e: SecurityException) {
                R.string.backup_save_failed
            }
            _state.update { it.copy(notice = notice) }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private fun discard() {
        viewModelScope.launch(Dispatchers.IO) { ReportCache.clear(context) }
    }

    override fun onCleared() {
        // Leaving Backup & data: the scope is gone, so a plain thread deletes the file.
        thread { ReportCache.clear(context) }
    }

    private suspend fun load(o: ReportOptions): MealReport.Report {
        val entries = log.range(o.from, o.to)
        val targets = profile.observeDailyTargets().first()
        val target = targets?.budgetKcal?.toDouble() to targets?.targets?.mapValues { (_, t) -> t.amount }.orEmpty()
        // One query for the whole range, so "All time" over years stays quick.
        val waterByDay = if (!o.water) emptyMap() else {
            val drinks = water.range(o.from, o.to).groupBy({ it.date }, { it.ml })
            val fromFood = entries.groupBy({ it.date }, { it.waterMl })
            (drinks.keys + fromFood.keys).associateWith { d -> WaterMath.dayTotalMl(drinks[d].orEmpty(), fromFood[d].orEmpty()) }.filterValues { it > 0 }
        }
        val weightByDay = if (!o.weight) emptyMap() else weights.entries.first()
            .groupBy { it.timestamp.atZone(clock.zone).toLocalDate() }
            .filterKeys { it in o.from..o.to }
            .mapValues { (_, list) -> list.maxBy { it.timestamp }.kg }
        currentCoroutineContext().ensureActive()
        return MealReport.build(o.from, o.to, entries, slots.observeMealSlots().first(), { target }, waterByDay, weightByDay)
    }
}

/** Backup & data › Meal report: the section, its options, the unlock, the progress, the preview. */
@Composable
fun MealReportSectionRoute(snackbar: SnackbarHostState, viewModel: MealReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val files = remember(units, locale, resources, is24Hour) {
        MealReportFiles(resources, UnitFormatter(units ?: UnitFormatter.defaultsFor(locale), locale, UnitLabels.from(resources)), locale, is24Hour)
    }
    val previewSnackbar = remember { SnackbarHostState() }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.PDF.mimeType)) { viewModel.saveTo(it) }
    val saveSheet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.SPREADSHEET.mimeType)) { viewModel.saveTo(it) }
    LaunchedEffect(state.notice) {
        val n = state.notice ?: return@LaunchedEffect
        viewModel.noticeShown()
        (if (state.step is ReportStep.Ready) previewSnackbar else snackbar).showSnackbar(resources.getString(n))
    }

    var asking by rememberSaveable { mutableStateOf(false) }
    var noLock by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.bpw_unlock_title)
    val subtitle = stringResource(R.string.report_unlock)
    val shareTitle = stringResource(R.string.report_share_title)
    val printJob = stringResource(R.string.report_print_job)
    MealReportSection(busy = state.step != null, onMake = { asking = true })
    if (noLock) NoScreenLockDialog(onDismiss = { noLock = false }, body = stringResource(R.string.report_no_lock))
    if (asking) {
        MealReportDialog(
            today = viewModel.today(),
            firstDate = state.firstDate,
            onContinue = { options ->
                asking = false
                // Health data leaving the app: the owner's unlock first, once for Save, Share and Print (owner, 2026-10-29).
                if (!DeviceAuth.hasScreenLock(context)) noLock = true
                else DeviceAuth.authenticate(context, title, subtitle, onSuccess = { viewModel.generate(options, files) })
            },
            onDismiss = { asking = false },
        )
    }
    when (val step = state.step) {
        is ReportStep.Working -> ReportWorkingDialog(step.progress, onCancel = viewModel::cancel)
        is ReportStep.Ready -> ReportPreview(
            ready = step,
            snackbar = previewSnackbar,
            onSave = { (if (step.options.format == ReportFormat.PDF) savePdf else saveSheet).launch(viewModel.fileName(step.options)) },
            onShare = { ReportOutput.share(context, step.file, step.options.format, shareTitle) },
            onPrint = { context.findActivity()?.let { ReportOutput.print(it, step.file, printJob) } },
            onClose = viewModel::close,
        )
        null -> Unit
    }
}

@Composable
fun MealReportSection(busy: Boolean, onMake: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.report_section_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.report_section_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onMake, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_make)) }
    }
}

/** The date presets (owner, 2026-10-29); each ends today, All time starts on the first logged day. */
enum class ReportRange(@StringRes val label: Int) {
    WEEK(R.string.report_range_week),
    MONTH(R.string.report_range_month),
    QUARTER(R.string.report_range_quarter),
    ALL(R.string.report_range_all),
    CUSTOM(R.string.report_range_custom),
    ;

    fun dates(today: LocalDate, firstDate: LocalDate?, custom: Pair<LocalDate, LocalDate>): Pair<LocalDate, LocalDate> = when (this) {
        WEEK -> today.minusDays(6) to today
        MONTH -> today.minusDays(29) to today
        QUARTER -> today.minusDays(89) to today
        ALL -> minOf(firstDate ?: today, today) to today
        CUSTOM -> custom
    }
}

/** Dates (the last 30 days by default), PDF or spreadsheet, weight and water, and every meal for a long PDF. */
@Composable
fun MealReportDialog(today: LocalDate, firstDate: LocalDate?, onContinue: (ReportOptions) -> Unit, onDismiss: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(ReportRange.MONTH) }
    var from by rememberSaveable { mutableStateOf(today.minusDays(29)) }
    var to by rememberSaveable { mutableStateOf(today) }
    var format by rememberSaveable { mutableStateOf(ReportFormat.PDF) }
    var weight by rememberSaveable { mutableStateOf(true) }
    var water by rememberSaveable { mutableStateOf(true) }
    var detail by rememberSaveable { mutableStateOf(false) }
    val (start, end) = range.dates(today, firstDate, from to to)
    val options = ReportOptions(start, end, format, weight, water, detail)
    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_section_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_dates), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportRange.entries.forEach { r ->
                        FilterChip(
                            selected = range == r,
                            onClick = {
                                // Custom starts from the dates shown, so it's a small change away.
                                if (r == ReportRange.CUSTOM && range != ReportRange.CUSTOM) { from = start; to = end }
                                range = r
                            },
                            label = { Text(stringResource(r.label)) },
                        )
                    }
                }
                if (range == ReportRange.CUSTOM) {
                    Text(stringResource(R.string.report_from), style = MaterialTheme.typography.titleSmall)
                    DateField(from, "", from, { from = it; if (to.isBefore(it)) to = it }, selectableTo = today)
                    Text(stringResource(R.string.report_to), style = MaterialTheme.typography.titleSmall)
                    DateField(to, "", to, { to = it }, selectableFrom = from, selectableTo = today)
                } else {
                    Text(
                        stringResource(R.string.report_range, dateFormat.format(start), dateFormat.format(end)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = format == ReportFormat.PDF, onClick = { format = ReportFormat.PDF }, label = { Text(stringResource(R.string.report_format_pdf)) })
                    FilterChip(
                        selected = format == ReportFormat.SPREADSHEET,
                        onClick = { format = ReportFormat.SPREADSHEET },
                        label = { Text(stringResource(R.string.report_format_sheet)) },
                    )
                }
                ToggleRow(stringResource(R.string.report_include_weight), null, weight) { weight = it }
                ToggleRow(stringResource(R.string.report_include_water), null, water) { water = it }
                if (options.summary) ToggleRow(stringResource(R.string.report_detail), stringResource(R.string.report_detail_body), detail) { detail = it }
                val contents = if (options.meals) R.string.report_contents else R.string.report_contents_summary
                Text(stringResource(contents), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onContinue(options) }) { Text(stringResource(R.string.report_generate)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** While the report is made: how far along, and Cancel. The rest of the app keeps responding. */
@Composable
fun ReportWorkingDialog(progress: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.report_working)) },
        text = {
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
