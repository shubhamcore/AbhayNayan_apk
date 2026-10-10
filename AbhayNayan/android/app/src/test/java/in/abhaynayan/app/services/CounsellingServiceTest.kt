package `in`.abhaynayan.app.services

import `in`.abhaynayan.app.TestFixtures
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.MockBackend
import `in`.abhaynayan.app.net.UrlConnectionTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CounsellingServiceTest {
    private val server = MockBackend()
    private val service = CounsellingService(ApiClient({ server.baseUrl }, UrlConnectionTransport(readTimeoutMs = 2_000)))
    private val reply = """{"summary":"S","risk_explanation":"R","counselling_script":"C","recommended_next_step":"N",
        "referral_required":true,"urgency":"DEMO_ONLY","language":"hi","safety_note":"X","mode":"GEMINI"}"""

    @After fun tearDown() = server.close()

    @Test fun offlineUsesDeterministicLocalTextWithoutNetwork() = runBlocking {
        val out = service.fetch(TestFixtures.demoRecord(), Language.HI, online = false)
        assertEquals(CounselSource.LOCAL_OFFLINE, out.source)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun recordsWithoutEstimateNeverCallTheServer() = runBlocking {
        val out = service.fetch(TestFixtures.disabledRecord(), Language.EN, online = true)
        assertEquals(CounselSource.LOCAL_OFFLINE, out.source)
        assertTrue(out.text.summary.startsWith("No bilirubin estimate"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun serverTextIsUsedAndRapidRepeatsShareOneRequest() = runBlocking {
        server.on("/api/counselling", 200, reply, delayMs = 150)
        val results = (1..4).map { async { service.fetch(TestFixtures.demoRecord(), Language.HI, online = true) } }.awaitAll()
        assertTrue(results.all { it.source == CounselSource.SERVER_GEMINI && it.text.script == "C" })
        assertEquals(1, server.requests.size)
        service.fetch(TestFixtures.demoRecord(), Language.HI, online = true) // cached
        assertEquals(1, server.requests.size)
    }

    @Test fun quotaOrServerErrorsFallBackToLocalText() = runBlocking {
        server.on("/api/counselling", 429, """{"detail":"quota"}""")
        val out = service.fetch(TestFixtures.demoRecord(), Language.EN, online = true)
        assertEquals(CounselSource.LOCAL_OFFLINE, out.source)
        assertTrue(out.note!!.contains("busy"))
    }

    @Test fun wrongLanguageReplyIsRejected() = runBlocking {
        server.on("/api/counselling", 200, reply) // replies "hi"
        val out = service.fetch(TestFixtures.demoRecord(), Language.EN, online = true)
        assertEquals(CounselSource.LOCAL_OFFLINE, out.source)
    }
}
