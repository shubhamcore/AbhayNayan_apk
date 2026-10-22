package `in`.abhaynayan.app.sync

import `in`.abhaynayan.app.TestFixtures
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.SyncState
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.MockBackend
import `in`.abhaynayan.app.net.UrlConnectionTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SyncStore that mirrors the Room DAO semantics (claiming only PENDING rows). */
class FakeStore(records: List<ScreeningEntity>, private val patients: List<PatientEntity>) : SyncStore {
    val rows = LinkedHashMap(records.associateBy { it.screeningId })
    override suspend fun resetInterrupted() { update({ it.syncState == SyncState.SYNCING }) { it.copy(syncState = SyncState.PENDING) } }
    override suspend fun pending(limit: Int) = rows.values.filter { it.syncState == SyncState.PENDING }.take(limit)
    override suspend fun patient(id: String) = patients.firstOrNull { it.patientId == id }
    override suspend fun claim(ids: List<String>, now: String): List<String> {
        val claimed = ids.filter { rows[it]?.syncState == SyncState.PENDING }
        claimed.forEach { rows[it] = rows.getValue(it).copy(syncState = SyncState.SYNCING) }
        return claimed
    }
    override suspend fun release(ids: List<String>) = update({ it.screeningId in ids && it.syncState == SyncState.SYNCING }) { it.copy(syncState = SyncState.PENDING) }
    override suspend fun markSynced(ids: List<String>, now: String) = update({ it.screeningId in ids }) { it.copy(syncState = SyncState.SYNCED, serverConfirmedAtUtc = now, lastSyncError = null) }
    override suspend fun markAttemptFailed(ids: List<String>, state: String, error: String) =
        update({ it.screeningId in ids }) { it.copy(syncState = state, syncAttempts = it.syncAttempts + 1, lastSyncError = error) }
    override suspend fun setState(ids: List<String>, state: String, note: String?) = update({ it.screeningId in ids }) { it.copy(syncState = state, lastSyncError = note) }
    private fun update(match: (ScreeningEntity) -> Boolean, change: (ScreeningEntity) -> ScreeningEntity) {
        rows.values.filter(match).forEach { rows[it.screeningId] = change(it) }
    }
    fun state(id: String) = rows.getValue(id).syncState
}

class SyncEngineTest {
    private val server = MockBackend()
    private val api = ApiClient({ server.baseUrl }, UrlConnectionTransport(connectTimeoutMs = 2_000, readTimeoutMs = 2_000))
    private val patients = listOf(TestFixtures.demoPatient, TestFixtures.realPatient)

    @After fun tearDown() = server.close()

    private fun ackAll() = server.on("/api/sync") { body ->
        val ids = JSONArray().also { out -> JSONObject(body).getJSONArray("records").let { a -> for (i in 0 until a.length()) out.put(a.getJSONObject(i).getString("id")) } }
        MockBackend.Reply(200, JSONObject().put("accepted", ids.length()).put("duplicate", 0).put("synced_ids", ids).toString())
    }

    @Test fun successfulSyncMarksRecordsSyncedAndSendsNoNames() = runBlocking {
        ackAll()
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-a1111111"), TestFixtures.demoRecord("scr-b2222222")), patients)
        val outcome = SyncEngine(store, api).run()
        assertEquals(2, outcome.synced)
        assertEquals(SyncState.SYNCED, store.state("scr-a1111111"))
        assertEquals(1, server.requestsTo("/api/sync").size)
        assertFalse(server.requestsTo("/api/sync").single().body.contains(TestFixtures.demoPatient.displayName))
    }

    @Test fun ineligibleRecordsStayLocalAndAreNeverSent() = runBlocking {
        ackAll()
        val local = TestFixtures.disabledRecord().copy(syncState = SyncState.PENDING)
        val store = FakeStore(listOf(local), patients)
        val outcome = SyncEngine(store, api).run()
        assertEquals(1, outcome.localOnly)
        assertEquals(SyncState.LOCAL_ONLY, store.state(local.screeningId))
        assertTrue(server.requestsTo("/api/sync").isEmpty())
    }

    @Test fun serverErrorKeepsRecordsPendingForRetryThenSucceeds() = runBlocking {
        server.on("/api/sync", 500, """{"detail":"boom"}""")
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-c3333333")), patients)
        val engine = SyncEngine(store, api)
        val first = engine.run()
        assertTrue(first.retryLater)
        assertEquals(SyncState.PENDING, store.state("scr-c3333333"))
        assertEquals(1, store.rows.getValue("scr-c3333333").syncAttempts)
        ackAll() // connection restored
        val second = engine.run()
        assertEquals(1, second.synced)
        assertEquals(SyncState.SYNCED, store.state("scr-c3333333"))
    }

    @Test fun repeatedFailuresStopAfterMaxAttempts() = runBlocking {
        server.on("/api/sync", 503, "{}")
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-d4444444", attempts = SyncEngine.MAX_ATTEMPTS - 1)), patients)
        SyncEngine(store, api).run()
        assertEquals(SyncState.FAILED, store.state("scr-d4444444"))
    }

    @Test fun validationErrorIsolatesTheBadRecordInABatch() = runBlocking {
        server.on("/api/sync") { body ->
            val records = JSONObject(body).getJSONArray("records")
            val ids = (0 until records.length()).map { records.getJSONObject(it).getString("id") }
            if ("scr-bad00000" in ids) MockBackend.Reply(422, """{"detail":"invalid"}""")
            else MockBackend.Reply(200, JSONObject().put("accepted", ids.size).put("duplicate", 0).put("synced_ids", JSONArray(ids)).toString())
        }
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-good0000"), TestFixtures.demoRecord("scr-bad00000"), TestFixtures.demoRecord("scr-good1111")), patients)
        val outcome = SyncEngine(store, api).run()
        assertEquals(2, outcome.synced)
        assertEquals(1, outcome.failed)
        assertEquals(SyncState.FAILED, store.state("scr-bad00000"))
        assertEquals(SyncState.SYNCED, store.state("scr-good0000"))
    }

