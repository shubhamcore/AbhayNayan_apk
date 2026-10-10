package `in`.abhaynayan.app.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Rejection reasons are worded for frontline workers; [code] is stable for records and tests. */
enum class QualityIssue(val code: String, val message: String, val blocking: Boolean = true) {
    RESOLUTION_TOO_LOW("RESOLUTION_TOO_LOW", "The image is too small to check. Retake with the camera."),
    TOO_DARK("TOO_DARK", "The image is too dark. Move to better light (not direct sunlight) and retake."),
    TOO_BRIGHT("TOO_BRIGHT", "The image is too bright or washed out. Avoid direct sunlight and retake."),
    GLARE_CLIPPING("GLARE_CLIPPING", "Strong glare is hiding the target area. Tilt the phone slightly and retake."),
    BLURRY("BLURRY", "The image is blurry. Hold the phone steady, tap to focus, and retake."),
    TARGET_NOT_VISIBLE("TARGET_NOT_VISIBLE", "The target area looks empty or uniform. Place the eye region inside the guide and retake."),
    /** Informational: small hand movement is corrected automatically; large movement only skips the flash comparison. */
    MOTION_BETWEEN_FRAMES("MOTION_BETWEEN_FRAMES", "The phone moved too much between the two photos, so the flash comparison was skipped. The normal photo is still used — no retake needed.", blocking = false),
    FLASH_PAIR_UNUSABLE("FLASH_PAIR_UNUSABLE", "The flash comparison could not be used. The plain photo can still be checked.", blocking = false),
    REGION_NOT_CONFIRMED("REGION_NOT_CONFIRMED", "Confirm that the target area is inside the guide."),
}

data class QualityReport(
    val issues: List<QualityIssue>,
    val meanLuma: Double,
    val lumaStdDev: Double,
    val sharpness: Double,
    val clippedFraction: Double,
) {
    val passed: Boolean get() = issues.none { it.blocking }
    val statusCode: String get() = if (passed) "PASSED" else "REJECTED:" + issues.filter { it.blocking }.joinToString(",") { it.code }
}

/**
 * Engineering capture checks. Thresholds are heuristics chosen for this prototype and documented in
 * docs/ANDROID_ML_STATUS.md; passing them does not mean any biomarker estimate would be accurate.
 */
object ImageQualityChecker {
    const val MIN_DIMENSION = 64
    const val DARK_LUMA = 40.0
    const val BRIGHT_LUMA = 215.0
    const val MAX_CLIPPED_FRACTION = .10
    const val MIN_SHARPNESS = 8.0
    const val MIN_ROI_STDDEV = 3.0

    fun check(image: RgbImage, roi: PixelRect): QualityReport {
        if (image.width < MIN_DIMENSION || image.height < MIN_DIMENSION) {
            return QualityReport(listOf(QualityIssue.RESOLUTION_TOO_LOW), 0.0, 0.0, 0.0, 0.0)
        }
        val issues = mutableListOf<QualityIssue>()
        val wholeLuma = meanLuma(image, PixelRect(0, 0, image.width, image.height))
        if (wholeLuma < DARK_LUMA) issues += QualityIssue.TOO_DARK
        if (wholeLuma > BRIGHT_LUMA) issues += QualityIssue.TOO_BRIGHT

        var clipped = 0
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        for (y in roi.top until roi.bottom) for (x in roi.left until roi.right) {
            val p = image.pixels[y * image.width + x]
            val r = p ushr 16 and 0xff; val g = p ushr 8 and 0xff; val b = p and 0xff
            if (r >= 250 || g >= 250 || b >= 250) clipped++
            val l = luma(p)
            sum += l; sumSq += l * l; count++
        }
        val mean = sum / count
        val std = sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
        val clippedFraction = clipped.toDouble() / count
        if (clippedFraction > MAX_CLIPPED_FRACTION && QualityIssue.TOO_BRIGHT !in issues) issues += QualityIssue.GLARE_CLIPPING
        if (std < MIN_ROI_STDDEV) issues += QualityIssue.TARGET_NOT_VISIBLE
        // Conjunctiva and sclera are smooth surfaces, so sharpness is judged on the whole frame
        // (lashes, lid margin, skin texture); hand-shake blur smears the whole frame, not just the ROI.
        val sharpness = maxOf(laplacianVariance(image, roi), laplacianVariance(image, PixelRect(0, 0, image.width, image.height)))
        if (sharpness < MIN_SHARPNESS && QualityIssue.TARGET_NOT_VISIBLE !in issues && QualityIssue.TOO_DARK !in issues) {
            issues += QualityIssue.BLURRY
        }
        return QualityReport(issues, wholeLuma, std, sharpness, clippedFraction)
    }

