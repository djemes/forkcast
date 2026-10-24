package app.forkcast.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.forkcast.ui.common.MealTimeField
import app.forkcast.ui.common.keyboardAware
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.ui.scan.CameraViewfinder
import app.forkcast.ui.scan.rememberCameraPermission
import coil3.compose.AsyncImage
import java.io.File
import java.util.UUID

@Composable
fun PhotoMealRoute(
    onBack: () -> Unit,
    onQueued: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: PhotoMealViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { if (it is PhotoMealEffect.Queued) onQueued(it.mealName) }
    }
    PhotoMealScreen(state, viewModel::onEvent, onBack, onOpenAiSettings)
}

@Composable
fun PhotoMealScreen(
    state: PhotoMealUiState,
    onEvent: (PhotoMealEvent) -> Unit,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onEvent(PhotoMealEvent.AddPhotos(listOf(uri)))
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoMealUiState.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) onEvent(PhotoMealEvent.AddPhotos(uris))
    }
    val openGallery = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (PhotoMealUiState.MAX_PHOTOS - state.photos.size >= 2) pickMany.launch(request) else pickOne.launch(request)
    }

    // Notification permission is asked in context, the first time an estimate is sent (SPEC §4.8).
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onEvent(PhotoMealEvent.NotificationPermissionAsked)
        onEvent(PhotoMealEvent.Estimate)
    }
    val estimate = {
        val needsAsk = Build.VERSION.SDK_INT >= 33 && !state.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else onEvent(PhotoMealEvent.Estimate)
    }

    when (state.step) {
        PhotoStep.CAPTURE -> CaptureStep(state, onEvent, onBack, openGallery)
        // Describe has no camera step: Back leaves.
        PhotoStep.DETAILS -> DetailsStep(
            state, onEvent, onBack = { if (state.describe) onBack() else onEvent(PhotoMealEvent.BackToCamera) },
            onEstimate = estimate, onOpenAiSettings = onOpenAiSettings,
        )
    }
}

@Composable
private fun CaptureStep(state: PhotoMealUiState, onEvent: (PhotoMealEvent) -> Unit, onBack: () -> Unit, openGallery: () -> Unit) {
    val context = LocalContext.current
    // Photos are downscaled to 1568 px before they're sent (AI_PROVIDERS.md §5), so capture near
    // 2048 × 1536 instead of the full sensor (up to 200 MP on the S25 Ultra): faster shutter, small files.
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
    }
    val (granted, request) = rememberCameraPermission()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(granted) { if (!granted) request() }

    fun shoot() {
        if (busy || !state.canAddPhoto) return
        busy = true
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onEvent(PhotoMealEvent.AddPhotos(listOf(Uri.fromFile(file))))
                }
                override fun onError(exception: ImageCaptureException) {
                    busy = false
                }
            },
        )
    }

    val gallery: @Composable () -> Unit = {
        // Tonal, not outlined: an outline nearly vanished on the dark camera bar.
        FilledTonalIconButton(onClick = openGallery, enabled = state.canAddPhoto, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_photo_library), stringResource(R.string.photo_gallery))
        }
    }
    val shutter: @Composable () -> Unit = {
        FilledIconButton(
            onClick = ::shoot,
            enabled = granted && state.canAddPhoto && !busy,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
        ) { Icon(painterResource(R.drawable.ic_photo_camera), stringResource(R.string.photo_shutter), Modifier.size(32.dp)) }
    }
    val next: @Composable () -> Unit = {
        Button(onClick = { onEvent(PhotoMealEvent.Next) }, enabled = state.photos.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.photo_next), maxLines = 1)
        }
    }
    val count: @Composable () -> Unit = {
        Text(
            stringResource(R.string.photo_count, state.photos.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (maxWidth > maxHeight) {
            // Landscape (owner, 2026-10-24): the preview beside a bar on the right, the bottom bar
            // turned on its side: Next at the top, the shutter centered, the gallery at the bottom.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CameraPreview(granted, capture, request)
                    CameraTop(
                        state, onEvent, onBack,
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start)),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                        .padding(16.dp),
                ) {
                    // The same width with or without photos, so the preview doesn't jump when one is added.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        modifier = Modifier.width(SIDE_THUMB + 20.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    ) {
                        Thumbnails(state.photos, onRemove = { onEvent(PhotoMealEvent.RemovePhoto(it)) }, size = SIDE_THUMB, vertical = true)
                        count()
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { next() }
                        shutter()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) { gallery() }
                    }
                }
            }
        } else {
            CameraPreview(granted, capture, request)
            CameraTop(state, onEvent, onBack, Modifier.statusBarsPadding())
            // Bottom: thumbnails, then three equal zones (gallery · shutter · next) so the shutter
            // stays centered and full size at any font scale.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumbnails(state.photos, onRemove = { onEvent(PhotoMealEvent.RemovePhoto(it)) }, modifier = Modifier.weight(1f, fill = false))
                    count()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { gallery() }
                    shutter()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { next() }
                }
            }
        }
    }
}

