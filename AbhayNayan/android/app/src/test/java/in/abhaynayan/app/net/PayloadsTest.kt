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

    @Test fun screeningRecordMatchesBackendSchemaKeysExactly() {
        val json = Payloads.screeningRecord(TestFixtures.demoRecord())
        assertEquals(setOf("id", "patient", "result", "worker_id", "phc", "village", "created_at", "sync_status", "fhir_bundle"), json.keyNames())
        assertEquals(setOf("patient_id", "demo"), json.getJSONObject("patient").keyNames())
        val result = json.getJSONObject("result")
        assertEquals(setOf("screening_type", "estimate", "unit", "risk_category", "confidence", "quality_score", "requires_confirmation",
            "model_mode", "synthetic", "case_id", "case_label", "generated_at", "safety_note"), result.keyNames())
        assertEquals("DEMO", result.getString("model_mode"))
        assertTrue(result.getBoolean("synthetic"))
        assertEquals("g/dL", result.getString("unit"))
        assertEquals(.91, result.getDouble("quality_score"), 1e-9)
        assertTrue(json.isNull("fhir_bundle"))
        assertFalse(nameLike.containsMatchIn(json.toString()))
        assertFalse(json.toString().contains(TestFixtures.demoPatient.displayName))
    }

    @Test fun counsellingRequestCarriesOnlyStructuredNonIdentifyingFields() {
        val body = Payloads.counsellingRequest(TestFixtures.demoRecord(), Language.BHO)
        assertEquals(setOf("screening_type", "estimate", "unit", "risk_category", "confidence", "quality_score", "language", "pregnancy", "gestational_week"), body.keyNames())
        assertEquals("bho", body.getString("language"))
        assertTrue(body.getBoolean("pregnancy"))
        assertFalse(body.has("patient_id"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordsWithoutEstimateCannotBeSentToCounsellingEndpoint() {
        Payloads.counsellingRequest(TestFixtures.disabledRecord(), Language.EN)
    }

    @Test fun referralRequestUsesWorkerLabelAndLimits() {
        val body = Payloads.referralRequest("ref-1", TestFixtures.demoRecord(), "ASHA-017", Language.HI, "x".repeat(3000), "next")
        assertEquals(setOf("id", "screening", "worker_name", "language", "counselling", "next_step"), body.keyNames())
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
