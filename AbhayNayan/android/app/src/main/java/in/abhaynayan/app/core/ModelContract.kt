package `in`.abhaynayan.app.core

import org.json.JSONException
import org.json.JSONObject

/** Result provenance shown on every result, record, referral, and export. */
enum class ResultMode(val label: String, val description: String) {
    DEMO_SYNTHETIC(
        "Demo · synthetic",
        "A fixed synthetic fixture chosen for demonstration. It is not calculated from the camera or the person.",
    ),
    EXPERIMENTAL_RESEARCH_ONLY(
        "Experimental · research only",
        "Output of an unvalidated research model. Not for screening, referral, or treatment decisions.",
    ),
    VALIDATED_FOR_INTENDED_USE(
        "Validated for intended use",
        "Output of a model with documented validation and authorization for this intended use.",
    ),
    DISABLED_NO_VALID_MODEL(
        "No valid model · estimate disabled",
        "No suitable model is available, so no biomarker estimate was produced.",
    );

    val producesEstimate: Boolean get() = this != DISABLED_NO_VALID_MODEL
}

/** Kept deliberately separate: a hemoglobin model can never be used as a bilirubin model. */
enum class Biomarker(val displayName: String, val unit: String) {
    HEMOGLOBIN("Hemoglobin", "g/dL"),
    BILIRUBIN("Bilirubin", "mg/dL");

