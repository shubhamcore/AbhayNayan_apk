package `in`.abhaynayan.app.core

import `in`.abhaynayan.app.TestFixtures
import `in`.abhaynayan.app.data.PatientEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Edge-model loading, fail-closed behaviour and workflow integration. Unit tests use small synthetic
 * artifacts (`fake*`); tests that need the trained assets are skipped when the gitignored assets are absent.
 */
class EdgeModelsTest {
    private val n = ColourFeatures.NAMES.size

    private fun fakeArtifact(
        intercept: Double = 12.0,
        type: String = LinearColourArtifact.TYPE_REGRESSOR,
        edit: JSONObject.() -> Unit = {},
    ): String = JSONObject().apply {
        put("artifact_type", type); put("model_id", "fake"); put("version", "0.0-test")
        put("feature_version", ColourFeatures.VERSION); put("feature_names", JSONArray(ColourFeatures.NAMES))
        put("mean", JSONArray(List(n) { 0.5 })); put("scale", JSONArray(List(n) { 1.0 })); put("coefficients", JSONArray(List(n) { 0.0 }))
        put("intercept", intercept); put("validation_status", "RESEARCH_ONLY"); put("operating_threshold", 0.5)
        edit()
    }.toString()

    private fun card(sha: String, biomarker: String = "HEMOGLOBIN", runtime: String = "kotlin-linear-cf-v1") = JSONObject().apply {
        put("name", "fake"); put("version", "0.0-test"); put("biomarker", biomarker); put("input_kind", "colour_features")
        put("feature_version", ColourFeatures.VERSION); put("feature_count", n); put("artifact_sha256", sha)
        put("normalization", "cf-v1"); put("output_shape", JSONArray(listOf(1))); put("output_unit", if (biomarker == "HEMOGLOBIN") "g/dL" else "mg/dL")
        put("runtime", runtime); put("training_label_source", "synthetic test"); put("subjects", 10); put("samples", 10)
        put("subject_level_split", true); put("validation_status", "RESEARCH_ONLY")
    }.toString()

    private fun assets(vararg files: Pair<String, String>) = AssetSource { files.toMap()[it] }

    private fun hbAssets(model: String = fakeArtifact(), sha: String = LinearColourArtifact.sha256(model), meta: String = card(sha)) =
        assets(EdgeModelLoader.HB_MODEL to model, EdgeModelLoader.HB_METADATA to meta,
            EdgeModelLoader.JAUNDICE_CLASS_MODEL to fakeArtifact(0.0, LinearColourArtifact.TYPE_CLASSIFIER))

    private val pinkMasked = ColourFeaturesTest.load("pink_masked.png")

    // ----- loading / fail-closed -------------------------------------------------------------------

