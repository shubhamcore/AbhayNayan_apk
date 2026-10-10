package `in`.abhaynayan.app.core

/** How exposure is held constant between the ambient and flash frames. */
enum class LockStrategy(val description: String) {
    MANUAL_SENSOR("Manual exposure time and ISO copied from the converged ambient frame (MANUAL_SENSOR capability)."),
    AE_AWB_LOCK("Auto-exposure lock (and white-balance lock when available) before the flash frame."),
    NONE("Exposure cannot be held constant on this camera; the flash comparison is disabled (ambient-only mode)."),
}

data class CameraCapabilities(
    val hardwareLevel: String,
    val manualSensor: Boolean,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val flashAvailable: Boolean,
    val sensorOrientation: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val exposureRangeNs: LongRange?,
    val isoRange: IntRange?,
) {
    val lockStrategy: LockStrategy get() = choose(manualSensor, aeLockAvailable)
    val differentialSupported: Boolean get() = flashAvailable && lockStrategy != LockStrategy.NONE

    fun summary(): List<String> = buildList {
        add("Camera2 hardware level: $hardwareLevel")
        add("Stream: ${streamWidth}×$streamHeight YUV_420_888, sensor rotation $sensorOrientation°")
        add("Manual sensor control: ${yes(manualSensor)} · AE lock: ${yes(aeLockAvailable)} · AWB lock: ${yes(awbLockAvailable)}")
        add("Flash/torch: ${yes(flashAvailable)}")
        add(if (differentialSupported) "Ambient + flash pair: ${lockStrategy.description}"
            else if (!flashAvailable) "Fallback mode: no flash, ambient-only capture." else "Fallback mode: ${LockStrategy.NONE.description}")
        if (!awbLockAvailable && differentialSupported) add("White balance cannot be locked; the pair is checked for comparability but colour balance may drift.")
    }

    private fun yes(v: Boolean) = if (v) "yes" else "no"

    companion object {
        fun choose(manualSensor: Boolean, aeLock: Boolean): LockStrategy = when {
            manualSensor -> LockStrategy.MANUAL_SENSOR
            aeLock -> LockStrategy.AE_AWB_LOCK
            else -> LockStrategy.NONE
        }

        /** Picks the 4:3 stream size closest to 640×480 (small enough for low-RAM phones). */
        fun chooseStreamSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
            if (sizes.isEmpty()) return null
            val usable = sizes.filter { (w, h) -> w >= 320 && h >= 240 && w * h <= 1280 * 960 }
            val pool = usable.ifEmpty { sizes }
            val fourByThree = pool.filter { (w, h) -> kotlin.math.abs(w * 3 - h * 4) <= 4 }
            return (fourByThree.ifEmpty { pool }).minByOrNull { (w, h) -> kotlin.math.abs(w - 640) + kotlin.math.abs(h - 480) }
        }
    }
}

/** Two upright frames plus the metadata that proves (or fails to prove) they are comparable. */
data class CapturedFrames(
    val ambient: RgbImage,
    val flash: RgbImage?,
    val ambientMeta: FrameMetadata,
    val flashMeta: FrameMetadata?,
    val capabilities: CameraCapabilities?,
    val notes: List<String>,
    val source: String,
)
