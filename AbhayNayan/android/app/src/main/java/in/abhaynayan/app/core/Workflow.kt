package `in`.abhaynayan.app.core

import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import java.time.Instant
import java.util.Locale

/** The guided stages shared by both workflows; each workflow supplies its own details form. */
enum class WorkflowStage(val step: Int, val title: String) {
    PROFILE(1, "Profile"),
    DETAILS(2, "Details"),
    PREPARE(3, "Prepare camera"),
    CAPTURE(4, "Capture"),
    QUALITY(5, "Image checks"),
    ANALYSIS(6, "Analysis"),
    RESULT(7, "Result");

    companion object { const val COUNT = 7 }
}

data class CaptureAssessment(
    val frames: CapturedFrames,
    val roi: PixelRect,
    val quality: QualityReport,
    val differential: DifferentialResult?,
    val optical: OpticalPipelineResult?,
    val syntheticChart: Boolean,
) {
    val differentialStatus: String get() = when {
        frames.flash == null -> "NOT_CAPTURED"
        differential == null -> "NOT_EVALUATED"
        differential.accepted -> "ACCEPTED" + (if (differential.motionCorrected) " (hand movement of ${differential.shiftX},${differential.shiftY} px corrected)" else "") +
            ": EI mean ${"%.3f".format(Locale.ROOT, differential.erythemaIndexMean ?: Double.NaN)}"
        else -> "REJECTED: " + differential.rejectionReasons.joinToString(" ")
    }
}

sealed class AnalysisOutcome {
    data class Ready(val record: ScreeningEntity, val modelStatus: ModelStatus?) : AnalysisOutcome()
    data class Blocked(val message: String) : AnalysisOutcome()
    data class InferenceFailed(val message: String) : AnalysisOutcome()
}

object WorkflowEngine {
    /** Runs the local quality gate and (only when comparable) the flash differential. CPU-bound: call off the main thread. */
    fun assess(frames: CapturedFrames, syntheticChart: Boolean, guide: NormalizedRect = NormalizedRect.MANUAL_CENTER_GUIDE): CaptureAssessment {
        val ambient = frames.ambient
        val roi = OpticalPipeline.roiBounds(ambient.width, ambient.height, guide)
        val base = ImageQualityChecker.check(ambient, roi)
        val issues = base.issues.toMutableList()
        val flash = frames.flash
        val flashMeta = frames.flashMeta
        val differential = if (flash != null && flashMeta != null) DifferentialAnalyzer.analyze(ambient, flash, frames.ambientMeta, flashMeta, roi) else null
        // Movement between the two frames never forces a retake: small movement is compensated, and
        // large movement only skips the flash comparison (non-blocking); the ambient photo is still used.
        if (differential != null && !differential.accepted) {
            issues += if (differential.excessiveMotion) QualityIssue.MOTION_BETWEEN_FRAMES else QualityIssue.FLASH_PAIR_UNUSABLE
        }
        val alignedFlash = if (flash != null && differential?.accepted == true) DifferentialAnalyzer.alignedFlash(flash, differential.shiftX, differential.shiftY) else null
        val optical = if (base.issues.none { it == QualityIssue.RESOLUTION_TOO_LOW }) {
            try {
                OpticalPipeline.process(ambient, alignedFlash, guide)
            } catch (_: IllegalArgumentException) { null }
        } else null
        return CaptureAssessment(frames, roi, base.copy(issues = issues.distinct()), differential, optical, syntheticChart)
    }

