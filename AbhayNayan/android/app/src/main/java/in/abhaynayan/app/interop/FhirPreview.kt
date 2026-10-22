package `in`.abhaynayan.app.interop

import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * FHIR R4-style export mirroring backend/app/services/fhir.py (resource set, IDs, tags, preliminary status).
 * Pseudonymous: no name, phone, address, or image. Not a certified FHIR/ABDM integration.
 */
object FhirExport {
    const val NOTE = "Preliminary prototype screening record; not a diagnosis or validated clinical measurement. Confirm with appropriate clinical testing and local protocol."

    fun safeId(value: String): String = value.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-') it else '-' }
        .joinToString("").take(64).ifEmpty { UUID.randomUUID().toString() }

    /** Same derivation as the backend's `_related_id`, so IDs match after a server round-trip. */
    fun relatedId(prefix: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(8)
        return "$prefix-${safeId(source).take(48)}-$digest".take(64)
    }

    private fun tag(code: String, display: String) =
        JSONObject().put("tag", JSONArray().put(JSONObject().put("system", "urn:abhaynayan:tag").put("code", code).put("display", display)))

    fun bundle(record: ScreeningEntity, phc: String? = null, now: String = Instant.now().toString()): JSONObject {
        val type = ScreeningType.fromWire(record.screeningType) ?: error("Unknown screening type.")
        val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
        val patientId = safeId(record.patientId)
        val encounterId = relatedId("enc", record.screeningId)
        val observationId = relatedId("obs", record.screeningId)
        val reportId = relatedId("report", record.screeningId)
        val effective = record.createdAtUtc
        val unit = type.biomarker.unit

        val patient = JSONObject().put("resourceType", "Patient").put("id", patientId).put("active", true)
            .put("identifier", JSONArray().put(JSONObject().put("system", "urn:abhaynayan:demo-patient-id").put("value", patientId)))
            .put("gender", "unknown")
            .put("meta", tag("prototype", "Prototype demonstration record"))
        val encounter = JSONObject().put("resourceType", "Encounter").put("id", encounterId).put("status", "finished")
            .put("class", JSONObject().put("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode").put("code", "AMB").put("display", "ambulatory"))
            .put("subject", JSONObject().put("reference", "Patient/$patientId"))
            .put("period", JSONObject().put("start", effective).put("end", effective))
            .put("meta", tag("prototype", "Prototype encounter"))
        if (!phc.isNullOrBlank()) encounter.put("serviceProvider", JSONObject().put("display", phc.take(100)))

        val observation = JSONObject().put("resourceType", "Observation").put("id", observationId).put("status", "preliminary")
            .put("category", JSONArray().put(JSONObject().put("coding", JSONArray().put(
                JSONObject().put("system", "http://terminology.hl7.org/CodeSystem/observation-category").put("code", "exam").put("display", "Exam")))))
            .put("code", JSONObject().put("text", if (type == ScreeningType.MATERNAL_ANEMIA) "Optical hemoglobin screening estimate" else "Optical bilirubin screening estimate"))
            .put("subject", JSONObject().put("reference", "Patient/$patientId"))
            .put("encounter", JSONObject().put("reference", "Encounter/$encounterId"))
            .put("effectiveDateTime", effective)
            .put("note", JSONArray().put(JSONObject().put("text", NOTE)))
        val estimate = record.estimate
        if (mode.producesEstimate && estimate != null) {
            observation.put("valueQuantity", JSONObject().put("value", estimate).put("unit", unit).put("system", "http://unitsofmeasure.org").put("code", unit))
            // Only fixtures have a category; a research model output has none ("NONE" is not an interpretation).
            if (mode == ResultMode.DEMO_SYNTHETIC) observation.put("interpretation", JSONArray().put(JSONObject().put("text", record.demoTag)))
        } else {
            // No supported model output exists, so no value is fabricated.
            observation.put("dataAbsentReason", JSONObject().put("coding", JSONArray().put(
                JSONObject().put("system", "http://terminology.hl7.org/CodeSystem/data-absent-reason").put("code", "not-performed").put("display", "Not Performed"))))
        }
        observation.put("method", JSONObject().put("text", when (mode) {
            ResultMode.DEMO_SYNTHETIC -> "Smartphone optical screening prototype; synthetic demo case"
            ResultMode.EXPERIMENTAL_RESEARCH_ONLY -> "Experimental research-only model; not validated"
            ResultMode.VALIDATED_FOR_INTENDED_USE -> "Validated model ${record.modelVersion}"
            ResultMode.DISABLED_NO_VALID_MODEL -> "Image quality check only; no valid model available"
        }))
        observation.put("meta", if (mode == ResultMode.DEMO_SYNTHETIC) tag("synthetic-demo", "Synthetic demo only") else tag("prototype", "Prototype record"))

        val report = JSONObject().put("resourceType", "DiagnosticReport").put("id", reportId).put("status", "preliminary")
            .put("code", JSONObject().put("text", "AbhayNayan prototype screening report"))
            .put("subject", JSONObject().put("reference", "Patient/$patientId"))
            .put("encounter", JSONObject().put("reference", "Encounter/$encounterId"))
            .put("effectiveDateTime", effective)
            .put("result", JSONArray().put(JSONObject().put("reference", "Observation/$observationId")))
            .put("conclusion", NOTE)
            .put("meta", tag("not-clinical-diagnostic", "Not a clinical diagnosis"))

        val entries = JSONArray()
        listOf(patient, encounter, observation, report).forEach { resource ->
            entries.put(JSONObject().put("fullUrl", "https://fhir.abhaynayan.invalid/${resource.getString("resourceType")}/${resource.getString("id")}").put("resource", resource))
        }
        return JSONObject().put("resourceType", "Bundle").put("id", UUID.randomUUID().toString()).put("type", "collection")
            .put("timestamp", now).put("meta", tag("prototype", "AbhayNayan hackathon prototype")).put("entry", entries)
    }
}
