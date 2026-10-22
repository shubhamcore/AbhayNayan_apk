package `in`.abhaynayan.app.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import `in`.abhaynayan.app.core.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SpeechState(val ready: Boolean = false, val speaking: Boolean = false, val note: String? = null)

/** Optional on-device TTS. The text is always shown on screen; speech is an extra, never the only channel. */
class CounselingSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val engine = TextToSpeech(context.applicationContext, this)
    private val mutable = MutableStateFlow(SpeechState(note = "Preparing text-to-speech…"))
    val state: StateFlow<SpeechState> = mutable.asStateFlow()

    override fun onInit(status: Int) {
        val ok = status == TextToSpeech.SUCCESS
        mutable.value = SpeechState(ready = ok, note = if (ok) null else "Text-to-speech is not available on this phone. Read the text aloud instead.")
        if (ok) engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = true) }
            override fun onDone(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = false, note = "Speech stopped because of an error.") }
        })
    }

    fun speak(text: String, language: Language) {
        if (!mutable.value.ready || text.isBlank()) return
        // Bhojpuri voices are rare; Devanagari text is read with a Hindi voice when needed.
        val candidates = when (language) {
            Language.HI -> listOf("hi-IN")
            Language.BHO -> listOf("bho-IN", "hi-IN")
            Language.EN -> listOf("en-IN", "en-US")
        }.map { Locale.forLanguageTag(it) }
        val chosen = candidates.firstOrNull { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (chosen == null) {
            mutable.value = mutable.value.copy(note = "No ${language.label} voice is installed. Read the text aloud instead.")
            return
        }
        engine.language = chosen
        val note = if (language == Language.BHO && chosen.language == "hi") "Using a Hindi voice for Bhojpuri text." else null
        mutable.value = mutable.value.copy(note = note)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "abhaynayan-counsel")
    }

    fun stop() {
        engine.stop()
        mutable.value = mutable.value.copy(speaking = false)
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
        mutable.value = SpeechState()
    }
}