    /** Variance of a 4-neighbour Laplacian on luma inside the ROI (0–255 scale). */
    fun laplacianVariance(image: RgbImage, roi: PixelRect): Double {
        val left = maxOf(1, roi.left); val top = maxOf(1, roi.top)
        val right = minOf(image.width - 1, roi.right); val bottom = minOf(image.height - 1, roi.bottom)
        var n = 0; var sum = 0.0; var sumSq = 0.0
        for (y in top until bottom) for (x in left until right) {
            val c = luma(image.pixels[y * image.width + x])
            val lap = luma(image.pixels[y * image.width + x - 1]) + luma(image.pixels[y * image.width + x + 1]) +
                luma(image.pixels[(y - 1) * image.width + x]) + luma(image.pixels[(y + 1) * image.width + x]) - 4 * c
            sum += lap; sumSq += lap * lap; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }

    private fun meanLuma(image: RgbImage, rect: PixelRect): Double {
        var sum = 0.0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) sum += luma(image.pixels[y * image.width + x])
        return sum / (rect.width * rect.height)
    }

    fun luma(p: Int): Double = .299 * (p ushr 16 and 0xff) + .587 * (p ushr 8 and 0xff) + .114 * (p and 0xff)
}

/** Per-frame capture settings reported by Camera2 (null when unknown, e.g. imported images). */
data class FrameMetadata(
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val flashFired: Boolean?,
    val sensorTimestampNs: Long?,
    val source: String,
)

data class DifferentialResult(
    val accepted: Boolean,
    val rejectionReasons: List<String>,
    val intervalMs: Long?,
    val exposureRatio: Double?,
    val isoRatio: Double?,
    /** Estimated movement of the flash frame relative to the ambient frame, in pixels (compensated). */
    val shiftX: Int,
    val shiftY: Int,
    val alignmentResidual: Double,
    val flashClippedFraction: Double,
    val meanDeltaLinear: Triple<Double, Double, Double>?,
    val validPixelFraction: Double,
    val erythemaIndexMean: Double?,
    val erythemaIndexMedian: Double?,
    /** True when movement was too large to compensate or the scene changed between frames. */
    val excessiveMotion: Boolean = false,
) {
    val motionCorrected: Boolean get() = accepted && (shiftX != 0 || shiftY != 0)
}

/**
 * Validates an ambient + flash pair and, only if it is comparable, computes the exploratory
 * differential features. See docs/ANDROID_ML_STATUS.md § Optical features for the exact math:
 *
 *  - Input: 8-bit sRGB-encoded ARGB frames from the same stream size (Camera2 YUV_420_888 converted
 *    with full-range BT.601, or decoded JPEG/PNG). Channel order R, G, B.
 *  - Registration: hand-held capture always moves a little between the two frames. The translation
 *    of the flash frame is estimated (coarse search on a downsampled image, then a pixel-level
 *    refinement) and compensated before any subtraction. Only movement larger than
 *    [MAX_SHIFT_FRACTION] of the frame width, or a scene change, rejects the comparison.
 *  - Linearization: inverse sRGB transfer function per channel to [0, 1]. The phone ISP tone curve is
 *    unknown, so this is an approximation, not radiometric calibration.
 *  - ΔI = I_flash(x + dx, y + dy) − I_ambient(x, y) per channel, on linearized values.
 *  - Invalid pixels: outside the overlap after registration, any channel ≥ 250 in either frame
 *    (clipped), or ΔR or ΔG ≤ 0.
 *  - EI = ln(ΔR + ε) − ln(ΔG + ε), ε = 1e-4, averaged over valid ROI pixels.
 */
object DifferentialAnalyzer {
    const val MAX_EXPOSURE_DIFFERENCE = .02
    const val MAX_INTERVAL_MS = 1_000L
    const val MAX_FLASH_CLIPPED_FRACTION = .05
    const val MIN_MEAN_DELTA = .01
    /** Mean absolute difference of z-normalised luma after alignment; above this the scene changed. */
    const val MAX_ALIGNMENT_RESIDUAL = .60
    /** Largest compensated movement, as a fraction of the frame width (≈ 48 px at 480 px). */
    const val MAX_SHIFT_FRACTION = .10
    const val MIN_VALID_FRACTION = .30
    const val EPSILON = 1e-4
    private const val ALIGN_WIDTH = 64

