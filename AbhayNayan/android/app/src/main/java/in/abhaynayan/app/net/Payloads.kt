package `in`.abhaynayan.app.net

import `in`.abhaynayan.app.core.DEMO_MODEL_VERSION
import `in`.abhaynayan.app.core.DEMO_SAFETY_NOTE
import `in`.abhaynayan.app.core.DemoInference
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.PregnancyStatus
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningMetadata
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which request format the connected server accepts. The server's Pydantic models reject unknown fields,
 * so new fields may only be sent once `/api/health` reports `api_contract_version` 1.x
 * (hrdkkmr/AbhayNayan_web docs/API_CONTRACT.md §1).
 */
enum class Contract {
    /** Unversioned prototype API (no `api_contract_version`), e.g. the backend copy bundled in this repo. */
    LEGACY_0_1,
    /** API contract 1.x: nullable confidence/quality, provenance, context, per-record sync results. */
    V1;

    companion object {
        fun fromVersion(version: String?): Contract = if (version?.substringBefore('.')?.toIntOrNull() == 1) V1 else LEGACY_0_1
    }
}

/**
 * Builds request bodies that match backend/app/models/schemas.py exactly for the given [Contract].
 * Patient names and images are never included.
 */
object Payloads {
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    private val SAFE_PATIENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    const val DEMO_LIMITATION = "Fixed demonstration value; not computed from any image, feature, or person."

    sealed class Eligibility {
        data object Eligible : Eligibility()
        data class LocalOnly(val reason: String) : Eligibility()
    }

    /** The backend accepts only synthetic DEMO records from synthetic demo profiles (both contract versions). */
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

    /** ScreeningResult. Contract 1.0: a fixture has no confidence or quality, so both are null. */
    fun result(record: ScreeningEntity, contract: Contract = Contract.V1): JSONObject {
        val type = requireNotNull(ScreeningType.fromWire(record.screeningType)) { "Unknown screening type." }
        val fixture = requireNotNull(DemoInference.resolve(type, record.caseId, record.demoTag)) { "Unknown demo fixture." }
        val estimate = requireNotNull(record.estimate) { "A record without an estimate cannot be sent." }
        val json = JSONObject()
            .put("screening_type", type.wireName)
            .put("estimate", estimate)
            .put("unit", type.biomarker.unit)
            .put("requires_confirmation", true)
            .put("model_mode", "DEMO")
            .put("synthetic", true)
            .put("case_id", fixture.id)
            .put("case_label", fixture.label)
            .put("generated_at", record.createdAtUtc)
            .put("safety_note", DEMO_SAFETY_NOTE)
        return when (contract) {
            Contract.V1 -> json
                .put("risk_category", fixture.demoTag)
                .put("confidence", JSONObject.NULL)
                .put("quality_score", JSONObject.NULL)
                .put("provenance", ResultMode.DEMO_SYNTHETIC.name)
                .put("model_version", DEMO_MODEL_VERSION)
                .put("limitations", JSONArray().put(DEMO_LIMITATION))
            // The 0.1 server requires numeric placeholders; it also predates the DEMO_REVIEW rename.
            Contract.LEGACY_0_1 -> json
                .put("risk_category", if (fixture.demoTag == "DEMO_REVIEW") "REQUIRES_REVIEW" else fixture.demoTag)
                .put("confidence", fixture.confidencePlaceholder)
                .put("quality_score", fixture.fixtureQualityPlaceholder)
        }
    }

    /** Minimal non-identifying context (contract 1.0 `ScreeningContext`); null when nothing applies. */
    fun context(record: ScreeningEntity): JSONObject? {
        val m = ScreeningMetadata.fromJson(record.metadataJson)
        val pregnancy = when (m.pregnancy) { PregnancyStatus.PREGNANT -> true; PregnancyStatus.NOT_PREGNANT -> false; PregnancyStatus.UNKNOWN -> null }
        val age = m.infantAgeHours?.takeIf { it in 0..8760 }
        val week = m.gestationalWeek?.takeIf { it in 1..45 }
        if (pregnancy == null && age == null && week == null) return null
        return JSONObject()
            .put("infant_age_hours", age ?: JSONObject.NULL)
            .put("gestational_week", week ?: JSONObject.NULL)
            .put("pregnancy", pregnancy ?: JSONObject.NULL)
    }

