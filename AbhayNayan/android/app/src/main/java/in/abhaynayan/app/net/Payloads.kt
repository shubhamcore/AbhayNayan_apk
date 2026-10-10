package `in`.abhaynayan.app.net

import `in`.abhaynayan.app.core.DEMO_SAFETY_NOTE
import `in`.abhaynayan.app.core.DemoInference
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.PregnancyStatus
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningMetadata
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import org.json.JSONObject

/**
 * Builds request bodies that match backend/app/models/schemas.py exactly. Those models use
 * `extra='forbid'`, so only listed fields are sent. Patient names and images are never included.
 */
object Payloads {
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    private val SAFE_PATIENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

    sealed class Eligibility {
        data object Eligible : Eligibility()
        data class LocalOnly(val reason: String) : Eligibility()
    }

    /** The current backend accepts only synthetic DEMO records from synthetic demo profiles. */
    fun syncEligibility(record: ScreeningEntity, patient: PatientEntity?): Eligibility {
        val type = ScreeningType.fromWire(record.screeningType) ?: return Eligibility.LocalOnly("Unknown screening type.")
        return when {
            patient == null -> Eligibility.LocalOnly("Profile not found on this phone.")
            !patient.isSyntheticDemo -> Eligibility.LocalOnly("Real/local profiles are not synced: the backend accepts synthetic demo records only.")
            record.resultMode != ResultMode.DEMO_SYNTHETIC.name || !record.synthetic || record.modelMode != "DEMO" ->
                Eligibility.LocalOnly("Only synthetic demo results can be synced with the current backend.")
            record.estimate == null -> Eligibility.LocalOnly("Records without an estimate have no backend endpoint yet.")
            DemoInference.resolve(type, record.caseId, record.demoTag) == null -> Eligibility.LocalOnly("Demo fixture not recognised.")
            !SAFE_ID.matches(record.screeningId) -> Eligibility.LocalOnly("Record ID is not in the server's allowed format.")
            !SAFE_PATIENT_ID.matches(record.patientId) -> Eligibility.LocalOnly("Patient ID is not pseudonymous/alphanumeric.")
            else -> Eligibility.Eligible
        }
    }

    /** ScreeningResult. confidence/quality_score are the fixture placeholders, identical to the website. */
    fun result(record: ScreeningEntity): JSONObject {
        val type = requireNotNull(ScreeningType.fromWire(record.screeningType)) { "Unknown screening type." }
        val fixture = requireNotNull(DemoInference.resolve(type, record.caseId, record.demoTag)) { "Unknown demo fixture." }
        val estimate = requireNotNull(record.estimate) { "A record without an estimate cannot be sent." }
        return JSONObject()
            .put("screening_type", type.wireName)
            .put("estimate", estimate)
            .put("unit", type.biomarker.unit)
            .put("risk_category", fixture.demoTag)
            .put("confidence", fixture.confidencePlaceholder)
            .put("quality_score", fixture.fixtureQualityPlaceholder)
            .put("requires_confirmation", true)
            .put("model_mode", "DEMO")
            .put("synthetic", true)
            .put("case_id", fixture.id)
            .put("case_label", fixture.label)
            .put("generated_at", record.createdAtUtc)
            .put("safety_note", DEMO_SAFETY_NOTE)
    }

    /** ScreeningRecord; the server regenerates any FHIR bundle, so none is sent. */
    fun screeningRecord(record: ScreeningEntity): JSONObject = JSONObject()
        .put("id", record.screeningId)
        .put("patient", JSONObject().put("patient_id", record.patientId).put("demo", true))
        .put("result", result(record))
        .put("worker_id", record.workerId.take(64))
        .put("phc", record.phc.take(100))
        .put("village", JSONObject.NULL)
        .put("created_at", record.createdAtUtc)
        .put("sync_status", "queued")
        .put("fhir_bundle", JSONObject.NULL)

    /** GeminiCounselRequest. Only structured, non-identifying fields. */
    fun counsellingRequest(record: ScreeningEntity, language: Language): JSONObject {
        val result = result(record)
        val metadata = ScreeningMetadata.fromJson(record.metadataJson)
        val body = JSONObject()
            .put("screening_type", result.getString("screening_type"))
            .put("estimate", result.getDouble("estimate"))
            .put("unit", result.getString("unit"))
            .put("risk_category", result.getString("risk_category"))
            .put("confidence", result.getDouble("confidence"))
            .put("quality_score", result.getDouble("quality_score"))
            .put("language", language.code)
        when (metadata.pregnancy) {
            PregnancyStatus.PREGNANT -> body.put("pregnancy", true)
            PregnancyStatus.NOT_PREGNANT -> body.put("pregnancy", false)
            PregnancyStatus.UNKNOWN -> Unit
        }
        metadata.gestationalWeek?.takeIf { it in 1..45 }?.let { body.put("gestational_week", it) }
        metadata.infantAgeHours?.takeIf { it in 0..8760 }?.let { body.put("infant_age_hours", it) }
        return body
    }

    /** ReferralRequest. worker_name is the pseudonymous worker label, never a personal name field. */
    fun referralRequest(referralId: String, record: ScreeningEntity, workerLabel: String, language: Language, counselling: String, nextStep: String): JSONObject {
        require(SAFE_ID.matches(referralId)) { "Referral ID must be alphanumeric." }
        return JSONObject()
            .put("id", referralId)
            .put("screening", screeningRecord(record))
            .put("worker_name", workerLabel.take(100))
            .put("language", language.code)
            .put("counselling", counselling.take(2000))
            .put("next_step", nextStep.take(500))
    }
}
