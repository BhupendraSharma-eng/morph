package bhupendra.morph.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import bhupendra.morph.device.Voice

/**
 * The assistant role requires a RecognitionService, and picking Morph as assistant can make it the system's
 * default recognizer. So this forwards to the real one (Google's) — voice typing in every app keeps working.
 */
class MorphRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        val target = Voice.systemRecognizer(this) ?: return cb.error(SpeechRecognizer.ERROR_CLIENT)
        inner?.destroy()
        inner = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = runCatching { cb.readyForSpeech(params ?: Bundle()) }.let {}
                override fun onBeginningOfSpeech() = runCatching { cb.beginningOfSpeech() }.let {}
                override fun onRmsChanged(rmsdB: Float) = runCatching { cb.rmsChanged(rmsdB) }.let {}
                override fun onBufferReceived(buffer: ByteArray?) = runCatching { cb.bufferReceived(buffer ?: ByteArray(0)) }.let {}
                override fun onEndOfSpeech() = runCatching { cb.endOfSpeech() }.let {}
                override fun onError(error: Int) = runCatching { cb.error(error) }.let {}
                override fun onResults(results: Bundle?) = runCatching { cb.results(results ?: Bundle()) }.let {}
                override fun onPartialResults(partialResults: Bundle?) = runCatching { cb.partialResults(partialResults ?: Bundle()) }.let {}
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(intent)
        }
    }

    override fun onStopListening(cb: Callback) { inner?.stopListening() }
    override fun onCancel(cb: Callback) { inner?.cancel() }
    override fun onDestroy() { inner?.destroy(); inner = null; super.onDestroy() }
}
