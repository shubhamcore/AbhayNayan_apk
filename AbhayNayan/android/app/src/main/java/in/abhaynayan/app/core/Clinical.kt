package `in`.abhaynayan.app.core

import org.json.JSONArray
import org.json.JSONObject

enum class Language(val code: String, val label: String, val nativeLabel: String) {
    EN("en", "English", "English"),
    HI("hi", "Hindi", "हिन्दी"),
    BHO("bho", "Bhojpuri", "भोजपुरी");

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: EN
    }
}

enum class PregnancyStatus { PREGNANT, NOT_PREGNANT, UNKNOWN }

/** A danger sign only ever raises urgency; it never lowers it. */
enum class DangerSign(val type: ScreeningType, val label: String) {
    MATERNAL_BREATHLESS(ScreeningType.MATERNAL_ANEMIA, "Breathless at rest or with light work"),
    MATERNAL_FAINTING(ScreeningType.MATERNAL_ANEMIA, "Fainting, dizziness, or extreme tiredness"),
    MATERNAL_PALPITATIONS(ScreeningType.MATERNAL_ANEMIA, "Fast or pounding heartbeat"),
    MATERNAL_BLEEDING(ScreeningType.MATERNAL_ANEMIA, "Any vaginal bleeding"),
    NEWBORN_YELLOW_PALMS_SOLES(ScreeningType.NEONATAL_JAUNDICE, "Yellow palms or soles"),
    NEWBORN_POOR_FEEDING(ScreeningType.NEONATAL_JAUNDICE, "Not feeding well / stopped feeding"),
    NEWBORN_LETHARGIC(ScreeningType.NEONATAL_JAUNDICE, "Very sleepy, hard to wake, or floppy"),
    NEWBORN_CONVULSIONS(ScreeningType.NEONATAL_JAUNDICE, "Convulsions / fits"),
    NEWBORN_FEVER_OR_COLD(ScreeningType.NEONATAL_JAUNDICE, "Fever or body feels cold");

    companion object {
        fun forType(type: ScreeningType) = entries.filter { it.type == type }
    }
}

/** Minimal workflow metadata. No names, phone numbers, or addresses. */
data class ScreeningMetadata(
    val pregnancy: PregnancyStatus = PregnancyStatus.UNKNOWN,
    val gestationalWeek: Int? = null,
    val infantAgeHours: Int? = null,
    val gestationalAgeAtBirthWeeks: Int? = null,
    val visibleJaundice: Boolean? = null,
    val dangerSigns: Set<DangerSign> = emptySet(),
) {
    fun toJson(): String = JSONObject().apply {
        put("pregnancy", pregnancy.name)
        gestationalWeek?.let { put("gestational_week", it) }
        infantAgeHours?.let { put("infant_age_hours", it) }
        gestationalAgeAtBirthWeeks?.let { put("gestational_age_at_birth_weeks", it) }
        visibleJaundice?.let { put("visible_jaundice", it) }
        put("danger_signs", JSONArray(dangerSigns.map { it.name }))
    }.toString()

    companion object {
        fun fromJson(json: String?): ScreeningMetadata {
            if (json.isNullOrBlank()) return ScreeningMetadata()
            return try {
                val o = JSONObject(json)
                val signs = o.optJSONArray("danger_signs")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> DangerSign.entries.firstOrNull { it.name == a.optString(i) } }
                }.orEmpty().toSet()
                ScreeningMetadata(
                    pregnancy = PregnancyStatus.entries.firstOrNull { it.name == o.optString("pregnancy") } ?: PregnancyStatus.UNKNOWN,
                    gestationalWeek = o.optIntOrNull("gestational_week"),
                    infantAgeHours = o.optIntOrNull("infant_age_hours"),
                    gestationalAgeAtBirthWeeks = o.optIntOrNull("gestational_age_at_birth_weeks"),
                    visibleJaundice = if (o.has("visible_jaundice")) o.optBoolean("visible_jaundice") else null,
                    dangerSigns = signs,
                )
            } catch (_: Exception) { ScreeningMetadata() }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    }
}

