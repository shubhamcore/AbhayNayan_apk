package `in`.abhaynayan.app.core

import `in`.abhaynayan.app.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEngineTest {
    private val synthetic = WorkflowEngine.assess(WorkflowEngine.syntheticFrames(), syntheticChart = true)
    private val maternalMeta = ScreeningMetadata(pregnancy = PregnancyStatus.PREGNANT, gestationalWeek = 20)

    private class RecordingHb(private val mode: ResultMode, private val output: () -> Double) : HemoglobinModel {
        var calls = 0
        override fun status() = ModelStatus(Biomarker.HEMOGLOBIN, mode, "fake-hb", "0", emptyList())
        override fun estimateGramsPerDecilitre(roi: RgbImage): Double { calls++; return output() }
    }

    private class RecordingBili : BilirubinModel {
        var calls = 0
        override fun status() = ModelStatus(Biomarker.BILIRUBIN, ResultMode.EXPERIMENTAL_RESEARCH_ONLY, "fake-bili", "0", emptyList())
        override fun estimateMilligramsPerDecilitre(roi: RgbImage, ageHours: Int): Double { calls++; return 10.0 }
    }

    private fun analyze(
        patient: `in`.abhaynayan.app.data.PatientEntity = TestFixtures.demoPatient,
        assessment: CaptureAssessment = synthetic,
        confirmed: Boolean = true,
        demoCase: String? = null,
        hb: HemoglobinModel = UnavailableHemoglobinModel(),
        bili: BilirubinModel = UnavailableBilirubinModel(),
        type: ScreeningType = ScreeningType.MATERNAL_ANEMIA,
        meta: ScreeningMetadata = maternalMeta,
    ) = WorkflowEngine.analyze(type, patient, meta, assessment, confirmed, demoCase, hb, bili, "ASHA-1", "PHC", "scr-test0001", "2026-10-10T10:00:00Z")

    @Test fun syntheticChartPassesChecksAndAcceptsDifferential() {
        assertTrue(synthetic.quality.issues.toString(), synthetic.quality.passed)
        assertTrue(synthetic.differential!!.accepted)
        assertTrue(synthetic.differentialStatus.startsWith("ACCEPTED"))
    }

    @Test fun demoProfileWithFixtureProducesClearlyLabelledSyntheticRecord() {
        val ready = analyze(demoCase = "maternal-high") as AnalysisOutcome.Ready
        val r = ready.record
        assertEquals(ResultMode.DEMO_SYNTHETIC.name, r.resultMode)
        assertEquals(7.8, r.estimate!!, 1e-9)
        assertTrue(r.synthetic)
        assertEquals("maternal-high", r.caseId)
        assertEquals(ReferralUrgency.PROMPT_CLINICIAN_REVIEW.name, r.referralUrgency)
    }

    @Test fun realProfileGetsNoEstimateAndCannotUseFixtures() {
        val ready = analyze(patient = TestFixtures.realPatient) as AnalysisOutcome.Ready
        assertNull(ready.record.estimate)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL.name, ready.record.resultMode)
        assertTrue(analyze(patient = TestFixtures.realPatient, demoCase = "maternal-high") is AnalysisOutcome.Blocked)
    }

    @Test fun failedQualityOrUnconfirmedRegionBlocksAnalysis() {
        val dark = RgbImage(100, 100, IntArray(10_000) { (0xff shl 24) or 0x050505 })
        val bad = WorkflowEngine.assess(CapturedFrames(dark, null, FrameMetadata(null, null, null, null, "t"), null, null, emptyList(), "test"), false)
        assertTrue(analyze(assessment = bad) is AnalysisOutcome.Blocked)
        assertTrue(analyze(confirmed = false) is AnalysisOutcome.Blocked)
    }

    @Test fun maternalWorkflowNeverCallsTheBilirubinModel() {
        val hb = RecordingHb(ResultMode.EXPERIMENTAL_RESEARCH_ONLY) { 11.0 }
        val bili = RecordingBili()
        val ready = analyze(patient = TestFixtures.realPatient, hb = hb, bili = bili) as AnalysisOutcome.Ready
        assertEquals(1, hb.calls); assertEquals(0, bili.calls)
        assertEquals(ResultMode.EXPERIMENTAL_RESEARCH_ONLY.name, ready.record.resultMode)
        assertEquals(11.0, ready.record.estimate!!, 1e-6)
    }

    @Test fun neonatalWorkflowUsesBilirubinModelWithAgeAndNeverHemoglobin() {
        val hb = RecordingHb(ResultMode.EXPERIMENTAL_RESEARCH_ONLY) { 11.0 }
        val bili = RecordingBili()
        analyze(patient = TestFixtures.realPatient, hb = hb, bili = bili, type = ScreeningType.NEONATAL_JAUNDICE,
            meta = ScreeningMetadata(infantAgeHours = 48, visibleJaundice = false))
        assertEquals(0, hb.calls); assertEquals(1, bili.calls)
    }

    @Test fun inferenceExceptionsAndInvalidOutputsNeverBecomeResults() {
        val throwing = RecordingHb(ResultMode.EXPERIMENTAL_RESEARCH_ONLY) { error("interpreter crashed") }
        assertTrue(analyze(patient = TestFixtures.realPatient, hb = throwing) is AnalysisOutcome.InferenceFailed)
        val nan = RecordingHb(ResultMode.EXPERIMENTAL_RESEARCH_ONLY) { Double.NaN }
        assertTrue(analyze(patient = TestFixtures.realPatient, hb = nan) is AnalysisOutcome.InferenceFailed)
        val absurd = RecordingHb(ResultMode.EXPERIMENTAL_RESEARCH_ONLY) { 99.0 }
        assertTrue(analyze(patient = TestFixtures.realPatient, hb = absurd) is AnalysisOutcome.InferenceFailed)
    }

    private fun shiftedFlash(frames: CapturedFrames, by: Int): RgbImage {
        val w = frames.ambient.width
        return RgbImage(w, frames.ambient.height, IntArray(frames.ambient.pixels.size) { i ->
            val x = i % w; val y = i / w
            frames.flash!!.pixels[y * w + (x + by).coerceIn(0, w - 1)]
        })
    }

    @Test fun shakyHandPairPassesWithoutRetake() {
        val frames = WorkflowEngine.syntheticFrames()
        val a = WorkflowEngine.assess(frames.copy(flash = shiftedFlash(frames, 9)), syntheticChart = true)
        assertTrue(a.quality.issues.toString(), a.quality.passed)
        assertTrue(a.differential!!.accepted)
        assertTrue(a.differential!!.motionCorrected)
        assertTrue(a.differentialStatus, "corrected" in a.differentialStatus)
    }

    @Test fun largeMovementSkipsFlashComparisonButStillPasses() {
        val frames = WorkflowEngine.syntheticFrames()
        val a = WorkflowEngine.assess(frames.copy(flash = shiftedFlash(frames, 120)), syntheticChart = true)
        assertTrue(QualityIssue.MOTION_BETWEEN_FRAMES in a.quality.issues)
        assertTrue("motion must not force a retake", a.quality.passed)
        assertTrue(analyze(assessment = a, demoCase = "maternal-normal") is AnalysisOutcome.Ready)
    }

    @Test fun importedSingleImageHasNoDifferential() {
        val frames = WorkflowEngine.syntheticFrames().copy(flash = null, flashMeta = null)
        val a = WorkflowEngine.assess(frames, syntheticChart = false)
        assertNull(a.differential)
        assertEquals("NOT_CAPTURED", a.differentialStatus)
    }
}
