package `in`.abhaynayan.app.util

import android.util.Log
import `in`.abhaynayan.app.BuildConfig

/** Masks identifiers before anything reaches logcat. Patient names must never be passed here at all. */
object Redactor {
    private val rules = listOf(
        Regex("https?://\\S+") to "<url>",
        Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+") to "<email>",
        Regex("\\b(?:scr|ref)-[0-9a-fA-F-]{8,}") to "<record-id>",
        Regex("\\bABH-[A-Za-z0-9-]+") to "<patient-id>",
        Regex("(?<!\\d)(?:\\+?91[- ]?)?[6-9]\\d{9}(?!\\d)") to "<phone>",
        Regex("(?i)(api[_-]?key|token|secret|authorization)\\s*[:=]\\s*\\S+") to "$1=<redacted>",
    )

    fun redact(message: String): String = rules.fold(message) { text, (regex, replacement) -> regex.replace(text, replacement) }
}

object SafeLog {
    private const val TAG = "AbhayNayan"

    /** Debug builds only; release builds log nothing from the app layer. */
    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, Redactor.redact(message))
    }

    fun w(message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        // Exception messages can echo data; only the type is logged.
        Log.w(TAG, Redactor.redact(message) + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}
