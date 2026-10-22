package `in`.abhaynayan.app.core

import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kotlin port of the colour-feature contract `cf-v1` (AbhayNayan_web backend/app/ml/features.py).
 * Line-for-line equivalent so on-device and server models see the same numbers; verified against the
 * synthetic golden vectors in src/test/resources/ml_golden. Any change here must change the version.
 *
 * 1. Subsample: step = ceil(max(H, W) / 256); keep pixels at (y * step, x * step).
 * 2. Valid pixels: alpha >= 128, max(R,G,B) < 250 (not clipped) and max(R,G,B) >= 20 (not dark/masked).
 * 3. 25 statistics over valid pixels with channels in [0, 1]; percentiles use sorted[floor(q * (n - 1))].
 */
object ColourFeatures {
    const val VERSION = "cf-v1"
    const val MAX_SIDE = 256
    const val ALPHA_MIN = 128
    const val CLIP_LEVEL = 250
    const val DARK_LEVEL = 20
    const val MIN_VALID_PIXELS = 100
    private const val EPS = 1e-4

    val NAMES: List<String> = listOf(
        "mean_r", "mean_g", "mean_b", "std_r", "std_g", "std_b", "chroma_r", "chroma_g", "chroma_b",
        "median_r", "median_g", "median_b", "p10_r", "p90_r", "p10_g", "p90_g", "mean_s", "mean_v",
        "lab_l", "lab_a", "lab_b", "p10_lab_a", "p90_lab_a", "erythema_index", "yellow_index",
    )

    data class Result(val vector: DoubleArray, val validPixels: Int, val sampledPixels: Int, val step: Int)

    class FeatureException(message: String) : IllegalArgumentException(message)

    fun step(width: Int, height: Int): Int = max(1, (max(width, height) + MAX_SIDE - 1) / MAX_SIDE)

    fun extract(image: RgbImage): Result {
        val step = step(image.width, image.height)
        val rs = ArrayList<Double>(); val gs = ArrayList<Double>(); val bs = ArrayList<Double>()
        var sampled = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                sampled++
                val p = image.pixels[y * image.width + x]
                val a = (p ushr 24) and 0xff
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val mx = max(r, max(g, b))
                if (a >= ALPHA_MIN && mx < CLIP_LEVEL && mx >= DARK_LEVEL) {
                    rs += r / 255.0; gs += g / 255.0; bs += b / 255.0
                }
                x += step
            }
            y += step
        }
        val n = rs.size
        if (n < MIN_VALID_PIXELS) throw FeatureException("Only $n usable pixels after masking; at least $MIN_VALID_PIXELS are required.")
        val r = rs.toDoubleArray(); val g = gs.toDoubleArray(); val b = bs.toDoubleArray()
        val meanR = r.average(); val meanG = g.average(); val meanB = b.average()
        val sum = max(meanR + meanG + meanB, EPS)
        val sat = DoubleArray(n); val v = DoubleArray(n)
        val labL = DoubleArray(n); val labA = DoubleArray(n); val labB = DoubleArray(n)
        for (i in 0 until n) {
            val hi = max(r[i], max(g[i], b[i])); val lo = min(r[i], min(g[i], b[i]))
            sat[i] = if (hi > 0) (hi - lo) / max(hi, EPS) else 0.0
            v[i] = hi
            val lab = rgbToLab(r[i], g[i], b[i])
            labL[i] = lab[0]; labA[i] = lab[1]; labB[i] = lab[2]
        }
        val vector = doubleArrayOf(
            meanR, meanG, meanB,
            std(r, meanR), std(g, meanG), std(b, meanB),
            meanR / sum, meanG / sum, meanB / sum,
            lowerPercentile(r, .5), lowerPercentile(g, .5), lowerPercentile(b, .5),
            lowerPercentile(r, .1), lowerPercentile(r, .9), lowerPercentile(g, .1), lowerPercentile(g, .9),
            sat.average(), v.average(),
            labL.average(), labA.average(), labB.average(),
            lowerPercentile(labA, .1), lowerPercentile(labA, .9),
            ln(meanR + EPS) - ln(meanG + EPS),
            ln((meanR + meanG) / 2 + EPS) - ln(meanB + EPS),
        )
        if (vector.any { !it.isFinite() }) throw FeatureException("Feature computation produced a non-finite value.")
        return Result(vector, n, sampled, step)
    }

    private fun std(values: DoubleArray, mean: Double): Double = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)

    private fun lowerPercentile(values: DoubleArray, q: Double): Double {
        val sorted = values.copyOf().also { it.sort() }
        return sorted[floor(q * (sorted.size - 1)).toInt()]
    }

    private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun labF(t: Double): Double {
        val d = 6.0 / 29.0
        return if (t > d * d * d) cbrt(t) else t / (3 * d * d) + 4.0 / 29.0
    }

    /** sRGB (D65) in [0,1] -> CIE L*a*b*. */
    fun rgbToLab(r: Double, g: Double, b: Double): DoubleArray {
        val lr = linear(r); val lg = linear(g); val lb = linear(b)
        val x = lr * 0.4124564 + lg * 0.3575761 + lb * 0.1804375
        val y = lr * 0.2126729 + lg * 0.7151522 + lb * 0.0721750
        val z = lr * 0.0193339 + lg * 0.1191920 + lb * 0.9503041
        val fx = labF(x / 0.95047); val fy = labF(y / 1.0); val fz = labF(z / 1.08883)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}

/** True when some pixel is transparent, i.e. the image is a pre-segmented crop whose mask defines the ROI. */
fun RgbImage.hasTransparency(): Boolean = pixels.any { ((it ushr 24) and 0xff) < ColourFeatures.ALPHA_MIN }
