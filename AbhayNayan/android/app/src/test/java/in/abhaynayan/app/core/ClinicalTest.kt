package `in`.abhaynayan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalTest {
    @Test fun neonatalWorkflowRequiresAgeInHoursAndJaundiceAnswer() {
        val errors = MetadataValidator.validate(ScreeningType.NEONATAL_JAUNDICE, MetadataForm())
        assertTrue("infantAgeHours" in errors)
        assertTrue("visibleJaundice" in errors)
        assertTrue(MetadataValidator.validate(ScreeningType.NEONATAL_JAUNDICE, MetadataForm(infantAgeHours = "700", visibleJaundice = false)).containsKey("infantAgeHours"))
        assertTrue(MetadataValidator.validate(ScreeningType.NEONATAL_JAUNDICE, MetadataForm(infantAgeHours = "36", visibleJaundice = true, gestationalAgeAtBirthWeeks = "60")).containsKey("gestationalAgeAtBirthWeeks"))
        assertTrue(MetadataValidator.validate(ScreeningType.NEONATAL_JAUNDICE, MetadataForm(infantAgeHours = "36", visibleJaundice = true, gestationalAgeAtBirthWeeks = "38")).isEmpty())
    }

    @Test fun maternalWorkflowDoesNotNeedNewbornFieldsAndValidatesWeek() {
        assertTrue(MetadataValidator.validate(ScreeningType.MATERNAL_ANEMIA, MetadataForm()).isEmpty())
        assertTrue(MetadataValidator.validate(ScreeningType.MATERNAL_ANEMIA, MetadataForm(pregnancy = PregnancyStatus.PREGNANT, gestationalWeek = "50")).containsKey("gestationalWeek"))
        assertTrue(MetadataValidator.validate(ScreeningType.MATERNAL_ANEMIA, MetadataForm(pregnancy = PregnancyStatus.NOT_PREGNANT, gestationalWeek = "20")).containsKey("gestationalWeek"))
        val meta = MetadataValidator.toMetadata(ScreeningType.MATERNAL_ANEMIA, MetadataForm(pregnancy = PregnancyStatus.PREGNANT, gestationalWeek = "24"))
        assertEquals(24, meta.gestationalWeek)
        assertEquals(null, meta.infantAgeHours)
    }

    @Test fun dangerSignsFromTheOtherWorkflowAreIgnored() {
        val form = MetadataForm(dangerSigns = setOf(DangerSign.NEWBORN_CONVULSIONS))
        val meta = MetadataValidator.toMetadata(ScreeningType.MATERNAL_ANEMIA, form)
        assertTrue(meta.dangerSigns.isEmpty())
    }

    @Test fun metadataJsonRoundTripsAndToleratesGarbage() {
        val meta = ScreeningMetadata(infantAgeHours = 30, gestationalAgeAtBirthWeeks = 36, visibleJaundice = true, dangerSigns = setOf(DangerSign.NEWBORN_POOR_FEEDING))
        assertEquals(meta, ScreeningMetadata.fromJson(meta.toJson()))
        assertEquals(ScreeningMetadata(), ScreeningMetadata.fromJson("{not json"))
        assertEquals(ScreeningMetadata(), ScreeningMetadata.fromJson(null))
    }

    @Test fun urgencyRulesOnlyEscalate() {
        val t = ScreeningType.NEONATAL_JAUNDICE
        assertEquals(ReferralUrgency.URGENT_REFERRAL, ReferralRules.urgency(t, ScreeningMetadata(infantAgeHours = 12, visibleJaundice = true)))
        assertEquals(ReferralUrgency.PROMPT_CLINICIAN_REVIEW, ReferralRules.urgency(t, ScreeningMetadata(infantAgeHours = 60, visibleJaundice = true)))
        assertEquals(ReferralUrgency.URGENT_REFERRAL, ReferralRules.urgency(t, ScreeningMetadata(infantAgeHours = 60, visibleJaundice = false, dangerSigns = setOf(DangerSign.NEWBORN_YELLOW_PALMS_SOLES))))
        assertEquals(ReferralUrgency.PROMPT_CLINICIAN_REVIEW, ReferralRules.urgency(t, ScreeningMetadata(infantAgeHours = 60, visibleJaundice = false, gestationalAgeAtBirthWeeks = 35)))
        assertEquals(ReferralUrgency.CONFIRMATORY_TESTING, ReferralRules.urgency(t, ScreeningMetadata(infantAgeHours = 60, visibleJaundice = false)))
        // A demo tag can raise urgency but a "low" demo tag never lowers a danger sign.
        val m = ScreeningType.MATERNAL_ANEMIA
        assertEquals(ReferralUrgency.URGENT_REFERRAL, ReferralRules.urgency(m, ScreeningMetadata(dangerSigns = setOf(DangerSign.MATERNAL_BREATHLESS)), "DEMO_LOW"))
        assertEquals(ReferralUrgency.PROMPT_CLINICIAN_REVIEW, ReferralRules.urgency(m, ScreeningMetadata(), "DEMO_HIGH"))
        assertEquals(ReferralUrgency.CONFIRMATORY_TESTING, ReferralRules.urgency(m, ScreeningMetadata(), "DEMO_LOW"))
    }

    @Test fun counsellingExistsForEveryTypeModeAndLanguage() {
        for (type in ScreeningType.entries) for (mode in ResultMode.entries) for (lang in Language.entries) {
            val text = LocalCounselling.text(type, mode, lang)
            assertTrue(text.fullText.isNotBlank())
        }
        for (u in ReferralUrgency.entries) for (lang in Language.entries) assertTrue(LocalCounselling.urgencyLine(u, lang).isNotBlank())
    }

    @Test fun demoCounsellingMatchesBackendFallbackAndNoEstimateTextNeverClaimsAValue() {
        val demo = LocalCounselling.text(ScreeningType.NEONATAL_JAUNDICE, ResultMode.DEMO_SYNTHETIC, Language.EN)
        assertEquals("This is a synthetic prototype estimate, not a serum bilirubin result or diagnosis.", demo.summary)
        val none = LocalCounselling.text(ScreeningType.NEONATAL_JAUNDICE, ResultMode.DISABLED_NO_VALID_MODEL, Language.EN)
        assertTrue(none.summary.startsWith("No bilirubin estimate"))
        assertFalse(Regex("\\d+(\\.\\d+)?\\s*(mg|g)/dL").containsMatchIn(none.fullText))
    }

    @Test fun languageSwitchingFallsBackToEnglishForUnknownCodes() {
        assertEquals(Language.HI, Language.fromCode("hi"))
        assertEquals(Language.BHO, Language.fromCode("bho"))
        assertEquals(Language.EN, Language.fromCode("fr"))
        assertEquals(Language.EN, Language.fromCode(null))
        assertTrue(LocalCounselling.text(ScreeningType.MATERNAL_ANEMIA, ResultMode.DEMO_SYNTHETIC, Language.HI).summary.contains("प्रोटोटाइप"))
    }

    @Test fun demoFixturesMatchBackendCasesExactly() {
        val expected = mapOf(
            "maternal-normal" to Triple(11.8, "DEMO_LOW", .82 to .91),
            "maternal-moderate" to Triple(9.4, "DEMO_MODERATE", .82 to .91),
            "maternal-high" to Triple(7.8, "DEMO_HIGH", .79 to .90),
            "jaundice-review" to Triple(12.8, "REQUIRES_REVIEW", .79 to .90),
            "jaundice-escalate" to Triple(18.0, "DEMO_ESCALATE", .76 to .88),
        )
        val all = ScreeningType.entries.flatMap { DemoInference.cases(it) }
        assertEquals(expected.keys, all.map { it.id }.toSet())
        all.forEach { c ->
            val (estimate, tag, scores) = expected.getValue(c.id)
            assertEquals(estimate, c.estimate, 1e-9); assertEquals(tag, c.demoTag)
            assertEquals(scores.first, c.confidencePlaceholder, 1e-9); assertEquals(scores.second, c.fixtureQualityPlaceholder, 1e-9)
        }
        assertEquals("maternal-high", DemoInference.resolve(ScreeningType.MATERNAL_ANEMIA, null, "DEMO_HIGH")?.id)
    }
}
