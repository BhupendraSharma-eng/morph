package bhupendra.morph.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Morph's voice. Gemini TTS streamed straight into an [AudioTrack] when AI is on and a Google key is set (first
 * sound in ~1 s, raw 24 kHz PCM, no files); Android's on-device TextToSpeech otherwise (offline, AI off).
 * Music ducks while it talks; [level] drives the orb; [stop] cuts it off instantly (barge-in).
 */
class Speech(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _level = MutableStateFlow(0f)
    /** 0..1 loudness of what's being said right now (for the orb). */
    val level: StateFlow<Float> = _level

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Says [raw] (markdown stripped; long answers trimmed to their opening with a pointer to the screen). */
    fun speak(raw: String) {
        val text = speakable(raw).ifBlank { return }
        stop()
        val s = settings.state.value
        job = scope.launch(Dispatchers.IO) {
            begin()
            try {
                // Long text plays in sentence-aligned pieces back to back: nothing is dropped, and each piece
                // starts streaming quickly. If Gemini fails mid-way, the rest continues on the on-device voice.
                var useGemini = s.aiEnabled && s.googleApiKey.isNotBlank()
                for (piece in pieces(text)) {
                    if (!isActive) break
                    val ok = useGemini && runCatching { gemini(piece, s.googleApiKey, s.ttsVoice) }
                        .onFailure { android.util.Log.w("MorphSpeech", "gemini tts failed: ${it.message}") }.getOrDefault(false)
                    if (!ok) { useGemini = false; if (isActive) local(piece) }
                }
            } finally { end() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { track?.pause(); track?.flush() }
        tts?.stop()
        end()
    }

    private fun begin() { _speaking.value = true; runCatching { audio.requestAudioFocus(focus) } }
    private fun end() {
        _speaking.value = false; _level.value = 0f
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    /** Streams Gemini TTS chunk by chunk into the speaker. True if it played. */
    private suspend fun gemini(text: String, key: String, voice: String): Boolean {
        val t = Pcm.track24k(attrs)
        track = t
        var played = false
        try {
            bhupendra.morph.core.llm.GeminiTts(key).stream(text, voice).collect { pcm ->
                if (!played) { t.play(); played = true }
                _level.value = Pcm.level(pcm)
                t.write(pcm, 0, pcm.size)
            }
            if (played) drain(t)
        } finally {
            runCatching { t.stop(); t.release() }
            if (track === t) track = null
        }
        return played
    }

    /** Waits for the buffered tail to finish playing (written frames vs played head). */
    private suspend fun drain(t: AudioTrack) {
        val total = runCatching { t.bufferSizeInFrames }.getOrDefault(0)
        var last = -1; var still = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && still < 6) {
            val head = t.playbackHeadPosition
            if (head == last) still++ else still = 0
            last = head
            _level.value = if (still > 0) 0f else _level.value * 0.9f
            kotlinx.coroutines.delay(50)
            if (total == 0) break
        }
    }

    /** On-device fallback. Suspends until the utterance finishes (or is stopped). */
    private suspend fun local(text: String) {
        val engine = tts ?: kotlinx.coroutines.suspendCancellableCoroutine<TextToSpeech?> { c ->
            var made: TextToSpeech? = null
            made = TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS; c.resumeWith(Result.success(made)) }
        }.also { tts = it }
        if (engine == null || !ttsReady) return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { _level.value = 0.5f }
                override fun onDone(id: String?) { if (c.isActive) c.resumeWith(Result.success(Unit)) }
                @Deprecated("") override fun onError(id: String?) { if (c.isActive) c.resumeWith(Result.success(Unit)) }
                override fun onStop(id: String?, interrupted: Boolean) { if (c.isActive) c.resumeWith(Result.success(Unit)) }
            })
            engine.setAudioAttributes(attrs)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "morph")
            c.invokeOnCancellation { engine.stop() }
        }
    }

    companion object {
        const val TTS_MODEL = bhupendra.morph.core.llm.GeminiTts.MODEL
        val VOICES = listOf("Kore", "Puck", "Charon", "Aoede", "Leda", "Orus", "Zephyr", "Fenrir")

        /** Markdown, bullets and links out — the words themselves are all kept. */
        fun speakable(raw: String): String {
            var t = raw.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_`#>|]"), "")
                .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("\\s+"), " ").trim()
            return t
        }

        /** Splits at sentence ends into pieces of at most ~900 characters (a TTS-friendly size). */
        fun pieces(text: String, max: Int = 900): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            text.split(Regex("(?<=[.!?।])\\s+")).forEach { sentence ->
                if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) { out += cur.toString(); cur.clear() }
                if (sentence.length > max) sentence.chunked(max).forEach { out += it } else cur.append(if (cur.isEmpty()) "" else " ").append(sentence)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}
