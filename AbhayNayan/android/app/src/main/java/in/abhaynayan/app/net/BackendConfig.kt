package `in`.abhaynayan.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.edit
import `in`.abhaynayan.app.core.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

object BackendUrlPolicy {
    /** Hosts reachable over cleartext only in debug builds (see src/debug/res/xml/network_security_config.xml). */
    val DEV_CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    sealed class Result {
        data class Valid(val normalized: String) : Result()
        data class Invalid(val message: String) : Result()
    }

    fun validate(raw: String, debugBuild: Boolean): Result {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return Result.Invalid("Enter a backend URL, or leave the backend disabled.")
        val uri = try { URI(text) } catch (_: Exception) { return Result.Invalid("This is not a valid URL.") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return Result.Invalid("The URL needs a host name.")
        if (uri.userInfo != null) return Result.Invalid("Do not put credentials in the URL.")
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return Result.Invalid("Remove query or fragment parts.")
        return when (scheme) {
            "https" -> Result.Valid(text)
            "http" -> when {
                !debugBuild -> Result.Invalid("Release builds require HTTPS.")
                host !in DEV_CLEARTEXT_HOSTS -> Result.Invalid("HTTP is allowed only for 10.0.2.2 (emulator) or 127.0.0.1/localhost with adb reverse.")
                else -> Result.Valid(text)
            }
            else -> Result.Invalid("Use an https:// URL.")
        }
    }
}

data class AppSettings(
    val backendUrl: String?,
    val backendEnabled: Boolean,
    val workerLabel: String,
    val phcLabel: String,
    val language: Language,
)

/** Plain SharedPreferences: these values are not secrets (no API keys are ever stored on the phone). */
class SettingsStore(context: Context, private val defaultBackendUrl: String) {
    private val prefs = context.applicationContext.getSharedPreferences("abhaynayan_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    private fun read() = AppSettings(
        backendUrl = prefs.getString(KEY_URL, null) ?: defaultBackendUrl.ifBlank { null },
        backendEnabled = prefs.getBoolean(KEY_ENABLED, defaultBackendUrl.isNotBlank()),
        workerLabel = prefs.getString(KEY_WORKER, "demo-worker") ?: "demo-worker",
        phcLabel = prefs.getString(KEY_PHC, "Demo PHC") ?: "Demo PHC",
        language = Language.fromCode(prefs.getString(KEY_LANGUAGE, "en")),
    )

    /** URL used for requests, or null when the backend is disabled or unset. */
    fun activeBaseUrl(): String? = state.value.takeIf { it.backendEnabled }?.backendUrl

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit {
            putString(KEY_URL, next.backendUrl)
            putBoolean(KEY_ENABLED, next.backendEnabled)
            putString(KEY_WORKER, next.workerLabel)
            putString(KEY_PHC, next.phcLabel)
            putString(KEY_LANGUAGE, next.language.code)
        }
        state.value = next
    }

    fun clear() {
        prefs.edit { clear() }
        state.value = read()
    }

    private companion object {
        const val KEY_URL = "backend_url"
        const val KEY_ENABLED = "backend_enabled"
        const val KEY_WORKER = "worker_label"
        const val KEY_PHC = "phc_label"
        const val KEY_LANGUAGE = "language"
    }
}

/** Observes validated internet connectivity. This says nothing about whether the backend is reachable. */
class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { state.value = currentlyOnline() }
        override fun onLost(network: Network) { state.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state.value = currentlyOnline() }
    }

    init {
        try {
            manager?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback,
            )
        } catch (_: RuntimeException) {
            // Without a callback the initial value stays; sync still retries via WorkManager constraints.
        }
    }

    private fun currentlyOnline(): Boolean {
        val m = manager ?: return false
        val caps = m.getNetworkCapabilities(m.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
