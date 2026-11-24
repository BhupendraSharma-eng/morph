package bhupendra.morphy.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings

/** One row in the instant-search panel. */
sealed interface Hit {
    val key: String
    data class Shortcut(val entry: ShortcutEntry) : Hit { override val key get() = "s:" + entry.key }
    data class Contact(val id: Long, val name: String, val number: String?) : Hit { override val key get() = "c:$id" }
    data class Setting(val label: String, val emoji: String, val action: String) : Hit { override val key get() = "t:$action" }
}

data class SearchResults(val apps: List<AppEntry>, val hits: List<Hit>, val query: String = "") {
    val isEmpty get() = apps.isEmpty() && hits.isEmpty()
    companion object { val Empty = SearchResults(emptyList(), emptyList()) }
}

/**
 * Universal instant search — everything local, nothing waits on a model. Typing narrows apps,
 * app shortcuts, contacts and system settings; Enter still goes to the agent for everything else.
 */
class UniversalSearch(private val context: Context, private val catalog: AppCatalog) {

    private data class SettingPage(val label: String, val emoji: String, val action: String, val words: List<String>)

    private val settings = listOf(
        SettingPage("Wi-Fi", "📶", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wi-fi", "wireless", "internet")),
        SettingPage("Bluetooth", "🔵", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "bt", "pair", "earbuds", "headphones")),
        SettingPage("Mobile network", "📡", Settings.ACTION_NETWORK_OPERATOR_SETTINGS, listOf("mobile", "sim", "network", "data", "carrier")),
        SettingPage("Hotspot & tethering", "🛜", "android.settings.TETHER_SETTINGS", listOf("hotspot", "tether", "share internet")),
        SettingPage("Data usage", "📊", Settings.ACTION_DATA_USAGE_SETTINGS, listOf("data usage", "data limit")),
        SettingPage("Airplane mode", "✈️", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("airplane", "flight")),
        SettingPage("Display", "🔆", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "brightness", "screen", "dark mode", "font size")),
        SettingPage("Sound & vibration", "🔊", Settings.ACTION_SOUND_SETTINGS, listOf("sound", "ringtone", "vibration", "volume")),
        SettingPage("Do Not Disturb", "🌙", "android.settings.ZEN_MODE_SETTINGS", listOf("dnd", "do not disturb", "silence", "focus")),
        SettingPage("Notifications", "🔔", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, listOf("notification")),
        SettingPage("Battery", "🔋", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("battery", "power", "charging")),
        SettingPage("Battery saver", "🪫", Settings.ACTION_BATTERY_SAVER_SETTINGS, listOf("battery saver", "power saving")),
        SettingPage("Apps", "📦", Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "applications", "app info")),
        SettingPage("Default apps", "🏠", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listOf("default", "home app", "launcher", "browser")),
        SettingPage("Storage", "💾", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("storage", "space", "memory")),
        SettingPage("Location", "📍", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("location", "gps")),
        SettingPage("Security", "🔒", Settings.ACTION_SECURITY_SETTINGS, listOf("security", "lock", "fingerprint", "face unlock")),
        SettingPage("Privacy", "🛡️", Settings.ACTION_PRIVACY_SETTINGS, listOf("privacy", "permissions")),
        SettingPage("Accessibility", "♿", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("accessibility", "talkback")),
        SettingPage("Wallpaper", "🖼️", Intent.ACTION_SET_WALLPAPER, listOf("wallpaper", "background")),
        SettingPage("Date & time", "🕒", Settings.ACTION_DATE_SETTINGS, listOf("date", "time", "timezone")),
        SettingPage("Languages & input", "⌨️", Settings.ACTION_LOCALE_SETTINGS, listOf("language", "keyboard", "input")),
        SettingPage("NFC", "📳", Settings.ACTION_NFC_SETTINGS, listOf("nfc", "tap to pay")),
        SettingPage("Developer options", "🛠️", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("developer", "usb debugging", "adb")),
        SettingPage("About phone", "📱", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("about", "android version", "build number")),
        SettingPage("All settings", "⚙️", Settings.ACTION_SETTINGS, listOf("settings")),
    )

    val hasContacts: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Called off the main thread (contacts query touches a content provider). */
    fun search(raw: String): SearchResults {
        val q = normalize(raw)
        if (q.isEmpty()) return SearchResults.Empty
        val apps = catalog.search(q, 8)
        val people = if (q.length >= 2 && hasContacts) contacts(q) else emptyList()
        val peopleNames = people.map { normalize(it.name) }.toSet()
        // Phone/Contacts publish starred people as shortcuts; the contact row (with call/message) covers them.
        val shortcuts = catalog.searchShortcuts(q, 4).filterNot { it.norm in peopleNames }.take(3)
        // Apps (e.g. Settings itself) often publish the same destinations as shortcuts — show each once.
        val shortcutLabels = shortcuts.map { it.norm }.toSet()
        val hits = buildList {
            people.forEach { add(it) }
            shortcuts.forEach { add(Hit.Shortcut(it)) }
            if (q.length >= 2) settings
                .filter { s -> normalize(s.label).contains(q) || s.words.any { it.startsWith(q) || q.startsWith(it) } }
                .filterNot { normalize(it.label) in shortcutLabels }
                .take(2).forEach { add(Hit.Setting(it.label, it.emoji, it.action)) }
        }
        // What you chose for this before rises to the top.
        val habits = catalog.habits
        return SearchResults(apps, hits.sortedByDescending { habits.pickBoost(q, it.key) }, q)
    }

    private fun contacts(q: String): List<Hit.Contact> {
        val out = LinkedHashMap<Long, Hit.Contact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("$q%", "% $q%"),
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 40) {
                    val id = c.getLong(0)
                    if (id !in out) out[id] = Hit.Contact(id, c.getString(1), c.getString(2))
                }
            }
        }
        // Rank like apps: exact > starts-with > a word starts-with, then shorter names (closer matches) first.
        fun rank(c: Hit.Contact): Int {
            val n = normalize(c.name)
            return when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.split(' ').any { it.startsWith(q) } -> 2
                else -> 3
            }
        }
        return out.values.sortedWith(compareBy<Hit.Contact>(::rank).thenBy { it.name.length }).take(3)
    }

    fun open(hit: Hit) {
        val intent = when (hit) {
            is Hit.Shortcut -> { catalog.launchShortcut(hit.entry); return }
            is Hit.Contact -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, hit.id))
            is Hit.Setting -> Intent(hit.action)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { if (hit is Hit.Setting) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Calls straight away when Morphy may place calls; otherwise the dialer with the number ready. */
    fun call(c: Hit.Contact) = c.number?.let {
        val direct = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        start(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it))))
    }
    fun message(c: Hit.Contact) = c.number?.let { start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(it)))) }

    private fun start(i: Intent) { runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