    @Test fun noAssetsMeansDisabledAndNoValue() {
        val loaded = EdgeModelLoader.load(assets(), experimentalOptIn = true)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, loaded.hemoglobin.status().mode)
        assertTrue(loaded.hemoglobin is UnavailableHemoglobinModel)
        assertNull(loaded.jaundiceClass)
        try { loaded.hemoglobin.estimateGramsPerDecilitre(pinkMasked); throw AssertionError("expected failure") } catch (_: ModelUnavailableException) { }
    }

    @Test fun researchModelNeedsOptIn() {
        val off = EdgeModelLoader.load(hbAssets(), experimentalOptIn = false)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, off.hemoglobin.status().mode)
        assertTrue(off.hemoglobin.status().reasons.any { "experimental mode is off" in it })
        assertNull(off.jaundiceClass)
        val on = EdgeModelLoader.load(hbAssets(), experimentalOptIn = true)
        assertEquals(ResultMode.EXPERIMENTAL_RESEARCH_ONLY, on.hemoglobin.status().mode)
        assertEquals(12.0, on.hemoglobin.estimateGramsPerDecilitre(pinkMasked), 1e-12)
        assertNotNull(on.jaundiceClass)
    }

    @Test fun corruptTamperedOrIncompatibleArtifactsStayDisabled() {
        val good = fakeArtifact()
        val cases = mapOf(
            "corrupt json" to hbAssets(model = "{oops", sha = LinearColourArtifact.sha256("{oops")),
            "checksum mismatch" to hbAssets(model = fakeArtifact(99.0), sha = LinearColourArtifact.sha256(good)),
            "feature version" to fakeArtifact { put("feature_version", "cf-v0") }.let { hbAssets(model = it) },
            "feature names" to fakeArtifact { put("feature_names", JSONArray(listOf("a"))) }.let { hbAssets(model = it) },
            "zero scale" to fakeArtifact { put("scale", JSONArray(List(n) { 0.0 })) }.let { hbAssets(model = it) },
            "classifier in Hb slot" to fakeArtifact(type = LinearColourArtifact.TYPE_CLASSIFIER).let { hbAssets(model = it) },
            "validated claim" to fakeArtifact { put("validation_status", "VALIDATED") }.let { hbAssets(model = it) },
            "bilirubin card" to hbAssets(meta = card(LinearColourArtifact.sha256(good), biomarker = "BILIRUBIN")),
            "unknown runtime" to hbAssets(meta = card(LinearColourArtifact.sha256(good), runtime = "tflite")),
            "missing card" to assets(EdgeModelLoader.HB_MODEL to good),
            "card without model" to assets(EdgeModelLoader.HB_METADATA to card(LinearColourArtifact.sha256(good))),
            "throwing asset source" to AssetSource { throw IllegalStateException("io") },
        )
        for ((name, source) in cases) {
            val hb = EdgeModelLoader.load(source, experimentalOptIn = true).hemoglobin
            assertEquals(name, ResultMode.DISABLED_NO_VALID_MODEL, hb.status().mode)
            assertTrue(name, hb is UnavailableHemoglobinModel)
        }
    }

    @Test fun bilirubinStaysDisabledEvenWithResearchModelsOn() {
        EdgeModelLoader.load(hbAssets(), experimentalOptIn = true)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, ModelRegistry.status(Biomarker.BILIRUBIN, experimentalOptIn = true).mode)
    }

    @Test fun jaundiceScorerIsAClassScoreWithThreshold() {
        val scorer = EdgeModelLoader.load(hbAssets(), experimentalOptIn = true).jaundiceClass!!
        val s = scorer.score(pinkMasked)
        assertEquals(0.5, s.score, 1e-12)
        assertTrue(s.jaundiceLike)
        try { LinearColourHemoglobinModel(ModelRegistry.status(Biomarker.HEMOGLOBIN), LinearColourArtifact.parse(
            fakeArtifact(type = LinearColourArtifact.TYPE_CLASSIFIER), LinearColourArtifact.TYPE_CLASSIFIER).first!!)
            throw AssertionError("classifier accepted in Hb slot")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun outOfDistributionInputIsFlagged() {
        val art = LinearColourArtifact.parse(fakeArtifact { put("training", JSONObject().apply {
            put("feature_min", JSONArray(List(n) { 0.0 })); put("feature_max", JSONArray(List(n) { 1e-3 })) }) },
            LinearColourArtifact.TYPE_REGRESSOR).first!!
        assertTrue(art.outOfDistribution(ColourFeatures.extract(pinkMasked).vector).isNotEmpty())
    }

    // ----- workflow integration --------------------------------------------------------------------

    private val paleMasked = ColourFeaturesTest.load("pale_masked.png")  // 200x90: above the 64 px minimum
    private val maskedAssessment = WorkflowEngine.assess(
        CapturedFrames(paleMasked, null, FrameMetadata(null, null, null, null, "test"), null, null, emptyList(), "Imported PNG image"),
        syntheticChart = false,
    )

    private fun run(hb: HemoglobinModel, assessment: CaptureAssessment = maskedAssessment, patient: PatientEntity = TestFixtures.demoPatient) = WorkflowEngine.analyze(
        ScreeningType.MATERNAL_ANEMIA, patient, ScreeningMetadata(pregnancy = PregnancyStatus.PREGNANT, gestationalWeek = 20),
        assessment, true, null, hb, UnavailableBilirubinModel(), "ASHA-1", "PHC", "scr-edge0001", "2026-10-22T10:00:00Z",
    )

    @Test fun experimentalEdgeModelProducesLabelledLocalEstimate() {
        assertTrue(maskedAssessment.quality.issues.toString(), maskedAssessment.quality.passed)
        val hb = EdgeModelLoader.load(hbAssets(), experimentalOptIn = true).hemoglobin
        val ready = run(hb) as AnalysisOutcome.Ready
        assertEquals(12.0, ready.record.estimate!!, 1e-9)
        assertEquals(ResultMode.EXPERIMENTAL_RESEARCH_ONLY.name, ready.record.resultMode)
        assertEquals(false, ready.record.synthetic)
        assertEquals("NONE", ready.record.demoTag)
    }

    @Test fun disabledResearchModelIsNotNamedAsTheRecordsModel() {
        val ready = run(EdgeModelLoader.load(hbAssets(), experimentalOptIn = false).hemoglobin) as AnalysisOutcome.Ready
        assertEquals(null, ready.record.estimate)
        assertEquals("none", ready.record.modelVersion)
    }

    @Test fun experimentalEdgeModelNeverAttachesToARealPerson() {
        val hb = EdgeModelLoader.load(hbAssets(), experimentalOptIn = true).hemoglobin
        val ready = run(hb, patient = TestFixtures.realPatient) as AnalysisOutcome.Ready
        assertEquals(null, ready.record.estimate)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL.name, ready.record.resultMode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, ready.modelStatus!!.mode)
        // The Analysis screen uses the same rule, so it never shows "Experimental" for a real person.
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, WorkflowEngine.statusFor(hb.status(), TestFixtures.realPatient).mode)
        assertEquals(ResultMode.EXPERIMENTAL_RESEARCH_ONLY, WorkflowEngine.statusFor(hb.status(), TestFixtures.demoPatient).mode)
    }

    @Test fun implausibleOrFailingModelNeverYieldsAValue() {
        assertTrue(maskedAssessment.quality.issues.toString(), maskedAssessment.quality.passed)
        listOf(fakeArtifact(40.0), fakeArtifact(1.0)).forEach { model ->
            val outcome = run(EdgeModelLoader.load(hbAssets(model = model), experimentalOptIn = true).hemoglobin)
            assertTrue(outcome.toString(), outcome is AnalysisOutcome.InferenceFailed)
        }
    }

    @Test fun modelInputUsesWholeMaskedImageAndAmbientNotDifferential() {
        assertTrue(paleMasked.pixels.contentEquals(WorkflowEngine.modelInput(maskedAssessment)!!.pixels))
        val chart = WorkflowEngine.assess(WorkflowEngine.syntheticFrames(), syntheticChart = true)
        val input = WorkflowEngine.modelInput(chart)!!
        // RgbImage equality is by array reference, so compare pixel contents.
        val ambientCrop = OpticalPipeline.crop(chart.frames.ambient, chart.roi)
        assertTrue(ambientCrop.pixels.contentEquals(input.pixels))
        assertTrue(chart.optical!!.differentialApplied)  // the differential exists, but is not what the model sees
        assertTrue(!chart.optical!!.roi.pixels.contentEquals(input.pixels))
    }

    @Test fun mostlyTransparentSegmentedCropPassesMaskAwareGate() {
        // ~95 % transparent background, like the Eyes-defy-anemia crops; judged on the whole frame it read as TOO_DARK.
        val src = paleMasked
        val w = 400; val h = 300
        val px = IntArray(w * h)  // fully transparent black
        for (y in 0 until src.height / 2) for (x in 0 until src.width / 2) px[(y + 120) * w + x + 150] = src.pixels[(y + 20) * src.width + x + 50]
        val img = RgbImage(w, h, px)
        val q = ImageQualityChecker.check(img, OpticalPipeline.roiBounds(w, h, NormalizedRect.MANUAL_CENTER_GUIDE))
        assertTrue(q.issues.toString(), q.passed)
        val opaque = RgbImage(w, h, IntArray(w * h) { (0xff shl 24) or 0x101010 })
        assertTrue(QualityIssue.TOO_DARK in ImageQualityChecker.check(opaque, OpticalPipeline.roiBounds(w, h, NormalizedRect.MANUAL_CENTER_GUIDE)).issues)
    }

    // ----- trained artifacts (gitignored; skipped when absent) --------------------------------------

    private fun assetFile(path: String) = File("src/main/assets/$path")

    @Test fun trainedAssetsReproducePythonGoldenOutputs() {
        assumeTrue("trained assets not present", assetFile(EdgeModelLoader.HB_MODEL).exists() && assetFile(EdgeModelLoader.JAUNDICE_CLASS_MODEL).exists())
        val loaded = EdgeModelLoader.load({ p -> assetFile(p).takeIf { it.exists() }?.readText() }, experimentalOptIn = true)
        assertEquals(loaded.hemoglobin.status().reasons.toString(), ResultMode.EXPERIMENTAL_RESEARCH_ONLY, loaded.hemoglobin.status().mode)
        val tol = ColourFeaturesTest.golden.getJSONObject("tolerance").getDouble("linear_output_abs")
        var checked = 0
        for (c in ColourFeaturesTest.cases().filter { it.has("outputs") }) {
            val img = ColourFeaturesTest.load(c.getString("file"))
            val out = c.getJSONObject("outputs")
            assertEquals(c.getString("name"), out.getDouble("hb-ridge-cf"), loaded.hemoglobin.estimateGramsPerDecilitre(img), tol)
            assertEquals(c.getString("name"), out.getDouble("njn-logreg-cf"), loaded.jaundiceClass!!.score(img).score, tol)
            checked++
        }
        assertEquals(5, checked)
    }
}
