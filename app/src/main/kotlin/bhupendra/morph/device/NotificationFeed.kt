package bhupendra.morph.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Notif(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    /** What tapping it in the shade does (open the chat, the player, the post…). */
    val open: android.app.PendingIntent? = null,
    val isMedia: Boolean = false,
    /** Set for chat notifications: the conversation, keyed like IncomingMessage.threadKey. */
    val thread: String? = null,
    /** Chats: every unread message since your last reply, oldest first ("Name: text" in groups). */
    val messages: List<String> = emptyList(),
)

/** Fires another app's PendingIntent from our visible home screen. */
object PendingIntents {
    /**
     * Since Android 14 a sender must explicitly lend its foreground status, or the target activity
     * is silently blocked. We're the visible home screen, so allow-if-visible is exactly right.
     */
    fun sendFromForeground(context: android.content.Context, pi: android.app.PendingIntent): Boolean = runCatching {
        val opts = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        ).toBundle()
        pi.send(context, 0, null, null, null, null, opts)
        true
    }.getOrDefault(false)
}

/** In-memory feed of what's currently in the shade — the raw material for proactivity. */
object NotificationFeed {
    private val _items = MutableStateFlow<List<Notif>>(emptyList())
    val items: StateFlow<List<Notif>> = _items

    internal fun publish(list: List<Notif>) { _items.value = list.sortedByDescending { it.time } }

    fun hasAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NotificationFeedService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

class NotificationFeedService : NotificationListenerService() {

    override fun onListenerConnected() {
        sync()
        // Anything "for you" whose chat has no notification any more was dealt with while Morph wasn't listening.
        runCatching { activeNotifications }.getOrNull()?.let { (application as bhupendra.morph.MorphApp).graph.autoReply.reconcile(it.toList()) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sync()
        sbn?.let { (application as bhupendra.morph.MorphApp).graph.autoReply.onPosted(it) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = sync()

    /** With the reason: lets "needs you" items resolve when you deal with a chat outside Morph. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sync()
        sbn?.let { (application as bhupendra.morph.MorphApp).graph.autoReply.onRemoved(it, reason) }
    }
    override fun onListenerDisconnected() = NotificationFeed.publish(emptyList())

    /** The burst you haven't answered: messages after your own last one in the chat's MessagingStyle. */
    private fun unread(n: Notification): List<String> = runCatching {
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return emptyList()
        val me = style.user.name?.toString()
        val msgs = style.messages
        val mine = msgs.indexOfLast { it.person == null || it.person?.name?.toString() == me }
        val group = style.isGroupConversation
        msgs.drop(mine + 1).takeLast(12).mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            if (group) "${m.person?.name ?: "?"}: $text" else text
        }
    }.getOrDefault(emptyList())

    private fun sync() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationFeed.publish(active.mapNotNull(::toNotif))
    }

    private fun toNotif(sbn: StatusBarNotification): Notif? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(sbn.packageName)
        return Notif(
            sbn.key, sbn.packageName, app, title, text.take(400), sbn.postTime,
            open = n.contentIntent,
            isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            thread = runCatching { (application as bhupendra.morph.MorphApp).graph.autoReply.chatThreadOf(sbn) }.getOrNull(),
            messages = unread(n),
        )
    }
}
