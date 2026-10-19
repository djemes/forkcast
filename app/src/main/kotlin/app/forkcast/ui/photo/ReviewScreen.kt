package app.forkcast.ui.photo

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.model.kcal
import app.forkcast.ui.ai.aiErrorMessage
import app.forkcast.ui.ai.label
import app.forkcast.ui.format.UnitFormatter
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ReviewRoute(onBack: () -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.effect.collect { onBack() } }
    LaunchedEffect(state.loaded, state.detail) { if (state.loaded && state.detail == null) onBack() }
    ReviewScreen(state, viewModel::onEvent, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit, onBack: () -> Unit) {
    val f = UnitFormatter(state.units ?: UnitFormatter.defaultsFor(LocalLocale.current.platformLocale), LocalLocale.current.platformLocale)
    var confirmDelete by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<File?>(null) }
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val d = state.detail ?: return@Scaffold
        val e = d.estimate
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.photoFiles.isNotEmpty()) PhotoHeader(state.photoFiles, onOpen = { enlarged = it })
            Text(e.mealName ?: stringResource(R.string.photo_meal_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

            when (e.status) {
                EstimateStatus.PENDING, EstimateStatus.RUNNING -> {
                    Text(stringResource(R.string.review_running), style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    e.errorCode?.let { ErrorText(it, e.errorDetail, e.provider) }
                }
                EstimateStatus.FAILED -> {
                    ErrorText(e.errorCode, e.errorDetail, e.provider)
                    Button(onClick = { onEvent(ReviewEvent.Retry) }) { Text(stringResource(R.string.review_retry)) }
                }
                EstimateStatus.DONE, EstimateStatus.NEEDS_INPUT -> {
                    if (e.kcalExpected != null) {
                        Text(
                            stringResource(R.string.review_total, f.energy(e.kcalExpected!!), f.energyNumber(e.kcalLow ?: 0.0), f.energy(e.kcalHigh ?: 0.0)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    e.profileName?.let { name ->
                        val tier = if (e.provider == ProviderId.GEMINI && e.tierLabel != null && e.tierLabel != TierLabel.UNKNOWN) " · " + stringResource(e.tierLabel!!.label()) else ""
                        Text(stringResource(R.string.review_by, name + tier), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.warnings.isNotEmpty()) {
                        Text(stringResource(R.string.review_warnings, e.warnings.joinToString("; ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    e.result?.clarifyingQuestion?.takeIf { e.status == EstimateStatus.NEEDS_INPUT }?.let { q ->
                        QuestionCard(q, onAnswer = { onEvent(ReviewEvent.Correct(it)) }, onAcceptAsIs = { onEvent(ReviewEvent.AcceptAsIs) })
                    }
                    val (added, seen) = d.components.partition { it.isHiddenIngredient }
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
                    CorrectField(onSend = { onEvent(ReviewEvent.Correct(it)) })
                    Button(onClick = { onEvent(ReviewEvent.Save) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.review_save))
                    }
                }
            }
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
}

@Composable
private fun ErrorText(code: String?, detail: String?, provider: ProviderId?) {
    val res = LocalContext.current.resources
    val text = when (code) {
        AiErrorCodes.NO_PROFILE -> stringResource(R.string.review_no_profile)
        AiErrorCodes.DISABLED -> stringResource(R.string.review_disabled)
        else -> res.aiErrorMessage(errorFromCode(code, detail), provider)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        // The provider's own answer (HTTP status, error code, message), for when the summary isn't enough.
        detail?.takeIf { code != AiErrorCodes.NO_PROFILE && code != AiErrorCodes.DISABLED && it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

@Composable
private fun CorrectField(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.review_correct)) },
            placeholder = { Text(stringResource(R.string.review_correct_hint)) },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_send)) }
    }
}