/** The camera image, or why it's missing and a button to allow it. */
@Composable
private fun BoxScope.CameraPreview(granted: Boolean, capture: ImageCapture, request: () -> Unit) {
    if (granted) {
        CameraViewfinder(Modifier.fillMaxSize(), imageCapture = capture)
    } else {
        Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photo_camera_needed), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = request) { Text(stringResource(R.string.photo_allow_camera)) }
        }
    }
}

/**
 * Back on the left; on the right one warning button that shows or hides the tips (framing, and
 * the free-tier privacy note when it applies) on a scrim, so they read over a bright plate
 * (owner, 2026-10-24). Open or closed is remembered on this phone.
 */
@Composable
private fun CameraTop(state: PhotoMealUiState, onEvent: (PhotoMealEvent) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = SCRIM, contentColor = Color.White),
            ) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            val shown = stringResource(if (state.warningsOpen) R.string.photo_warnings_shown else R.string.photo_warnings_hidden)
            IconToggleButton(
                checked = state.warningsOpen,
                onCheckedChange = { onEvent(PhotoMealEvent.ToggleWarnings) },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    containerColor = SCRIM, contentColor = WARNING_YELLOW,
                    checkedContainerColor = SCRIM, checkedContentColor = WARNING_YELLOW,
                ),
                modifier = Modifier.semantics { stateDescription = shown },
            ) { Icon(painterResource(R.drawable.ic_warning), stringResource(R.string.photo_warnings)) }
        }
        if (state.warningsOpen) {
            CameraHint(stringResource(R.string.photo_framing_hint))
            if (state.firstIsFreeTier) CameraHint(stringResource(R.string.photo_free_tier_hint))
        }
    }
}

@Composable
private fun CameraHint(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .background(SCRIM, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Muted yellow for the warning button: noticeable on the scrim without shouting. */
private val WARNING_YELLOW = Color(0xFFD6BD6A)

/** Thumbnails in the landscape side bar, smaller so three fit beside the shutter. */
private val SIDE_THUMB = 56.dp

/** Translucent black behind text and buttons over the camera image. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/**
 * Photo thumbnails, all the same size and square-cropped. The remove control is a small badge on
 * the corner (24 dp to see, 48 dp to touch) so it never covers the photo.
 */
@Composable
private fun Thumbnails(photos: List<Uri>, onRemove: (Int) -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp, vertical: Boolean = false) {
    if (photos.isEmpty()) return
    val items: @Composable () -> Unit = {
        photos.forEachIndexed { i, uri ->
            // Room above and right of the image for the badge's touch area.
            Box(Modifier.size(size + 20.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.review_photo, i + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(size)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onRemove(i) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.photo_remove, i + 1),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
    if (vertical) Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) { items() }
    else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) { items() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsStep(
    state: PhotoMealUiState,
    onEvent: (PhotoMealEvent) -> Unit,
    onBack: () -> Unit,
    onEstimate: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.describe) R.string.describe_title else R.string.photo_meal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(padding)
                .keyboardAware(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (!state.describe) Thumbnails(state.photos, onRemove = { onEvent(PhotoMealEvent.RemovePhoto(it)) }, size = 88.dp)
            // Held here so fast typing never waits for the ViewModel's combined flow.
            var description by rememberSaveable { mutableStateOf(state.description) }
            val focus = remember { FocusRequester() }
            if (state.describe) LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it.take(PhotoMealUiState.MAX_DESCRIPTION)
                    onEvent(PhotoMealEvent.SetDescription(description))
                },
                label = { Text(stringResource(if (state.describe) R.string.describe_field else R.string.photo_description)) },
                placeholder = { Text(stringResource(if (state.describe) R.string.describe_hint else R.string.photo_description_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            MealTimeField(state.minute, onChange = { onEvent(PhotoMealEvent.SetTime(it)) }, latestMinute = state.latestMinute)
            Text(stringResource(R.string.photo_meal_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in state.slots) {
                    FilterChip(
                        selected = slot.id == state.mealSlotId,
                        onClick = { onEvent(PhotoMealEvent.SetMeal(slot.id)) },
                        label = { Text(slot.name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            when {
                !state.aiEnabled -> Text(stringResource(R.string.photo_ai_off), color = MaterialTheme.colorScheme.error)
                !state.hasProfile -> {
                    Text(stringResource(R.string.photo_no_ai), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.photo_open_ai_settings)) }
                }
            }
            Button(onClick = onEstimate, enabled = state.canEstimate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (state.describe) R.string.describe_estimate else R.string.photo_estimate))
            }
            if (state.describe) {
                Text(stringResource(R.string.describe_knowledge_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Camera shots wait here until the estimate copies them into app storage. */
const val CAPTURE_DIR = "photo-capture"
