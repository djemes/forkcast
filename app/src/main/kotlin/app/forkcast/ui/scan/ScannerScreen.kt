package app.forkcast.ui.scan

import android.media.AudioManager
import android.os.Build
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.network.FoodApiError
import app.forkcast.ui.theme.ForkcastTheme

@Composable
fun ScannerRoute(
    onBack: () -> Unit,
    onOpenFood: (foodId: String) -> Unit,
    onScanLabel: (gtin14: String) -> Unit,
    onEnterManually: (gtin14: String?) -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val flash = remember { Animatable(0f) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }
    val analyzer = remember { BarcodeAnalyzer { viewModel.onScanned(it) } }
    DisposableEffect(Unit) {
        onDispose {
            tone?.release()
            analyzer.close()
        }
    }
    analyzer.paused = state !is ScanState.Scanning

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ScanEffect.Detected -> {
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    flash.snapTo(1f)
                    flash.animateTo(0f, tween(400))
                }
                is ScanEffect.OpenFood -> {
                    analyzer.reset()
                    onOpenFood(effect.foodId)
                }
            }
        }
    }
    ScannerScreen(
        state = state,
        flash = { flash.value },
        analyzer = analyzer,
        onBack = onBack,
        onTyped = viewModel::onTyped,
        onRetry = viewModel::retry,
        onResume = { analyzer.reset(); viewModel.resume() },
        onScanLabel = onScanLabel,
        onEnterManually = onEnterManually,
    )
}

@Composable
fun ScannerScreen(
    state: ScanState,
    /** Read only while drawing, so the flash animation never recomposes the screen. */
    flash: () -> Float = { 0f },
    analyzer: BarcodeAnalyzer?,
    onBack: () -> Unit,
    onTyped: (String) -> Boolean,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onScanLabel: (String) -> Unit,
    onEnterManually: (String?) -> Unit,
) {
    val (granted, request) = rememberCameraPermission()
    var torch by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(granted) { if (!granted) request() }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && analyzer != null) {
            CameraViewfinder(Modifier.fillMaxSize(), analyzer = analyzer, onCamera = { camera = it })
            ScanWindow(flash)
        } else {
            PermissionExplainer(onAllow = request, modifier = Modifier.align(Alignment.Center))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
        ) {
            OverlayIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), onBack)
            Text(
                stringResource(R.string.scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                OverlayIconButton(
                    R.drawable.ic_flashlight,
                    stringResource(if (torch) R.string.scan_torch_off else R.string.scan_torch_on),
                ) { torch = !torch }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
        ) {
            if (state is ScanState.Scanning) {
                TextButton(onClick = { typing = true }, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.scan_type_barcode), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                ResultSheet(state, onRetry, onResume, onScanLabel, onEnterManually)
            }
        }
    }

    if (typing) TypeBarcodeDialog(onSubmit = { onTyped(it).also { ok -> if (ok) typing = false } }, onDismiss = { typing = false })
}

/** Dimmed camera with a rounded window that flashes pine on a successful read (DESIGN.md §6). */
@Composable
private fun ScanWindow(flash: () -> Float) {
    val pine = ForkcastTheme.colors.pine
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width * 0.8f
        val h = w * 0.55f
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2.4f)
        drawRect(Color.Black.copy(alpha = 0.45f))
        drawRoundRect(Color.Transparent, topLeft, Size(w, h), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
        val f = flash()
        drawRoundRect(
            color = if (f > 0f) pine else Color.White,
            topLeft = topLeft,
            size = Size(w, h),
            cornerRadius = CornerRadius(28.dp.toPx()),
            style = Stroke(width = (3 + 5 * f).dp.toPx()),
        )
    }
}

@Composable
private fun OverlayIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
    ) { Icon(painterResource(icon), description) }
}

@Composable
private fun ResultSheet(
    state: ScanState,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onScanLabel: (String) -> Unit,
    onEnterManually: (String?) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(20.dp)) {
            when (state) {
                is ScanState.LookingUp -> {
                    Text(stringResource(R.string.scan_looking_up, state.gtin.trimStart('0')), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ScanState.NotFound -> {
                    Text(stringResource(R.string.scan_not_found), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { onScanLabel(state.gtin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.scan_label))
                    }
                    OutlinedButton(onClick = { onEnterManually(state.gtin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.scan_enter_manually))
                    }
                    TextButton(onClick = onResume) { Text(stringResource(R.string.scan_keep_scanning)) }
                }
                is ScanState.Failed -> {
                    Text(
                        stringResource(if (state.error is FoodApiError.Offline) R.string.scan_offline else R.string.scan_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.scan_try_again))
                    }
                    OutlinedButton(onClick = { onEnterManually(state.gtin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.scan_enter_manually))
                    }
                    TextButton(onClick = onResume) { Text(stringResource(R.string.scan_keep_scanning)) }
                }
                ScanState.Scanning -> Unit
            }
        }
    }
}

@Composable
private fun TypeBarcodeDialog(onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_type_title)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }; invalid = false },
                label = { Text(stringResource(R.string.scan_type_field)) },
                isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.scan_type_invalid)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSubmit(code) }, enabled = code.length >= 8) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PermissionExplainer(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.camera_permission_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.camera_permission_allow)) }
        }
    }
}
