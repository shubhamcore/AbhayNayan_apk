package `in`.abhaynayan.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import `in`.abhaynayan.app.core.Language

/**
 * Core interface labels in English, Hindi, and Bhojpuri. Detailed technical text stays in English.
 * Hindi/Bhojpuri strings were drafted for this prototype and need review by fluent speakers.
 */
class Strings(val language: Language) {
    private fun t(en: String, hi: String, bho: String) = when (language) {
        Language.EN -> en
        Language.HI -> hi
        Language.BHO -> bho
    }

    val tagline = t("Optical screening workflow and counselling assistant", "ऑप्टिकल स्क्रीनिंग और परामर्श सहायक", "ऑप्टिकल स्क्रीनिंग आ सलाह सहायक")
    val online = t("Online", "ऑनलाइन", "ऑनलाइन")
    val offline = t("Offline", "ऑफ़लाइन", "ऑफलाइन")
    val startScreening = t("Start a screening", "स्क्रीनिंग शुरू करें", "स्क्रीनिंग शुरू करीं")
    val maternalTitle = t("Maternal anemia", "मातृ एनीमिया (खून की कमी)", "माई में खून के कमी")
    val maternalSubtitle = t("Lower eyelid (palpebral conjunctiva) check", "निचली पलक की जांच", "निचला पलक के जांच")
    val neonatalTitle = t("Newborn jaundice", "नवजात पीलिया", "नवजात पीलिया")
    val neonatalSubtitle = t("Eye-white (sclera) check · age in hours", "आंख के सफेद भाग की जांच · उम्र घंटों में", "आंख के उज्जर भाग के जांच · उमिर घंटा में")
    val recentRecords = t("Recent records", "हाल के रिकॉर्ड", "हाल के रिकॉर्ड")
    val allRecords = t("All records", "सभी रिकॉर्ड", "सब रिकॉर्ड")
    val noRecords = t("No screenings saved on this phone yet.", "इस फोन पर अभी कोई स्क्रीनिंग नहीं है।", "एह फोन पर अबहीं कवनो स्क्रीनिंग नइखे।")
    val pendingSync = t("Waiting to sync", "सिंक बाकी", "सिंक बाकी बा")
    val syncNow = t("Sync now", "अभी सिंक करें", "अबहीं सिंक करीं")
    val languageLabel = t("Language", "भाषा", "भाषा")
    val help = t("Help & safety", "मदद और सुरक्षा", "मदद आ सुरक्षा")
    val settings = t("Settings", "सेटिंग्स", "सेटिंग")
    val back = t("Back", "पीछे", "पाछे")
    val cancel = t("Cancel", "रद्द करें", "रद्द करीं")
    val continueLabel = t("Continue", "आगे बढ़ें", "आगे बढ़ीं")
    val retake = t("Retake", "फिर से लें", "फेर से लीं")
    val retry = t("Retry", "फिर कोशिश करें", "फेर कोशिश करीं")
    val save = t("Save & view report", "सेव करें और रिपोर्ट देखें", "सेव करीं आ रिपोर्ट देखीं")
    val report = t("Screening report", "स्क्रीनिंग रिपोर्ट", "स्क्रीनिंग रिपोर्ट")
    val sharePdf = t("Share PDF report", "PDF रिपोर्ट भेजें", "PDF रिपोर्ट भेजीं")
    val openPdf = t("Open PDF report", "PDF रिपोर्ट खोलें", "PDF रिपोर्ट खोलीं")
    val home = t("Home", "होम", "होम")
    val counselling = t("Counselling", "परामर्श", "सलाह")
    val play = t("Play aloud", "सुनाएं", "सुनाईं")
    val stop = t("Stop", "रोकें", "रोकीं")
    val referral = t("Referral", "रेफ़रल", "रेफरल")
    val shareReferral = t("Share referral summary", "रेफ़रल सारांश साझा करें", "रेफरल सारांश भेजीं")
    val safetyShort = t(
        "Prototype. Not a diagnosis. Always confirm with the proper test and follow local protocol.",
        "प्रोटोटाइप। यह निदान नहीं है। हमेशा सही जांच से पुष्टि करें और स्थानीय प्रोटोकॉल मानें।",
        "प्रोटोटाइप। ई निदान ना ह। हमेशा सही जांच से पुष्टि कराईं आ स्थानीय नियम मानीं।",
    )
    val noEstimateTitle = t("No estimate produced", "कोई अनुमान नहीं बना", "कवनो अंदाजा ना बनल")
    val dangerSigns = t("Danger signs (tick any present)", "खतरे के लक्षण (जो हों उन पर निशान लगाएं)", "खतरा के लक्षण (जवन होखे ओकरा पर निशान लगाईं)")
}

val LocalStrings = staticCompositionLocalOf { Strings(Language.EN) }
