package bhupendra.morph.operator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import bhupendra.morph.MorphApp
import bhupendra.morph.core.operator.Screen
import bhupendra.morph.core.operator.ScreenSnapshot
import bhupendra.morph.core.operator.Target
import bhupendra.morph.core.operator.UiAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Morph's hands and eyes inside other apps. Reads the active window's accessibility tree into a compact
 * list of elements and performs taps, typing, scrolling and global actions on them.
 * Only used while an operator task is running; it does nothing on its own.
 */
class OperatorService : AccessibilityService(), Screen {

    companion object {
        @Volatile var instance: OperatorService? = null
            private set
        private const val MAX_NODES = 160
        private const val MAX_CHARS = 7000
    }

    lateinit var overlay: OperatorOverlay
        private set
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var targets: Map<Int, Target> = emptyMap()

    override fun onServiceConnected() {
        instance = this
        overlay = OperatorOverlay(this)
    }

    override fun onDestroy() {
        instance = null
        overlay.hide()
        super.onDestroy()
    }

    /** Last time another app's UI changed — drives event-based settling instead of fixed sleeps. */
    @Volatile private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.packageName != packageName) lastEventAt = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Returns as soon as the app has been quiet for a moment, rather than always sleeping. Typical: 150–400 ms
     * on a responsive app; up to a few seconds for cold starts and loading.
     */
    override suspend fun settle(after: UiAction) {
        val maxMs = when (after) { is UiAction.OpenApp -> 3500L; UiAction.Back, UiAction.Home -> 1500L; else -> 2000L }
        val quietMs = 300L
        val start = android.os.SystemClock.uptimeMillis()
        kotlinx.coroutines.delay(120)
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEventAt >= quietMs || now - start >= maxMs) break
            kotlinx.coroutines.delay(40)
        }
    }
    override fun onInterrupt() = Unit

    // ---- Observe --------------------------------------------------------------------------------------

    override suspend fun observe(): ScreenSnapshot = withContext(Dispatchers.Main) {
        val root = windows.firstOrNull { it.isActive && it.root?.packageName != packageName }?.root
            ?: rootInActiveWindow
            ?: return@withContext ScreenSnapshot("unknown", "(screen not readable)")
        val app = appLabel(root.packageName?.toString())
        val ids = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val tg = LinkedHashMap<Int, Target>()
        val out = StringBuilder()

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (ids.size >= MAX_NODES || out.length > MAX_CHARS || !n.isVisibleToUser) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = n.hintText?.toString()?.trim().orEmpty()
            val interesting = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable ||
                text.isNotEmpty() || desc.isNotEmpty()
            var childDepth = depth
            if (interesting) {
                val id = ids.size + 1
                ids[id] = n
                val label = when {
                    n.isPassword -> "••••"
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    else -> hint
                }.replace('\n', ' ').take(90)
                tg[id] = Target(id, if (n.isPassword) "password field" else listOf(text, desc, hint).firstOrNull { it.isNotEmpty() }.orEmpty(), n.isPassword)
                val flags = buildList {
                    if (n.isClickable) add("tap")
                    if (n.isEditable) add("edit")
                    if (n.isScrollable) add("scroll")
                    if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                    if (n.isPassword) add("password")
                    if (!n.isEnabled) add("disabled")
                    if (n.isSelected) add("selected")
                }
                out.append("  ".repeat(depth.coerceAtMost(6)))
                    .append("[").append(id).append("] ").append(role(n))
                    .append(if (label.isNotEmpty()) " \"$label\"" else "")
                    .append(if (hint.isNotEmpty() && hint != label) " (hint: ${hint.take(40)})" else "")
                    .append(if (flags.isNotEmpty()) " {${flags.joinToString(",")}}" else "")
                    .append('\n')
                childDepth = depth + 1
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, childDepth) }
        }
        visit(root, 0)
        nodes = ids
        targets = tg
        ScreenSnapshot(app, out.toString().ifBlank { "(nothing readable on screen)" })
    }

    override fun target(id: Int): Target? = targets[id]

    private fun role(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            n.isEditable -> "Field"
            c.contains("Switch") -> "Switch"
            c.contains("CheckBox") -> "Checkbox"
            c.contains("RadioButton") -> "Radio"
            c.contains("Button") -> "Button"
            c.contains("Image") -> if (n.isClickable) "IconButton" else "Image"
            n.isScrollable -> "List"
            n.isClickable -> "Item"
            else -> "Text"
        }
    }

    private fun appLabel(pkg: String?): String = pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
    } ?: "unknown"

    // ---- Act ------------------------------------------------------------------------------------------

    override suspend fun act(action: UiAction): String = withContext(Dispatchers.Main) {
        when (action) {
            is UiAction.Tap -> node(action.id)?.let { n ->
                val target = clickableSelfOrAncestor(n)
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) "tapped" else gestureTap(n, 60).let { "tapped (gesture)" }
            } ?: "no element [${action.id}]"
            is UiAction.LongPress -> node(action.id)?.let { n ->
                val target = n.takeIf { it.isLongClickable } ?: clickableSelfOrAncestor(n)
                if (target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) "long-pressed" else gestureTap(n, 700).let { "long-pressed (gesture)" }
            } ?: "no element [${action.id}]"
            is UiAction.Type -> node(action.id)?.let { n ->
                if (n.isPassword) return@let "refused: password field"
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                })
                if (action.enter) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (ok) "typed" else "couldn't type there"
            } ?: "no element [${action.id}]"
            is UiAction.Scroll -> {
                val n = action.id?.let(::node) ?: nodes.values.firstOrNull { it.isScrollable }
                val op = if (action.down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (n?.performAction(op) == true) "scrolled" else { swipe(action.down); "scrolled (gesture)" }
            }
            UiAction.Back -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "went back" else "back failed"
            UiAction.Home -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "went home" else "home failed"
            is UiAction.OpenApp -> {
                val graph = (application as MorphApp).graph
                val app = graph.catalog.resolve(action.name)
                if (app != null && graph.catalog.launch(app)) "opened ${app.label}" else "no app called ${action.name}"
            }
        }
    }

    private fun node(id: Int) = nodes[id]

    private fun clickableSelfOrAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) { if (cur == null || cur!!.isClickable) return cur; cur = cur!!.parent }
        return null
    }

    private suspend fun gestureTap(n: AccessibilityNodeInfo, durationMs: Long) {
        val r = Rect().also(n::getBoundsInScreen)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build())
    }

    private suspend fun swipe(down: Boolean) {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (from, to) = if (down) m.heightPixels * 0.7f to m.heightPixels * 0.3f else m.heightPixels * 0.3f to m.heightPixels * 0.7f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build())
    }

    private suspend fun dispatch(g: GestureDescription) = suspendCancellableCoroutine { c ->
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(Unit) }
        }, null)
        if (!sent && c.isActive) c.resume(Unit)
    }
}
