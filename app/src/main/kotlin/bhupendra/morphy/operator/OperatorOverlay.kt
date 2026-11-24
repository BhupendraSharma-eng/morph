package bhupendra.morphy.operator

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import bhupendra.morphy.core.operator.OperatorUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The floating pill shown while Morphy operates another app: what it's doing right now, a Stop button,
 * and inline Allow/Deny or option buttons when it needs you. Drawn as an accessibility overlay, so it
 * floats above every app without the "draw over other apps" permission.
 */
class OperatorOverlay(private val service: AccessibilityService) : OperatorUi {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var buttons: LinearLayout
    var onStop: () -> Unit = {}

    private fun color(id: Int) = service.getColor(id)
    private val dp = service.resources.displayMetrics.density

    fun show(task: String) {
        if (root != null) { title.text = "Morphy · $task"; return }
        val pad = (16 * dp).toInt()
        title = TextView(service).apply {
            text = "Morphy · $task"; textSize = 13f; maxLines = 1
            setTextColor(color(android.R.color.system_accent1_200))
        }
        body = TextView(service).apply {
            text = "Starting…"; textSize = 16f
            setTextColor(color(android.R.color.system_neutral1_50))
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }
        buttons = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * dp
                setColor(color(android.R.color.system_neutral1_900))
                setStroke((1.5f * dp).toInt(), color(android.R.color.system_accent1_400))
            }
            elevation = 12 * dp
            addView(title); addView(body); addView(buttons)
        }
        setButtons(listOf("Stop" to { onStop() }))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (44 * dp).toInt()
            horizontalMargin = 0.03f
        }
        runCatching { wm.addView(root, lp) }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    /** Briefly show the outcome, then disappear. */
    fun finish(message: String) {
        root ?: return
        body.text = message
        setButtons(listOf("OK" to { hide() }))
        root?.postDelayed({ hide() }, 5000)
    }

    private fun setButtons(specs: List<Pair<String, () -> Unit>>) {
        buttons.removeAllViews()
        specs.forEachIndexed { i, (label, onClick) ->
            buttons.addView(Button(service).apply {
                text = label; isAllCaps = false; textSize = 14f
                val primary = i == specs.lastIndex
                setTextColor(color(if (primary) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50))
                background = GradientDrawable().apply {
                    cornerRadius = 22 * dp
                    setColor(color(if (primary) android.R.color.system_accent1_200 else android.R.color.system_neutral1_700))
                }
                minHeight = (40 * dp).toInt(); minimumHeight = (40 * dp).toInt()
                setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), 0)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8 * dp).toInt()
            })
        }
    }

    override suspend fun status(text: String) = withContext(Dispatchers.Main) {
        body.text = text
        setButtons(listOf("Stop" to { onStop() }))
    }

    override suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) {
        // In hands-free the question is also asked out loud; whichever answer comes first (voice or button) wins.
        val graph = (service.application as bhupendra.morphy.MorphyApp).graph
        suspendCancellableCoroutine { c ->
            body.text = "⚠️ $question"
            root?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            fun done(v: Boolean) { if (c.isActive) { setButtons(listOf("Stop" to { onStop() })); c.resume(v) } }
            setButtons(listOf("Don't" to { done(false) }, "Allow" to { done(true) }))
            if (graph.live.active) {
                val ask = graph.scope.launch { graph.live.askConfirm(question)?.let { v -> withContext(Dispatchers.Main) { done(v) } } }
                c.invokeOnCancellation { ask.cancel() }
            }
        }
    }

    override suspend fun choose(question: String, options: List<String>): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            body.text = question
            fun done(v: String?) { if (c.isActive) { setButtons(listOf("Stop" to { onStop() })); c.resume(v) } }
            setButtons(listOf<Pair<String, () -> Unit>>("Stop" to { done(null) }) + options.take(3).map { o -> o to { done(o) } })
        }
    }
}