    fun analyze(
        type: ScreeningType,
        patient: PatientEntity,
        metadata: ScreeningMetadata,
        assessment: CaptureAssessment,
        regionConfirmed: Boolean,
        demoCaseId: String?,
        hemoglobinModel: HemoglobinModel,
        bilirubinModel: BilirubinModel,
        workerId: String,
        phc: String,
        screeningId: String,
        now: String = Instant.now().toString(),
    ): AnalysisOutcome {
        if (!assessment.quality.passed) return AnalysisOutcome.Blocked("Image checks did not pass. Retake the photo.")
        if (!regionConfirmed) return AnalysisOutcome.Blocked(QualityIssue.REGION_NOT_CONFIRMED.message)
        val unit = type.biomarker.unit
        val base = ScreeningEntity(
            screeningId = screeningId, patientId = patient.patientId, screeningType = type.wireName,
            estimate = null, unit = unit, demoTag = "NONE", confidencePlaceholder = 0.0,
            captureQualityProxy = (assessment.quality.sharpness / 100.0).coerceIn(0.0, 1.0),
            modelMode = "NONE", synthetic = false, confirmationRequired = true, createdAtUtc = now,
            resultMode = ResultMode.DISABLED_NO_VALID_MODEL.name, caseId = null, modelVersion = "none",
            qualityStatus = assessment.quality.statusCode, captureSource = assessment.frames.source,
            differentialStatus = assessment.differentialStatus.take(300), metadataJson = metadata.toJson(),
            workerId = workerId, phc = phc,
        )
        if (patient.isSyntheticDemo && demoCaseId != null) {
            val fixture = try { DemoInference.byId(type, demoCaseId) } catch (_: IllegalStateException) {
                return AnalysisOutcome.Blocked("Choose one of the listed synthetic demo cases.")
            }
            val urgency = ReferralRules.urgency(type, metadata, fixture.demoTag)
            return AnalysisOutcome.Ready(base.copy(
                estimate = fixture.estimate, unit = fixture.unit, demoTag = fixture.demoTag,
                confidencePlaceholder = fixture.confidencePlaceholder, modelMode = "DEMO", synthetic = true,
                resultMode = ResultMode.DEMO_SYNTHETIC.name, caseId = fixture.id, modelVersion = DEMO_MODEL_VERSION,
                referralUrgency = urgency.name,
            ), null)
        }
        if (!patient.isSyntheticDemo && demoCaseId != null) return AnalysisOutcome.Blocked("Synthetic fixtures cannot be attached to a real/local profile.")
        val status = when (type.biomarker) {
            Biomarker.HEMOGLOBIN -> hemoglobinModel.status()
            Biomarker.BILIRUBIN -> bilirubinModel.status()
        }
        val urgency = ReferralRules.urgency(type, metadata)
        if (!status.mode.producesEstimate) {
            return AnalysisOutcome.Ready(base.copy(referralUrgency = urgency.name, modelVersion = status.version ?: "none"), status)
        }
        val roi = modelInput(assessment) ?: return AnalysisOutcome.InferenceFailed("The target region could not be extracted. Retake.")
        val value = try {
            when (type.biomarker) {
                Biomarker.HEMOGLOBIN -> ModelOutputValidator.validate(Biomarker.HEMOGLOBIN, floatArrayOf(hemoglobinModel.estimateGramsPerDecilitre(roi).toFloat()))
                Biomarker.BILIRUBIN -> {
                    val age = metadata.infantAgeHours ?: return AnalysisOutcome.Blocked("Age in hours is required.")
                    ModelOutputValidator.validate(Biomarker.BILIRUBIN, floatArrayOf(bilirubinModel.estimateMilligramsPerDecilitre(roi, age).toFloat()))
                }
            }
        } catch (e: RuntimeException) {
            return AnalysisOutcome.InferenceFailed("The model could not produce a result (${e.javaClass.simpleName}). No value is shown. Retry or continue without an estimate.")
        }
        val ood = (hemoglobinModel as? LinearColourHemoglobinModel)?.lastOutOfDistribution.orEmpty()
            .takeIf { type.biomarker == Biomarker.HEMOGLOBIN }.orEmpty()
        val shown = if (ood.isEmpty()) status else status.copy(reasons = status.reasons +
            "This image is outside the model's training data (${ood.take(4).joinToString()}); treat the value as unreliable.")
        return AnalysisOutcome.Ready(base.copy(
            estimate = value, resultMode = status.mode.name, modelMode = status.mode.name,
            modelVersion = "${status.modelName} ${status.version.orEmpty()}".trim(), referralUrgency = urgency.name,
        ), shown)
    }

    /**
     * Raw ambient colours for colour models. Never the flash-differential preview: that image is clipped and
     * normalised for display. A pre-segmented image (transparent background) is used whole because its mask is
     * the region; otherwise the confirmed manual guide is cropped from the ambient frame.
     */
    fun modelInput(assessment: CaptureAssessment): RgbImage? = try {
        val ambient = assessment.frames.ambient
        if (ambient.hasTransparency()) ambient else OpticalPipeline.crop(ambient, assessment.roi)
    } catch (_: IllegalArgumentException) { null }

    /** Synthetic colour chart frames for demos without a camera; equal metadata, 100 ms apart. */
    fun syntheticFrames(): CapturedFrames {
        val (ambient, flash) = OpticalPipeline.createAbstractDemoPair()
        return CapturedFrames(
            ambient = ambient, flash = flash,
            ambientMeta = FrameMetadata(10_000_000L, 100, false, 0L, "synthetic"),
            flashMeta = FrameMetadata(10_000_000L, 100, true, 100_000_000L, "synthetic"),
            capabilities = null,
            notes = listOf("Abstract synthetic colour chart: not an eye, patient, or anatomical image."),
            source = "Abstract synthetic colour chart (no camera)",
        )
    }
}
