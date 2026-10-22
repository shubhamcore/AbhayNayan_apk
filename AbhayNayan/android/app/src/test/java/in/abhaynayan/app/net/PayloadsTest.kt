package `in`.abhaynayan.app.net

import `in`.abhaynayan.app.TestFixtures
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.interop.FhirExport
import `in`.abhaynayan.app.interop.ReferralSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadsTest {
    private val nameLike = Regex("(?i)\"(name|display_name|displayName|phone|telecom|address|photo|image)\"")

    @Test fun onlySyntheticDemoRecordsAreSyncEligible() {
        assertEquals(Payloads.Eligibility.Eligible, Payloads.syncEligibility(TestFixtures.demoRecord(), TestFixtures.demoPatient))
        assertTrue(Payloads.syncEligibility(TestFixtures.disabledRecord(), TestFixtures.realPatient) is Payloads.Eligibility.LocalOnly)
        assertTrue(Payloads.syncEligibility(TestFixtures.demoRecord(), TestFixtures.realPatient) is Payloads.Eligibility.LocalOnly)
        assertTrue(Payloads.syncEligibility(TestFixtures.demoRecord(), null) is Payloads.Eligibility.LocalOnly)
        assertTrue(Payloads.syncEligibility(TestFixtures.demoRecord(id = "bad id with spaces"), TestFixtures.demoPatient) is Payloads.Eligibility.LocalOnly)
    }

    @Test fun screeningRecordMatchesContract10SchemaKeysExactly() {
        val json = Payloads.screeningRecord(TestFixtures.demoRecord(), Contract.V1)
        assertEquals(setOf("id", "patient", "result", "context", "worker_id", "phc", "village", "created_at", "id_origin", "sync_status", "fhir_bundle"), json.keyNames())
        assertEquals(setOf("patient_id", "demo"), json.getJSONObject("patient").keyNames())
        val result = json.getJSONObject("result")
        assertEquals(setOf("screening_type", "estimate", "unit", "risk_category", "confidence", "quality_score", "requires_confirmation",
            "model_mode", "synthetic", "provenance", "model_version", "case_id", "case_label", "generated_at", "limitations", "safety_note"), result.keyNames())
        // Contract rule: synthetic == (provenance == DEMO_SYNTHETIC) == (model_mode == DEMO).
        assertEquals("DEMO", result.getString("model_mode"))
        assertTrue(result.getBoolean("synthetic"))
        assertEquals("DEMO_SYNTHETIC", result.getString("provenance"))
        assertEquals("fixed-demo-fixtures-v1", result.getString("model_version"))
        assertEquals("g/dL", result.getString("unit"))
        // A fixture has no confidence or quality: null, never a substituted number.
        assertTrue(result.isNull("confidence"))
        assertTrue(result.isNull("quality_score"))
        assertEquals("client", json.getString("id_origin"))
        assertEquals(24, json.getJSONObject("context").getInt("gestational_week"))
        assertTrue(json.getJSONObject("context").getBoolean("pregnancy"))
        assertTrue(json.isNull("fhir_bundle"))
        assertFalse(nameLike.containsMatchIn(json.toString()))
        assertFalse(json.toString().contains(TestFixtures.demoPatient.displayName))
    }

    @Test fun legacyServerGetsThePre10FormatWithoutNewFields() {
        val record = TestFixtures.demoRecord().copy(screeningType = "neonatal_jaundice", unit = "mg/dL", estimate = 12.8, demoTag = "DEMO_REVIEW", caseId = "jaundice-review")
        val json = Payloads.screeningRecord(record, Contract.LEGACY_0_1)
        assertEquals(setOf("id", "patient", "result", "worker_id", "phc", "village", "created_at", "sync_status", "fhir_bundle"), json.keyNames())
        val result = json.getJSONObject("result")
        assertEquals(setOf("screening_type", "estimate", "unit", "risk_category", "confidence", "quality_score", "requires_confirmation",
            "model_mode", "synthetic", "case_id", "case_label", "generated_at", "safety_note"), result.keyNames())
        assertEquals("REQUIRES_REVIEW", result.getString("risk_category")) // the 0.1 server's tag
        assertEquals(.90, result.getDouble("quality_score"), 1e-9)          // the 0.1 server requires a number
        val counsel = Payloads.counsellingRequest(record, Language.EN, Contract.LEGACY_0_1)
        assertFalse(counsel.has("provenance") || counsel.has("request_id"))
        val referral = Payloads.referralRequest("ref-1", record, "ASHA-017", Language.EN, "c", "n", Contract.LEGACY_0_1)
        assertFalse(referral.has("reason") || referral.has("counselling_mode"))
    }

    @Test fun contractIsDetectedFromHealthVersion() {
        assertEquals(Contract.V1, Contract.fromVersion("1.0.0"))
        assertEquals(Contract.V1, Contract.fromVersion("1.4.2"))
        assertEquals(Contract.LEGACY_0_1, Contract.fromVersion(null))
        assertEquals(Contract.LEGACY_0_1, Contract.fromVersion("2.0.0")) // unknown major: never send 1.x fields blindly
    }

    @Test fun counsellingRequestCarriesOnlyStructuredNonIdentifyingFields() {
        val body = Payloads.counsellingRequest(TestFixtures.demoRecord(), Language.BHO)
        assertEquals(setOf("screening_type", "estimate", "unit", "risk_category", "confidence", "quality_score", "language", "pregnancy",
            "gestational_week", "provenance", "request_id"), body.keyNames())
        assertEquals("bho", body.getString("language"))
        assertTrue(body.isNull("confidence"))
        assertEquals("cns-${TestFixtures.demoRecord().screeningId}-bho", body.getString("request_id"))
        assertTrue(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{7,95}").matches(body.getString("request_id")))
        assertTrue(body.getBoolean("pregnancy"))
        assertFalse(body.has("patient_id"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordsWithoutEstimateCannotBeSentToCounsellingEndpoint() {
        Payloads.counsellingRequest(TestFixtures.disabledRecord(), Language.EN)
    }

    @Test fun referralRequestUsesWorkerLabelAndLimits() {
        val body = Payloads.referralRequest("ref-1", TestFixtures.demoRecord(), "ASHA-017", Language.HI, "x".repeat(3000), "next", counsellingMode = "GEMINI")
        assertEquals(setOf("id", "screening", "worker_name", "language", "counselling", "next_step", "counselling_mode", "reason"), body.keyNames())
        assertEquals("GEMINI", body.getString("counselling_mode"))
        assertEquals("SCREENING_CONFIRMATION", body.getString("reason"))
        assertEquals(2000, body.getString("counselling").length)
        assertEquals("ASHA-017", body.getString("worker_name"))
    }

    @Test fun fhirIdsMatchBackendDerivationAndNoNameIsExported() {
        // Expected values computed with backend/app/services/fhir.py::_related_id.
        assertEquals("enc-scr-1f3a9c2e-0000-4000-8000-000000000001-2b33dfd2", FhirExport.relatedId("enc", "scr-1f3a9c2e-0000-4000-8000-000000000001"))
        assertEquals("obs-weird-id-with-spaces-5254238e", FhirExport.relatedId("obs", "weird id/with spaces"))
        val bundle = FhirExport.bundle(TestFixtures.demoRecord(), "Demo PHC", now = "2026-10-10T10:00:00Z")
        val entries = bundle.getJSONArray("entry")
        assertEquals(listOf("Patient", "Encounter", "Observation", "DiagnosticReport"), (0 until entries.length()).map { entries.getJSONObject(it).getJSONObject("resource").getString("resourceType") })
        val observation = entries.getJSONObject(2).getJSONObject("resource")
        assertEquals("preliminary", observation.getString("status"))
        assertEquals(9.4, observation.getJSONObject("valueQuantity").getDouble("value"), 1e-9)
        assertEquals("http://unitsofmeasure.org", observation.getJSONObject("valueQuantity").getString("system"))
        assertEquals("synthetic-demo", observation.getJSONObject("meta").getJSONArray("tag").getJSONObject(0).getString("code"))
        assertFalse(bundle.getJSONArray("entry").getJSONObject(0).getJSONObject("resource").has("name"))
        assertFalse(bundle.toString().contains(TestFixtures.demoPatient.displayName))
    }

    @Test fun fhirForDisabledModeUsesDataAbsentReasonInsteadOfAValue() {
        val observation = FhirExport.bundle(TestFixtures.disabledRecord()).getJSONArray("entry").getJSONObject(2).getJSONObject("resource")
        assertFalse(observation.has("valueQuantity"))
        assertEquals("not-performed", observation.getJSONObject("dataAbsentReason").getJSONArray("coding").getJSONObject(0).getString("code"))
    }

    @Test fun referralSummaryStatesProvenanceAndCannotBeMistakenForLabResult() {
        val demo = ReferralSummary.from(TestFixtures.demoRecord()).toText()
        assertTrue("SYNTHETIC DEMO VALUE 9.4 g/dL" in demo)
        assertTrue("NOT measured from this person" in demo)
        assertTrue("DEMO_SYNTHETIC" in demo)
        assertTrue("Not a laboratory result" in demo)
        val disabled = ReferralSummary.from(TestFixtures.disabledRecord())
        assertEquals("URGENT_REFERRAL", disabled.urgency.name) // visible jaundice at 20 h
        val text = disabled.toText()
        assertTrue("No bilirubin estimate" in text)
        assertTrue("Infant age: 20 hours" in text)
        assertFalse(text.contains(TestFixtures.realPatient.displayName))
    }
}