object MetadataValidator {
    /** Returns field → message for every problem; empty when the form may continue. */
    fun validate(type: ScreeningType, form: MetadataForm): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        when (type) {
            ScreeningType.MATERNAL_ANEMIA -> {
                val week = form.gestationalWeek.trim()
                if (form.pregnancy == PregnancyStatus.PREGNANT && week.isNotEmpty()) {
                    val value = week.toIntOrNull()
                    if (value == null || value !in 1..45) errors["gestationalWeek"] = "Enter a week between 1 and 45, or leave it blank."
                }
                if (form.pregnancy != PregnancyStatus.PREGNANT && week.isNotEmpty()) errors["gestationalWeek"] = "Gestational week applies only to a current pregnancy."
            }
            ScreeningType.NEONATAL_JAUNDICE -> {
                val age = form.infantAgeHours.trim().toIntOrNull()
                when {
                    form.infantAgeHours.isBlank() -> errors["infantAgeHours"] = "Age in hours is required for newborn jaundice review."
                    age == null || age < 0 -> errors["infantAgeHours"] = "Enter the age as a whole number of hours."
                    age > 672 -> errors["infantAgeHours"] = "This workflow covers newborns up to 28 days (672 hours)."
                }
                val ga = form.gestationalAgeAtBirthWeeks.trim()
                if (ga.isNotEmpty() && (ga.toIntOrNull() == null || ga.toInt() !in 22..44)) {
                    errors["gestationalAgeAtBirthWeeks"] = "Enter weeks between 22 and 44, or leave it blank."
                }
                if (form.visibleJaundice == null) errors["visibleJaundice"] = "Answer whether yellow colour is visible."
            }
        }
        return errors
    }

    fun toMetadata(type: ScreeningType, form: MetadataForm): ScreeningMetadata {
        require(validate(type, form).isEmpty()) { "Metadata form has validation errors." }
        val signs = form.dangerSigns.filter { it.type == type }.toSet()
        return when (type) {
            ScreeningType.MATERNAL_ANEMIA -> ScreeningMetadata(
                pregnancy = form.pregnancy,
                gestationalWeek = form.gestationalWeek.trim().toIntOrNull()?.takeIf { form.pregnancy == PregnancyStatus.PREGNANT },
                dangerSigns = signs,
            )
            ScreeningType.NEONATAL_JAUNDICE -> ScreeningMetadata(
                infantAgeHours = form.infantAgeHours.trim().toInt(),
                gestationalAgeAtBirthWeeks = form.gestationalAgeAtBirthWeeks.trim().toIntOrNull(),
                visibleJaundice = form.visibleJaundice,
                dangerSigns = signs,
            )
        }
    }
}

data class MetadataForm(
    val pregnancy: PregnancyStatus = PregnancyStatus.UNKNOWN,
    val gestationalWeek: String = "",
    val infantAgeHours: String = "",
    val gestationalAgeAtBirthWeeks: String = "",
    val visibleJaundice: Boolean? = null,
    val dangerSigns: Set<DangerSign> = emptySet(),
)

enum class ReferralUrgency(val label: String, val action: String) {
    URGENT_REFERRAL("Urgent referral", "Danger sign recorded: arrange urgent referral to the nearest facility under local protocol now."),
    PROMPT_CLINICIAN_REVIEW("Prompt clinician review", "Arrange prompt review by a qualified clinician and confirmatory testing."),
    CONFIRMATORY_TESTING("Confirmatory testing", "Arrange the appropriate confirmatory test and follow the local protocol."),
}

/**
 * Deterministic, escalation-only next-step rules. They never conclude that a person is well and never
 * use an image-derived value. Danger-sign lists follow WHO IMNCI / maternal danger-sign guidance in spirit.
 */
object ReferralRules {
    const val REVIEW_STATUS = "DRAFT: escalation-only rules pending review by the programme's clinical governance lead before field use."

