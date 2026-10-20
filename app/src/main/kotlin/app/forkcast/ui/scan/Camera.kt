package app.forkcast.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.forkcast.core.domain.Gtin
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/** Camera permission, asked in context the first time a scanner opens (never on launch). */
@Composable
fun rememberCameraPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return granted to { launcher.launch(Manifest.permission.CAMERA) }
}

/**
 * Back-camera preview bound to the screen's lifecycle, with optional frame analysis and photo
 * capture. Unbinds when it leaves the composition.
 */
@Composable
fun CameraViewfinder(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
    imageCapture: ImageCapture? = null,
    onCamera: (Camera) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val currentOnCamera by rememberUpdatedState(onCamera)

    LaunchedEffect(lifecycleOwner, analyzer, imageCapture) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val executor = Executors.newSingleThreadExecutor()
        val analysis = analyzer?.let {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(executor, it) }
        }
        val useCases = listOfNotNull(preview, analysis, imageCapture)
        try {
            provider.unbindAll()
            currentOnCamera(provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()))
            awaitCancellation()
        } finally {
            provider.unbind(*useCases.toTypedArray())
            executor.shutdown()
        }
    }
    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
}

/**
 * Reads EAN-13, EAN-8, UPC-A and UPC-E with ML Kit's bundled (offline) model and reports
 * each new code once, normalized to GTIN-14.
 */
class BarcodeAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build(),
    )

    @Volatile var paused = false
    private var last: String? = null

    /** Frees ML Kit's native scanner when the screen goes away. */
    fun close() = scanner.close()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || media == null) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                val gtin = codes.firstNotNullOfOrNull { code ->
                    val raw = code.rawValue ?: return@firstNotNullOfOrNull null
                    if (code.format == Barcode.FORMAT_UPC_E) Gtin.normalizeUpcE(raw) ?: Gtin.normalize(raw) else Gtin.normalize(raw)
                }
                if (gtin != null && gtin != last && !paused) {
                    last = gtin
                    onCode(gtin)
                }
            }
            .addOnCompleteListener { image.close() }
    }

    /** Lets the same code be reported again (e.g. after the user dismisses a result). */
    fun reset() {
        last = null
        paused = false
    }
}
