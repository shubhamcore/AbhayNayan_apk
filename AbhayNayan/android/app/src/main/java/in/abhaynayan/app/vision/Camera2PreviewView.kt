package `in`.abhaynayan.app.vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import `in`.abhaynayan.app.core.CameraCapabilities
import `in`.abhaynayan.app.core.CapturedFrames
import `in`.abhaynayan.app.core.FrameMetadata
import `in`.abhaynayan.app.core.ImageTransforms
import `in`.abhaynayan.app.core.LockStrategy
import `in`.abhaynayan.app.core.RgbImage

sealed class CaptureOutcome {
    data class Success(val frames: CapturedFrames) : CaptureOutcome()
    data class Failure(val message: String) : CaptureOutcome()
}

/**
 * Rear-camera Camera2 preview plus an ambient → flash still pair read from a YUV_420_888 ImageReader.
 * All camera work runs on a background HandlerThread; callbacks are delivered on the main thread.
 * Frames are converted in memory and never written to storage.
 */
class Camera2PreviewView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    interface Listener {
        fun onCameraReady(capabilities: CameraCapabilities)
        fun onCameraError(message: String)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    @Volatile private var bg: Handler? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var characteristics: CameraCharacteristics? = null
    @Volatile private var capabilities: CameraCapabilities? = null
    @Volatile private var lastResult: TotalCaptureResult? = null
    @Volatile private var started = false
    private var job: CaptureJob? = null
    private var waiter: Waiter? = null

    init { surfaceTextureListener = this }

    fun start() {
        if (started) return
        started = true
        if (thread == null) {
            thread = HandlerThread("abhaynayan-camera").also { it.start() }
            bg = Handler(thread!!.looper)
        }
        if (isAvailable) bg?.post { open() }
    }

    /** Releases everything; safe to call repeatedly (pause, dispose, surface destroyed). */
    fun close() {
        started = false
        val handler = bg
        if (handler != null) {
            handler.post {
                finish(CaptureOutcome.Failure("The camera was closed before the capture finished."))
                releaseCamera()
            }
            thread?.quitSafely()
        } else {
            releaseCamera()
        }
        thread = null
        bg = null
    }

    fun captureFrames(callback: (CaptureOutcome) -> Unit) {
        val handler = bg
        if (handler == null || device == null || session == null) {
            callback(CaptureOutcome.Failure("The camera is not ready yet. Wait a moment and try again."))
            return
        }
        handler.post { begin(callback) }
    }

