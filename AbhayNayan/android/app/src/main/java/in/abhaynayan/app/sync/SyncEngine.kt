package `in`.abhaynayan.app.sync

import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.SyncState
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.ApiErrorKind
import `in`.abhaynayan.app.net.ApiResult
import `in`.abhaynayan.app.net.Contract
import `in`.abhaynayan.app.net.Payloads
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant

/** Persistence operations the sync engine needs; implemented by Room in production and fakes in tests. */
interface SyncStore {
    suspend fun resetInterrupted()
    suspend fun pending(limit: Int): List<ScreeningEntity>
    suspend fun patient(id: String): PatientEntity?
    /** Atomically moves PENDING → SYNCING; returns the IDs actually claimed. */
    suspend fun claim(ids: List<String>, now: String): List<String>
    suspend fun release(ids: List<String>)
    suspend fun markSynced(ids: List<String>, now: String)
    suspend fun markAttemptFailed(ids: List<String>, state: String, error: String)
    suspend fun setState(ids: List<String>, state: String, note: String?)
}

data class SyncOutcome(val synced: Int, val failed: Int, val localOnly: Int, val retryLater: Boolean, val message: String)

/**
 * Sends queued synthetic demo records to POST /api/sync. Duplicate-safe: the client-generated record ID is
 * idempotent on the server, rows are claimed before sending, and a mutex prevents overlapping runs.
 */
