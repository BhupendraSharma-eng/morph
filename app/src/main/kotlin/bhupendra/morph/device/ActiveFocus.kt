package bhupendra.morph.device

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import bhupendra.morph.operator.OperatorService

/**
 * What the user is looking at right now, so Morph doesn't spend AI on a message they're already reading: you're in
 * the chat, mid-conversation — there's nothing to triage, summarise or auto-reply.
 *
 * With the operator's accessibility access it checks the open screen for that conversation's name (so another chat
 * in the same app still counts as new); without it, it can only tell which app is in front (usage access).
 */
object ActiveFocus {
    /** True if the screen is on and [conversation] in [pkg] is open in front of the user. */
    fun isOpen(context: Context, pkg: String, conversation: String?): Boolean = runCatching {
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        val svc = OperatorService.instance
        if (svc != null) {
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != pkg) return false
            return conversation.isNullOrBlank() || root.findAccessibilityNodeInfosByText(conversation).isNotEmpty()
        }
        foregroundApp(context) == pkg
    }.getOrDefault(false)

    /** The app in front, from the last few minutes of usage events (null without usage access). */
    fun foregroundApp(context: Context): String? = runCatching {
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return null
        OperatorService.instance?.rootInActiveWindow?.packageName?.toString()?.let { return it }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        last
    }.getOrNull()
}
