package app.forkcast.ui.report

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.format.UnitLabels
import app.forkcast.ui.onboarding.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class MealReportUiState(
    val busy: Boolean = false,
    /** Chosen and unlocked: the screen opens the file picker for these options. */
    val pick: ReportOptions? = null,
    /** "Meal report saved" or "Couldn't save the file", until shown. */
    val notice: Int? = null,
)

/** The meal report for a health professional (owner, 2026-10-29): load a date range, write the chosen file. */
@HiltViewModel
class MealReportViewModel @Inject constructor(
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

    fun today(): LocalDate = LocalDate.now(clock)

    /** After the phone's unlock: the picker opens next. */
    fun chosen(options: ReportOptions) = _state.update { it.copy(pick = options) }

    fun fileName(options: ReportOptions): String = "${options.from}_to_${options.to}_forkcast-meal-report.${options.format.extension}"

    /** The picker returned [uri] (null = canceled). A failure is said to the user; nothing is logged (no logs, CLAUDE.md). */
    @Suppress("SwallowedException")
    fun save(uri: Uri?, files: MealReportFiles) {
        val options = _state.value.pick ?: return
        _state.update { it.copy(pick = null) }
        if (uri == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val notice = try {
                val report = load(options)
                documents.writeStream(uri) { out -> files.write(out, report, options) }
                R.string.report_saved
            } catch (e: IOException) {
                R.string.backup_save_failed
            } catch (e: SecurityException) {
                R.string.backup_save_failed
            }
            _state.update { it.copy(busy = false, notice = notice) }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private suspend fun load(o: ReportOptions): MealReport.Report {
        val entries = log.range(o.from, o.to)
        val targets = profile.observeDailyTargets().first()
        val target = targets?.budgetKcal?.toDouble() to targets?.targets?.mapValues { (_, t) -> t.amount }.orEmpty()
        val days = generateSequence(o.from) { it.plusDays(1) }.takeWhile { !it.isAfter(o.to) }.toList()
        val waterByDay = if (!o.water) emptyMap() else days.associateWith { d ->
            WaterMath.dayTotalMl(water.observeDay(d).first().map { it.ml }, entries.filter { it.date == d }.map { it.waterMl })
        }.filterValues { it > 0 }
        val weightByDay = if (!o.weight) emptyMap() else weights.entries.first()
            .groupBy { it.timestamp.atZone(clock.zone).toLocalDate() }
            .filterKeys { it in o.from..o.to }
            .mapValues { (_, list) -> list.maxBy { it.timestamp }.kg }
        return MealReport.build(o.from, o.to, entries, slots.observeMealSlots().first(), { target }, waterByDay, weightByDay)
    }
}

/** Backup & data › Meal report: the section, its options dialog, the unlock, the picker, and how it went. */
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
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.PDF.mimeType)) { viewModel.save(it, files) }
    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.SPREADSHEET.mimeType)) { viewModel.save(it, files) }
    LaunchedEffect(state.pick) {
        val o = state.pick ?: return@LaunchedEffect
        (if (o.format == ReportFormat.PDF) pickPdf else pickSheet).launch(viewModel.fileName(o))
    }
    LaunchedEffect(state.notice) {
        val n = state.notice ?: return@LaunchedEffect
        viewModel.noticeShown()
        snackbar.showSnackbar(resources.getString(n))
    }

    var asking by rememberSaveable { mutableStateOf(false) }
    var noLock by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.bpw_unlock_title)
    val subtitle = stringResource(R.string.report_unlock)
    MealReportSection(busy = state.busy, onMake = { asking = true })
    if (noLock) NoScreenLockDialog(onDismiss = { noLock = false }, body = stringResource(R.string.report_no_lock))
    if (asking) {
        MealReportDialog(
            today = viewModel.today(),
            onContinue = { options ->
                asking = false
                // Health data leaving the phone: the owner's unlock first (owner, 2026-10-29).
                if (!DeviceAuth.hasScreenLock(context)) noLock = true
                else DeviceAuth.authenticate(context, title, subtitle, onSuccess = { viewModel.chosen(options) })
            },
            onDismiss = { asking = false },
        )
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

/** Dates (the last 7 days by default, at most a year), PDF or spreadsheet, weight and water. */
@Composable
fun MealReportDialog(today: LocalDate, onContinue: (ReportOptions) -> Unit, onDismiss: () -> Unit) {
    var from by rememberSaveable { mutableStateOf(today.minusDays(6)) }
    var to by rememberSaveable { mutableStateOf(today) }
    var format by rememberSaveable { mutableStateOf(ReportFormat.PDF) }
    var weight by rememberSaveable { mutableStateOf(true) }
    var water by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_section_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_from), style = MaterialTheme.typography.titleSmall)
                DateField(from, "", from, { from = it; if (to.isBefore(it)) to = it }, selectableTo = today)
                Text(stringResource(R.string.report_to), style = MaterialTheme.typography.titleSmall)
                DateField(to, "", to, { to = it }, selectableFrom = from, selectableTo = minOf(today, from.plusDays(MAX_DAYS - 1)))
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
                Text(stringResource(R.string.report_contents), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(ReportOptions(from, minOf(to, from.plusDays(MAX_DAYS - 1)), format, weight, water)) }) {
                Text(stringResource(R.string.report_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_DAYS = 366L
