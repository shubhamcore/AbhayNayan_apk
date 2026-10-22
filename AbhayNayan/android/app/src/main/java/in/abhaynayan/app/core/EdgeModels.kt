package `in`.abhaynayan.app.core

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.exp

/**
 * On-device research models over [ColourFeatures] (`cf-v1`). Pure Kotlin arithmetic: no inference
 * runtime, no network, no image leaves the phone. Artifacts are produced by AbhayNayan_web
 * scripts/ml/train_hb.py and train_njn.py and bundled as app assets (see docs/edge_ai.md):
 *
 *   assets/models/hemoglobin/{metadata.json, model.json}
 *   assets/models/jaundice_class/model.json      (NJN dataset class score; never bilirubin)
 *
 * Loading fails closed: a missing, corrupt, tampered or incompatible artifact yields no model.
 */
data class LinearColourArtifact(
    val modelId: String,
    val version: String,
    val logistic: Boolean,
    val mean: DoubleArray,
    val scale: DoubleArray,
    val coefficients: DoubleArray,
    val intercept: Double,
    val featureMin: DoubleArray?,
    val featureMax: DoubleArray?,
    val operatingThreshold: Double?,
) {
    fun raw(features: DoubleArray): Double {
        require(features.size == coefficients.size) { "Feature vector has ${features.size} values; model expects ${coefficients.size}." }
        var z = intercept
        for (i in features.indices) z += (features[i] - mean[i]) / scale[i] * coefficients[i]
        return if (logistic) 1.0 / (1.0 + exp(-z)) else z
    }

    /** Features more than 10 % of the training range outside it. */
    fun outOfDistribution(features: DoubleArray): List<String> {
        val lo = featureMin ?: return emptyList()
        val hi = featureMax ?: return emptyList()
        return features.indices.filter {
            val pad = (hi[it] - lo[it]) * 0.10
            features[it] < lo[it] - pad || features[it] > hi[it] + pad
        }.map { ColourFeatures.NAMES[it] }
    }

    companion object {
        const val TYPE_REGRESSOR = "LINEAR_COLOUR_REGRESSOR"
        const val TYPE_CLASSIFIER = "LOGISTIC_COLOUR_CLASSIFIER"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Parses and validates; returns null plus problems rather than a partially usable model. */
        fun parse(json: String, expectedType: String, expectedSha256: String? = null): Pair<LinearColourArtifact?, List<String>> {
            if (expectedSha256 != null && sha256(json) != expectedSha256) return null to listOf("Model file does not match its checksum.")
            val root = try { JSONObject(json) } catch (_: JSONException) { return null to listOf("Model file is not valid JSON.") }
            val problems = mutableListOf<String>()
            fun doubles(key: String, required: Boolean = true): DoubleArray? {
                val a = root.optJSONArray(key) ?: run { if (required) problems += "Missing $key."; return null }
                return DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }
            }
            val type = root.optString("artifact_type")
            if (type != expectedType) problems += "Artifact type $type is not $expectedType."
            if (root.optString("feature_version") != ColourFeatures.VERSION) problems += "Feature contract is not ${ColourFeatures.VERSION}."
            val names = root.optJSONArray("feature_names")
            if (names == null || (0 until names.length()).map { names.optString(it) } != ColourFeatures.NAMES) problems += "Feature names do not match."
            if (root.optString("validation_status") != ModelArtifactMetadata.STATUS_RESEARCH_ONLY) problems += "Only research-only models are supported."
            val mean = doubles("mean"); val scale = doubles("scale"); val coef = doubles("coefficients")
            val n = ColourFeatures.NAMES.size
            if (listOfNotNull(mean, scale, coef).any { it.size != n || it.any { v -> !v.isFinite() } }) problems += "Parameter arrays are malformed."
            if (scale != null && scale.any { it <= 0 }) problems += "Scale values must be positive."
            val intercept = root.optDouble("intercept", Double.NaN)
            if (!intercept.isFinite()) problems += "Missing intercept."
            val training = root.optJSONObject("training")
            fun range(key: String) = training?.optJSONArray(key)?.let { a -> DoubleArray(a.length()) { a.optDouble(it) } }?.takeIf { it.size == n }
            val threshold = root.optDouble("operating_threshold", Double.NaN).takeIf { it.isFinite() }
            if (type == TYPE_CLASSIFIER && (threshold == null || threshold !in 0.0..1.0)) problems += "Classifier needs an operating_threshold in [0, 1]."
            if (problems.isNotEmpty()) return null to problems
            return LinearColourArtifact(
                root.optString("model_id"), root.optString("version"), type == TYPE_CLASSIFIER,
                mean!!, scale!!, coef!!, intercept, range("feature_min"), range("feature_max"), threshold,
            ) to emptyList()
        }
    }
}

