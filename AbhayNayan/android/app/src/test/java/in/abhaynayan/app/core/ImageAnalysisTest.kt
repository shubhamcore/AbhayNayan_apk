package `in`.abhaynayan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ImageAnalysisTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /**
     * Deterministic, non-repeating textured scene (smooth shading at incommensurate frequencies plus fine
     * hashed grain), like skin/eyelid texture. Content at (x, y) comes from scene point (x − dx, y − dy),
     * so `scene(dx = 6)` is the same scene after the camera moved 6 px.
     */
    private fun scene(w: Int = 160, h: Int = 120, dx: Int = 0, dy: Int = 0, add: Int = 0): RgbImage {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - dx; val sy = y - dy
            val v = 60 * valueNoise(sx, sy, 11) + 35 * valueNoise(sx, sy, 5) + 12 * (hash(sx, sy, 3) - .5)
            px[y * w + x] = argb((140 + v * .6).toInt() + add, (95 + v * .45).toInt() + add, (85 + v * .4).toInt() + add)
        }
        return RgbImage(w, h, px)
    }

    /** Deterministic pseudo-random value in [0, 1) for an integer lattice point. */
    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 374_761_393 + y * 668_265_263 + seed * 2_147_483_647
        h = (h xor (h ushr 13)) * 1_274_126_177
        return ((h xor (h ushr 16)) and 0xffff) / 65_536.0
    }

    /** Bilinearly interpolated lattice noise with the given cell size, centred on zero. */
    private fun valueNoise(x: Int, y: Int, cell: Int): Double {
        val gx = Math.floorDiv(x, cell); val gy = Math.floorDiv(y, cell)
        val fx = Math.floorMod(x, cell).toDouble() / cell; val fy = Math.floorMod(y, cell).toDouble() / cell
        val top = hash(gx, gy, cell) * (1 - fx) + hash(gx + 1, gy, cell) * fx
        val bottom = hash(gx, gy + 1, cell) * (1 - fx) + hash(gx + 1, gy + 1, cell) * fx
        return top * (1 - fy) + bottom * fy - .5
    }

    private fun solid(w: Int, h: Int, c: Int) = RgbImage(w, h, IntArray(w * h) { c })
    private fun roi(img: RgbImage) = OpticalPipeline.roiBounds(img.width, img.height, NormalizedRect.MANUAL_CENTER_GUIDE)
    private val metaA = FrameMetadata(10_000_000, 200, false, 0, "test")
    private val metaF = FrameMetadata(10_000_000, 200, true, 120_000_000, "test")

    @Test fun texturedWellLitImagePasses() {
        val img = scene()
        val report = ImageQualityChecker.check(img, roi(img))
        assertTrue(report.issues.toString(), report.passed)
        assertEquals("PASSED", report.statusCode)
    }

    @Test fun darkBrightUniformAndTinyImagesAreRejectedWithReasons() {
        val dark = solid(100, 100, argb(10, 10, 10))
        assertTrue(QualityIssue.TOO_DARK in ImageQualityChecker.check(dark, roi(dark)).issues)
        val bright = solid(100, 100, argb(240, 240, 240))
        assertTrue(QualityIssue.TOO_BRIGHT in ImageQualityChecker.check(bright, roi(bright)).issues)
        val flat = solid(100, 100, argb(120, 90, 80))
        val flatReport = ImageQualityChecker.check(flat, roi(flat))
        assertTrue(QualityIssue.TARGET_NOT_VISIBLE in flatReport.issues)
        assertFalse(flatReport.passed)
        val tiny = solid(20, 20, argb(120, 90, 80))
        assertEquals(listOf(QualityIssue.RESOLUTION_TOO_LOW), ImageQualityChecker.check(tiny, roi(tiny)).issues)
    }

    @Test fun smoothGradientIsReportedAsBlurry() {
        val w = 120; val h = 120
        val img = RgbImage(w, h, IntArray(w * h) { i -> val x = i % w; argb(80 + x / 2, 60 + x / 3, 50 + x / 4) })
        val report = ImageQualityChecker.check(img, roi(img))
        assertTrue(report.issues.toString(), QualityIssue.BLURRY in report.issues)
        assertTrue(report.statusCode.startsWith("REJECTED:"))
    }

    @Test fun glareInTargetIsRejected() {
        val base = scene()
        val r = roi(base)
        val px = base.pixels.copyOf()
        for (y in r.top until r.bottom) for (x in r.left until r.left + r.width / 3) px[y * base.width + x] = argb(255, 255, 255)
        val report = ImageQualityChecker.check(RgbImage(base.width, base.height, px), r)
        assertTrue(QualityIssue.GLARE_CLIPPING in report.issues)
    }

    @Test fun alignedComparablePairIsAcceptedAndEiMatchesFormula() {
        val ambient = scene()
        val flash = scene(add = 30)
        val result = DifferentialAnalyzer.analyze(ambient, flash, metaA, metaF, roi(ambient))
        assertTrue(result.rejectionReasons.toString(), result.accepted)
        assertEquals(0, result.shiftX); assertEquals(0, result.shiftY)
        assertEquals(120L, result.intervalMs)
        val ei = assertNotNull(result.erythemaIndexMean).let { result.erythemaIndexMean!! }
        // Recompute EI for one pixel by hand and check the mean is in a sensible band around it.
        val p = ambient.pixels[roi(ambient).top * ambient.width + roi(ambient).left]
        val f = flash.pixels[roi(ambient).top * ambient.width + roi(ambient).left]
        val dr = DifferentialAnalyzer.linear(f ushr 16 and 0xff) - DifferentialAnalyzer.linear(p ushr 16 and 0xff)
        val dg = DifferentialAnalyzer.linear(f ushr 8 and 0xff) - DifferentialAnalyzer.linear(p ushr 8 and 0xff)
        val single = ln(dr + DifferentialAnalyzer.EPSILON) - ln(dg + DifferentialAnalyzer.EPSILON)
        assertTrue("EI $ei vs pixel $single", kotlin.math.abs(ei - single) < .5)
        assertTrue(ei.isFinite())
    }

    @Test fun smallHandMovementIsDetectedAndCompensated() {
        val ambient = scene()
        // Typical hand shake between frames: a few pixels in x and y.
        for ((dx, dy) in listOf(6 to 0, -5 to 3, 3 to -4, 9 to 7)) {
            val flash = scene(dx = dx, dy = dy, add = 30)
            val result = DifferentialAnalyzer.analyze(ambient, flash, metaA, metaF, roi(ambient))
            assertTrue("shift ($dx,$dy): ${result.rejectionReasons}", result.accepted)
            assertEquals(dx, result.shiftX); assertEquals(dy, result.shiftY)
            assertTrue(result.motionCorrected)
            // Compensation must give the same EI as a perfectly still pair (scene is just translated).
            val still = DifferentialAnalyzer.analyze(ambient, scene(add = 30), metaA, metaF, roi(ambient))
            assertEquals(still.erythemaIndexMean!!, result.erythemaIndexMean!!, .05)
        }
    }

    @Test fun movementTooLargeToCorrectOnlyRejectsTheFlashComparison() {
        val ambient = scene()
        val result = DifferentialAnalyzer.analyze(ambient, scene(dx = 45, dy = 30, add = 30), metaA, metaF, roi(ambient))
        assertFalse(result.toString(), result.accepted)
        assertTrue(result.toString(), result.excessiveMotion)
        assertNull(result.erythemaIndexMean)
        assertFalse(QualityIssue.MOTION_BETWEEN_FRAMES.blocking)
    }

    @Test fun alignedFlashShiftsContentBackIntoPlace() {
        val a = scene()
        val moved = scene(dx = 4, dy = -3)
        val aligned = DifferentialAnalyzer.alignedFlash(moved, 4, -3)
        val r = roi(a)
        for (y in r.top until r.bottom) for (x in r.left until r.right) assertEquals(a.pixels[y * a.width + x], aligned.pixels[y * a.width + x])
    }

    @Test fun exposureChangeOrMissingMetadataRejectsDifferential() {
        val ambient = scene(); val flash = scene(add = 30)
        val changed = DifferentialAnalyzer.analyze(ambient, flash, metaA, metaF.copy(exposureTimeNs = 5_000_000), roi(ambient))
        assertFalse(changed.accepted)
        assertTrue(changed.rejectionReasons.any { "Exposure" in it })
        val iso = DifferentialAnalyzer.analyze(ambient, flash, metaA, metaF.copy(sensitivityIso = 400), roi(ambient))
        assertTrue(iso.rejectionReasons.any { "ISO" in it })
        val unknown = DifferentialAnalyzer.analyze(ambient, flash, metaA.copy(exposureTimeNs = null), metaF, roi(ambient))
        assertFalse(unknown.accepted)
    }

    @Test fun weakFlashNotFiredOrSlowPairIsRejected() {
        val ambient = scene()
        val weak = DifferentialAnalyzer.analyze(ambient, scene(add = 1), metaA, metaF, roi(ambient))
        assertFalse(weak.accepted)
        val notFired = DifferentialAnalyzer.analyze(ambient, scene(add = 30), metaA, metaF.copy(flashFired = false), roi(ambient))
        assertTrue(notFired.rejectionReasons.any { "did not fire" in it })
        val slow = DifferentialAnalyzer.analyze(ambient, scene(add = 30), metaA, metaF.copy(sensorTimestampNs = 3_000_000_000), roi(ambient))
        assertTrue(slow.rejectionReasons.any { "ms apart" in it })
    }

    @Test fun clippedFlashFrameIsRejected() {
        val ambient = scene()
        val result = DifferentialAnalyzer.analyze(ambient, solid(ambient.width, ambient.height, argb(255, 255, 255)), metaA, metaF, roi(ambient))
        assertFalse(result.accepted)
        assertTrue(result.flashClippedFraction > .9)
    }

    @Test fun differentSizedFramesAreRejected() {
        val result = DifferentialAnalyzer.analyze(scene(), scene(w = 100), metaA, metaF, roi(scene()))
        assertFalse(result.accepted)
    }

    @Test fun rotateClockwiseMapsCornersCorrectly() {
        val img = RgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val r90 = ImageTransforms.rotate(img, 90)
        assertEquals(2, r90.width); assertEquals(3, r90.height)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), r90.pixels.toList())
        assertEquals(listOf(6, 5, 4, 3, 2, 1), ImageTransforms.rotate(img, 180).pixels.toList())
        assertEquals(listOf(3, 6, 2, 5, 1, 4), ImageTransforms.rotate(img, 270).pixels.toList())
        assertEquals(img, ImageTransforms.rotate(img, 360))
    }

    @Test fun yuvConversionHandlesGreyAndPaddedStrides() {
        val w = 4; val h = 2
        val yStride = 6 // padded rows
        val y = ByteArray(yStride * h) { 128.toByte() }
        val u = ByteArray(8) { 128.toByte() }
        val v = ByteArray(8) { 128.toByte() }
        val grey = ImageTransforms.yuv420ToArgb(w, h, y, yStride, 1, u, v, 4, 2)
        assertTrue(grey.pixels.all { it == argb(128, 128, 128) })
        val red = ImageTransforms.yuv420ToArgb(w, h, ByteArray(yStride * h) { 76.toByte() }, yStride, 1,
            ByteArray(8) { 85.toByte() }, ByteArray(8) { 255.toByte() }, 4, 2)
        val p = red.pixels.first()
        assertTrue((p ushr 16 and 0xff) > 240 && (p ushr 8 and 0xff) < 15 && (p and 0xff) < 15)
    }

    @Test fun lockStrategyAndStreamSizeSelection() {
        assertEquals(LockStrategy.MANUAL_SENSOR, CameraCapabilities.choose(manualSensor = true, aeLock = true))
        assertEquals(LockStrategy.AE_AWB_LOCK, CameraCapabilities.choose(manualSensor = false, aeLock = true))
        assertEquals(LockStrategy.NONE, CameraCapabilities.choose(manualSensor = false, aeLock = false))
        val noFlash = CameraCapabilities("LEGACY", false, true, true, false, 90, 640, 480, null, null)
        assertFalse(noFlash.differentialSupported)
        assertTrue(noFlash.summary().any { "Fallback mode" in it })
        val noLock = noFlash.copy(flashAvailable = true, aeLockAvailable = false)
        assertFalse(noLock.differentialSupported)
        assertEquals(640 to 480, CameraCapabilities.chooseStreamSize(listOf(4000 to 3000, 1920 to 1080, 640 to 480, 320 to 240)))
        assertEquals(800 to 600, CameraCapabilities.chooseStreamSize(listOf(4000 to 3000, 1280 to 720, 800 to 600)))
        assertNull(CameraCapabilities.chooseStreamSize(emptyList()))
    }
}
