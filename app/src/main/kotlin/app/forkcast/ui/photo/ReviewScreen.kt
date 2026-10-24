package app.forkcast.ui.photo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.ai.ComponentEdits
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.AttemptRecord
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import app.forkcast.ui.ai.aiErrorMessage
import app.forkcast.ui.common.DiscardChangesDialog
import app.forkcast.ui.common.keyboardAware
import app.forkcast.ui.format.UnitFormatter
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun ReviewRoute(
    onBack: () -> Unit,
    /** "Log manually instead": search for the meal, prefilled with the description (and the photo shown). */
    onLogManually: (mealSlotId: String, date: String, query: String, photo: String?) -> Unit = { _, _, _, _ -> },
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { e ->
            when (e) {
                ReviewEffect.Close -> onBack()
                is ReviewEffect.OpenSearch -> onLogManually(e.mealSlotId, e.date, e.query, e.photo)
            }
        }
    }
    LaunchedEffect(state.loaded, state.detail) { if (state.loaded && state.detail == null) onBack() }
    ReviewScreen(state, viewModel::onEvent, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit, onBack: () -> Unit) {
    val f = UnitFormatter(state.units ?: UnitFormatter.defaultsFor(LocalLocale.current.platformLocale), LocalLocale.current.platformLocale)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<File?>(null) }
    // Back with unsaved edits asks first (owner, 2026-10-23); without edits it just leaves.
    val leave = { if (state.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    state.match?.let { m ->
        MatchSearchSheet(
            state = m,
            f = f,
            onQuery = { onEvent(ReviewEvent.SearchMatch(it)) },
            onPick = { onEvent(ReviewEvent.PickMatch(it)) },
            onDismiss = { onEvent(ReviewEvent.CloseMatch) },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = { IconButton(onClick = leave) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val d = state.detail ?: return@Scaffold
        val e = d.estimate
        // The clock for "Waiting for Gemini · 0:12" and "next try at": ticks only while something is pending.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val stage = EstimateStage.of(e, now)
        LaunchedEffect(e.status) {
            while (e.status == EstimateStatus.RUNNING || e.status == EstimateStatus.PENDING) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(padding)
                .keyboardAware(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.photoFiles.isNotEmpty()) PhotoHeader(state.photoFiles, onOpen = { enlarged = it })
            if (state.isText && !e.description.isNullOrBlank()) DescribedCard(e.description!!)
            Text(
                e.mealName ?: stringResource(if (state.isText) R.string.describe_title else R.string.photo_meal_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )

            when (stage) {
                EstimateStage.Done, EstimateStage.NeedsAnswer -> ReviewBody(state, e, f, onEvent, onCancel = leave)
                else -> {
                    StatusCard(stage, e, now)
                    EstimateActions(state, stage, onEvent)
                }
            }
            DetailsSection(e)
            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                Text(stringResource(R.string.review_delete), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    enlarged?.let { file -> PhotoViewer(file, onClose = { enlarged = null }) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.review_delete_title)) },
            text = { Text(stringResource(R.string.review_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onEvent(ReviewEvent.Delete) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDiscard = { confirmDiscard = false; onEvent(ReviewEvent.Discard) },
            onKeepEditing = { confirmDiscard = false },
        )
    }
}

/** What the user typed for a described meal. */
@Composable
private fun DescribedCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.describe_you_said), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("“$text”", style = MaterialTheme.typography.bodyLarge)
    }
}

/** The estimate, ready: total, portion eaten, components, assumptions, Correct it, Cancel / Save. */
@Composable
private fun ReviewBody(state: ReviewUiState, e: AiEstimateEntity, f: UnitFormatter, onEvent: (ReviewEvent) -> Unit, onCancel: () -> Unit) {
    val components = state.components
    val plate = ComponentEdits.totals(components)
    val portion = state.portion
    Text(
        stringResource(R.string.review_total, f.energy(plate.expected * portion), f.energyNumber(plate.low * portion), f.energy(plate.high * portion)),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    e.profileName?.let { name ->
        Text(stringResource(R.string.review_by, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (e.warnings.isNotEmpty()) {
        Text(stringResource(R.string.review_warnings, e.warnings.joinToString("; ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    e.result?.clarifyingQuestion?.takeIf { e.status == EstimateStatus.NEEDS_INPUT }?.let { q ->
        QuestionCard(q, onAnswer = { onEvent(ReviewEvent.Correct(it)) }, onAcceptAsIs = { onEvent(ReviewEvent.AcceptAsIs) })
    }
    PortionPicker(portion, plate.expected, f) { onEvent(ReviewEvent.SetPortion(it)) }
    // What the entry gets: every component's nutrients × the portion, vitamins and minerals with % DV.
    app.forkcast.ui.today.NutrientsExpander(components.map { it.nutrientsUsed }.sumNutrients().mapValues { (_, v) -> v * portion }, f)
    val (added, seen) = components.partition { it.isHiddenIngredient }
    seen.forEach { c -> key(c.id) { ComponentRow(c, f, onEvent) } }
    if (added.isNotEmpty()) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.review_likely_added), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { onEvent(ReviewEvent.RemoveAllAdded) }) { Text(stringResource(R.string.review_remove_added)) }
        }
        added.forEach { c -> key(c.id) { ComponentRow(c, f, onEvent) } }
    }
    val assumptions = e.result?.assumptions.orEmpty()
    if (assumptions.isNotEmpty()) {
        Text(stringResource(R.string.review_assumptions), style = MaterialTheme.typography.titleSmall)
        Text(assumptions.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.isText) {
        Text(stringResource(R.string.describe_knowledge_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CorrectField(onSend = { onEvent(ReviewEvent.Correct(it)) })
    var saveAsFood by rememberSaveable(e.id) { mutableStateOf(true) }
    var foodName by rememberSaveable(e.id) { mutableStateOf(e.mealName.orEmpty()) }
    if (state.isText) {
        // Never ask twice: the described meal becomes a food that search finds offline (owner, 2026-10-23).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = saveAsFood, role = Role.Switch, onValueChange = { saveAsFood = it }),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.describe_save_as_food), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.describe_save_as_food_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = saveAsFood, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
        }
        if (saveAsFood) {
            OutlinedTextField(
                value = foodName,
                onValueChange = { foodName = it },
                label = { Text(stringResource(R.string.describe_food_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.cancel)) }
        Button(
            onClick = { onEvent(if (state.isText) ReviewEvent.Save(saveAsFood, foodName) else ReviewEvent.Save()) },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.review_save)) }
    }
}

/**
 * "How much did you eat?" (owner, 2026-10-23): scales the entry locally, no AI request. The plate
 * stays as estimated, so "All" brings it back.
 */
@Composable
private fun PortionPicker(portion: Double, plateKcal: Double, f: UnitFormatter, onPick: (Double) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.portion_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComponentEdits.PORTIONS.forEach { p ->
                FilterChip(selected = portion == p, onClick = { onPick(p) }, label = { Text(portionLabel(p)) })
            }
            val isCustom = portion !in ComponentEdits.PORTIONS
            FilterChip(
                selected = isCustom,
                onClick = { custom = true },
                label = { Text(if (isCustom) stringResource(R.string.portion_custom_value, (portion * 100).roundToInt()) else stringResource(R.string.portion_custom)) },
            )
        }
        if (portion != 1.0) {
            Text(
                stringResource(R.string.portion_summary, portionLabel(portion), f.energy(plateKcal * portion), f.energy(plateKcal)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (custom) {
        var text by rememberSaveable { mutableStateOf((portion * 100).roundToInt().toString()) }
        val value = text.toIntOrNull()?.takeIf { it in 1..300 }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(stringResource(R.string.portion_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.portion_percent)) },
                    suffix = { Text("%") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(onClick = { value?.let { onPick(it / 100.0) }; custom = false }, enabled = value != null) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun portionLabel(p: Double): String = when (p) {
    0.25 -> "¼"
    0.5 -> "½"
    0.75 -> "¾"
    1.0 -> stringResource(R.string.portion_all)
    else -> "${(p * 100).roundToInt()}%"
}

/**
 * One plain status at a time (owner, 2026-10-23): what's happening, for how long or until when,
 * and, while it runs, a progress line split into its labeled steps (send, answer, match foods).
 */
@Composable
private fun StatusCard(stage: EstimateStage, e: AiEstimateEntity, now: Long) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val failed = stage is EstimateStage.Failed
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        val (headline, sub) = stageText(stage, e, now)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(
                    when (stage) {
                        is EstimateStage.Failed -> R.drawable.ic_error
                        is EstimateStage.RetryWaiting, is EstimateStage.QuotaWaiting, EstimateStage.Offline -> R.drawable.ic_pause_circle
                        else -> R.drawable.ic_photo_camera
                    },
                ),
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(headline, style = MaterialTheme.typography.titleMedium, color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
        }
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stage.step?.let { step ->
            // Determinate: which of the three steps it's on, with the steps named under the line.
            LinearProgressIndicator(progress = { (step - 0.5f) / EstimateStage.STEPS }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(R.string.stage_step_send, R.string.stage_step_answer, R.string.stage_step_match).forEachIndexed { i, label ->
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i + 1 == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(stringResource(R.string.stage_leave_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stage is EstimateStage.Failed && stage.tried.isNotEmpty()) {
            Text(stringResource(R.string.stage_tried), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            stage.tried.forEach { a ->
                Text(
                    "• " + res.getString(R.string.stage_tried_line, clockTime(a.atMs), attemptReason(res, a)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Headline and detail line for a stage, in plain words. */
@Composable
private fun stageText(stage: EstimateStage, e: AiEstimateEntity, now: Long): Pair<String, String?> {
    val res = androidx.compose.ui.platform.LocalResources.current
    return when (stage) {
        EstimateStage.Starting -> stringResource(R.string.stage_starting) to stringResource(R.string.stage_starting_body)
        is EstimateStage.Asking -> {
            val who = stage.profile ?: stringResource(R.string.stage_the_ai)
            val headline = when {
                stage.after != null -> stringResource(R.string.stage_trying_next, who)
                stage.sent -> stringResource(R.string.stage_waiting, who, elapsed(now - (stage.sinceMs ?: now)))
                else -> stringResource(R.string.stage_sending, who)
            }
            headline to stage.after?.let { attemptReason(res, it) }
        }
        EstimateStage.Matching -> stringResource(R.string.stage_matching) to null
        is EstimateStage.RetryWaiting -> stringResource(R.string.stage_retry_waiting) to listOfNotNull(
            stage.last?.let { attemptReason(res, it) },
            stringResource(R.string.stage_retry_when, stage.attempt, stage.maxAttempts, clockTime(stage.nextAtMs)),
        ).joinToString("\n")
        is EstimateStage.QuotaWaiting -> stringResource(R.string.stage_quota) to listOfNotNull(
            stage.last?.let { attemptReason(res, it) },
            stringResource(R.string.stage_quota_when, clockTime(stage.nextAtMs)),
        ).joinToString("\n")
        EstimateStage.Offline -> stringResource(R.string.stage_offline) to stringResource(R.string.stage_starting_body)
        is EstimateStage.Failed -> stringResource(R.string.stage_failed) to when (stage.code) {
            AiErrorCodes.NO_PROFILE -> stringResource(R.string.review_no_profile)
            AiErrorCodes.DISABLED -> stringResource(R.string.review_disabled)
            AiErrorCodes.STOPPED -> stringResource(R.string.stage_stopped)
            else -> res.aiErrorMessage(errorFromCode(stage.code, stage.detail), e.provider)
        }
        EstimateStage.Done, EstimateStage.NeedsAnswer -> "" to null
    }
}

/** "Gemini 3.8 Flash took too long (2:00)", "Gemini 3.8 Flash: daily quota used up". */
fun attemptReason(res: android.content.res.Resources, a: AttemptRecord): String {
    val who = a.profileName
    return when (a.result) {
        AiErrorCodes.OK -> res.getString(R.string.attempt_ok, who, elapsed(a.durationMs))
        "Timeout" -> res.getString(R.string.attempt_timeout, who, elapsed(a.durationMs))
        "RateLimited" -> res.getString(R.string.attempt_rate_limited, who)
        "QuotaExhausted" -> res.getString(R.string.attempt_quota, who)
        "Server" -> res.getString(R.string.attempt_server, who, a.httpStatus ?: 500)
        "Network" -> res.getString(R.string.attempt_network)
        "InvalidKey" -> res.getString(R.string.attempt_key, who)
        "ModelNotFound" -> res.getString(R.string.attempt_model, who, a.model)
        "ContentRefused" -> res.getString(R.string.attempt_refused, who)
        "BadResponse" -> res.getString(R.string.attempt_bad, who)
        else -> res.getString(R.string.attempt_rejected, who, a.httpStatus ?: 400)
    }
}

/** "0:42", "2:00". */
fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun clockTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun clockTimeSeconds(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * What can be done while an estimate isn't finished (owner, 2026-10-23): Retry now, Try with another
 * profile, Edit description and retry, Log manually instead, Add backup model. Delete is below.
 */
@Composable
private fun EstimateActions(state: ReviewUiState, stage: EstimateStage, onEvent: (ReviewEvent) -> Unit) {
    val e = state.detail?.estimate ?: return
    var pickProfile by remember { mutableStateOf(false) }
    var editDescription by remember { mutableStateOf(false) }
    val running = e.status == EstimateStatus.RUNNING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!running) {
            Button(onClick = { onEvent(ReviewEvent.RetryNow) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_retry_now))
            }
            if (state.profiles.size > 1) {
                OutlinedButton(onClick = { pickProfile = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_try_other_profile))
                }
            }
            OutlinedButton(onClick = { editDescription = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_edit_description))
            }
        }
        OutlinedButton(onClick = { onEvent(ReviewEvent.LogManually) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_log_manually))
        }
        val offer = state.backupOffer
        if (offer != null && (stage is EstimateStage.Failed || stage is EstimateStage.QuotaWaiting || stage is EstimateStage.RetryWaiting)) {
            BackupModelCard(offer.name, offer.fromProfileName) { onEvent(ReviewEvent.AddBackupModel) }
        }
    }
    if (pickProfile) {
        var chosen by remember { mutableStateOf(state.profiles.firstOrNull { it.id != e.profileId }?.id) }
        AlertDialog(
            onDismissRequest = { pickProfile = false },
            title = { Text(stringResource(R.string.action_try_other_profile)) },
            text = {
                Column {
                    state.profiles.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = chosen == p.id, role = Role.RadioButton, onClick = { chosen = p.id }),
                        ) {
                            RadioButton(selected = chosen == p.id, onClick = null)
                            Text(p.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chosen?.let { onEvent(ReviewEvent.RetryWith(it)) }; pickProfile = false }, enabled = chosen != null) {
                    Text(stringResource(R.string.action_retry_now))
                }
            },
            dismissButton = { TextButton(onClick = { pickProfile = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (editDescription) {
        var text by rememberSaveable { mutableStateOf(e.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editDescription = false },
            title = { Text(stringResource(R.string.action_edit_description)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(PhotoMealUiState.MAX_DESCRIPTION) },
                    label = { Text(stringResource(R.string.describe_field)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ReviewEvent.EditDescription(text)); editDescription = false },
                    enabled = !state.isText || text.isNotBlank(),
                ) { Text(stringResource(R.string.action_retry_now)) }
            },
            dismissButton = { TextButton(onClick = { editDescription = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "Add backup model": the Gemini budget model on the same key, a separate free quota (owner, 2026-10-23). */
@Composable
fun BackupModelCard(modelName: String, fromProfile: String, onAdd: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(stringResource(R.string.backup_model_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            stringResource(R.string.backup_model_body, modelName, fromProfile),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.backup_model_add)) }
        }
    }
}

/** "Details" for troubleshooting: provider, model, and every request with time, duration and result. */
@Composable
private fun DetailsSection(e: AiEstimateEntity) {
    if (e.attemptLog.isEmpty() && e.model == null) return
    val res = androidx.compose.ui.platform.LocalResources.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.details_hide else R.string.details_show)) { open = !open },
        ) {
            Text(stringResource(R.string.details_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val small = MaterialTheme.typography.bodySmall
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                listOfNotNull(
                    e.provider?.let { res.getString(R.string.details_provider, providerName(it)) },
                    e.model?.let { res.getString(R.string.details_model, it) },
                    res.getString(R.string.details_prompt, e.promptVersion),
                    e.latencyMs?.let { res.getString(R.string.details_latency, elapsed(it)) },
                    e.inputTokens?.let { res.getString(R.string.details_tokens, it, e.outputTokens ?: 0) },
                ).forEach { Text(it, style = small, color = muted) }
                if (e.attemptLog.isNotEmpty()) {
                    Text(stringResource(R.string.details_requests), style = MaterialTheme.typography.labelLarge)
                    e.attemptLog.asReversed().forEach { a ->
                        Column {
                            Text(
                                res.getString(R.string.details_request_line, clockTimeSeconds(a.atMs), a.profileName, a.model, elapsed(a.durationMs), a.result),
                                style = small,
                            )
                            val extra = listOfNotNull(a.httpStatus?.let { "HTTP $it" }, a.providerCode, a.quota, a.retryAfterMs?.let { res.getString(R.string.details_retry_after, elapsed(it)) })
                            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = small, color = muted)
                            a.message?.takeIf { a.result != AiErrorCodes.OK }?.let { Text(it, style = small, color = muted) }
                        }
                    }
                }
            }
        }
    }
}

private fun providerName(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "Claude"
    ProviderId.GEMINI -> "Gemini"
    ProviderId.OPENAI_COMPAT -> "OpenAI-compatible"
}

/** One food: name, matched source (tap to switch), grams stepper and slider (model range ×1.5), kcal. */
@Composable
private fun ComponentRow(c: AiComponentEntity, f: UnitFormatter, onEvent: (ReviewEvent) -> Unit) {
    val min = (c.gramsLow / 1.5).coerceAtLeast(0.0).toFloat()
    val max = (c.gramsHigh * 1.5).coerceAtLeast(c.grams * 1.2).coerceAtLeast(1.0).toFloat()
    var slider by remember(c.id, c.grams) { mutableFloatStateOf(c.grams.toFloat().coerceIn(min, max)) }
    val step = if (c.grams < 30) 1.0 else 5.0
    val sliderLabel = stringResource(R.string.review_grams_slider, c.name)
    val usda = c.useSource == ComponentSource.USDA && c.matchedName != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) {
        // Name and source on the left; kcal is never truncated (DESIGN.md §5); remove on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (usda) stringResource(R.string.review_matched, c.matchedName!!) else stringResource(R.string.review_ai_values),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(f.energy(c.nutrientsUsed.kcal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            IconButton(onClick = { onEvent(ReviewEvent.Remove(c.id)) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.review_remove_item, c.name))
            }
        }
        // Amount: stepper, then the slider bounded to the model's range ×1.5 (SPEC §4.6 step 8).
        FlowRow(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onEvent(ReviewEvent.SetGrams(c.id, (c.grams - step).coerceAtLeast(0.0))) }) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.review_less, c.name))
                }
                Text(
                    f.grams(c.grams),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                IconButton(onClick = { onEvent(ReviewEvent.SetGrams(c.id, c.grams + step)) }) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.review_more, c.name))
                }
            }
            if (c.usdaPer100g != null) {
                TextButton(onClick = {
                    onEvent(ReviewEvent.SetSource(c.id, if (c.useSource == ComponentSource.USDA) ComponentSource.AI else ComponentSource.USDA))
                }) {
                    Text(stringResource(if (c.useSource == ComponentSource.USDA) R.string.review_use_ai else R.string.review_use_usda))
                }
            }
            // Pick another food from a search (USDA and your own foods).
            TextButton(onClick = { onEvent(ReviewEvent.ChangeMatch(c.id)) }) {
                Text(stringResource(R.string.review_change_match))
            }
        }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onEvent(ReviewEvent.SetGrams(c.id, slider.toDouble().roundToInt().toDouble())) },
            valueRange = min..max,
            modifier = Modifier
                .padding(end = 12.dp)
                .semantics { contentDescription = sliderLabel },
        )
    }
}

/**
 * The meal photo at the top (DESIGN.md §6): the first one full width at a fixed 4:3, so every
 * estimate looks the same whatever the camera's aspect; extra photos as equal tiles below.
 * Tap any photo to see it whole.
 */
@Composable
private fun PhotoHeader(files: List<File>, onOpen: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val first = files.first()
        AsyncImage(
            model = first,
            contentDescription = stringResource(R.string.review_photo, 1),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = stringResource(R.string.review_photo_open)) { onOpen(first) },
        )
        if (files.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                files.drop(1).forEachIndexed { i, file ->
                    AsyncImage(
                        model = file,
                        contentDescription = stringResource(R.string.review_photo, i + 2),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = stringResource(R.string.review_photo_open)) { onOpen(file) },
                    )
                }
            }
        }
    }
}

/** A photo shown whole on black, edge to edge, with a close button. */
@Composable
private fun PhotoViewer(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.review_photo, 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.review_photo_close)) }
        }
    }
}

@Composable
private fun QuestionCard(question: String, onAnswer: (String) -> Unit, onAcceptAsIs: () -> Unit) {
    var answer by rememberSaveable { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.review_question, question), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(answer, { answer = it }, label = { Text(stringResource(R.string.review_answer_hint)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(answer); answer = "" }, enabled = answer.isNotBlank()) { Text(stringResource(R.string.review_send)) }
                TextButton(onClick = onAcceptAsIs) { Text(stringResource(R.string.review_accept_as_is)) }
            }
        }
    }
}

/**
 * "Correct it" for real corrections (wrong food, cooking method); how much was eaten is the portion
 * control above. Multi-line, and the screen scrolls it above the keyboard (keyboardAware).
 */
@Composable
private fun CorrectField(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    // Send sits inside the field, so it stays above the keyboard with the text (owner, 2026-10-23).
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.review_correct)) },
        placeholder = { Text(stringResource(R.string.review_correct_hint)) },
        minLines = 2,
        trailingIcon = {
            TextButton(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_send)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