    fun urgency(type: ScreeningType, metadata: ScreeningMetadata, demoTag: String? = null): ReferralUrgency {
        if (metadata.dangerSigns.any { it.type == type }) return ReferralUrgency.URGENT_REFERRAL
        if (type == ScreeningType.NEONATAL_JAUNDICE) {
            val age = metadata.infantAgeHours
            // Visible jaundice in the first 24 hours of life always needs urgent assessment.
            if (metadata.visibleJaundice == true && age != null && age < 24) return ReferralUrgency.URGENT_REFERRAL
            if (metadata.visibleJaundice == true) return ReferralUrgency.PROMPT_CLINICIAN_REVIEW
            val ga = metadata.gestationalAgeAtBirthWeeks
            if (ga != null && ga < 37) return ReferralUrgency.PROMPT_CLINICIAN_REVIEW
        }
        // Demo tags mirror the backend: review/high/escalate tags require referral; they cannot lower urgency.
        val tag = demoTag.orEmpty().uppercase()
        if (listOf("HIGH", "ESCALATE", "REVIEW").any { it in tag }) return ReferralUrgency.PROMPT_CLINICIAN_REVIEW
        return ReferralUrgency.CONFIRMATORY_TESTING
    }
}

data class CounselText(
    val summary: String,
    val riskExplanation: String,
    val script: String,
    val nextStep: String,
    val safetyNote: String,
) {
    val fullText: String get() = listOf(summary, riskExplanation, script, nextStep, safetyNote).joinToString(" ")
}

/** Offline counselling. DEMO text is copied verbatim from backend/app/services/language.py. */
object LocalCounselling {
    fun text(type: ScreeningType, mode: ResultMode, language: Language): CounselText =
        if (mode == ResultMode.DEMO_SYNTHETIC) DEMO.getValue(type).getValue(language) else NO_ESTIMATE.getValue(type).getValue(language)

    fun urgencyLine(urgency: ReferralUrgency, language: Language): String = when (language) {
        Language.EN -> urgency.action
        Language.HI -> when (urgency) {
            ReferralUrgency.URGENT_REFERRAL -> "खतरे का लक्षण दर्ज है: स्थानीय प्रोटोकॉल के अनुसार तुरंत नज़दीकी स्वास्थ्य केंद्र भेजें।"
            ReferralUrgency.PROMPT_CLINICIAN_REVIEW -> "योग्य चिकित्सक से जल्द समीक्षा और पुष्टि जांच कराएं।"
            ReferralUrgency.CONFIRMATORY_TESTING -> "उपयुक्त पुष्टि जांच कराएं और स्थानीय प्रोटोकॉल का पालन करें।"
        }
        Language.BHO -> when (urgency) {
            ReferralUrgency.URGENT_REFERRAL -> "खतरा के लक्षण दर्ज बा: स्थानीय नियम के हिसाब से तुरंत नजदीकी स्वास्थ्य केंद्र भेजीं।"
            ReferralUrgency.PROMPT_CLINICIAN_REVIEW -> "योग्य डॉक्टर से जल्दी देखाईं आ पुष्टि जांच कराईं।"
            ReferralUrgency.CONFIRMATORY_TESTING -> "उचित पुष्टि जांच कराईं आ स्थानीय नियम मानीं।"
        }
    }