    // ----- opening -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!started || device != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission is not granted.")
            return
        }
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull()
            if (id == null) { postError("No camera was found on this phone."); return }
            val chars = manager.getCameraCharacteristics(id)
            characteristics = chars
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!started) { camera.close(); return }
                    device = camera
                    configure(camera, chars)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null
                    finish(CaptureOutcome.Failure("The camera was disconnected (another app may be using it)."))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    finish(CaptureOutcome.Failure("Camera error ($error)."))
                    postError("The camera could not be opened (error $error). Close other camera apps and retry.")
                }
            }, bg)
        } catch (e: CameraAccessException) {
            postError("The camera is unavailable right now. Close other camera apps and retry.")
        } catch (e: SecurityException) {
            postError("Camera permission was revoked.")
        } catch (e: RuntimeException) {
            postError("The camera could not be opened on this phone.")
        }
    }

    private fun configure(camera: CameraDevice, chars: CameraCharacteristics) {
        val texture = surfaceTexture ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }.orEmpty()
        val size = CameraCapabilities.chooseStreamSize(yuv)
        if (size == null) { postError("This camera does not offer a supported YUV image format."); return }
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }.orEmpty()
        val preview = previewSizes.firstOrNull { it == size } ?: CameraCapabilities.chooseStreamSize(previewSizes) ?: size
        texture.setDefaultBufferSize(preview.first, preview.second)
        val surface = Surface(texture)
        previewSurface = surface
        val imageReader = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ onImage(it) }, bg)
        reader = imageReader

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        capabilities = CameraCapabilities(
            hardwareLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            },
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            streamWidth = size.first,
            streamHeight = size.second,
            exposureRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
        )
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!started) { s.close(); return }
                    session = s
                    if (setRepeating(torch = false, lock = null)) {
                        val ready = capabilities
                        if (ready != null) mainHandler.post { listener?.onCameraReady(ready) }
                    } else postError("The camera preview could not start.")
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    postError("The camera could not be configured on this phone.")
                }
            }, bg)
        } catch (e: CameraAccessException) {
            postError("The camera became unavailable.")
        } catch (e: IllegalStateException) {
            postError("The camera was closed while starting.")
        }
    }

    // ----- requests ------------------------------------------------------------------------------

    private sealed class Lock {
        data class Manual(val exposureNs: Long, val iso: Int, val frameDurationNs: Long?) : Lock()
        data object AutoLocked : Lock()
    }

    private fun applySettings(builder: CaptureRequest.Builder, torch: Boolean, lock: Lock?) {
        val chars = characteristics
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        val awbLock = capabilities?.awbLockAvailable == true
        when (lock) {
            null -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                if (awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            is Lock.Manual -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lock.exposureNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, lock.iso)
                lock.frameDurationNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
                if (awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            Lock.AutoLocked -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }
        builder.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun setRepeating(torch: Boolean, lock: Lock?): Boolean {
        val camera = device ?: return false
        val s = session ?: return false
        val surface = previewSurface ?: return false
        return try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applySettings(builder, torch, lock)
            s.setRepeatingRequest(builder.build(), repeatingCallback, bg)
            true
        } catch (e: CameraAccessException) { false } catch (e: IllegalStateException) { false } catch (e: IllegalArgumentException) { false }
    }

    private class Waiter(val predicate: (TotalCaptureResult) -> Boolean, val needed: Int, val maxFrames: Int, val onDone: (Boolean) -> Unit) {
        var matched = 0
        var frames = 0
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            val w = waiter ?: return
            w.frames++
            if (w.predicate(result)) w.matched++ else w.matched = 0
            when {
                w.matched >= w.needed -> { waiter = null; w.onDone(true) }
                w.frames >= w.maxFrames -> { waiter = null; w.onDone(false) }
            }
        }
    }

    private fun waitFor(needed: Int, maxFrames: Int, predicate: (TotalCaptureResult) -> Boolean, onDone: (Boolean) -> Unit) {
        waiter = Waiter(predicate, needed, maxFrames, onDone)
    }

    // ----- capture sequence ----------------------------------------------------------------------

    private enum class Role { AMBIENT, FLASH }

    private inner class CaptureJob(val callback: (CaptureOutcome) -> Unit) {
        var lock: Lock? = null
        var expected: Role? = null
        val notes = mutableListOf<String>()
        val images = HashMap<Long, RgbImage>()
        val meta = HashMap<Role, FrameMetadata>()
        val frames = HashMap<Role, RgbImage>()
        val timeout = Runnable { finish(CaptureOutcome.Failure("The camera did not respond in time. Retake.")) }
    }

    private fun begin(callback: (CaptureOutcome) -> Unit) {
        if (job != null) { mainHandler.post { callback(CaptureOutcome.Failure("A capture is already in progress.")) }; return }
        val caps = capabilities
        if (caps == null || device == null || session == null) {
            mainHandler.post { callback(CaptureOutcome.Failure("The camera is not ready yet.")) }
            return
        }
        val current = CaptureJob(callback)
        job = current
        bg?.postDelayed(current.timeout, CAPTURE_TIMEOUT_MS)
        if (!caps.differentialSupported) {
            current.notes += if (!caps.flashAvailable) "No flash on this camera: ambient-only capture." else LockStrategy.NONE.description
            still(Role.AMBIENT)
            return
        }
        val strategy = caps.lockStrategy
        val converged = lastResult
        val exposure = converged?.get(CaptureResult_EXPOSURE)
        val iso = converged?.get(CaptureResult_ISO)
        if (strategy == LockStrategy.MANUAL_SENSOR && converged != null && exposure != null && iso != null) {
            current.lock = Lock.Manual(exposure, iso, converged.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION))
            current.notes += "Exposure fixed at ${exposure / 1_000} µs, ISO $iso for both frames."
            if (!setRepeating(false, current.lock)) return fail("Could not apply manual exposure.")
            waitFor(needed = 2, maxFrames = 12, predicate = { it.request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF }) { still(Role.AMBIENT) }
        } else if (caps.aeLockAvailable) {
            current.lock = Lock.AutoLocked
            if (strategy == LockStrategy.MANUAL_SENSOR) current.notes += "Manual values unavailable; using AE lock instead."
            if (!setRepeating(false, current.lock)) return fail("Could not lock exposure.")
            waitFor(needed = 1, maxFrames = 30, predicate = { r ->
                r.request.get(CaptureRequest.CONTROL_AE_LOCK) == true &&
                    r.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_LOCKED
            }) { locked ->
                if (!locked) current.notes += "The camera did not confirm the exposure lock; the pair will be checked strictly."
                still(Role.AMBIENT)
            }
        } else {
            current.notes += "Exposure could not be fixed; ambient-only capture."
            still(Role.AMBIENT)
        }
    }

    private fun still(role: Role) {
        val current = job ?: return
        val camera = device ?: return fail("Camera closed.")
        val s = session ?: return fail("Camera closed.")
        val surface = previewSurface ?: return fail("Camera closed.")
        val imageSurface = reader?.surface ?: return fail("Camera closed.")
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            builder.addTarget(imageSurface)
            applySettings(builder, torch = role == Role.FLASH, lock = current.lock)
            current.expected = role
            s.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val flash = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE)
                    current.meta[role] = FrameMetadata(
                        exposureTimeNs = result.get(CaptureResult_EXPOSURE),
                        sensitivityIso = result.get(CaptureResult_ISO),
                        flashFired = flash?.let { it == CameraMetadata.FLASH_STATE_FIRED },
                        sensorTimestampNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP),
                        source = "Camera2 YUV_420_888",
                    )
                    tryComplete(role)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    fail("The camera failed to take the ${role.name.lowercase()} photo. Retake.")
                }
            }, bg)
        } catch (e: CameraAccessException) { fail("Camera became unavailable.") } catch (e: IllegalStateException) { fail("Camera closed.") }
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try { imageReader.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return
        val current = job
        try {
            if (current?.expected == null) return
            val caps = capabilities ?: return
            val rgb = ImageTransforms.rotate(toRgb(image), caps.sensorOrientation)
            current.images[image.timestamp] = rgb
        } catch (e: RuntimeException) {
            fail("A camera frame could not be read. Retake.")
        } finally {
            image.close()
        }
        current?.expected?.let { tryComplete(it) }
    }

    private fun tryComplete(role: Role) {
        val current = job ?: return
        if (current.frames.containsKey(role)) return
        val meta = current.meta[role] ?: return
        val image = meta.sensorTimestampNs?.let { current.images.remove(it) } ?: run {
            // Without a matching timestamp, only accept when exactly one unclaimed frame exists.
            if (current.images.size == 1 && meta.sensorTimestampNs == null) current.images.values.first().also { current.images.clear(); current.notes += "Frame timestamp not reported; matched by order." } else null
        } ?: return
        current.frames[role] = image
        current.expected = null
        when {
            role == Role.AMBIENT && current.lock == null -> { setRepeating(false, null); succeed() }
            role == Role.AMBIENT -> startTorch(current)
            else -> { setRepeating(false, null); succeed() }
        }
    }

    private fun startTorch(current: CaptureJob) {
        if (!setRepeating(torch = true, lock = current.lock)) {
            current.notes += "Torch could not be switched on; ambient-only result."
            setRepeating(false, null); succeed(); return
        }
        waitFor(needed = 3, maxFrames = 24, predicate = { r ->
            r.request.get(CaptureRequest.FLASH_MODE) == CaptureRequest.FLASH_MODE_TORCH &&
                r.get(android.hardware.camera2.CaptureResult.FLASH_STATE) == CameraMetadata.FLASH_STATE_FIRED
        }) { confirmed ->
            if (!confirmed) current.notes += "The camera did not report the torch as on; the pair will be checked strictly."
            still(Role.FLASH)
        }
    }

    private fun succeed() {
        val current = job ?: return
        val ambient = current.frames[Role.AMBIENT] ?: return fail("No ambient frame.")
        val ambientMeta = current.meta.getValue(Role.AMBIENT)
        finish(CaptureOutcome.Success(CapturedFrames(
            ambient = ambient,
            flash = current.frames[Role.FLASH],
            ambientMeta = ambientMeta,
            flashMeta = current.meta[Role.FLASH],
            capabilities = capabilities,
            notes = current.notes.toList(),
            source = "Camera2 rear camera · YUV_420_888 ${ambient.width}×${ambient.height}",
        )))
    }

    private fun fail(message: String) {
        setRepeating(false, null)
        finish(CaptureOutcome.Failure(message))
    }

    /** Delivers at most once per job and always clears waiting state. */
    private fun finish(outcome: CaptureOutcome) {
        val current = job ?: return
        job = null
        waiter = null
        bg?.removeCallbacks(current.timeout)
        current.images.clear()
        mainHandler.post { current.callback(outcome) }
    }

    private fun releaseCamera() {
        waiter = null
        try { session?.stopRepeating() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null
        lastResult = null
    }

    private fun postError(message: String) = mainHandler.post { listener?.onCameraError(message) }

    private fun toRgb(image: Image): RgbImage {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported image format." }
        val planes = image.planes
        fun bytes(i: Int) = planes[i].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        return ImageTransforms.yuv420ToArgb(
            image.width, image.height,
            bytes(0), planes[0].rowStride, planes[0].pixelStride,
            bytes(1), bytes(2), planes[1].rowStride, planes[1].pixelStride,
        )
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (started) bg?.post { open() } }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { close(); return true }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 6_000L
        val CaptureResult_EXPOSURE: android.hardware.camera2.CaptureResult.Key<Long> = android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME
        val CaptureResult_ISO: android.hardware.camera2.CaptureResult.Key<Int> = android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY
    }
}