    /** ScreeningRecord; the server regenerates any FHIR bundle, so none is sent. */
    fun screeningRecord(record: ScreeningEntity, contract: Contract = Contract.V1): JSONObject {
        val json = JSONObject()
            .put("id", record.screeningId)
            .put("patient", JSONObject().put("patient_id", record.patientId).put("demo", true))
            .put("result", result(record, contract))
            .put("worker_id", record.workerId.take(64))
            .put("phc", record.phc.take(100))
            .put("village", JSONObject.NULL)
            .put("created_at", record.createdAtUtc)
            .put("sync_status", "queued")
            .put("fhir_bundle", JSONObject.NULL)
        if (contract == Contract.V1) {
            json.put("context", context(record) ?: JSONObject.NULL)
            json.put("id_origin", "client")
        }
        return json
    }

    /** Idempotency key for counselling (contract 1.0 §4.3): `cns-<screening id>-<language>`. */
    fun counsellingRequestId(record: ScreeningEntity, language: Language): String = "cns-${record.screeningId}-${language.code}".take(96)

    /** GeminiCounselRequest. Only structured, non-identifying fields. */
    fun counsellingRequest(record: ScreeningEntity, language: Language, contract: Contract = Contract.V1): JSONObject {
        val result = result(record, contract)
        val metadata = ScreeningMetadata.fromJson(record.metadataJson)
        val body = JSONObject()
            .put("screening_type", result.getString("screening_type"))
            .put("estimate", result.getDouble("estimate"))
            .put("unit", result.getString("unit"))
            .put("risk_category", result.getString("risk_category"))
            .put("confidence", result.get("confidence"))
            .put("quality_score", result.get("quality_score"))
            .put("language", language.code)
        when (metadata.pregnancy) {
            PregnancyStatus.PREGNANT -> body.put("pregnancy", true)
            PregnancyStatus.NOT_PREGNANT -> body.put("pregnancy", false)
            PregnancyStatus.UNKNOWN -> Unit
        }
        metadata.gestationalWeek?.takeIf { it in 1..45 }?.let { body.put("gestational_week", it) }
        metadata.infantAgeHours?.takeIf { it in 0..8760 }?.let { body.put("infant_age_hours", it) }
        if (contract == Contract.V1) {
            body.put("provenance", ResultMode.DEMO_SYNTHETIC.name)
            body.put("request_id", counsellingRequestId(record, language))
        }
        return body
    }

    /** ReferralRequest. worker_name carries the pseudonymous worker code, never a personal name. */
    fun referralRequest(
        referralId: String,
        record: ScreeningEntity,
        workerLabel: String,
        language: Language,
        counselling: String,
        nextStep: String,
        contract: Contract = Contract.V1,
        counsellingMode: String = "LOCAL_FALLBACK",
    ): JSONObject {
        require(SAFE_ID.matches(referralId)) { "Referral ID must be alphanumeric." }
        val body = JSONObject()
            .put("id", referralId)
            .put("screening", screeningRecord(record, contract))
            .put("worker_name", workerLabel.take(100))
            .put("language", language.code)
            .put("counselling", counselling.take(2000))
            .put("next_step", nextStep.take(500))
        if (contract == Contract.V1) {
            body.put("counselling_mode", if (counsellingMode in setOf("GEMINI", "LOCAL_FALLBACK", "GEMINI_RECOVERED")) counsellingMode else "LOCAL_FALLBACK")
            body.put("reason", "SCREENING_CONFIRMATION")
        }
        return body
    }
}
