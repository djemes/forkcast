package app.forkcast.ui.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.domain.NutritionLabelParser
import app.forkcast.core.domain.ParsedLabel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Parsed label values handed to the custom-food form as a navigation argument. */
@Serializable
data class LabelPrefill(
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    /** Per serving, keyed by NutrientKey name. */
    val nutrients: Map<String, Double> = emptyMap(),
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun from(p: ParsedLabel) = LabelPrefill(p.servingLabel, p.servingGrams, p.perServing.mapKeys { it.key.name })
        private val lenient = Json { ignoreUnknownKeys = true }

        fun decode(s: String): LabelPrefill? = runCatching { lenient.decodeFromString(serializer(), s) }.getOrNull()
    }
}

sealed interface LabelState {
    data object Ready : LabelState
    data object Reading : LabelState
    /** Calories or serving weight couldn't be read; the user can retry or type it in. */
    data class Unreadable(val partial: LabelPrefill) : LabelState
}

@HiltViewModel
class LabelScanViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow<LabelState>(LabelState.Ready)
    val state: StateFlow<LabelState> = _state.asStateFlow()
    private val parsed = Channel<LabelPrefill>(Channel.BUFFERED)
    val results: Flow<LabelPrefill> = parsed.receiveAsFlow()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Runs on-device OCR (bundled model, offline) and parses the Nutrition Facts text. */
    fun read(bitmap: Bitmap) {
        _state.value = LabelState.Reading
        viewModelScope.launch {
            val text = runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text }.getOrDefault("")
            val label = NutritionLabelParser.parse(text)
            val prefill = LabelPrefill.from(label)
            if (label.isUsable) {
                _state.value = LabelState.Ready
                parsed.send(prefill)
            } else {
                _state.value = LabelState.Unreadable(prefill)
            }
        }
    }

    fun failed() { _state.value = LabelState.Unreadable(LabelPrefill()) }
    fun retry() { _state.value = LabelState.Ready }

    override fun onCleared() = recognizer.close()
}

@Composable
fun LabelScanRoute(
    onBack: () -> Unit,
    onParsed: (LabelPrefill) -> Unit,
    viewModel: LabelScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val (granted, request) = rememberCameraPermission()
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(granted) { if (!granted) request() }
    LaunchedEffect(viewModel) { viewModel.results.collect(onParsed) }

    suspend fun takePhoto(): Bitmap = suspendCancellableCoroutine { cont ->
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = image.use { it.toUprightBitmap() }
                cont.resume(bmp)
            }
            override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraViewfinder(Modifier.fillMaxSize(), imageCapture = capture)
        else PermissionExplainer(onAllow = request, modifier = Modifier.align(Alignment.Center))

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
            ) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
            Text(
                stringResource(R.string.label_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            when (val s = state) {
                LabelState.Reading -> {
                    Text(stringResource(R.string.label_reading), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is LabelState.Unreadable -> {
                    Text(stringResource(R.string.label_unreadable), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.scan_try_again))
                    }
                    OutlinedButton(onClick = { onParsed(s.partial) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.scan_enter_manually))
                    }
                }
                LabelState.Ready -> Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            runCatching { takePhoto() }.onSuccess(viewModel::read).onFailure { viewModel.failed() }
                            busy = false
                        }
                    },
                    enabled = granted && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.label_capture)) }
            }
        }
    }
}

/** Applies the capture's rotation so OCR reads the text upright. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bmp = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
