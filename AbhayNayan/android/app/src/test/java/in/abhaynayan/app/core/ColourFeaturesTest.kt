package `in`.abhaynayan.app.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Checks the Kotlin cf-v1 port against golden vectors written by the Python reference implementation
 * (AbhayNayan_web scripts/ml/export_golden_vectors.py). The images are synthetic; no patient pixels.
 */
class ColourFeaturesTest {
    companion object {
        val golden: JSONObject = JSONObject(resource("golden_cf_v1.json").readText())

        fun resource(name: String) = requireNotNull(ColourFeaturesTest::class.java.classLoader!!.getResource("ml_golden/$name")) { "missing ml_golden/$name" }

        /** PNG -> RgbImage (ARGB, non-premultiplied, as Bitmap.getPixels gives the app). */
        fun load(file: String): RgbImage = decodePng(resource(file).readBytes())

        /**
         * Minimal decoder for the golden PNGs (8-bit, non-interlaced RGB or RGBA, filters 0-4). The local JVM
         * test classpath is android.jar, which has neither javax.imageio nor a working BitmapFactory.
         */
        fun decodePng(bytes: ByteArray): RgbImage {
            fun u32(i: Int) = ((bytes[i].toInt() and 0xff) shl 24) or ((bytes[i + 1].toInt() and 0xff) shl 16) or
                ((bytes[i + 2].toInt() and 0xff) shl 8) or (bytes[i + 3].toInt() and 0xff)
            var i = 8
            var w = 0; var h = 0; var channels = 0
            val idat = ByteArrayOutputStream()
            while (i < bytes.size) {
                val len = u32(i); val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
                when (type) {
                    "IHDR" -> {
                        w = u32(i + 8); h = u32(i + 12)
                        require(bytes[i + 16].toInt() == 8 && bytes[i + 20].toInt() == 0) { "only 8-bit non-interlaced PNG" }
                        channels = when (bytes[i + 17].toInt()) { 2 -> 3; 6 -> 4; else -> error("only RGB/RGBA PNG") }
                    }
                    "IDAT" -> idat.write(bytes, i + 8, len)
                }
                i += 12 + len
            }
            val inflater = Inflater().apply { setInput(idat.toByteArray()) }
            val stride = w * channels
            val raw = ByteArray((stride + 1) * h)
            var off = 0
            while (off < raw.size) off += inflater.inflate(raw, off, raw.size - off).also { require(it > 0 || !inflater.needsInput()) { "truncated PNG" } }
            val out = IntArray(stride * h)
            for (y in 0 until h) {
                val filter = raw[y * (stride + 1)].toInt()
                for (x in 0 until stride) {
                    val v = raw[y * (stride + 1) + 1 + x].toInt() and 0xff
                    val a = if (x >= channels) out[y * stride + x - channels] else 0
                    val b = if (y > 0) out[(y - 1) * stride + x] else 0
                    val c = if (x >= channels && y > 0) out[(y - 1) * stride + x - channels] else 0
                    val pred = when (filter) {
                        0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2
                        4 -> { val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                        else -> error("bad filter $filter")
                    }
                    out[y * stride + x] = (v + pred) and 0xff
                }
            }
            val px = IntArray(w * h) { p ->
                val o = p * channels
                val alpha = if (channels == 4) out[o + 3] else 255
                (alpha shl 24) or (out[o] shl 16) or (out[o + 1] shl 8) or out[o + 2]
            }
            return RgbImage(w, h, px)
        }

        fun cases(): List<JSONObject> = golden.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }

    @Test fun featureNamesMatchPythonContract() {
        val names = golden.getJSONArray("feature_names")
        assertEquals((0 until names.length()).map { names.getString(it) }, ColourFeatures.NAMES)
        assertEquals(ColourFeatures.VERSION, golden.getString("feature_version"))
    }

    @Test fun goldenFeatureVectorsMatchPython() {
        val tol = golden.getJSONObject("tolerance").getDouble("features_abs")
        var checked = 0
        for (c in cases().filter { it.has("features") }) {
            val r = ColourFeatures.extract(load(c.getString("file")))
            assertEquals(c.getString("name"), c.getInt("step"), r.step)
            assertEquals(c.getString("name"), c.getInt("valid_pixels"), r.validPixels)
            val expected = c.getJSONArray("features").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            assertArrayEquals(c.getString("name"), expected, r.vector, tol)
            checked++
        }
        assertEquals(5, checked)
    }

    @Test fun goldenDarkImageIsRejected() {
        val dark = cases().single { it.getString("name") == "too_dark_rgb" }
        try {
            ColourFeatures.extract(load(dark.getString("file")))
            fail("expected rejection")
        } catch (_: ColourFeatures.FeatureException) { }
    }

    @Test fun transparentBackgroundIsIgnoredAndDetected() {
        val masked = load("pink_masked.png")
        assertTrue(masked.hasTransparency())
        val greenBehindMask = masked.pixels.map { if (((it ushr 24) and 0xff) < 128) 0x0000ff00 else it }.toIntArray()
        assertArrayEquals(ColourFeatures.extract(masked).vector, ColourFeatures.extract(RgbImage(masked.width, masked.height, greenBehindMask)).vector, 0.0)
        assertFalse(load("skin_rgb_large.png").hasTransparency())
    }

    @Test fun labReferencePoints() {
        assertArrayEquals(doubleArrayOf(100.0, 0.0, 0.0), ColourFeatures.rgbToLab(1.0, 1.0, 1.0), 0.01)
        assertArrayEquals(doubleArrayOf(53.24, 80.09, 67.20), ColourFeatures.rgbToLab(1.0, 0.0, 0.0), 0.05)
    }

    @Test fun subsampleStepMatchesContract() {
        assertEquals(1, ColourFeatures.step(256, 100))
        assertEquals(2, ColourFeatures.step(257, 100))
        assertEquals(4, ColourFeatures.step(900, 420))
    }
}