    private val LINEAR = DoubleArray(256) { v ->
        val c = v / 255.0
        if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
    }

    fun linear(channel8: Int): Double = LINEAR[channel8]

    fun analyze(
        ambient: RgbImage,
        flash: RgbImage,
        ambientMeta: FrameMetadata,
        flashMeta: FrameMetadata,
        roi: PixelRect,
    ): DifferentialResult {
        val reasons = mutableListOf<String>()
        if (ambient.width != flash.width || ambient.height != flash.height) {
            return rejected(listOf("Frames have different sizes."))
        }
        val interval = if (ambientMeta.sensorTimestampNs != null && flashMeta.sensorTimestampNs != null) {
            (flashMeta.sensorTimestampNs - ambientMeta.sensorTimestampNs) / 1_000_000L
        } else null
        if (interval != null && (interval < 0 || interval > MAX_INTERVAL_MS)) reasons += "Frames were ${interval} ms apart (limit $MAX_INTERVAL_MS ms)."
        val exposureRatio = ratio(ambientMeta.exposureTimeNs?.toDouble(), flashMeta.exposureTimeNs?.toDouble())
        val isoRatio = ratio(ambientMeta.sensitivityIso?.toDouble(), flashMeta.sensitivityIso?.toDouble())
        if (exposureRatio == null || isoRatio == null) {
            reasons += "Exposure settings were not reported, so the frames cannot be shown to be comparable."
        } else {
            if (abs(exposureRatio - 1) > MAX_EXPOSURE_DIFFERENCE) reasons += "Exposure time changed between frames."
            if (abs(isoRatio - 1) > MAX_EXPOSURE_DIFFERENCE) reasons += "Sensor sensitivity (ISO) changed between frames."
        }
        if (flashMeta.flashFired == false) reasons += "The flash did not fire for the second frame."

        val alignment = estimateShift(ambient, flash)
        val shiftX = alignment.dx; val shiftY = alignment.dy
        var excessiveMotion = false
        if (alignment.outOfRange) {
            excessiveMotion = true
            reasons += "The phone moved more than can be corrected between the two frames."
        } else if (alignment.residual > MAX_ALIGNMENT_RESIDUAL) {
            excessiveMotion = true
            reasons += "The scene changed between frames (for example a blink or large movement)."
        }

        var flashClipped = 0; var roiCount = 0; var valid = 0
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        val eiValues = ArrayList<Double>()
        for (y in roi.top until roi.bottom) for (x in roi.left until roi.right) {
            val fx = x + shiftX; val fy = y + shiftY
            roiCount++
            if (fx !in 0 until flash.width || fy !in 0 until flash.height) continue // outside the overlap
            val a = ambient.pixels[y * ambient.width + x]; val f = flash.pixels[fy * flash.width + fx]
            val fr = f ushr 16 and 0xff; val fg = f ushr 8 and 0xff; val fb = f and 0xff
            val ar = a ushr 16 and 0xff; val ag = a ushr 8 and 0xff; val ab = a and 0xff
            val clippedFlash = fr >= 250 || fg >= 250 || fb >= 250
            if (clippedFlash) flashClipped++
            if (clippedFlash || ar >= 250 || ag >= 250 || ab >= 250) continue
            val dr = LINEAR[fr] - LINEAR[ar]; val dg = LINEAR[fg] - LINEAR[ag]; val db = LINEAR[fb] - LINEAR[ab]
            sumR += dr; sumG += dg; sumB += db
            if (dr <= 0 || dg <= 0) continue
            valid++
            eiValues += ln(dr + EPSILON) - ln(dg + EPSILON)
        }
        val clippedFraction = flashClipped.toDouble() / roiCount
        if (clippedFraction > MAX_FLASH_CLIPPED_FRACTION) reasons += "Flash glare clipped ${(clippedFraction * 100).toInt()}% of the target area."
        val unclipped = (roiCount - flashClipped).coerceAtLeast(1)
        val meanDelta = Triple(sumR / unclipped, sumG / unclipped, sumB / unclipped)
        if (!excessiveMotion && (meanDelta.first + meanDelta.second + meanDelta.third) / 3 < MIN_MEAN_DELTA) {
            reasons += "The flash added too little light (too far away or very bright surroundings)."
        }
        val validFraction = valid.toDouble() / roiCount
        if (!excessiveMotion && validFraction < MIN_VALID_FRACTION) reasons += "Too few usable pixels in the target area for the flash comparison."

        val accepted = reasons.isEmpty()
        eiValues.sort()
        return DifferentialResult(
            accepted = accepted,
            rejectionReasons = reasons,
            intervalMs = interval,
            exposureRatio = exposureRatio,
            isoRatio = isoRatio,
            shiftX = shiftX, shiftY = shiftY,
            alignmentResidual = alignment.residual,
            flashClippedFraction = clippedFraction,
            meanDeltaLinear = meanDelta,
            validPixelFraction = validFraction,
            erythemaIndexMean = if (accepted && eiValues.isNotEmpty()) eiValues.average() else null,
            erythemaIndexMedian = if (accepted && eiValues.isNotEmpty()) eiValues[eiValues.size / 2] else null,
            excessiveMotion = excessiveMotion,
        )
    }