    private val DEMO = mapOf(
        ScreeningType.MATERNAL_ANEMIA to mapOf(
            Language.EN to CounselText(
                "This is a synthetic prototype estimate, not a laboratory result or diagnosis.",
                "The demo category is a software demonstration tag only; it is not a clinical risk classification.",
                "Please arrange confirmation with the appropriate health-centre test and follow local maternal-care protocol. A clinician should review the result.",
                "Confirm with appropriate clinical testing; follow the local maternal-health protocol and clinician advice.",
                "Do not use this demo value to start, stop, or change treatment.",
            ),
            Language.HI to CounselText(
                "यह केवल एक कृत्रिम प्रोटोटाइप अनुमान है, लैब रिपोर्ट या निदान नहीं।",
                "डेमो श्रेणी केवल सॉफ्टवेयर प्रदर्शन के लिए है; यह चिकित्सकीय जोखिम वर्गीकरण नहीं है।",
                "कृपया स्वास्थ्य केंद्र में उपयुक्त जांच से इसकी पुष्टि कराएं और स्थानीय मातृ-स्वास्थ्य प्रोटोकॉल का पालन करें। स्वास्थ्यकर्मी या चिकित्सक से चर्चा करें।",
                "उपयुक्त जांच से पुष्टि करें और स्थानीय मातृ-स्वास्थ्य प्रोटोकॉल व चिकित्सक की सलाह का पालन करें।",
                "इस डेमो मान के आधार पर दवा शुरू, बंद या बदलें नहीं।",
            ),
            Language.BHO to CounselText(
                "ई खाली डेमो खातिर बनावल अंदाजा बा, लैब रिपोर्ट भा पक्का निदान नइखे।",
                "डेमो के श्रेणी खाली सॉफ्टवेयर देखावे खातिर बा; ई असली मेडिकल जोखिम के फैसला नइखे।",
                "कृपया स्वास्थ्य केंद्र पर जरूरी जांच कराके पुष्टि कराईं आ स्थानीय मातृ-स्वास्थ्य नियम मानीं। डॉक्टर भा स्वास्थ्यकर्मी से बात करीं।",
                "उचित जांच से पुष्टि कराईं आ स्थानीय मातृ-स्वास्थ्य नियम आ डॉक्टर के सलाह मानीं।",
                "एह डेमो मान के आधार पर दवाई शुरू, बंद भा बदलीं मत।",
            ),
        ),
        ScreeningType.NEONATAL_JAUNDICE to mapOf(
            Language.EN to CounselText(
                "This is a synthetic prototype estimate, not a serum bilirubin result or diagnosis.",
                "Newborn review depends on age in hours, gestational age, symptoms, and local protocol. This demo does not calculate a treatment threshold.",
                "Please arrange prompt review by a qualified clinician and confirm with the appropriate test. Follow the newborn-care protocol for the infant’s exact age and condition.",
                "Seek clinician review and confirm with appropriate bilirubin testing under local newborn-care protocol.",
                "Do not use this demo value to delay care or decide treatment.",
            ),
            Language.HI to CounselText(
                "यह केवल कृत्रिम प्रोटोटाइप अनुमान है, सीरम बिलीरुबिन रिपोर्ट या निदान नहीं।",
                "नवजात की समीक्षा उम्र (घंटों में), गर्भकाल, लक्षण और स्थानीय प्रोटोकॉल पर निर्भर करती है। यह डेमो उपचार सीमा नहीं बताता।",
                "कृपया योग्य चिकित्सक से शीघ्र समीक्षा कराएं और उपयुक्त जांच से पुष्टि करें। शिशु की सही उम्र और स्थिति के अनुसार स्थानीय नवजात-देखभाल प्रोटोकॉल अपनाएं।",
                "चिकित्सक से समीक्षा कराएं और स्थानीय नवजात प्रोटोकॉल के अनुसार उपयुक्त बिलीरुबिन जांच से पुष्टि करें।",
                "इस डेमो मान के कारण देखभाल में देरी न करें और इससे उपचार तय न करें।",
            ),
            Language.BHO to CounselText(
                "ई खाली कृत्रिम डेमो के अंदाजा बा, सीरम बिलीरुबिन जांच भा निदान नइखे।",
                "नवजात के जांच ओकरा उमिर (घंटा में), जनम के समय, लक्षण आ स्थानीय नियम पर निर्भर बा। ई डेमो इलाज के सीमा नइखे बतावत।",
                "कृपया बच्चा के योग्य डॉक्टर से जल्दी देखाईं आ उचित जांच से पुष्टि कराईं। बच्चा के सही उमिर आ हालत के हिसाब से स्थानीय नवजात देखभाल नियम मानीं।",
                "डॉक्टर से समीक्षा कराईं आ स्थानीय नवजात नियम के अनुसार बिलीरुबिन जांच से पुष्टि कराईं।",
                "एह डेमो मान के चलते इलाज में देरी मत करीं आ दवाई के फैसला एहसे मत लीं।",
            ),
        ),
    )

