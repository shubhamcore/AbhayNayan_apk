package `in`.abhaynayan.app.interop

import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.LocalCounselling
import `in`.abhaynayan.app.core.ReferralRules
import `in`.abhaynayan.app.core.ReferralUrgency
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningMetadata
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import java.util.Locale

/** Structured referral summary shared by the share sheet, the server request, and tests. */
data class ReferralSummary(
    val recordId: String,
    val patientId: String,
    val screeningType: ScreeningType,
    val timestampUtc: String,
    val resultMode: ResultMode,
    val provenance: String,
    val observation: String,
    val qualityStatus: String,
    val metadataLines: List<String>,
    val urgency: ReferralUrgency,
    val nextStep: String,
    val limitations: List<String>,
) {
    fun toText(): String = buildString {
        appendLine("ABHAYNAYAN · SCREENING REFERRAL SUMMARY (PROTOTYPE)")
        appendLine("Not a diagnosis. Not a laboratory result.")
        appendLine()
        appendLine("Record ID: $recordId")
        appendLine("Patient ID (pseudonymous): $patientId")
        appendLine("Screening: ${screeningType.displayName}")
        appendLine("Date/time (UTC): $timestampUtc")
        appendLine("Result mode: ${resultMode.name} — ${resultMode.label}")
        appendLine("Provenance: $provenance")
        appendLine("Observation: $observation")
        appendLine("Image quality: $qualityStatus")
        metadataLines.forEach { appendLine(it) }
        appendLine()
        appendLine("URGENCY: ${urgency.label.uppercase(Locale.ROOT)}")
        appendLine("Next step: $nextStep")
        appendLine()
        appendLine("Limitations:")
        limitations.forEach { appendLine("• $it") }
        appendLine("No photo is attached. Next-step rules: ${ReferralRules.REVIEW_STATUS}")
    }

    companion object {
        fun from(record: ScreeningEntity, language: Language = Language.EN): ReferralSummary {
            val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
            val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
            val metadata = ScreeningMetadata.fromJson(record.metadataJson)
            val urgency = ReferralRules.urgency(type, metadata, record.demoTag.takeIf { mode == ResultMode.DEMO_SYNTHETIC })
            val estimate = record.estimate
            val observation = when {
                mode == ResultMode.DEMO_SYNTHETIC && estimate != null ->
                    "SYNTHETIC DEMO VALUE ${"%.1f".format(Locale.ROOT, estimate)} ${record.unit} (fixture ${record.demoTag}; NOT measured from this person)"
                mode.producesEstimate && estimate != null -> "${"%.1f".format(Locale.ROOT, estimate)} ${record.unit} (${mode.label})"
                else -> "No ${type.biomarker.displayName.lowercase()} estimate — no valid model available"
            }
            val lines = buildList {
                if (type == ScreeningType.MATERNAL_ANEMIA) {
                    add("Pregnancy: ${metadata.pregnancy.name.lowercase().replace('_', ' ')}" + (metadata.gestationalWeek?.let { " · week $it" } ?: ""))
                } else {
                    metadata.infantAgeHours?.let { add("Infant age: $it hours") }
                    metadata.gestationalAgeAtBirthWeeks?.let { add("Gestational age at birth: $it weeks") }
                    metadata.visibleJaundice?.let { add("Visible yellow colour reported: ${if (it) "yes" else "no"}") }
                }
                add("Danger signs: " + (metadata.dangerSigns.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.label } ?: "none recorded"))
            }
            val limitations = buildList {
                add(if (type == ScreeningType.MATERNAL_ANEMIA) "Confirm hemoglobin with an appropriate laboratory or approved point-of-care test." else "Confirm bilirubin with an appropriate serum/transcutaneous test under newborn-care protocol.")
                add("Image checks are engineering quality checks only.")
                if (mode == ResultMode.DEMO_SYNTHETIC) add("Synthetic demo values must never be treated as patient measurements.")
            }
            return ReferralSummary(
                recordId = record.screeningId, patientId = record.patientId, screeningType = type,
                timestampUtc = record.createdAtUtc, resultMode = mode,
                provenance = if (mode == ResultMode.DEMO_SYNTHETIC) "Fixed fixture set ${record.modelVersion}" else "Model: ${record.modelVersion}",
                observation = observation, qualityStatus = record.qualityStatus, metadataLines = lines,
                urgency = urgency, nextStep = LocalCounselling.urgencyLine(urgency, language), limitations = limitations,
            )
        }
    }
}