    private fun ratio(a: Double?, b: Double?): Double? = if (a == null || b == null || a <= 0) null else b / a

    private fun rejected(reasons: List<String>) = DifferentialResult(false, reasons, null, null, null, 0, 0, 1.0, 0.0, null, 0.0, null, null)

    data class Alignment(val dx: Int, val dy: Int, val residual: Double, val outOfRange: Boolean)

    /**
     * Estimates how far [b] (flash) is translated relative to [a] (ambient): b(x + dx, y + dy) ≈ a(x, y).
     * Both frames are band-passed and z-normalised so the flash brightness/shading change does not look like motion.
     *  1. Coarse: box-downsample to ~64 px wide and search ±[MAX_SHIFT_FRACTION] of the width.
     *  2. Fine: search ±(scale) px at full resolution around the coarse estimate.
     */
    fun estimateShift(a: RgbImage, b: RgbImage): Alignment {
        val scale = maxOf(1, a.width / ALIGN_WIDTH)
        val w = a.width / scale; val h = a.height / scale
        val search = maxOf(1, kotlin.math.ceil(w * MAX_SHIFT_FRACTION).toInt()) + 1 // one step beyond the limit
        if (w <= 2 * search + 4 || h <= 2 * search + 4) return Alignment(0, 0, 0.0, false)
        val la = downsampledZLuma(a, scale, w, h); val lb = downsampledZLuma(b, scale, w, h)
        var best = Double.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -search..search) for (dx in -search..search) {
            var sum = 0.0; var n = 0
            for (y in search until h - search) for (x in search until w - search) {
                sum += abs(la[y * w + x] - lb[(y + dy) * w + x + dx]); n++
            }
            val score = sum / n
            // Prefer the smaller shift on ties so identical frames never report motion.
            if (score < best - 1e-9 || (abs(score - best) <= 1e-9 && abs(dx) + abs(dy) < abs(bx) + abs(by))) { best = score; bx = dx; by = dy }
        }
        val limit = a.width * MAX_SHIFT_FRACTION
        if (abs(bx * scale) > limit || abs(by * scale) > limit) return Alignment(bx * scale, by * scale, best, true)
        if (scale == 1) return Alignment(bx, by, best, false)

