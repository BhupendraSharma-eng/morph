package bhupendra.morph.agent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import bhupendra.morph.AppGraph
import bhupendra.morph.MainActivity
import bhupendra.morph.R
import bhupendra.morph.core.llm.GeminiLive
import bhupendra.morph.core.llm.LiveCall
import bhupendra.morph.core.llm.LiveEvent
import bhupendra.morph.data.CanvasItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import bhupendra.morph.data.LiveUsage
import java.util.UUID

/**
 * Hands-free Morph on Gemini Live: you talk, it talks back, and it acts while it talks.
 *
 * A second *driver* of the same agent — same tool registry, same approval rule after untrusted content, same
 * conversation on screen (your words and its words appear as they're said, tool cards as usual), and every exchange
 * is added to the text agent's history so typing afterwards continues the same thread. The operator runs as a
 * background (non-blocking) tool: Morph keeps talking with you while it works in other apps, then tells you how it went.
 *
 * Audio: mic at 16 kHz with the platform echo canceller (VoIP mode, speaker unless a headset is in) streamed up in
 * 100 ms chunks; replies at 24 kHz into an AudioTrack; talking over Morph cuts it off (server-side barge-in).
 * Sessions are capped by the server (~10–15 min), so it resumes transparently with the latest resumption handle.
 * Ends on a second tap of the orb, the notification's Stop, or a minute of silence.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)   // Channel.isEmpty for the playback queue
class LiveSession(private val graph: AppGraph) {
    enum class State { Off, Connecting, Listening, Thinking, Speaking }

    private val _state = MutableStateFlow(State.Off)
    val state: StateFlow<State> = _state
    private val _level = MutableStateFlow(0f)
    /** Mic level while listening, voice level while speaking (drives the orb). */
    val level: StateFlow<Float> = _level
    private val _caption = MutableStateFlow("")
    /** What's happening, one line ("Setting an alarm…", or the last thing said). */
    val caption: StateFlow<String> = _caption
    private val _sessionUsd = MutableStateFlow(0.0)
    /** Estimated cost of this session so far (USD), for the caption. */
    val sessionUsd: StateFlow<Double> = _sessionUsd
    private var startedAt = 0L

    private var scope: CoroutineScope? = null
    private var live: GeminiLive? = null
    private var handle: String? = null
    private var lastActivity = 0L
    private val running = mutableSetOf<String>()      // tool call ids in flight
    @Volatile private var tainted = false              // untrusted content read this session → Act tools ask first
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Canvas: one "heard" bubble and one "said" bubble per turn, updated as the transcription grows.
    private var heardKey: String? = null; private var saidKey: String? = null
    private val heard = StringBuilder(); private val said = StringBuilder()

    private val audio = graph.app.getSystemService(AudioManager::class.java)
    private val playback = Channel<ByteArray>(Channel.UNLIMITED)
    private var track: AudioTrack? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()).build()

    val active: Boolean get() = _state.value != State.Off

    /** Starts hands-free. Returns a reason it can't, or null. Call from the visible activity (mic + FGS rules). */
    fun start(): String? {
        if (active) return null
        val s = graph.settings.state.value
        if (!s.aiEnabled) return "AI is off — turn it on in Settings for hands-free."
        if (s.googleApiKey.isBlank()) return "Add your Google AI key in Settings → Voice for hands-free."
        if (graph.app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "mic"
        if (overCap()) return "This month's hands-free budget (\$${"%.0f".format(s.liveCapUsd)}) is used up — raise it in Settings → Voice."
        graph.speech.stop()
        graph.runtime.cancel()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        ending = false
        _state.value = State.Connecting; _caption.value = "Connecting…"; _sessionUsd.value = 0.0
        lastActivity = System.currentTimeMillis(); startedAt = lastActivity
        runCatching { graph.app.startForegroundService(Intent(graph.app, LiveService::class.java)) }
        audioOn()
        sc.launch { connectLoop(s.googleApiKey, s.ttsVoice) }
        sc.launch(Dispatchers.IO) { mic() }
        sc.launch(Dispatchers.IO) { speaker() }
        sc.launch { idleWatch() }
        return null
    }

    fun stop() {
        if (!active) return
        _state.value = State.Off; _caption.value = ""; _level.value = 0f
        finishTurn()
        graph.liveUsage.add(seconds = (System.currentTimeMillis() - startedAt) / 1000)
        pending.values.forEach { it.deferred.complete(false) }; pending.clear()
        live?.close(); live = null; handle = null
        graph.lookAtScreen.onScreenshot = null
        scope?.cancel(); scope = null
        clearPlayback()
        audioOff()
        runCatching { graph.app.stopService(Intent(graph.app, LiveService::class.java)) }
    }

    // ---- Connection ---------------------------------------------------------------------------------------

    private suspend fun connectLoop(key: String, voice: String) {
        var failures = 0
        val g = GeminiLive(key)
        live = g
        // Live drives the screen itself (screen tool) instead of handing tasks to the operator agent.
        val tools = graph.registry.specs.filter { it.name !in EXCLUDED } + liveTools.values.map { it.spec } + CONFIRM_SPEC + END_SPEC
        // look_at_screen also shows Live a picture of the screen (as a video frame) — it can see, not just read.
        graph.lookAtScreen.onScreenshot = { jpeg -> g.sendImage(jpeg) }
        while (scope?.isActive == true && active) {
            var closedError: String? = null
            g.connect(system(), tools, emptySet(), voice, handle).collect { ev ->
                when (ev) {
                    is LiveEvent.Ready -> { failures = 0; if (_state.value == State.Connecting) { _state.value = State.Listening; _caption.value = "I'm listening" } }
                    is LiveEvent.Audio -> { _state.value = State.Speaking; playback.trySend(ev.pcm); lastActivity = System.currentTimeMillis() }
                    is LiveEvent.Heard -> onHeard(ev.text)
                    is LiveEvent.Said -> onSaid(ev.text)
                    is LiveEvent.ToolCalls -> ev.calls.forEach { call -> scope?.launch { runTool(g, call) } }
                    is LiveEvent.ToolsCancelled -> running.removeAll(ev.ids.toSet())
                    LiveEvent.Interrupted -> { clearPlayback(); runCatching { track?.pause(); track?.flush(); track?.play() } }
                    LiveEvent.TurnComplete -> {
                        finishTurn()
                        if (ending) scope?.launch { endAfterGoodbye() }
                        else if (running.isEmpty()) _state.value = State.Listening
                    }
                    // Each usage report covers one model turn (context included), so they add up.
                    is LiveEvent.Usage -> {
                        graph.liveUsage.add(inTokens = ev.promptTokens, outTokens = ev.responseTokens)
                        bhupendra.morph.core.llm.UsageMeter.record(bhupendra.morph.core.llm.UsageKind.Live, GeminiLive.MODEL,
                            ev.promptTokens, ev.responseTokens,
                            ev.promptTokens * LiveUsage.IN_PER_TOKEN + ev.responseTokens * LiveUsage.OUT_PER_TOKEN, estimated = true)
                        _sessionUsd.value += ev.promptTokens * LiveUsage.IN_PER_TOKEN + ev.responseTokens * LiveUsage.OUT_PER_TOKEN
                        if (overCap()) withContext(Dispatchers.Main) { notice("Hands-free stopped: this month's budget is used up (Settings → Voice)."); stop() }
                    }
                    is LiveEvent.Resumption -> handle = ev.handle
                    is LiveEvent.GoingAway -> Unit   // the socket will close; we reconnect below with the handle
                    is LiveEvent.Closed -> closedError = ev.error
                }
            }
            if (!active) break
            failures++
            if (failures > 3) { withContext(Dispatchers.Main) { notice("Hands-free lost its connection${closedError?.let { " ($it)" } ?: ""}."); stop() }; break }
            _caption.value = "Reconnecting…"
            delay(600L * failures)
        }
    }

    private fun system(): String = graph.liveInstructions()

    // ---- Tools ----------------------------------------------------------------------------------------------

    private suspend fun runTool(g: GeminiLive, call: LiveCall) {
        if (call.name == CONFIRM) return confirm(g, call)
        if (call.name == END) { ending = true; g.sendToolResults(listOf(Triple(call, "Ending after your goodbye.", false))); return }
        val tool = liveTools[call.name] ?: graph.registry[call.name] ?: run { g.sendToolResults(listOf(Triple(call, "Error: no tool named ${call.name}", false))); return }
        running += call.id
        _state.value = State.Thinking; _caption.value = tool.spec.label + "…"
        lastActivity = System.currentTimeMillis()
        val background = false
        val result = try {
            val args = runCatching { json.parseToJsonElement(call.argsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            // Same trust rule as typed turns (after reading others' content, per the autonomy dial) — but asked out
            // loud: the call is parked and the model asks you; your "yes" comes back through confirm_action.
            // Also: a screen tap that looks final (pay, send, delete… per the dial) asks first, like the operator did.
            val question = (tool as? bhupendra.morph.operator.ConfirmsFirst)?.confirmQuestion(args)
                ?: if (tainted && graph.settings.state.value.autonomy.gatesAfterUntrusted(tool.spec))
                    "${tool.spec.label.lowercase()}: ${describe(args)}" else null
            if (question != null) {
                val id = park { execute(tool, args) }
                running -= call.id
                g.sendToolResults(listOf(Triple(call, "NOT DONE YET — needs the user's OK. Ask them out loud, in one short " +
                    "sentence, whether to $question. Then call $CONFIRM with id \"$id\" and approved true/false based on " +
                    "their answer.", background)))
                return
            }
            execute(tool, args)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "Error: ${e.message}" }
        running -= call.id
        lastActivity = System.currentTimeMillis()
        if (active) g.sendToolResults(listOf(Triple(call, result.take(6000), background)))
        if (running.isEmpty() && _state.value == State.Thinking) _state.value = State.Listening
    }

    /** Drops queued speech (barge-in, or ending). */
    private fun clearPlayback() { do { val more = playback.tryReceive().isSuccess } while (more) }

    /** Tools only hands-free has: driving the screen directly. */
    private val liveTools = listOf<bhupendra.morph.core.agent.Tool>(
        bhupendra.morph.operator.ScreenTool { graph.settings.state.value.autonomy },
    ).associateBy { it.spec.name }

    /** "Bye" / "stop": let the goodbye play out, then end. */
    @Volatile private var ending = false
    private suspend fun endAfterGoodbye() {
        val until = System.currentTimeMillis() + 8_000
        delay(400)
        while ((_state.value == State.Speaking || !playback.isEmpty) && System.currentTimeMillis() < until) delay(150)
        delay(300)
        withContext(Dispatchers.Main) { stop() }
    }

    /** Runs a tool for real: result text for the model, its card (if any) on screen. */
    private suspend fun execute(tool: bhupendra.morph.core.agent.Tool, args: JsonObject): String {
        val out = withContext(Dispatchers.Main) { tool.run(args) }
        if (tool.spec.untrustedOutput) tainted = true
        out.surface?.let { s -> withContext(Dispatchers.Main) { post(CanvasItem.Card(s)) } }
        return out.result
    }

    // ---- Voice approvals --------------------------------------------------------------------------------------

    /** Something waiting for a spoken yes/no: a parked tool call, or the operator's confirm before a final tap. */
    private class Pending(val run: (suspend () -> String)?, val deferred: CompletableDeferred<Boolean> = CompletableDeferred())
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private var nextId = 0

    private fun park(run: (suspend () -> String)?): String = "c${++nextId}".also { pending[it] = Pending(run) }

    /** confirm_action(id, approved): runs the parked call (its result is this call's result) or releases the operator. */
    private suspend fun confirm(g: GeminiLive, call: LiveCall) {
        val args = runCatching { json.parseToJsonElement(call.argsJson) as JsonObject }.getOrNull()
        val id = (args?.get("id") as? JsonPrimitive)?.content
        val yes = (args?.get("approved") as? JsonPrimitive)?.content == "true"
        val p = id?.let { pending.remove(it) }
        val result = when {
            p == null -> "No pending confirmation with id $id."
            !yes -> { p.deferred.complete(false); "Okay — not doing it." }
            else -> { p.deferred.complete(true); p.run?.let { runCatching { it() }.getOrElse { e -> "Error: ${e.message}" } } ?: "Approved; continuing." }
        }
        g.sendToolResults(listOf(Triple(call, result.take(6000), false)))
    }

    /**
     * The operator wants a yes before a final tap (pay, send, buy…). In hands-free it's also asked out loud; the first
     * answer wins (voice or the overlay's buttons). Null when hands-free is off.
     */
    suspend fun askConfirm(question: String): Boolean? {
        val g = live ?: return null
        if (!active) return null
        val id = park(null)
        g.sendText("[The phone task needs the user's OK before continuing (confirmation id \"$id\"): $question — ask them out " +
            "loud in one short sentence, then call $CONFIRM with that id and their answer.]")
        return kotlinx.coroutines.withTimeoutOrNull(90_000) { pending[id]?.deferred?.await() } ?: false.also { pending.remove(id) }
    }

    private fun describe(args: JsonObject) = args.entries.joinToString(", ") { (k, v) ->
        "$k = " + ((v as? JsonPrimitive)?.content ?: v.toString()).take(160)
    }

    private fun overCap(): Boolean {
        val cap = graph.settings.state.value.liveCapUsd
        return cap > 0 && graph.liveUsage.month.value.usd >= cap
    }

    // ---- Transcripts on screen --------------------------------------------------------------------------------

    private fun onHeard(t: String) {
        lastActivity = System.currentTimeMillis()
        heard.append(t)
        val text = heard.toString().trim().ifEmpty { return }
        _caption.value = text
        scope?.launch(Dispatchers.Main) {
            val k = heardKey ?: UUID.randomUUID().toString().also { heardKey = it; post(CanvasItem.UserEcho(text, it)); return@launch }
            replace(k, CanvasItem.UserEcho(text, k))
        }
    }

    private fun onSaid(t: String) {
        lastActivity = System.currentTimeMillis()
        said.append(t)
        val text = said.toString().trim().ifEmpty { return }
        _caption.value = text
        scope?.launch(Dispatchers.Main) {
            val k = saidKey ?: UUID.randomUUID().toString().also { saidKey = it; post(CanvasItem.Reply(text, streaming = true, key = it)); return@launch }
            replace(k, CanvasItem.Reply(text, streaming = true, key = k))
        }
    }

    /** End of a turn: settle the bubbles and hand the exchange to the text agent's history. */
    private fun finishTurn() {
        val u = heard.toString().trim(); val a = said.toString().trim()
        val sk = saidKey
        heard.clear(); said.clear(); heardKey = null; saidKey = null
        if (sk != null) graph.scope.launch(Dispatchers.Main) { replace(sk, CanvasItem.Reply(a, streaming = false, key = sk)) }
        if (u.isNotBlank() || a.isNotBlank()) graph.scope.launch { graph.runtime.foregroundAgent.remember(u, a) }
    }

    private fun post(item: CanvasItem) { graph.canvas.items.add(item) }
    private fun replace(key: String, item: CanvasItem) {
        val i = graph.canvas.items.indexOfFirst { it.key == key }
        if (i >= 0) graph.canvas.items[i] = item
    }
    private fun notice(text: String) = post(CanvasItem.Notice(text, error = false))

    // ---- Audio ---------------------------------------------------------------------------------------------

    /** VoIP mode gives the platform echo canceller its best shot; speaker unless a headset is connected. */
    private fun audioOn() {
        runCatching {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val outs = audio.availableCommunicationDevices
            val headset = outs.firstOrNull { it.type in HEADSETS }
            (headset ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { audio.setCommunicationDevice(it) }
            audio.requestAudioFocus(focus)
        }
    }

    private fun audioOff() {
        runCatching { audio.clearCommunicationDevice(); audio.mode = savedMode; audio.abandonAudioFocusRequest(focus) }
        runCatching { track?.stop(); track?.release() }; track = null
    }

    @SuppressLint("MissingPermission")   // checked in start()
    private suspend fun mic() {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        val buf = ByteArray(3200)   // 100 ms
        try {
            rec.startRecording()
            while (kotlinx.coroutines.currentCoroutineContext().isActive && active) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (_state.value != State.Connecting) live?.sendAudio(buf, n)
                if (_state.value == State.Listening) _level.value = bhupendra.morph.device.Pcm.level(buf, n)
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); aec?.release(); ns?.release()
        }
    }

    private suspend fun speaker() {
        val t = bhupendra.morph.device.Pcm.track24k(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        track = t
        t.play()
        while (kotlinx.coroutines.currentCoroutineContext().isActive && active) {
            val pcm = playback.receiveCatching().getOrNull() ?: break
            _level.value = bhupendra.morph.device.Pcm.level(pcm)
            t.write(pcm, 0, pcm.size)
            if (playback.isEmpty) {   // nothing queued: once the tail plays out, we're listening again
                delay(250)
                if (playback.isEmpty && _state.value == State.Speaking) { _state.value = if (running.isEmpty()) State.Listening else State.Thinking; _level.value = 0f }
            }
        }
    }

    /** A minute with nobody talking and nothing running: end the session (the mic shouldn't stream into a quiet room). */
    private suspend fun idleWatch() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive && active) {
            delay(5_000)
            if (running.isEmpty() && _state.value == State.Listening && System.currentTimeMillis() - lastActivity > IDLE_MS) {
                withContext(Dispatchers.Main) { stop() }
            }
        }
    }

    companion object {
        const val CONFIRM = "confirm_action"
        const val END = "end_conversation"
        private val END_SPEC = bhupendra.morph.core.agent.ToolSpec(
            name = END,
            description = "End the hands-free conversation. Call when the user says bye, stop, that's all, thanks that's it, " +
                "or otherwise signals they're done — after a very short goodbye.",
            parameters = bhupendra.morph.core.agent.schema(emptyList()) { },
        )
        private val CONFIRM_SPEC = bhupendra.morph.core.agent.ToolSpec(
            name = CONFIRM,
            description = "Report the user's spoken answer to a pending confirmation (an action that needed their OK).",
            parameters = bhupendra.morph.core.agent.schema(listOf("id", "approved")) {
                string("id", "The confirmation id you were given")
                boolean("approved", "true only if the user clearly said yes")
            },
        )
        /**
         * Not offered in hands-free: ask_user needs a tap (Morph asks out loud instead), and operate_phone is replaced by
         * driving the screen directly with the screen tool.
         */
        private val EXCLUDED = setOf("ask_user", "operate_phone")
        private const val IDLE_MS = 60_000L
        private val HEADSETS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}

/**
 * Keeps hands-free alive when you leave Morph (the operator opens other apps; the screen may turn off): a
 * microphone-type foreground service with a Stop action. The session itself lives in [AppGraph].
 */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as bhupendra.morph.MorphApp).graph
        if (intent?.action == ACTION_STOP) { graph.live.stop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hands-free", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Morph is listening hands-free"; setSound(null, null)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Morph is listening")
            .setContentText("Hands-free · talk any time")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        runCatching { startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onFailure { android.util.Log.w("MorphLive", "foreground start failed: ${it.message}") }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "morph_live"
        const val ACTION_STOP = "bhupendra.morph.live.STOP"
    }
}
