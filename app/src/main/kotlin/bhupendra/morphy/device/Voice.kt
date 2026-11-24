package bhupendra.morphy.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over the platform recognizer with live partials and a normalized mic level. */
class Voice(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        stop()
        // Always talk to the real system recognizer — never to Morphy's own proxy (which would loop).
        val r = systemRecognizer(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onLevel(0f)
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                listener.onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                listener.onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a connection"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    // The recognizer ending on its own (disconnected service, cancelled, busy) isn't news: say nothing.
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ""
                    else -> "Voice error ($error)"
                })
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        })
    }

    fun finish() { recognizer?.stopListening() }

    companion object {
        /** The best installed speech recognizer that isn't Morphy's proxy (Google's, when present). */
        fun systemRecognizer(context: Context): android.content.ComponentName? {
            val services = context.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }.filter { it.packageName != context.packageName }
            val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.as", "com.google.android.tts")
            val pick = preferred.firstNotNullOfOrNull { p -> services.firstOrNull { it.packageName == p } } ?: services.firstOrNull()
            return pick?.let { android.content.ComponentName(it.packageName, it.name) }
        }
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