    // New text for the no-estimate path. Hindi/Bhojpuri need review by fluent speakers before field use.
    private val NO_ESTIMATE = mapOf(
        ScreeningType.MATERNAL_ANEMIA to mapOf(
            Language.EN to CounselText(
                "No hemoglobin estimate was made. This app does not have a validated model for hemoglobin.",
                "The photo was checked only for image quality. Image quality does not show whether hemoglobin is normal or low.",
                "Please get a hemoglobin test at the health centre (for example CBC or the approved point-of-care test) and follow the local maternal-care protocol.",
                "Arrange the hemoglobin test and clinician advice under local protocol.",
                "Do not start, stop, or change treatment because of this app.",
            ),
            Language.HI to CounselText(
                "हीमोग्लोबिन का कोई अनुमान नहीं बनाया गया। इस ऐप में हीमोग्लोबिन के लिए मान्य मॉडल नहीं है।",
                "फोटो केवल गुणवत्ता के लिए जांची गई। इससे यह पता नहीं चलता कि हीमोग्लोबिन सामान्य है या कम।",
                "कृपया स्वास्थ्य केंद्र पर हीमोग्लोबिन जांच कराएं (जैसे CBC या मान्य त्वरित जांच) और स्थानीय मातृ-स्वास्थ्य प्रोटोकॉल का पालन करें।",
                "स्थानीय प्रोटोकॉल के अनुसार हीमोग्लोबिन जांच और चिकित्सक की सलाह लें।",
                "इस ऐप के कारण दवा शुरू, बंद या बदलें नहीं।",
            ),
            Language.BHO to CounselText(
                "हीमोग्लोबिन के कवनो अंदाजा ना बनल। एह ऐप में हीमोग्लोबिन खातिर मान्य मॉडल नइखे।",
                "फोटो खाली गुणवत्ता खातिर जांचल गइल। एहसे ई पता ना चले कि हीमोग्लोबिन ठीक बा कि कम।",
                "कृपया स्वास्थ्य केंद्र पर हीमोग्लोबिन जांच कराईं (जइसे CBC भा मान्य जल्दी वाला जांच) आ स्थानीय मातृ-स्वास्थ्य नियम मानीं।",
                "स्थानीय नियम के हिसाब से हीमोग्लोबिन जांच आ डॉक्टर के सलाह लीं।",
                "एह ऐप के चलते दवाई शुरू, बंद भा बदलीं मत।",
            ),
        ),
        ScreeningType.NEONATAL_JAUNDICE to mapOf(
            Language.EN to CounselText(
                "No bilirubin estimate was made. This app has no validated bilirubin model, and a phone photo cannot replace a bilirubin test.",
                "How urgent newborn jaundice is depends on age in hours, gestational age, danger signs, and local protocol.",
                "Examine the baby as per the newborn-care protocol. If yellow colour is visible, arrange clinician review and a bilirubin test.",
                "Follow the newborn-care protocol for the baby's exact age and condition.",
                "Do not delay care because of this app.",
            ),
            Language.HI to CounselText(
                "बिलीरुबिन का कोई अनुमान नहीं बनाया गया। इस ऐप में मान्य बिलीरुबिन मॉडल नहीं है, और फोन की फोटो बिलीरुबिन जांच की जगह नहीं ले सकती।",
                "नवजात पीलिया कितना गंभीर है, यह घंटों में उम्र, गर्भकाल, खतरे के लक्षण और स्थानीय प्रोटोकॉल पर निर्भर है।",
                "नवजात-देखभाल प्रोटोकॉल के अनुसार शिशु की जांच करें। पीलापन दिखे तो चिकित्सक से समीक्षा और बिलीरुबिन जांच कराएं।",
                "शिशु की सही उम्र और स्थिति के अनुसार नवजात-देखभाल प्रोटोकॉल का पालन करें।",
                "इस ऐप के कारण देखभाल में देरी न करें।",
            ),
            Language.BHO to CounselText(
                "बिलीरुबिन के कवनो अंदाजा ना बनल। एह ऐप में मान्य बिलीरुबिन मॉडल नइखे, आ फोन के फोटो बिलीरुबिन जांच के जगह ना ले सके।",
                "नवजात के पीलिया केतना गंभीर बा, ई घंटा में उमिर, जनम के समय, खतरा के लक्षण आ स्थानीय नियम पर निर्भर बा।",
                "नवजात देखभाल नियम के हिसाब से बच्चा के जांच करीं। पियरपन लउके त डॉक्टर से देखाईं आ बिलीरुबिन जांच कराईं।",
                "बच्चा के सही उमिर आ हालत के हिसाब से नवजात देखभाल नियम मानीं।",
                "एह ऐप के चलते इलाज में देरी मत करीं।",
            ),
        ),
    )
}
