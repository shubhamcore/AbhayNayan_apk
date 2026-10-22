package `in`.abhaynayan.app

import `in`.abhaynayan.app.core.AnalysisOutcome
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.PregnancyStatus
import `in`.abhaynayan.app.core.RgbImage
import `in`.abhaynayan.app.core.ScreeningMetadata
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.core.UnavailableBilirubinModel
import `in`.abhaynayan.app.core.UnavailableHemoglobinModel
import `in`.abhaynayan.app.core.WorkflowEngine
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.interop.FhirExport
import `in`.abhaynayan.app.interop.ReferralSummary
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.ApiResult
import `in`.abhaynayan.app.net.Payloads
import `in`.abhaynayan.app.net.UrlConnectionTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * End-to-end check that a hand-held (slightly shaken) capture produces a saved record and every report
 * format: the on-screen result, the shareable referral summary, the FHIR-style export, and the server
 * referral request. The last test talks to a real backend only when ABHAYNAYAN_LIVE_BACKEND is set.
 */
class ReportGenerationTest {
    private fun shakyFrames() = WorkflowEngine.syntheticFrames().let { f ->
        val w = f.ambient.width
        f.copy(flash = RgbImage(w, f.ambient.height, IntArray(f.ambient.pixels.size) { i ->
            f.flash!!.pixels[(i / w) * w + ((i % w) + 7).coerceAtMost(w - 1)]
        }))
    }

    private fun record(patient: PatientEntity, type: ScreeningType, meta: ScreeningMetadata, demoCase: String?): ScreeningEntity {
        val assessment = WorkflowEngine.assess(shakyFrames(), syntheticChart = true)
        assertTrue("shaky capture must pass: ${assessment.quality.issues}", assessment.quality.passed)
        val outcome = WorkflowEngine.analyze(type, patient, meta, assessment, regionConfirmed = true, demoCaseId = demoCase,
            hemoglobinModel = UnavailableHemoglobinModel(), bilirubinModel = UnavailableBilirubinModel(),
            workerId = "ASHA-017", phc = "Demo PHC", screeningId = "scr-" + UUID.randomUUID())
        return (outcome as AnalysisOutcome.Ready).record
    }

    @Test fun demoMaternalReportIsGeneratedInEveryFormat() {
        val r = record(TestFixtures.demoPatient, ScreeningType.MATERNAL_ANEMIA, ScreeningMetadata(pregnancy = PregnancyStatus.PREGNANT, gestationalWeek = 22), "maternal-high")
        assertTrue(r.differentialStatus, r.differentialStatus.startsWith("ACCEPTED"))

        val summary = ReferralSummary.from(r, Language.HI)
        val text = summary.toText()
        listOf(r.screeningId, "Maternal anemia", "DEMO_SYNTHETIC", "SYNTHETIC DEMO VALUE 7.8 g/dL", "Pregnancy: pregnant · week 22", "URGENCY: PROMPT CLINICIAN REVIEW", "Limitations:")
            .forEach { assertTrue("missing '$it' in:\n$text", it in text) }
        assertTrue(summary.nextStep.contains("चिकित्सक")) // Hindi next step

        val bundle = FhirExport.bundle(r, "Demo PHC")
        assertEquals(4, bundle.getJSONArray("entry").length())
        assertEquals(7.8, bundle.getJSONArray("entry").getJSONObject(2).getJSONObject("resource").getJSONObject("valueQuantity").getDouble("value"), 1e-9)

        val request = Payloads.referralRequest("ref-" + UUID.randomUUID(), r, "ASHA-017", Language.HI, "script", summary.nextStep)
        assertEquals(r.screeningId, request.getJSONObject("screening").getString("id"))
    }

    @Test fun realNewbornReportIsGeneratedWithoutAnyValue() {
        val r = record(TestFixtures.realPatient, ScreeningType.NEONATAL_JAUNDICE, ScreeningMetadata(infantAgeHours = 18, visibleJaundice = true), null)
        assertEquals(null, r.estimate)
        val text = ReferralSummary.from(r).toText()
        listOf("DISABLED_NO_VALID_MODEL", "No bilirubin estimate", "Infant age: 18 hours", "URGENCY: URGENT REFERRAL").forEach { assertTrue("missing '$it'", it in text) }
        assertFalse(Regex("\\d+(\\.\\d)? mg/dL").containsMatchIn(text))
        val obs = FhirExport.bundle(r).getJSONArray("entry").getJSONObject(2).getJSONObject("resource")
        assertTrue(obs.has("dataAbsentReason"))
    }

    /** Run with ABHAYNAYAN_LIVE_BACKEND=http://127.0.0.1:8000 against `uvicorn app.main:app` from backend/. */
    @Test fun liveBackendAcceptsRecordAndProducesReferralPdf() = runBlocking {
        val base = System.getenv("ABHAYNAYAN_LIVE_BACKEND")
        assumeTrue("ABHAYNAYAN_LIVE_BACKEND not set; live backend test skipped", !base.isNullOrBlank())
        val api = ApiClient({ base }, UrlConnectionTransport(readTimeoutMs = 60_000))
        val newborn = TestFixtures.demoPatient.copy(patientId = "ABH-DEMO-NEWBORN", subjectKind = "NEWBORN")
        val r = record(newborn, ScreeningType.NEONATAL_JAUNDICE, ScreeningMetadata(infantAgeHours = 40, visibleJaundice = true), "jaundice-review")
        val contract = (api.contract() as ApiResult.Success).value
        println("Live backend $base speaks $contract")

        val sync = api.sync(JSONArray().put(Payloads.screeningRecord(r, contract)))
        assertTrue("sync failed: $sync", sync is ApiResult.Success && r.screeningId in sync.value.syncedIds)
        val again = api.sync(JSONArray().put(Payloads.screeningRecord(r, contract)))
        assertEquals("duplicate must not be re-inserted", 1, (again as ApiResult.Success).value.duplicate)

        val counsel = api.counselling(Payloads.counsellingRequest(r, Language.BHO, contract))
        assertTrue("counselling failed: $counsel", counsel is ApiResult.Success && counsel.value.language == "bho")

        val summary = ReferralSummary.from(r)
        val referralId = "ref-" + UUID.randomUUID()
        val created = api.createReferral(Payloads.referralRequest(referralId, r, "ASHA-017", Language.EN, "Arrange review.", summary.nextStep, contract))
        assertTrue("referral failed: $created", created is ApiResult.Success)
        val pdfUrl = api.absolute((created as ApiResult.Success).value.pdfPath)!!
        val connection = URL(pdfUrl).openConnection() as HttpURLConnection
        val bytes = connection.inputStream.use { it.readBytes() }
        assertEquals(200, connection.responseCode)
        assertEquals("application/pdf", connection.contentType)
        assertTrue("not a PDF", String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-")
        assertTrue("PDF suspiciously small: ${bytes.size}", bytes.size > 1_000)
        java.io.File(System.getProperty("java.io.tmpdir"), "abhaynayan-referral-check.pdf").writeBytes(bytes)
    }
}