    companion object {
        fun fromWire(value: String): Biomarker? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Model card fields that must accompany any on-device artifact (assets/models/<biomarker>/metadata.json).
 * Parsing is strict: an incomplete card keeps inference disabled.
 *
 * Two input kinds are accepted: `tensor` (an image tensor, e.g. a future TFLite model: input_shape,
 * input_dtype and channel_order are required) and `colour_features` (the cf-v1 feature vector of
 * [ColourFeatures]: feature_version, feature_count and artifact_sha256 of model.json are required).
 */
data class ModelArtifactMetadata(
    val name: String,
    val version: String,
    val biomarker: Biomarker,
    val inputShape: List<Int>,
    val inputDType: String,
    val channelOrder: String,
    val normalization: String,
    val outputShape: List<Int>,
    val outputUnit: String,
    val runtime: String,
    val trainingLabelSource: String,
    val subjects: Int,
    val samples: Int,
    val subjectLevelSplit: Boolean,
    val validationStatus: String,
    val approvalReference: String?,
    val redistributionApproved: Boolean,
    val limitations: List<String>,
    val inputKind: String = INPUT_TENSOR,
    val featureVersion: String? = null,
    val artifactSha256: String? = null,
) {
    companion object {
        const val INPUT_TENSOR = "tensor"
        const val INPUT_COLOUR_FEATURES = "colour_features"
        const val STATUS_RESEARCH_ONLY = "RESEARCH_ONLY"
        const val STATUS_VALIDATED = "VALIDATED"
        private val DTYPES = setOf("float32", "uint8", "int8")
        private val CHANNEL_ORDERS = setOf("RGB", "BGR")

        /** Returns the parsed card or a list of human-readable problems. */
        fun parse(json: String): Pair<ModelArtifactMetadata?, List<String>> {
            val problems = mutableListOf<String>()
            val root = try { JSONObject(json) } catch (_: JSONException) { return null to listOf("Model card is not valid JSON.") }
            fun str(key: String): String = root.optString(key, "").trim().also { if (it.isEmpty()) problems += "Missing $key." }
            fun ints(key: String): List<Int> {
                val array = root.optJSONArray(key) ?: run { problems += "Missing $key."; return emptyList() }
                return (0 until array.length()).map { array.optInt(it, -2) }
            }
            val name = str("name")
            val version = str("version")
            val biomarker = Biomarker.fromWire(str("biomarker")).also { if (it == null) problems += "Unknown biomarker." }
            val inputKind = root.optString("input_kind", INPUT_TENSOR)
            val tensor = inputKind == INPUT_TENSOR
            if (!tensor && inputKind != INPUT_COLOUR_FEATURES) problems += "Unknown input_kind $inputKind."
            val inputShape = if (tensor) ints("input_shape") else emptyList()
            val dtype = if (tensor) str("input_dtype") else ""
            val channelOrder = if (tensor) str("channel_order") else "RGB"
            val featureVersion = if (!tensor) str("feature_version") else null
            val artifactSha = if (!tensor) str("artifact_sha256") else null
            if (!tensor && featureVersion.orEmpty().isNotEmpty() && featureVersion != ColourFeatures.VERSION) problems += "Unsupported feature_version $featureVersion."
            if (!tensor && root.optInt("feature_count", -1) != ColourFeatures.NAMES.size) problems += "feature_count must be ${ColourFeatures.NAMES.size}."
            if (!tensor && artifactSha.orEmpty().isNotEmpty() && !Regex("[0-9a-f]{64}").matches(artifactSha!!)) problems += "artifact_sha256 must be 64 hex characters."
            val normalization = str("normalization")
            val outputShape = ints("output_shape")
            val outputUnit = str("output_unit")
            val runtime = str("runtime")
            val labelSource = str("training_label_source")
            val subjects = root.optInt("subjects", 0)
            val samples = root.optInt("samples", 0)
            val status = str("validation_status")
            if (tensor && (inputShape.size != 4 || inputShape.drop(1).any { it <= 0 })) problems += "input_shape must be [batch, height, width, channels]."
            if (tensor && inputShape.size == 4 && inputShape[3] != 3) problems += "Only 3-channel colour input is supported."
            if (tensor && dtype.isNotEmpty() && dtype !in DTYPES) problems += "Unsupported input_dtype $dtype."
            if (tensor && channelOrder.isNotEmpty() && channelOrder !in CHANNEL_ORDERS) problems += "Unsupported channel_order $channelOrder."
            if (outputShape.isEmpty() || outputShape.any { it <= 0 && it != -1 } || outputShape.fold(1) { a, b -> a * maxOf(b, 1) } != 1) {
                problems += "Output must be a single scalar estimate."
            }
            if (biomarker != null && outputUnit.isNotEmpty() && outputUnit != biomarker.unit) problems += "Output unit $outputUnit does not match ${biomarker.unit}."
            if (subjects <= 0 || samples <= 0) problems += "Subject and sample counts are required."
            if (status.isNotEmpty() && status != STATUS_RESEARCH_ONLY && status != STATUS_VALIDATED) problems += "Unknown validation_status $status."
            if (!root.optBoolean("subject_level_split", false)) problems += "Evaluation must use a subject-level train/test split."
            val limitations = root.optJSONArray("limitations")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (problems.isNotEmpty() || biomarker == null) return null to problems
            return ModelArtifactMetadata(
                name, version, biomarker, inputShape, dtype, channelOrder, normalization, outputShape, outputUnit,
                runtime, labelSource, subjects, samples, true, status,
                root.optString("approval_reference", "").ifBlank { null },
                root.optBoolean("redistribution_approved", false), limitations, inputKind, featureVersion, artifactSha,
            ) to emptyList()
        }
    }
}

/** Evidence that a validated mode was authorized; deliberately absent in this build. */
data class ValidationAuthorization(val approvalReference: String)

data class ModelStatus(
    val biomarker: Biomarker,
    val mode: ResultMode,
    val modelName: String,
    val version: String?,
    val reasons: List<String>,
)

object ModelModeResolver {
    /**
     * Decides the result mode for a biomarker. Fails closed: any missing artifact, runtime, card field,
     * biomarker mismatch, or authorization keeps estimates disabled.
     */
    fun resolve(
        expected: Biomarker,
        metadata: ModelArtifactMetadata?,
        metadataProblems: List<String>,
        artifactPresent: Boolean,
        runtimeAvailable: Boolean,
        experimentalOptIn: Boolean,
        validatedModeAllowedByBuild: Boolean,
        authorization: ValidationAuthorization?,
    ): ModelStatus {
        val reasons = mutableListOf<String>()
        if (!artifactPresent) reasons += "No ${expected.displayName.lowercase()} model artifact is bundled."
        if (!runtimeAvailable) reasons += "This build has no on-device runtime for this model format."
        if (metadata == null) reasons += if (metadataProblems.isEmpty()) "No model card (metadata.json) is bundled." else "Model card rejected: ${metadataProblems.joinToString(" ")}"
        if (metadata != null && metadata.biomarker != expected) reasons += "Artifact is a ${metadata.biomarker.displayName} model; it cannot be used for ${expected.displayName}."
        val name = metadata?.name ?: "none"
        if (reasons.isNotEmpty()) return ModelStatus(expected, ResultMode.DISABLED_NO_VALID_MODEL, name, metadata?.version, reasons)
        requireNotNull(metadata)
        val validated = metadata.validationStatus == ModelArtifactMetadata.STATUS_VALIDATED &&
            validatedModeAllowedByBuild && metadata.redistributionApproved &&
            authorization != null && authorization.approvalReference == metadata.approvalReference
        if (validated) return ModelStatus(expected, ResultMode.VALIDATED_FOR_INTENDED_USE, name, metadata.version, emptyList())
        if (experimentalOptIn) {
            return ModelStatus(expected, ResultMode.EXPERIMENTAL_RESEARCH_ONLY, name, metadata.version,
                listOf("Research-only model enabled by a developer setting. Not validated."))
        }
        return ModelStatus(expected, ResultMode.DISABLED_NO_VALID_MODEL, name, metadata.version,
            listOf("A research-only model is present but experimental mode is off."))
    }
}

/** A candidate model that was inspected; recorded so the app can show provenance honestly. */
data class ModelCandidate(
    val name: String,
    val biomarker: Biomarker,
    val kind: String,
    val inputs: String,
    val evidence: String,
    val artifactInApk: Boolean,
    val whyNotDeployed: String,
)

object ModelRegistry {
    /** Candidates verified during the October 2026 audit (see docs/ANDROID_ML_STATUS.md). */
    val inspectedCandidates: List<ModelCandidate> = listOf(
        ModelCandidate(
            name = "anemia_color_baseline v0.1 (Ridge regression)",
            biomarker = Biomarker.HEMOGLOBIN,
            kind = "Standardized Ridge regression, 3 inputs",
            inputs = "Precomputed %Red / %Green / %Blue pixel columns from Anemia_Dataset.csv",
            evidence = "104 rows / 104 subjects, 5-fold GroupKFold: MAE 1.5041 g/dL, RMSE 1.9392 g/dL, R² 0.3987 (re-run and matched). Internal CV only.",
            artifactInApk = false,
            whyNotDeployed = "How the CSV's pixel percentages were computed (source images, ROI, segmentation, device) is undocumented, so the phone cannot reproduce the training features. Error is too large for patient-level use.",
        ),
        ModelCandidate(
            name = "hemovision_baseline (MobileNetV3-Small regression)",
            biomarker = Biomarker.HEMOGLOBIN,
            kind = "Keras MobileNetV3-Small, 224×224 RGB, frozen ImageNet backbone",
            inputs = "Pre-segmented palpebral conjunctiva PNGs (Eyes-Defy-Anemia, India + Italy)",
            evidence = "288 images / 145 patients, patient-level split 101/22/22. Test MAE 1.838 g/dL, RMSE 2.190 g/dL, R² −0.197 (worse than predicting the mean).",
            artifactInApk = false,
            whyNotDeployed = "Negative test R²; saved only as an ephemeral Kaggle .keras file with no TFLite export or model card; trained on expert-segmented crops that the phone does not produce.",
        ),
        ModelCandidate(
            name = "hb-ridge-cf 1.0.0-research (colour-feature Ridge regression)",
            biomarker = Biomarker.HEMOGLOBIN,
            kind = "Ridge regression on 25 cf-v1 colour features; pure Kotlin, no runtime",
            inputs = "Expert-segmented palpebral conjunctiva crops (Eyes-defy-anemia, one standardized capture device)",
            evidence = "163 training patients; held-out test 54 patients: MAE 1.30 g/dL (95% CI 1.04–1.58), R² 0.51. " +
                "On 110 children from 3 unseen Ghanaian hospitals (CP-AnemiC): MAE 2.07 g/dL, R² −0.64 (worse than the mean).",
            artifactInApk = false,
            whyNotDeployed = "Research-only: needs a locally built research APK plus the developer opt-in. It fails on a different " +
                "population/device, over-estimates low Hb (7–10 g/dL by ≈2.7 g/dL), and phone crops are not the segmented input it was trained on.",
        ),
        ModelCandidate(
            name = "No bilirubin model",
            biomarker = Biomarker.BILIRUBIN,
            kind = "—",
            inputs = "478 forehead/sternum JPEGs (Neonatal Jaundice Assessment dataset)",
            evidence = "No bilirubin label table exists in the archive; images are skin, not sclera. NJN has only jaundice/normal folder classes.",
            artifactInApk = false,
            whyNotDeployed = "Without ground-truth bilirubin labels no model can be trained or evaluated. Labels must not be invented.",
        ),
    )

    /** Runtimes this build can execute. There is no tensor runtime (TFLite/LiteRT); validated mode is never permitted. */
    val SUPPORTED_RUNTIMES: Set<String> = setOf("kotlin-linear-cf-v1")
    const val VALIDATED_MODE_ALLOWED = false

    fun status(
        biomarker: Biomarker,
        artifactPresent: Boolean = false,
        metadataJson: String? = null,
        experimentalOptIn: Boolean = false,
    ): ModelStatus {
        val (metadata, problems) = metadataJson?.let { ModelArtifactMetadata.parse(it) } ?: (null to emptyList())
        return ModelModeResolver.resolve(
            expected = biomarker,
            metadata = metadata,
            metadataProblems = problems,
            artifactPresent = artifactPresent,
            runtimeAvailable = metadata?.runtime in SUPPORTED_RUNTIMES,
            experimentalOptIn = experimentalOptIn,
            validatedModeAllowedByBuild = VALIDATED_MODE_ALLOWED,
            authorization = null,
        )
    }
}

/** Separate typed entry points so call sites cannot swap biomarkers. */
interface HemoglobinModel {
    fun status(): ModelStatus
    /** Returns g/dL. Implementations must throw rather than guess when inputs are invalid. */
    fun estimateGramsPerDecilitre(roi: RgbImage): Double
}

interface BilirubinModel {
    fun status(): ModelStatus
    /** Returns mg/dL. Implementations must throw rather than guess when inputs are invalid. */
    fun estimateMilligramsPerDecilitre(roi: RgbImage, ageHours: Int): Double
}

class UnavailableHemoglobinModel(private val status: ModelStatus = ModelRegistry.status(Biomarker.HEMOGLOBIN)) : HemoglobinModel {
    init { require(status.biomarker == Biomarker.HEMOGLOBIN) { "Hemoglobin slot requires a hemoglobin status." } }
    override fun status() = status
    override fun estimateGramsPerDecilitre(roi: RgbImage): Double =
        throw ModelUnavailableException(status.reasons.joinToString(" "))
}

class UnavailableBilirubinModel(private val status: ModelStatus = ModelRegistry.status(Biomarker.BILIRUBIN)) : BilirubinModel {
    init { require(status.biomarker == Biomarker.BILIRUBIN) { "Bilirubin slot requires a bilirubin status." } }
    override fun status() = status
    override fun estimateMilligramsPerDecilitre(roi: RgbImage, ageHours: Int): Double =
        throw ModelUnavailableException(status.reasons.joinToString(" "))
}

class ModelUnavailableException(message: String) : IllegalStateException(message)

/** Validates a raw model output before it may be displayed; never clamps a bad value into range. */
object ModelOutputValidator {
    private val plausible = mapOf(Biomarker.HEMOGLOBIN to 2.0..25.0, Biomarker.BILIRUBIN to 0.0..40.0)

    fun validate(biomarker: Biomarker, raw: FloatArray): Double {
        require(raw.size == 1) { "Expected one output value, got ${raw.size}." }
        val value = raw[0].toDouble()
        require(value.isFinite()) { "Model output is not a finite number." }
        require(value in plausible.getValue(biomarker)) { "Model output is outside the physiological range; discarded." }
        return value
    }
}
