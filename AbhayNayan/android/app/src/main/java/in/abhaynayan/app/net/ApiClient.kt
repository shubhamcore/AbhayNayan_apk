package `in`.abhaynayan.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class HttpRequest(val method: String, val url: String, val jsonBody: String? = null)
data class HttpResponse(val code: Int, val body: String)

/** Seam for tests; the production transport is [UrlConnectionTransport]. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxResponseBytes: Int = 2_000_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (request.jsonBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(request.jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = input.readNBytesCompat(maxResponseBytes)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            return HttpResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("Response too large.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

enum class ApiErrorKind { NOT_CONFIGURED, UNREACHABLE, TIMEOUT, HTTP, INVALID_RESPONSE }

/** Typed outcome. Error messages are user-facing and never include bodies, stack traces, or URLs. */
sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val kind: ApiErrorKind, val httpCode: Int?, val userMessage: String, val retryable: Boolean) : ApiResult<Nothing>()
}

object ApiErrors {
    fun forHttp(code: Int): ApiResult.Failure = when (code) {
        400, 422 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server did not accept this record (validation error).", retryable = false)
        401, 403 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server refused access. Check the backend settings.", retryable = false)
        404 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server does not offer this feature (version mismatch?).", retryable = false)
        408 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server timed out. Will retry later.", retryable = true)
        429 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server is busy (too many requests). Will retry later.", retryable = true)
        in 500..599 -> ApiResult.Failure(ApiErrorKind.HTTP, code, "The server had a problem. Will retry later.", retryable = true)
        else -> ApiResult.Failure(ApiErrorKind.HTTP, code, "Unexpected server response ($code).", retryable = false)
    }

    val notConfigured = ApiResult.Failure(ApiErrorKind.NOT_CONFIGURED, null, "No backend is configured. Records stay on this phone.", retryable = false)
    val unreachable = ApiResult.Failure(ApiErrorKind.UNREACHABLE, null, "Cannot reach the server. Records stay on this phone and will sync later.", retryable = true)
    val timeout = ApiResult.Failure(ApiErrorKind.TIMEOUT, null, "The server took too long to respond. Will retry later.", retryable = true)
    val invalid = ApiResult.Failure(ApiErrorKind.INVALID_RESPONSE, null, "The server sent a response the app could not read.", retryable = true)
}

data class HealthInfo(val status: String, val mode: String, val geminiConfigured: Boolean)
data class SyncAck(val accepted: Int, val duplicate: Int, val syncedIds: List<String>)
data class CounselResponse(
    val summary: String,
    val riskExplanation: String,
    val script: String,
    val nextStep: String,
    val referralRequired: Boolean,
    val urgency: String,
    val language: String,
    val safetyNote: String,
    val mode: String,
)
data class ReferralAck(val id: String, val screeningId: String, val created: Boolean, val pdfPath: String)
data class DashboardSummary(val totalScreenings: Int, val referrals: Int, val todayScreenings: Int, val mode: String)
data class ServerScreening(val id: String, val screeningType: String, val estimate: Double, val unit: String, val modelMode: String, val createdAt: String)

/**
 * Client for the FastAPI backend in hrdkkmr/AbhayNayan_web (backend/app/main.py). Only endpoints that
 * exist there are called. All calls run on Dispatchers.IO.
 */
class ApiClient(
    private val baseUrl: () -> String?,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    suspend fun health(): ApiResult<HealthInfo> = call("GET", "/api/health") { o ->
        HealthInfo(o.getString("status"), o.optString("mode"), o.optBoolean("gemini_configured", false))
    }

    suspend fun sync(records: JSONArray): ApiResult<SyncAck> {
        require(records.length() in 1..100) { "Sync batches must contain 1–100 records." }
        return call("POST", "/api/sync", JSONObject().put("records", records).toString()) { o ->
            val ids = o.getJSONArray("synced_ids")
            SyncAck(o.getInt("accepted"), o.getInt("duplicate"), (0 until ids.length()).map { ids.getString(it) })
        }
    }

    suspend fun counselling(request: JSONObject): ApiResult<CounselResponse> = call("POST", "/api/counselling", request.toString()) { o ->
        CounselResponse(
            summary = o.getString("summary"),
            riskExplanation = o.getString("risk_explanation"),
            script = o.getString("counselling_script"),
            nextStep = o.getString("recommended_next_step"),
            referralRequired = o.getBoolean("referral_required"),
            urgency = o.getString("urgency"),
            language = o.getString("language"),
            safetyNote = o.getString("safety_note"),
            mode = o.optString("mode", "LOCAL_FALLBACK"),
        )
    }

    suspend fun createReferral(request: JSONObject): ApiResult<ReferralAck> = call("POST", "/api/referrals", request.toString()) { o ->
        val pdf = o.getString("pdf_url")
        if (!pdf.startsWith("/api/referrals/")) throw JSONException("Unexpected pdf_url")
        ReferralAck(o.getString("id"), o.getString("screening_id"), o.optBoolean("created", false), pdf)
    }

    suspend fun dashboard(): ApiResult<DashboardSummary> = call("GET", "/api/dashboard") { o ->
        DashboardSummary(o.getInt("total_screenings"), o.getInt("referrals"), o.getInt("today_screenings"), o.optString("mode", "DEMO"))
    }

    suspend fun recentScreenings(limit: Int = 20): ApiResult<List<ServerScreening>> {
        require(limit in 1..500)
        return callArray("GET", "/api/screenings?limit=$limit") { array ->
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val r = o.getJSONObject("result")
                ServerScreening(o.getString("id"), r.getString("screening_type"), r.getDouble("estimate"), r.getString("unit"),
                    r.optString("model_mode"), o.optString("created_at"))
            }
        }
    }

    /** Absolute URL for a server-relative path; null when no backend is configured. */
    fun absolute(path: String): String? = baseUrl()?.trimEnd('/')?.plus(path)

    private suspend fun <T> call(method: String, path: String, body: String? = null, parse: (JSONObject) -> T): ApiResult<T> =
        execute(method, path, body) { text -> parse(JSONObject(text)) }

    private suspend fun <T> callArray(method: String, path: String, parse: (JSONArray) -> T): ApiResult<T> =
        execute(method, path, null) { text -> parse(JSONArray(text)) }

    private suspend fun <T> execute(method: String, path: String, body: String?, parse: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val root = baseUrl()?.trimEnd('/') ?: return@withContext ApiErrors.notConfigured
            val response = try {
                transport.execute(HttpRequest(method, root + path, body))
            } catch (_: SocketTimeoutException) {
                return@withContext ApiErrors.timeout
            } catch (_: IOException) {
                return@withContext ApiErrors.unreachable
            } catch (_: SecurityException) {
                return@withContext ApiErrors.unreachable
            } catch (_: IllegalArgumentException) {
                return@withContext ApiErrors.notConfigured
            }
            if (response.code !in 200..299) return@withContext ApiErrors.forHttp(response.code)
            try {
                ApiResult.Success(parse(response.body))
            } catch (_: JSONException) {
                ApiErrors.invalid
            } catch (_: RuntimeException) {
                ApiErrors.invalid
            }
        }
}
