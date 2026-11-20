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
)

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

    override fun onListenerConnected() = sync()
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sync()
        sbn?.let { (application as bhupendra.morph.MorphApp).graph.autoReply.onPosted(it) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = sync()
    override fun onListenerDisconnected() = NotificationFeed.publish(emptyList())

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
        return Notif(sbn.key, sbn.packageName, app, title, text.take(400), sbn.postTime)
    }
}
