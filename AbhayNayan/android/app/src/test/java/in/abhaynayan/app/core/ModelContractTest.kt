package `in`.abhaynayan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractTest {
    private fun card(biomarker: String = "HEMOGLOBIN", status: String = "RESEARCH_ONLY", unit: String = "g/dL", approval: String? = null) = """
        {"name":"test-model","version":"0.1","biomarker":"$biomarker","input_shape":[1,224,224,3],
         "input_dtype":"float32","channel_order":"RGB","normalization":"x/127.5-1","output_shape":[1,1],
         "output_unit":"$unit","runtime":"tflite","training_label_source":"lab Hb","subjects":145,"samples":288,
         "subject_level_split":true,"validation_status":"$status","redistribution_approved":true
         ${approval?.let { ",\"approval_reference\":\"$it\"" } ?: ""}}
    """.trimIndent()

    private fun resolve(
        expected: Biomarker = Biomarker.HEMOGLOBIN,
        json: String? = card(),
        artifact: Boolean = true,
        runtime: Boolean = true,
        experimental: Boolean = false,
        buildAllows: Boolean = false,
        auth: ValidationAuthorization? = null,
    ): ModelStatus {
        val (meta, problems) = json?.let { ModelArtifactMetadata.parse(it) } ?: (null to emptyList())
        return ModelModeResolver.resolve(expected, meta, problems, artifact, runtime, experimental, buildAllows, auth)
    }

    @Test fun shippedBuildDisablesBothBiomarkers() {
        val hb = ModelRegistry.status(Biomarker.HEMOGLOBIN)
        val bili = ModelRegistry.status(Biomarker.BILIRUBIN)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, hb.mode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, bili.mode)
        assertTrue(hb.reasons.any { "artifact" in it })
        assertFalse(ModelRegistry.VALIDATED_MODE_ALLOWED)
    }

    @Test fun missingArtifactRuntimeOrCardFailsClosed() {
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(artifact = false, experimental = true).mode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(runtime = false, experimental = true).mode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(json = null, experimental = true).mode)
    }

    @Test fun hemoglobinModelIsNeverUsedForBilirubin() {
        val status = resolve(expected = Biomarker.BILIRUBIN, json = card(biomarker = "HEMOGLOBIN"), experimental = true)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, status.mode)
        assertTrue(status.reasons.any { "cannot be used" in it })
    }

    @Test fun researchModelNeedsExplicitExperimentalOptIn() {
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(experimental = false).mode)
        assertEquals(ResultMode.EXPERIMENTAL_RESEARCH_ONLY, resolve(experimental = true).mode)
    }

    @Test fun validatedModeRequiresBuildFlagAndMatchingAuthorization() {
        val validated = card(status = "VALIDATED", approval = "IRB-2026-01")
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(json = validated).mode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(json = validated, buildAllows = true, auth = ValidationAuthorization("OTHER")).mode)
        assertEquals(ResultMode.DISABLED_NO_VALID_MODEL, resolve(json = validated, buildAllows = false, auth = ValidationAuthorization("IRB-2026-01")).mode)
        assertEquals(ResultMode.VALIDATED_FOR_INTENDED_USE, resolve(json = validated, buildAllows = true, auth = ValidationAuthorization("IRB-2026-01")).mode)
    }

    @Test fun modelCardRejectsWrongUnitAndInvalidShapes() {
        val (meta, problems) = ModelArtifactMetadata.parse(card(unit = "mg/dL"))
        assertNull(meta)
        assertTrue(problems.any { "does not match" in it })
        val badShape = card().replace("[1,224,224,3]", "[224,224]").replace("[1,1]", "[1,5]")
        val (meta2, problems2) = ModelArtifactMetadata.parse(badShape)
        assertNull(meta2)
        assertTrue(problems2.size >= 2)
        assertNull(ModelArtifactMetadata.parse("not json").first)
        assertNotNull(ModelArtifactMetadata.parse(card()).first)
    }

    @Test fun cardWithoutSubjectLevelSplitIsRejected() {
        val (meta, problems) = ModelArtifactMetadata.parse(card().replace("\"subject_level_split\":true", "\"subject_level_split\":false"))
        assertNull(meta)
        assertTrue(problems.any { "subject-level" in it })
    }

    @Test(expected = ModelUnavailableException::class)
    fun unavailableHemoglobinModelThrowsInsteadOfGuessing() {
        UnavailableHemoglobinModel().estimateGramsPerDecilitre(RgbImage(2, 2, IntArray(4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bilirubinSlotRejectsHemoglobinStatus() {
        UnavailableBilirubinModel(ModelRegistry.status(Biomarker.HEMOGLOBIN))
    }

    @Test fun outputValidatorRejectsNonFiniteOutOfRangeAndWrongShape() {
        assertEquals(11.2, ModelOutputValidator.validate(Biomarker.HEMOGLOBIN, floatArrayOf(11.2f)), 1e-4)
        listOf(floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(40f), floatArrayOf(1f, 2f), floatArrayOf()).forEach { raw ->
            try {
                ModelOutputValidator.validate(Biomarker.HEMOGLOBIN, raw)
                throw AssertionError("Expected rejection for ${raw.toList()}")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun registryDocumentsInspectedCandidatesWithoutBundlingThem() {
        val candidates = ModelRegistry.inspectedCandidates
        assertTrue(candidates.none { it.artifactInApk })
        assertTrue(candidates.any { it.biomarker == Biomarker.HEMOGLOBIN && "1.5041" in it.evidence })
        assertTrue(candidates.any { it.biomarker == Biomarker.HEMOGLOBIN && "−0.197" in it.evidence })
        assertTrue(candidates.any { it.biomarker == Biomarker.BILIRUBIN && "No bilirubin label" in it.evidence })
    }

    @Test fun onlyDisabledModeHasNoEstimate() {
        assertFalse(ResultMode.DISABLED_NO_VALID_MODEL.producesEstimate)
        assertTrue(ResultMode.DEMO_SYNTHETIC.producesEstimate)
    }
}
