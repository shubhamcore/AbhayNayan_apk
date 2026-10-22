package `in`.abhaynayan.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Desktop-JVM timing of cf-v1 extraction + linear model on a 640x480 frame (the app's capture size).
 * NOT a phone measurement: no device or emulator was available. Printed to the test report's system-out.
 */
class EdgeBenchmarkTest {
    @Test fun featureExtractionAndInferenceTiming() {
        val rnd = Random(7)
        val w = 640; val h = 480
        val frame = RgbImage(w, h, IntArray(w * h) {
            (0xff shl 24) or ((180 + rnd.nextInt(40)) shl 16) or ((90 + rnd.nextInt(40)) shl 8) or (90 + rnd.nextInt(40))
        })
        val roi = OpticalPipeline.crop(frame, OpticalPipeline.roiBounds(w, h, NormalizedRect.MANUAL_CENTER_GUIDE))
        val n = ColourFeatures.NAMES.size
        val model = LinearColourArtifact("bench", "0", false, DoubleArray(n) { .5 }, DoubleArray(n) { 1.0 }, DoubleArray(n) { .1 }, 12.0, null, null, null)
        repeat(30) { model.raw(ColourFeatures.extract(roi).vector) }  // JIT warm-up
        val times = (0 until 200).map {
            val t = System.nanoTime()
            model.raw(ColourFeatures.extract(roi).vector)
            (System.nanoTime() - t) / 1e6
        }.sorted()
        val full = (0 until 50).map {
            val t = System.nanoTime(); ColourFeatures.extract(frame); (System.nanoTime() - t) / 1e6
        }.sorted()
        println("EDGE_BENCH roi=${roi.width}x${roi.height} median_ms=%.3f p90_ms=%.3f | full_frame_640x480 median_ms=%.3f | jvm=%s %s".format(
            times[100], times[180], full[25], System.getProperty("java.vm.name"), System.getProperty("java.version")))
        assertTrue(times[100] < 1000.0)
    }
}