    @Test fun duplicatesReportedByServerAreStillConfirmed() = runBlocking {
        server.on("/api/sync", 200, """{"accepted":0,"duplicate":1,"synced_ids":["scr-e5555555"]}""")
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-e5555555")), patients)
        SyncEngine(store, api).run()
        assertEquals(SyncState.SYNCED, store.state("scr-e5555555"))
    }

    @Test fun interruptedSyncFromAPreviousProcessIsResumed() = runBlocking {
        ackAll()
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-f6666666").copy(syncState = SyncState.SYNCING)), patients)
        SyncEngine(store, api).run()
        assertEquals(SyncState.SYNCED, store.state("scr-f6666666"))
    }

    @Test fun concurrentRunsNeverSendTheSameRecordTwice() = runBlocking {
        server.on("/api/sync") { body ->
            val ids = JSONObject(body).getJSONArray("records").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            MockBackend.Reply(200, JSONObject().put("accepted", ids.size).put("duplicate", 0).put("synced_ids", JSONArray(ids)).toString(), delayMs = 200)
        }
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-g7777777")), patients)
        val engine = SyncEngine(store, api)
        listOf(async { engine.run() }, async { engine.run() }).awaitAll()
        val sentIds = server.requestsTo("/api/sync").flatMap { r -> JSONObject(r.body).getJSONArray("records").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } } }
        assertEquals(listOf("scr-g7777777"), sentIds)
    }

    @Test fun contract10PerRecordResultsAreAppliedAndLocalCopiesKept() = runBlocking {
        server.on("/api/sync", 200, """{"accepted":1,"duplicate":1,"conflict":1,"rejected":1,
            "synced_ids":["scr-acc00000","scr-dup00000"],
            "results":[{"id":"scr-acc00000","status":"accepted","error":null},
                       {"id":"scr-dup00000","status":"duplicate","error":null},
                       {"id":"scr-con00000","status":"conflict","error":{"code":"ID_CONFLICT","status":409,"retryable":false,"message":"x","fields":[]}},
                       {"id":"scr-rej00000","status":"rejected","error":{"code":"VALIDATION_ERROR","status":422,"retryable":false,"message":"x","fields":[]}}],
            "server_time":"2026-10-10T08:12:13Z","api_contract_version":"1.0.0"}""")
        val ids = listOf("scr-acc00000", "scr-dup00000", "scr-con00000", "scr-rej00000", "scr-unack000")
        val store = FakeStore(ids.map { TestFixtures.demoRecord(it) }, patients)
        val outcome = SyncEngine(store, api).run()
        assertEquals(2, outcome.synced)
        assertEquals(SyncState.SYNCED, store.state("scr-acc00000"))
        assertEquals(SyncState.SYNCED, store.state("scr-dup00000"))
        assertEquals(SyncState.CONFLICT, store.state("scr-con00000"))
        assertEquals(SyncState.REJECTED, store.state("scr-rej00000"))
        assertTrue(store.rows.getValue("scr-rej00000").lastSyncError!!.contains("VALIDATION_ERROR"))
        assertEquals("unacknowledged records stay queued", SyncState.PENDING, store.state("scr-unack000"))
        assertEquals(5, store.rows.size) // nothing deleted locally
        // Contract 1.0 body: nullable placeholders and provenance were sent.
        val sent = JSONObject(server.requestsTo("/api/sync").single().body).getJSONArray("records").getJSONObject(0).getJSONObject("result")
        assertTrue(sent.isNull("confidence"))
        assertEquals("DEMO_SYNTHETIC", sent.getString("provenance"))
    }

    @Test fun legacyServerReceivesPre10Payload() = runBlocking {
        server.on("/api/health", 200, """{"status":"ok","mode":"PROTOTYPE_DEMO","gemini_configured":false}""")
        ackAll()
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-leg00000")), patients)
        SyncEngine(store, api).run()
        assertEquals(SyncState.SYNCED, store.state("scr-leg00000"))
        val sent = JSONObject(server.requestsTo("/api/sync").single().body).getJSONArray("records").getJSONObject(0)
        assertFalse(sent.has("id_origin"))
        assertEquals(.91, sent.getJSONObject("result").getDouble("quality_score"), 1e-9)
    }

    @Test fun unreachableServerKeepsQueueAndRetriesLater() = runBlocking {
        val dead = ApiClient({ "http://127.0.0.1:9" }, UrlConnectionTransport(connectTimeoutMs = 1_000, readTimeoutMs = 1_000))
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-off00000")), patients)
        val outcome = SyncEngine(store, dead).run()
        assertTrue(outcome.retryLater)
        assertEquals(SyncState.PENDING, store.state("scr-off00000"))
    }

    @Test fun notConfiguredLeavesQueueUntouched() = runBlocking {
        val store = FakeStore(listOf(TestFixtures.demoRecord("scr-h8888888")), patients)
        val outcome = SyncEngine(store, ApiClient({ null })).run()
        assertFalse(outcome.retryLater)
        assertEquals(SyncState.PENDING, store.state("scr-h8888888"))
        assertEquals(0, store.rows.getValue("scr-h8888888").syncAttempts)
    }
}
