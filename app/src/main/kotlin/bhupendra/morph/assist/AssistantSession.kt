package bhupendra.morph.assist

import android.Manifest
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import bhupendra.morph.MainActivity
import bhupendra.morph.MorphApp
import bhupendra.morph.agent.Busy
import bhupendra.morph.core.surface.Action
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.device.NotificationFeed
import bhupendra.morph.device.PendingIntents
import bhupendra.morph.device.Voice
import bhupendra.morph.ui.MorphTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Registers Morph as a digital assistant. All the work happens in the session below. */
class MorphVoiceService : VoiceInteractionService() {
    /** Called just before the panel shows, while the user's app is still in front: read it now. */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        AssistBridge.lastScreen = runCatching { bhupendra.morph.operator.OperatorService.instance?.foregroundSnapshot() }.getOrNull()
    }
}

class MorphSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * The floating assistant: a Compose panel over whatever app you're in. It's just another view onto the
 * always-running AgentRuntime — same conversation, same tools, same canvas as the home screen.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    val graph = (context.applicationContext as MorphApp).graph
    private val voice = Voice(context)
    private var scope: CoroutineScope? = null

    // ---- Panel state --------------------------------------------------------------------------------
    var input by mutableStateOf("")
    var listening by mutableStateOf(false)
        private set
    var voiceLevel by mutableFloatStateOf(0f)
        private set
    var busy by mutableStateOf<Busy>(Busy.Idle)
        private set
    /** App the user summoned us over, and its visible text. */
    var screenApp by mutableStateOf<String?>(null)
        private set
    private var screenText by mutableStateOf<String?>(null)   // observable: may arrive just after the panel opens
    /** Canvas keys that existed when the panel opened — the panel shows only what's new. */
    var since by mutableStateOf<Set<String>>(emptySet())
        private set
    var shownAt by mutableStateOf(0L)
        private set

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        window.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.decorView.setViewTreeLifecycleOwner(this)
            w.decorView.setViewTreeSavedStateRegistryOwner(this)
            w.decorView.setViewTreeViewModelStoreOwner(this)
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setViewTreeViewModelStoreOwner(this@AssistantSession)
        setContent { MorphTheme { AssistantPanel(this@AssistantSession) } }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Over Morph's own home the home *is* the assistant: start listening there instead of stacking a panel.
        if (AssistBridge.homeInFront) AssistBridge.listenOnHome?.let { listen -> listen(); hide(); return }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { graph.runtime.busy.collect { busy = it } }
        }
        AssistBridge.hidePanel = { hide() }
        graph.prewarm()   // you're about to ask something: have the TLS connection ready
        since = graph.canvas.items.map { it.key }.toSet()
        shownAt = System.currentTimeMillis()
        input = ""
        // Note: don't reset screen context here — Android may deliver onHandleAssist *before* onShow.
        // Some platforms withhold the AssistStructure; use the snapshot the operator's accessibility service took
        // just before this panel appeared (see MorphVoiceService.onPrepareToShowSession).
        if (screenText == null) AssistBridge.lastScreen?.let { snap ->
            screenApp = snap.app
            screenText = "App: ${snap.app}\n" + snap.tree.lines().joinToString("\n") { it.substringAfter("] ").trim() }.take(4000)
        }
        AssistBridge.lastScreen = null
        // Voice-first, like an assistant should be.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
    }

    override fun onHide() {
        voice.stop(); listening = false
        screenApp = null; screenText = null   // the next summon brings its own screen
        AssistBridge.hidePanel = null
        scope?.cancel(); scope = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBackPressed() = hide()

    /** Android hands us the structure of the screen we were summoned over (when "use text from screen" is on). */
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure ?: return
        val pkg = structure.activityComponent?.packageName
        if (pkg == context.packageName) return
        screenApp = pkg?.let { p ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }
        screenText = extract(structure).takeIf { it.isNotBlank() }?.let { "App: $screenApp\n$it" }
    }

    private fun extract(s: AssistStructure): String {
        val lines = LinkedHashSet<String>()
        var chars = 0
        fun visit(n: AssistStructure.ViewNode?) {
            if (n == null || chars > 4000 || n.visibility != View.VISIBLE) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.replace('\n', ' ')
            if (!t.isNullOrBlank() && t.length > 1 && lines.add(t.take(300))) chars += t.length
            for (i in 0 until n.childCount) visit(n.getChildAt(i))
        }
        for (i in 0 until s.windowNodeCount) visit(s.getWindowNodeAt(i).rootViewNode)
        return lines.joinToString("\n")
    }

    // ---- Intents ---------------------------------------------------------------------------------------

    fun submit(raw: String = input) {
        val text = raw.trim()
        if (text.isEmpty()) return
        input = ""
        voice.stop(); listening = false

        graph.local.match(text)?.let { m ->
            scope?.launch {
                val out = m.tool.run(m.args)
                if (m.tool.spec.name == "open_app") hide()
                else graph.canvas.add(out.surface?.let { CanvasItem.Card(it) } ?: CanvasItem.Notice(out.result, error = false))
            }
            return
        }
        val provider = graph.settings.state.value.provider() ?: run {
            graph.canvas.add(CanvasItem.Notice("Connect an AI in Morph's settings first.", error = true, action = "Settings" to Action.OpenSettings))
            return
        }
        val screen = screenText
        scope?.launch { graph.conversations.beforeAsk(); graph.runtime.ask(text, provider, screen) }
            ?: graph.runtime.ask(text, provider, screen)
    }

    fun stop() = graph.runtime.cancel()

    fun toggleMic() = if (listening) voice.finish() else startListening()

    private fun startListening() {
        if (!voice.available) return
        listening = true
        voice.start(object : Voice.Listener {
            override fun onPartial(text: String) { input = text }
            override fun onLevel(level: Float) { voiceLevel = level }
            override fun onFinal(text: String) { listening = false; voiceLevel = 0f; if (text.isNotBlank()) submit(text) }
            override fun onError(message: String) { listening = false; voiceLevel = 0f; if (input.isNotBlank()) submit(input) }
        })
    }

    fun perform(action: Action) {
        when (action) {
            is Action.Say -> submit(action.text)
            is Action.Launch -> { graph.catalog.resolve(action.app)?.let(graph.catalog::launch); hide() }
            is Action.OpenUrl -> { start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action.url))); hide() }
            is Action.OpenNotification -> {
                val pi = NotificationFeed.items.value.firstOrNull { it.key == action.key }?.open
                if (pi == null || !PendingIntents.sendFromForeground(context, pi)) graph.catalog.resolve(action.app)?.let(graph.catalog::launch)
                hide()
            }
            is Action.RunTool -> scope?.launch {
                val tool = graph.registry[action.name] ?: return@launch
                val args = runCatching { Json.parseToJsonElement(action.argsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                val out = tool.run(args)
                graph.canvas.add(out.surface?.let { CanvasItem.Card(it) } ?: CanvasItem.Notice(out.result, error = false))
            }
            is Action.DismissNotifications -> NotificationFeed.dismiss(action.keys)
            // Permissions, settings and Morph's own pages live in the home app.
            else -> openMorph()
        }
    }

    fun openMorph() {
        start(Intent(context, MainActivity::class.java))
        hide()
    }

    private fun start(i: Intent) { runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    val hasScreen: Boolean get() = screenText != null
}