class SyncEngine(
    private val store: SyncStore,
    private val api: ApiClient,
    private val clock: () -> String = { Instant.now().toString() },
) {
    private val mutex = Mutex()

    suspend fun run(): SyncOutcome = mutex.withLock {
        store.resetInterrupted()
        if (store.pending(1).isEmpty()) return@withLock SyncOutcome(0, 0, 0, retryLater = false, message = "Nothing to sync.")
        // The request format depends on the server's contract version (checked via /api/health, cached).
        val contract = when (val c = api.contract()) {
            is ApiResult.Success -> c.value
            is ApiResult.Failure -> return@withLock SyncOutcome(0, 0, 0, retryLater = c.retryable, message = c.userMessage)
        }
        var synced = 0; var failed = 0; var localOnly = 0
        val seen = HashSet<String>()
        while (true) {
            val batch = store.pending(BATCH_SIZE).filter { seen.add(it.screeningId) }
            if (batch.isEmpty()) break
            val eligible = mutableListOf<ScreeningEntity>()
            for (record in batch) {
                when (val e = Payloads.syncEligibility(record, store.patient(record.patientId))) {
                    is Payloads.Eligibility.Eligible -> eligible += record
                    is Payloads.Eligibility.LocalOnly -> { store.setState(listOf(record.screeningId), SyncState.LOCAL_ONLY, e.reason); localOnly++ }
                }
            }
            if (eligible.isEmpty()) continue
            val claimed = store.claim(eligible.map { it.screeningId }, clock()).toSet()
            val toSend = eligible.filter { it.screeningId in claimed }
            if (toSend.isEmpty()) continue
            when (val step = send(toSend, contract)) {
                is Step.Done -> { synced += step.synced; failed += step.failed }
                is Step.Stop -> return@withLock SyncOutcome(synced, failed, localOnly, step.retryLater, step.message)
            }
        }
        val message = when {
            synced == 0 && failed == 0 -> "Nothing to sync."
            failed == 0 -> "$synced record(s) synced. No photos or names were sent."
            else -> "$synced synced, $failed rejected by the server."
        }
        SyncOutcome(synced, failed, localOnly, retryLater = false, message = message)
    }

    private sealed class Step {
        data class Done(val synced: Int, val failed: Int) : Step()
        data class Stop(val retryLater: Boolean, val message: String) : Step()
    }

    private suspend fun send(records: List<ScreeningEntity>, contract: Contract): Step {
        val ids = records.map { it.screeningId }
        val payload = try {
            JSONArray().also { array -> records.forEach { array.put(Payloads.screeningRecord(it, contract)) } }
        } catch (e: IllegalArgumentException) {
            store.markAttemptFailed(ids, SyncState.FAILED, "Record could not be prepared for sync.")
            return Step.Done(0, ids.size)
        }
        return when (val result = api.sync(payload)) {
            is ApiResult.Success -> {
                val ack = result.value
                if (ack.results.isNotEmpty()) return applyItemResults(records, ack.results)
                val confirmed = ack.syncedIds.toSet().intersect(ids.toSet()).toList()
                store.markSynced(confirmed, clock())
                val missing = records.filter { it.screeningId !in confirmed }
                if (missing.isNotEmpty()) failAttempt(missing, "Server did not confirm this record.")
                Step.Done(confirmed.size, 0)
            }
            is ApiResult.Failure -> when {
                result.kind == ApiErrorKind.NOT_CONFIGURED -> { store.release(ids); Step.Stop(false, result.userMessage) }
                result.retryable -> { failAttempt(records, result.userMessage); Step.Stop(true, result.userMessage) }
                records.size > 1 -> {
                    // Isolate the rejected record(s) so one invalid row does not block the rest.
                    store.release(ids)
                    var ok = 0; var bad = 0
                    for (record in records) {
                        val claimed = store.claim(listOf(record.screeningId), clock())
                        if (claimed.isEmpty()) continue
                        when (val single = send(listOf(record), contract)) {
                            is Step.Done -> { ok += single.synced; bad += single.failed }
                            is Step.Stop -> return single
                        }
                    }
                    Step.Done(ok, bad)
                }
                else -> { store.markAttemptFailed(ids, SyncState.FAILED, result.userMessage); Step.Done(0, 1) }
            }
        }
    }

    /**
     * Contract 1.0 per-record outcomes (API_CONTRACT.md §4.2/§6): accepted/duplicate → synced; conflict and
     * rejected leave the queue but the local copy is always kept; anything unacknowledged stays queued.
     */
    private suspend fun applyItemResults(records: List<ScreeningEntity>, results: List<`in`.abhaynayan.app.net.SyncItem>): Step {
        val byId = results.filter { it.id != null }.groupBy { it.id!! }
        var ok = 0; var bad = 0
        val unacknowledged = mutableListOf<ScreeningEntity>()
        for (record in records) {
            val outcome = byId[record.screeningId]?.let { items -> items.firstOrNull { it.status == "accepted" || it.status == "duplicate" } ?: items.first() }
            when (outcome?.status) {
                "accepted", "duplicate" -> { store.markSynced(listOf(record.screeningId), clock()); ok++ }
                "conflict" -> { store.setState(listOf(record.screeningId), SyncState.CONFLICT, "The server already holds a different record with this ID. This copy is kept on the phone."); bad++ }
                "rejected" -> { store.setState(listOf(record.screeningId), SyncState.REJECTED, "The server did not accept this record (${outcome.errorCode ?: "validation"}). It is kept on the phone."); bad++ }
                else -> unacknowledged += record
            }
        }
        if (unacknowledged.isNotEmpty()) failAttempt(unacknowledged, "Server did not confirm this record.")
        return Step.Done(ok, bad)
    }

    private suspend fun failAttempt(records: List<ScreeningEntity>, message: String) {
        val (exhausted, retry) = records.partition { it.syncAttempts + 1 >= MAX_ATTEMPTS }
        if (retry.isNotEmpty()) store.markAttemptFailed(retry.map { it.screeningId }, SyncState.PENDING, message)
        if (exhausted.isNotEmpty()) store.markAttemptFailed(exhausted.map { it.screeningId }, SyncState.FAILED, "$message Stopped after $MAX_ATTEMPTS attempts.")
    }

    companion object {
        const val BATCH_SIZE = 100
        const val MAX_ATTEMPTS = 8
    }
}
