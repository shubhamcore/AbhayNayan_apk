package `in`.abhaynayan.app.net

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
    private val server = MockBackend()
    private val client = ApiClient({ server.baseUrl }, UrlConnectionTransport(connectTimeoutMs = 2_000, readTimeoutMs = 500))

    @After fun tearDown() = server.close()

    private fun failure(result: ApiResult<*>) = result as ApiResult.Failure

    @Test fun healthParsesAndToleratesUnexpectedExtraFields() = runBlocking {
        server.on("/api/health", 200, """{"status":"ok","mode":"PROTOTYPE_DEMO","gemini_configured":false,"database":"sqlite-demo","new_field":[1,2]}""")
        val result = client.health() as ApiResult.Success
        assertEquals("ok", result.value.status)
        assertFalse(result.value.geminiConfigured)
    }

    @Test fun httpErrorsMapToSafeMessagesAndRetryability() = runBlocking {
        val expectations = mapOf(400 to false, 401 to false, 403 to false, 404 to false, 422 to false, 429 to true, 500 to true, 503 to true)
        for ((code, retryable) in expectations) {
            server.on("/api/health", code, """{"detail":"Traceback ... /srv/app/secret.py GEMINI_API_KEY=abc"}""")
            val f = failure(client.health())
            assertEquals(ApiErrorKind.HTTP, f.kind)
            assertEquals(code, f.httpCode)
            assertEquals("code $code", retryable, f.retryable)
            assertFalse(f.userMessage.contains("Traceback") || f.userMessage.contains("secret") || f.userMessage.contains("GEMINI"))
        }
    }

    @Test fun contract10ErrorEnvelopeRefinesCodeAndRetryability() = runBlocking {
        server.on("/api/referrals", 409, """{"detail":"Conflict","error":{"code":"ID_CONFLICT","message":"m","status":409,"retryable":false,"fields":[]},"api_contract_version":"1.0.0"}""")
        val conflict = failure(client.createReferral(JSONObject()))
        assertEquals("ID_CONFLICT", conflict.errorCode)
        assertFalse(conflict.retryable)
        assertTrue(conflict.userMessage.contains("different record"))
        server.on("/api/referrals", 503, """{"detail":"DISABLED","error":{"code":"MODEL_DISABLED","message":"m","status":503,"retryable":false,"fields":[]}}""")
        val disabled = failure(client.createReferral(JSONObject()))
        assertFalse("503 MODEL_DISABLED must not be retried", disabled.retryable)
        server.on("/api/referrals", 500, """{"detail":"x","error":{"code":"PDF_GENERATION_FAILED","message":"m","status":500,"retryable":true,"fields":[]}}""")
        assertTrue(failure(client.createReferral(JSONObject())).retryable)
    }

    @Test fun contractVersionIsReadFromHealthAndCached() = runBlocking {
        assertEquals(Contract.V1, (client.contract() as ApiResult.Success).value)
        client.contract()
        assertEquals(1, server.requestsTo("/api/health").size)
        val legacy = MockBackend().apply { on("/api/health", 200, """{"status":"ok","mode":"PROTOTYPE_DEMO","gemini_configured":false}""") }
        try {
            assertEquals(Contract.LEGACY_0_1, (ApiClient({ legacy.baseUrl }).contract() as ApiResult.Success).value)
        } finally { legacy.close() }
    }

    @Test fun invalidJsonAndMissingFieldsAreInvalidResponses() = runBlocking {
        server.on("/api/health", 200, "<html>proxy login</html>")
        assertEquals(ApiErrorKind.INVALID_RESPONSE, failure(client.health()).kind)
        server.on("/api/sync", 200, """{"accepted":1}""")
        assertEquals(ApiErrorKind.INVALID_RESPONSE, failure(client.sync(JSONArray().put(JSONObject()))).kind)
        server.on("/api/referrals", 200, """{"id":"r","screening_id":"s","created":true,"pdf_url":"https://evil.example/x.pdf"}""")
        assertEquals(ApiErrorKind.INVALID_RESPONSE, failure(client.createReferral(JSONObject())).kind)
    }

    @Test fun slowServerTimesOutAsRetryable() = runBlocking {
        server.on("/api/health", 200, """{"status":"ok"}""", delayMs = 1_500)
        val f = failure(client.health())
        assertEquals(ApiErrorKind.TIMEOUT, f.kind)
        assertTrue(f.retryable)
    }

    @Test fun unreachableServerIsRetryable() = runBlocking {
        val dead = ApiClient({ "http://127.0.0.1:9" }, UrlConnectionTransport(connectTimeoutMs = 1_000, readTimeoutMs = 1_000))
        val f = failure(dead.health())
        assertTrue(f.kind == ApiErrorKind.UNREACHABLE || f.kind == ApiErrorKind.TIMEOUT)
        assertTrue(f.retryable)
    }

    @Test fun missingConfigurationNeverSendsARequest() = runBlocking {
        val none = ApiClient({ null }, UrlConnectionTransport())
        assertEquals(ApiErrorKind.NOT_CONFIGURED, failure(none.health()).kind)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun counsellingParsesBackendResponse() = runBlocking {
        server.on("/api/counselling", 200, """{"summary":"s","risk_explanation":"r","counselling_script":"c","recommended_next_step":"n",
            "referral_required":true,"urgency":"DEMO_ONLY","language":"hi","safety_note":"x","mode":"LOCAL_FALLBACK"}""")
        val r = client.counselling(JSONObject().put("language", "hi")) as ApiResult.Success
        assertEquals("c", r.value.script)
        assertEquals("LOCAL_FALLBACK", r.value.mode)
        assertEquals("POST", server.requests.last().method)
    }

    @Test fun urlPolicySeparatesDevelopmentAndDeployedBackends() {
        assertTrue(BackendUrlPolicy.validate("https://abhaynayan.example.org/", debugBuild = false) is BackendUrlPolicy.Result.Valid)
        assertEquals("https://abhaynayan.example.org", (BackendUrlPolicy.validate("https://abhaynayan.example.org/", false) as BackendUrlPolicy.Result.Valid).normalized)
        assertTrue(BackendUrlPolicy.validate("http://10.0.2.2:8000", debugBuild = true) is BackendUrlPolicy.Result.Valid)
        assertTrue(BackendUrlPolicy.validate("http://10.0.2.2:8000", debugBuild = false) is BackendUrlPolicy.Result.Invalid)
        assertTrue(BackendUrlPolicy.validate("http://192.168.1.20:8000", debugBuild = true) is BackendUrlPolicy.Result.Invalid)
        assertTrue(BackendUrlPolicy.validate("https://user:pw@host.org", debugBuild = true) is BackendUrlPolicy.Result.Invalid)
        assertTrue(BackendUrlPolicy.validate("ftp://host.org", debugBuild = true) is BackendUrlPolicy.Result.Invalid)
        assertTrue(BackendUrlPolicy.validate("", debugBuild = true) is BackendUrlPolicy.Result.Invalid)
    }
}