/** Reads bundled text assets; returns null when absent. Abstracted so tests need no Android context. */
fun interface AssetSource {
    fun read(path: String): String?
}

/** Research-only hemoglobin regressor. Throws instead of guessing; the caller validates the range. */
class LinearColourHemoglobinModel(private val status: ModelStatus, private val artifact: LinearColourArtifact) : HemoglobinModel {
    init {
        require(status.biomarker == Biomarker.HEMOGLOBIN) { "Hemoglobin slot requires a hemoglobin status." }
        require(!artifact.logistic) { "A classifier cannot fill the hemoglobin slot." }
    }

    var lastOutOfDistribution: List<String> = emptyList()
        private set

    override fun status() = status

    override fun estimateGramsPerDecilitre(roi: RgbImage): Double {
        if (!status.mode.producesEstimate) throw ModelUnavailableException(status.reasons.joinToString(" "))
        val features = ColourFeatures.extract(roi).vector
        lastOutOfDistribution = artifact.outOfDistribution(features)
        return artifact.raw(features)
    }
}

/** Score for the NJN dataset class "jaundice". NOT bilirubin and never placed in the bilirubin slot. */
data class JaundiceClassScore(
    val score: Double,
    val threshold: Double,
    val modelVersion: String,
    val outOfDistribution: List<String>,
) {
    val jaundiceLike: Boolean get() = score >= threshold
}

class JaundiceClassScorer(private val artifact: LinearColourArtifact) {
    init { require(artifact.logistic) { "Jaundice class scorer requires a logistic artifact." } }

    fun score(image: RgbImage): JaundiceClassScore {
        val features = ColourFeatures.extract(image).vector
        val s = artifact.raw(features)
        check(s.isFinite() && s in 0.0..1.0) { "Classifier returned an invalid score." }
        return JaundiceClassScore(s, artifact.operatingThreshold!!, "${artifact.modelId} ${artifact.version}", artifact.outOfDistribution(features))
    }
}

/** Loads the bundled edge models and resolves their status. Never throws. */
object EdgeModelLoader {
    const val HB_METADATA = "models/hemoglobin/metadata.json"
    const val HB_MODEL = "models/hemoglobin/model.json"
    const val JAUNDICE_CLASS_MODEL = "models/jaundice_class/model.json"

    data class Loaded(
        val hemoglobin: HemoglobinModel,
        val jaundiceClass: JaundiceClassScorer?,
        val jaundiceClassProblems: List<String>,
    )

    fun load(assets: AssetSource, experimentalOptIn: Boolean): Loaded {
        val metadataJson = safeRead(assets, HB_METADATA)
        val modelJson = safeRead(assets, HB_MODEL)
        val (metadata, _) = metadataJson?.let { ModelArtifactMetadata.parse(it) } ?: (null to emptyList())
        val (artifact, artifactProblems) = modelJson?.let {
            LinearColourArtifact.parse(it, LinearColourArtifact.TYPE_REGRESSOR, metadata?.artifactSha256)
        } ?: (null to emptyList())
        var status = ModelRegistry.status(
            Biomarker.HEMOGLOBIN, artifactPresent = artifact != null, metadataJson = metadataJson, experimentalOptIn = experimentalOptIn,
        )
        if (modelJson != null && artifact == null) {
            status = status.copy(mode = ResultMode.DISABLED_NO_VALID_MODEL, reasons = status.reasons + artifactProblems.map { "Model file rejected: $it" })
        }
        val hemoglobin: HemoglobinModel = if (artifact != null && status.mode.producesEstimate) {
            LinearColourHemoglobinModel(status, artifact)
        } else {
            UnavailableHemoglobinModel(status)
        }
        val (jaundice, jProblems) = when {
            !experimentalOptIn -> null to listOf("Experimental research models are off.")
            else -> safeRead(assets, JAUNDICE_CLASS_MODEL)?.let { LinearColourArtifact.parse(it, LinearColourArtifact.TYPE_CLASSIFIER) }
                ?: (null to listOf("No jaundice-class research model is bundled."))
        }
        return Loaded(hemoglobin, jaundice?.let { JaundiceClassScorer(it) }, jProblems)
    }

    private fun safeRead(assets: AssetSource, path: String): String? = try { assets.read(path) } catch (_: Exception) { null }
}
