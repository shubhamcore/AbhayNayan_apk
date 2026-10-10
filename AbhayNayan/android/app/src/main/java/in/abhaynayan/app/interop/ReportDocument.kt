package `in`.abhaynayan.app.interop

import `in`.abhaynayan.app.core.CounselText
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.ReferralRules
import `in`.abhaynayan.app.core.ReferralUrgency
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tone { NORMAL, DEMO, WARNING, URGENT }

data class ReportSection(
    val heading: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val paragraphs: List<String> = emptyList(),
    val tone: Tone = Tone.NORMAL,
)

/**
 * Device-generated screening report (rendered to PDF by `PdfReportWriter`, or to text for sharing).
 * Built for every record, including records with no estimate. Pseudonymous: no nickname, photo, or address.
 */
data class ReportDocument(
    val title: String,
    val subtitle: String,
    val sections: List<ReportSection>,
    val footer: String,
) {
    fun toPlainText(): String = buildString {
        appendLine(title.uppercase(Locale.ROOT))
        appendLine(subtitle)
        sections.forEach { s ->
            appendLine()
            appendLine("■ ${s.heading}")
            s.rows.forEach { (k, v) -> appendLine("$k: $v") }
            s.paragraphs.forEach { appendLine(it) }
        }
        appendLine()
        append(footer)
    }

    companion object {
        private val LOCAL_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

        private fun localTime(utc: String, zone: ZoneId): String =
            try { LOCAL_TIME.withZone(zone).format(Instant.parse(utc)) + " (${zone.id})" } catch (_: Exception) { utc }

        fun from(
            record: ScreeningEntity,
            language: Language,
            counsel: CounselText?,
            counselSource: String?,
            appVersion: String,
            generatedAtUtc: String = Instant.now().toString(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): ReportDocument {
            val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
            val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
            val summary = ReferralSummary.from(record, language)
            val urgency = summary.urgency
            val estimate = record.estimate

            val result = when {
                mode == ResultMode.DEMO_SYNTHETIC && estimate != null -> ReportSection(
                    heading = "Result · ${mode.label}",
                    rows = listOf(
                        "Synthetic demo value" to "${"%.1f".format(Locale.ROOT, estimate)} ${record.unit}",
                        "Demo tag" to record.demoTag,
                    ),
                    paragraphs = listOf(
                        "FIXED DEMO VALUE — NOT MEASURED FROM THIS PERSON OR PHOTO. Not a laboratory result. For training and demonstration only.",
                    ),
                    tone = Tone.DEMO,
                )
                mode.producesEstimate && estimate != null -> ReportSection(
                    heading = "Result · ${mode.label}",
                    rows = listOf("${type.biomarker.displayName} (${mode.name})" to "${"%.1f".format(Locale.ROOT, estimate)} ${record.unit}"),
                    paragraphs = listOf(mode.description),
                    tone = Tone.WARNING,
                )
                else -> ReportSection(
                    heading = "Result · No estimate produced",
                    rows = listOf("${type.biomarker.displayName}" to "Not measured — no valid model is available"),
                    paragraphs = listOf(
                        "This app did not estimate ${type.biomarker.displayName.lowercase()}. Only photo quality was checked. " +
                            if (type == ScreeningType.MATERNAL_ANEMIA) "Arrange a hemoglobin test (for example CBC or an approved point-of-care test)."
                            else "A phone photo cannot replace a bilirubin test or the newborn-care assessment.",
                    ),
                )
            }

            val nextStep = ReportSection(
                heading = "Next step · ${urgency.label}",
                paragraphs = listOf(summary.nextStep, ReferralRules.REVIEW_STATUS),
                tone = if (urgency == ReferralUrgency.URGENT_REFERRAL) Tone.URGENT else Tone.NORMAL,
            )

            val details = ReportSection(
                heading = "Screening details",
                rows = buildList {
                    add("Screening" to type.displayName)
                    add("Record ID" to record.screeningId)
                    add("Patient ID (pseudonymous)" to record.patientId)
                    add("Date / time" to localTime(record.createdAtUtc, zone))
                    add("Worker / facility" to "${record.workerId} · ${record.phc}")
                    summary.metadataLines.forEach { line ->
                        val (k, v) = line.split(": ", limit = 2).let { if (it.size == 2) it[0] to it[1] else "Note" to line }
                        add(k to v)
                    }
                },
            )

            val imageChecks = ReportSection(
                heading = "Image checks (photo quality only)",
                rows = listOf(
                    "Image quality" to record.qualityStatus,
                    "Flash comparison" to record.differentialStatus,
                    "Capture source" to record.captureSource.ifBlank { "Not recorded" },
                ),
                paragraphs = listOf("These checks measure whether the photo was usable. They do not measure health."),
            )

            val counselling = counsel?.let { c ->
                ReportSection(
                    heading = "Counselling (${language.nativeLabel})",
                    paragraphs = listOf(c.summary, c.riskExplanation, c.script, c.safetyNote) +
                        listOfNotNull(counselSource?.let { "Source: $it" }),
                )
            }

            val provenance = ReportSection(
                heading = "Provenance and limitations",
                rows = listOf(
                    "Result mode" to mode.name,
                    "Model / fixture" to if (mode == ResultMode.DEMO_SYNTHETIC) "${record.modelVersion} (case ${record.caseId ?: record.demoTag})" else record.modelVersion,
                ),
                paragraphs = summary.limitations.map { "• $it" } + listOf(
                    "• This prototype is not clinically validated and is not a diagnosis.",
                    "• No photo is included in or stored with this report.",
                ),
                tone = Tone.WARNING,
            )

            return ReportDocument(
                title = "AbhayNayan · Screening report",
                subtitle = "Prototype screening workflow — not a diagnosis, not a laboratory result. Confirm with appropriate testing and follow local protocol.",
                sections = listOfNotNull(result, nextStep, details, imageChecks, counselling, provenance),
                footer = "Generated on this phone ${localTime(generatedAtUtc, zone)} · AbhayNayan $appVersion · Record ${record.screeningId}",
            )
        }
    }
}
