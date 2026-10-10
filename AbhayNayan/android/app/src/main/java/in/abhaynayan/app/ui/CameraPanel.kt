package `in`.abhaynayan.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.abhaynayan.app.core.CameraCapabilities
import `in`.abhaynayan.app.core.CapturedFrames
import `in`.abhaynayan.app.core.NormalizedRect
import `in`.abhaynayan.app.vision.Camera2PreviewView
import `in`.abhaynayan.app.vision.CaptureOutcome

/**
 * Live rear-camera preview with the manual target guide. The guide rectangle matches
 * NormalizedRect.MANUAL_CENTER_GUIDE, which is the region analysed later. No landmark model is used.
 */
@Composable
fun CameraPanel(targetLabel: String, onFrames: (CapturedFrames) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }

    if (!granted) {
        SectionCard(title = "Camera permission") {
            InfoText("The camera is used only on this phone. Photos are not saved or uploaded.")
            if (denied) ErrorText("Camera permission was denied. You can allow it in Android Settings › Apps › AbhayNayan › Permissions, or import a photo instead.")
            PrimaryButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
        }
        return
    }

    var view by remember { mutableStateOf<Camera2PreviewView?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(view, lifecycle) {
        val v = view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.start()
                Lifecycle.Event.ON_PAUSE -> { v?.close(); busy = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); v?.close() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth(.82f).align(Alignment.CenterHorizontally).aspectRatio(3f / 4f)
                .background(Color(0xFF0E2328), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Camera preview with a target guide for the $targetLabel" },
        ) {
            AndroidView(
                factory = { ctx ->
                    Camera2PreviewView(ctx).also { cam ->
                        cam.listener = object : Camera2PreviewView.Listener {
                            override fun onCameraReady(capabilities: CameraCapabilities) { caps = capabilities; cameraError = null }
                            override fun onCameraError(message: String) { cameraError = message }
                        }
                        view = cam
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val g = NormalizedRect.MANUAL_CENTER_GUIDE
            Box(
                Modifier.offset(x = maxWidth * g.left.toFloat(), y = maxHeight * g.top.toFloat())
                    .size(maxWidth * (g.right - g.left).toFloat(), maxHeight * (g.bottom - g.top).toFloat())
                    .border(3.dp, Color(0xFFF2D27A), RoundedCornerShape(24.dp)),
            )
            Text(
                if (caps == null && cameraError == null) "Opening camera…" else "Place the $targetLabel inside the box",
                color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(Color(0xCC0C272D), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        cameraError?.let { ErrorText(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (busy) "Capturing… hold still" else "Capture", {
                val v = view ?: return@PrimaryButton
                busy = true
                v.captureFrames { outcome ->
                    busy = false
                    when (outcome) {
                        is CaptureOutcome.Success -> onFrames(outcome.frames)
                        is CaptureOutcome.Failure -> onError(outcome.message)
                    }
                }
            }, Modifier.weight(1f), enabled = !busy && caps != null)
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        caps?.let { c ->
            SectionCard(title = "This camera") {
                c.summary().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Palette.Body) }
            }
        }
    }
}
