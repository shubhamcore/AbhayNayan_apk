package `in`.abhaynayan.app.services

import `in`.abhaynayan.app.core.CounselText
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.LocalCounselling
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.ApiResult
import `in`.abhaynayan.app.net.Payloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CounselSource(val label: String) {
    SERVER_GEMINI("Server · Gemini (communication aid only)"),
    SERVER_FALLBACK("Server · reviewed fallback text"),
    LOCAL_OFFLINE("On this phone · offline text"),
}

data class CounselOutcome(val text: CounselText, val source: CounselSource, val note: String?) {
    /** Wire value for contract 1.0 `counselling_mode` on referrals. */
    val wireMode: String get() = if (source == CounselSource.SERVER_GEMINI) "GEMINI" else "LOCAL_FALLBACK"
}

/**
 * Counselling copy. The server (and Gemini behind it) only supplies wording for synthetic demo records;
 * referral urgency is always decided by the deterministic local rules, never by this text.
 */
class CounsellingService(private val api: ApiClient) {
    private val lock = Mutex()
    private val cache = HashMap<Pair<String, Language>, CounselOutcome>()
    private val inFlight = HashMap<Pair<String, Language>, CompletableDeferred<CounselOutcome>>()

    fun local(type: ScreeningType, mode: ResultMode, language: Language) =
        CounselOutcome(LocalCounselling.text(type, mode, language), CounselSource.LOCAL_OFFLINE, null)

    /** Returns cached or freshly fetched text; concurrent calls for the same key share one request. */
    suspend fun fetch(record: ScreeningEntity, language: Language, online: Boolean): CounselOutcome {
        val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
        val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
        val fallback = local(type, mode, language)
        // The backend contract requires an estimate; non-demo records always use local text.
        if (mode != ResultMode.DEMO_SYNTHETIC || record.estimate == null) return fallback
        if (!online) return fallback.copy(note = "Offline: showing on-device text.")
        val key = record.screeningId to language
        val (deferred, owner) = lock.withLock {
            cache[key]?.let { return it }
            inFlight[key]?.let { it to false } ?: (CompletableDeferred<CounselOutcome>().also { inFlight[key] = it } to true)
        }
        if (!owner) return deferred.await()
        val outcome = try {
            val contract = when (val c = api.contract()) {
                is ApiResult.Success -> c.value
                is ApiResult.Failure -> null
            }
            if (contract == null) {
                fallback.copy(note = "Server not reachable; showing on-device text.")
            } else {
                when (val result = api.counselling(Payloads.counsellingRequest(record, language, contract))) {
                    is ApiResult.Success -> {
                        val r = result.value
                        if (r.language != language.code) fallback.copy(note = "Server replied in another language; showing on-device text.")
                        else CounselOutcome(
                            CounselText(r.summary, r.riskExplanation, r.script, r.nextStep, r.safetyNote),
                            if (r.mode.startsWith("GEMINI")) CounselSource.SERVER_GEMINI else CounselSource.SERVER_FALLBACK,
                            r.fallbackReason?.let { "Server used its reviewed text (${it.lowercase().replace('_', ' ')})." },
                        )
                    }
                    is ApiResult.Failure -> fallback.copy(note = result.userMessage)
                }
            }
        } catch (_: IllegalArgumentException) {
            fallback
        }
        lock.withLock {
            inFlight.remove(key)
            // Only Gemini wording is cached; server fallback text (quota, timeout) is retried on the next fetch.
            if (outcome.source == CounselSource.SERVER_GEMINI) cache[key] = outcome
        }
        deferred.complete(outcome)
        return outcome
    }
}