        // Full-resolution refinement around the coarse estimate (central region).
        val margin = (limit + scale + 2).toInt()
        val x0 = margin; val x1 = a.width - margin; val y0 = margin; val y1 = a.height - margin
        if (x1 - x0 < 16 || y1 - y0 < 16) return Alignment(bx * scale, by * scale, best, false)
        val fa = fullZLuma(a, scale); val fb = fullZLuma(b, scale)
        var fineBest = Double.MAX_VALUE; var fx = bx * scale; var fy = by * scale
        for (dy in by * scale - scale..by * scale + scale) for (dx in bx * scale - scale..bx * scale + scale) {
            var sum = 0.0; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { sum += abs(fa[y * a.width + x] - fb[(y + dy) * a.width + x + dx]); n++; x += 1 }
                y += 1
            }
            val score = sum / n
            if (score < fineBest - 1e-9 || (abs(score - fineBest) <= 1e-9 && abs(dx) + abs(dy) < abs(fx) + abs(fy))) { fineBest = score; fx = dx; fy = dy }
        }
        return Alignment(fx, fy, fineBest, abs(fx) > limit || abs(fy) > limit)
    }

    /** Flash frame shifted into the ambient frame's coordinates (edges repeated) for visualisation. */
    fun alignedFlash(flash: RgbImage, dx: Int, dy: Int): RgbImage {
        if (dx == 0 && dy == 0) return flash
        val out = IntArray(flash.pixels.size)
        for (y in 0 until flash.height) for (x in 0 until flash.width) {
            val sx = (x + dx).coerceIn(0, flash.width - 1); val sy = (y + dy).coerceIn(0, flash.height - 1)
            out[y * flash.width + x] = flash.pixels[sy * flash.width + sx]
        }
        return RgbImage(flash.width, flash.height, out)
    }

    /**
     * Box-downsampled luma, band-passed (minus a 4-cell blur) and z-normalised. Removing the smooth
     * component means flash shading and broad lighting gradients cannot create a false match.
     */
    private fun downsampledZLuma(img: RgbImage, scale: Int, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0.0
            for (yy in 0 until scale) for (xx in 0 until scale) sum += ImageQualityChecker.luma(img.pixels[(y * scale + yy) * img.width + x * scale + xx])
            out[y * w + x] = sum / (scale * scale)
        }
        val low = boxBlur(out, w, h, 4)
        return zNormalize(DoubleArray(out.size) { out[it] - low[it] })
    }

    /** Full-resolution band-pass (light denoise minus a wide blur), z-normalised, for sub-cell refinement. */
    private fun fullZLuma(img: RgbImage, scale: Int): DoubleArray {
        val luma = DoubleArray(img.pixels.size) { ImageQualityChecker.luma(img.pixels[it]) }
        val fine = boxBlur(luma, img.width, img.height, 1)
        val low = boxBlur(luma, img.width, img.height, 4 * scale)
        return zNormalize(DoubleArray(luma.size) { fine[it] - low[it] })
    }

    /** Separable box blur with edge clamping, O(n) per pass using running sums. */
    private fun boxBlur(src: DoubleArray, w: Int, h: Int, r: Int): DoubleArray {
        if (r <= 0) return src.copyOf()
        val tmp = DoubleArray(src.size)
        val out = DoubleArray(src.size)
        val n = (2 * r + 1).toDouble()
        for (y in 0 until h) {
            var acc = 0.0
            for (k in -r..r) acc += src[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / n
                acc += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0.0
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private fun zNormalize(values: DoubleArray): DoubleArray {
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).coerceAtLeast(1e-6)
        for (i in values.indices) values[i] = (values[i] - mean) / std
        return values
    }
}

/** Pure image transforms used by the camera path; covered by unit tests. */
object ImageTransforms {
    /** Rotates clockwise by 0/90/180/270 degrees so sensor-oriented frames become upright. */
    fun rotate(image: RgbImage, degrees: Int): RgbImage {
        val d = ((degrees % 360) + 360) % 360
        require(d % 90 == 0) { "Rotation must be a multiple of 90 degrees." }
        if (d == 0) return image
        val w = image.width; val h = image.height
        val (ow, oh) = if (d == 180) w to h else h to w
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (d) {
                90 -> (h - 1 - y) to x
                180 -> (w - 1 - x) to (h - 1 - y)
                else -> y to (w - 1 - x)
            }
            out[ny * ow + nx] = image.pixels[y * w + x]
        }
        return RgbImage(ow, oh, out)
    }

    /**
     * YUV_420_888 → ARGB using full-range BT.601 (JFIF), which Camera2 YUV output uses on typical devices.
     * Plane strides are honoured so padded buffers convert correctly.
     */
    fun yuv420ToArgb(
        width: Int, height: Int,
        y: ByteArray, yRowStride: Int, yPixelStride: Int,
        u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int,
    ): RgbImage {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            val uvRow = (row / 2) * uvRowStride
            for (col in 0 until width) {
                val yy = (y[row * yRowStride + col * yPixelStride].toInt() and 0xff).toDouble()
                val uvIndex = uvRow + (col / 2) * uvPixelStride
                val cb = (u[uvIndex].toInt() and 0xff) - 128.0
                val cr = (v[uvIndex].toInt() and 0xff) - 128.0
                val r = (yy + 1.402 * cr).toInt().coerceIn(0, 255)
                val g = (yy - .344136 * cb - .714136 * cr).toInt().coerceIn(0, 255)
                val b = (yy + 1.772 * cb).toInt().coerceIn(0, 255)
                out[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(width, height, out)
    }
}
