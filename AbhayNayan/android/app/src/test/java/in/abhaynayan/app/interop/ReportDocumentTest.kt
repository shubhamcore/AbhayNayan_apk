package `in`.abhaynayan.app.interop

import `in`.abhaynayan.app.TestFixtures
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.LocalCounselling
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReportDocumentTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test fun reportIsProducedForARecordWithoutAnEstimate() {
        val record = TestFixtures.disabledRecord()
        val counsel = LocalCounselling.text(ScreeningType.NEONATAL_JAUNDICE, ResultMode.DISABLED_NO_VALID_MODEL, Language.EN)
        val doc = ReportDocument.from(record, Language.EN, counsel, "On this phone · offline text", "0.3.0-demo", "2026-10-10T10:00:00Z", ist)
        val text = doc.toPlainText()
        listOf(
            "Result · No estimate produced", "Not measured — no valid model is available",
            "Next step · Urgent referral", record.screeningId, record.patientId,
            "Infant age", "20 hours", "Image quality", "PASSED",
            "No bilirubin estimate was made", "DISABLED_NO_VALID_MODEL", "No photo is included",
            "10 Oct 2026, 15:30 (Asia/Kolkata)",
        ).forEach { assertTrue("missing '$it' in:\n$text", it in text) }
        assertEquals(Tone.URGENT, doc.sections.first { it.heading.startsWith("Next step") }.tone)
        assertFalse(Regex("\\d+(\\.\\d)? mg/dL").containsMatchIn(text))
        assertFalse("nickname must never appear", text.contains(TestFixtures.realPatient.displayName))
    }

    @Test fun demoReportLabelsTheValueAsSyntheticNotMeasured() {
        val record = TestFixtures.demoRecord()
        val doc = ReportDocument.from(record, Language.HI, LocalCounselling.text(ScreeningType.MATERNAL_ANEMIA, ResultMode.DEMO_SYNTHETIC, Language.HI), null, "0.3.0-demo", zone = ist)
        val result = doc.sections.first()
        assertEquals(Tone.DEMO, result.tone)
        assertTrue(result.rows.any { it.first == "Synthetic demo value" && it.second == "9.4 g/dL" })
        assertTrue(result.paragraphs.single().startsWith("FIXED DEMO VALUE — NOT MEASURED"))
        val text = doc.toPlainText()
        assertTrue("Hindi counselling included", text.contains("प्रोटोटाइप"))
        assertTrue(text.contains("Counselling (हिन्दी)"))
        assertFalse(text.contains(TestFixtures.demoPatient.displayName))
    }

    @Test fun reportWithoutCounsellingStillHasCoreSections() {
        val doc = ReportDocument.from(TestFixtures.demoRecord(), Language.EN, null, null, "0.3.0-demo", zone = ist)
        assertEquals(listOf("Result", "Next step", "Screening details", "Image checks", "Provenance and limitations"),
            doc.sections.map { it.heading.substringBefore(" ·").substringBefore(" (") })
    }
}
