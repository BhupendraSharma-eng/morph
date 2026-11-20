package bhupendra.morph.device

import android.Manifest
import android.app.AlarmManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.SchemaBuilder
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.agent.schema
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.n(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

private fun tool(
    name: String, label: String, description: String, risk: Risk = Risk.Read,
    required: List<String> = emptyList(), params: SchemaBuilder.() -> Unit = {},
    untrusted: Boolean = false,
    body: suspend (JsonObject) -> ToolOutcome,
): Tool = object : Tool {
    override val spec = ToolSpec(name, description, schema(required, params), risk, label = label, untrustedOutput = untrusted)
    override suspend fun run(args: JsonObject) = body(args)
}

/** Everything the agent can do on the phone. Each tool is self-describing (schema + risk + label). */
class DeviceTools(
    private val context: Context,
    private val catalog: AppCatalog,
    private val settings: SettingsStore,
) {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile var torchOn = false
        private set

    init {
        camera.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
        }, Handler(Looper.getMainLooper()))
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun card(icon: String, title: String, vararg blocks: Block, tone: Tone = Tone.Neutral) =
        Surface(title = title, icon = icon, tone = tone, blocks = blocks.toList())

    // ---- Tools -------------------------------------------------------------------------------------

    val openApp = tool("open_app", "Opening", "Open an installed app by name or package. Fuzzy names are fine.", Risk.Act,
        listOf("app"), { string("app", "App name or package, e.g. 'spotify'") }) { a ->
        val e = catalog.resolve(a.s("app").orEmpty()) ?: return@tool ToolOutcome("No installed app matches '${a.s("app")}'.")
        if (withContext(Dispatchers.Main) { catalog.launch(e) }) ToolOutcome("Opened ${e.label}.") else ToolOutcome("Couldn't open ${e.label}.")
    }

    val findApps = tool("find_apps", "Looking for apps", "Search installed apps. Returns names and packages.",
        params = { string("query", "Search text; empty returns the most-used apps") }) { a ->
        val q = a.s("query")
        val found = if (q == null) catalog.frequent(12) else catalog.search(q, 12)
        ToolOutcome(if (found.isEmpty()) "No apps found." else found.joinToString("\n") { "${it.label} (${it.pkg})" })
    }

    val timer = tool("set_timer", "Setting a timer", "Start a countdown timer.", Risk.Act,
        params = {
            integer("minutes", "Minutes"); integer("seconds", "Extra seconds"); string("label", "What it's for")
        }) { a ->
        val secs = ((a.n("minutes") ?: 0.0) * 60 + (a.n("seconds") ?: 0.0)).roundToInt()
        if (secs <= 0) return@tool ToolOutcome("Error: timer length must be positive.")
        val ok = start(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { a.s("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
        val pretty = if (secs >= 60) "${secs / 60} min" + (if (secs % 60 > 0) " ${secs % 60}s" else "") else "${secs}s"
        if (!ok) ToolOutcome("No clock app can set timers.")
        else ToolOutcome("Timer set for $pretty.", card("⏱️", "Timer running",
            Block.Metric(pretty, a.s("label") ?: "Countdown started"), tone = Tone.Tertiary))
    }

    val alarm = tool("set_alarm", "Setting an alarm", "Set an alarm at a time of day (24h).", Risk.Act,
        listOf("hour", "minute"), {
            integer("hour", "0-23"); integer("minute", "0-59"); string("label", "Optional label")
        }) { a ->
        val h = a.n("hour")?.toInt() ?: return@tool ToolOutcome("Error: hour required")
        val m = a.n("minute")?.toInt() ?: 0
        val ok = start(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { a.s("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
        val t = "%02d:%02d".format(h, m)
        if (!ok) ToolOutcome("No clock app can set alarms.")
        else ToolOutcome("Alarm set for $t.", card("⏰", "Alarm set", Block.Metric(t, a.s("label") ?: "Wake up"), tone = Tone.Secondary))
    }

    val flashlight = tool("flashlight", "Flashlight", "Turn the flashlight on or off.", Risk.Act,
        listOf("on"), { boolean("on", "true to turn on") }) { a ->
        val on = a.b("on") ?: !torchOn
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@tool ToolOutcome("This phone has no flashlight.")
        camera.setTorchMode(id, on)
        torchOn = on
        ToolOutcome("Flashlight ${if (on) "on" else "off"}.")
    }

    val volume = tool("set_volume", "Adjusting volume", "Set a volume level in percent.", Risk.Act,
        listOf("percent"), {
            integer("percent", "0-100")
            string("stream", "Which volume", listOf("media", "ring", "alarm", "notification"))
        }) { a ->
        val stream = when (a.s("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val pct = (a.n("percent") ?: 50.0).coerceIn(0.0, 100.0)
        val max = audio.getStreamMaxVolume(stream)
        runCatching { audio.setStreamVolume(stream, (max * pct / 100).roundToInt(), AudioManager.FLAG_SHOW_UI) }
            .fold({ ToolOutcome("${a.s("stream") ?: "media"} volume set to ${pct.roundToInt()}%.") },
                { ToolOutcome("Couldn't change that volume (Do Not Disturb may block it).") })
    }

    val media = tool("media_control", "Controlling playback", "Control whatever is playing.", Risk.Act,
        listOf("action"), { string("action", "Command", listOf("play_pause", "next", "previous")) }) { a ->
        val code = when (a.s("action")) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        ToolOutcome("Sent ${a.s("action") ?: "play_pause"}.")
    }

    val status = tool("device_status", "Checking the phone", "Battery, charging, network, next alarm, volume, time.") {
        ToolOutcome(statusText())
    }

    val webSearch = tool("open_browser_search", "Opening browser", "Open a search in the browser. ONLY when the user explicitly asks to open/browse it themselves — to answer questions, use web_search / search_news instead.", Risk.Handoff,
        listOf("query"), { string("query", "Search query") }) { a ->
        val q = a.s("query").orEmpty()
        val ok = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        ToolOutcome(if (ok) "Opened search results for '$q'." else "No browser available.")
    }

    val openUrl = tool("open_url", "Opening link", "Open a URL or deep link.", Risk.Handoff,
        listOf("url"), { string("url", "https://… or any deep link") }) { a ->
        ToolOutcome(if (start(Intent(Intent.ACTION_VIEW, Uri.parse(a.s("url"))))) "Opened." else "Nothing can open that link.")
    }

    val navigate = tool("navigate", "Starting navigation", "Start navigation or show a place on the map.", Risk.Handoff,
        listOf("destination"), { string("destination", "Place or address") }) { a ->
        val d = Uri.encode(a.s("destination").orEmpty())
        val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$d"))) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$d")))
        ToolOutcome(if (ok) "Navigation started." else "No maps app found.")
    }

    val findContact = tool("find_contact", "Looking up contacts", "Find phone numbers for a contact by name.",
        required = listOf("name"), params = { string("name", "Contact name, partial ok") }) { a ->
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@tool ToolOutcome(
                "Contacts permission is not granted. A button to grant it is now shown to the user; ask them to tap it and retry.",
                permissionCard("📇", "Let me see your contacts", "So I can call and text people by name.", Manifest.permission.READ_CONTACTS),
            )
        }
        val rows = withContext(Dispatchers.IO) { queryContacts(a.s("name").orEmpty()) }
        ToolOutcome(if (rows.isEmpty()) "No contact matches '${a.s("name")}'." else rows.joinToString("\n") { "${it.first}: ${it.second}" })
    }

    val dial = tool("call", "Calling", "Open the dialer with a number ready to call (user taps call).", Risk.Handoff,
        listOf("number"), { string("number", "Phone number") }) { a ->
        ToolOutcome(if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.s("number")))))) "Dialer opened." else "No dialer.")
    }

    val sms = tool("compose_message", "Writing a message", "Open a pre-filled SMS for the user to review and send.", Risk.Handoff,
        listOf("to", "text"), { string("to", "Phone number"); string("text", "Message body") }) { a ->
        val ok = start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.s("to")))).putExtra("sms_body", a.s("text")))
        ToolOutcome(if (ok) "Message drafted; the user will review and send it." else "No messaging app.")
    }

    val calendar = tool("add_event", "Adding to calendar", "Draft a calendar event for the user to confirm.", Risk.Handoff,
        listOf("title", "start"), {
            string("title", "Event title")
            string("start", "Local start time, ISO like 2026-11-21T18:30")
            string("end", "Local end time, optional")
            string("location", "Optional")
        }) { a ->
        fun millis(s: String?) = s?.let {
            runCatching { LocalDateTime.parse(it.take(16), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
        val begin = millis(a.s("start")) ?: return@tool ToolOutcome("Error: couldn't read start time; use 2026-11-21T18:30.")
        val end = millis(a.s("end")) ?: (begin + 3_600_000)
        val ok = start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.s("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .apply { a.s("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) } })
        ToolOutcome(if (ok) "Event drafted in the calendar app for the user to save." else "No calendar app.")
    }

    val settingsPanel = tool("open_settings", "Opening settings", "Open a quick settings panel or settings page.", Risk.Handoff,
        listOf("panel"), {
            string("panel", "Which", listOf("wifi", "internet", "bluetooth", "volume", "nfc", "display", "battery", "notifications", "dnd", "apps"))
        }) { a ->
        val action = when (a.s("panel")) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.Panel.ACTION_NFC
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "notifications" -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
            else -> Settings.ACTION_APPLICATION_SETTINGS
        }
        ToolOutcome(if (start(Intent(action))) "Opened ${a.s("panel")} settings." else "Couldn't open that.")
    }

    val notifications = tool("get_notifications", "Reading notifications", "Read the notifications currently in the shade.", untrusted = true) {
        if (!NotificationFeed.hasAccess(context)) {
            return@tool ToolOutcome(
                "No notification access yet. A card asking for it is shown to the user.",
                Surface(title = "Let me read your notifications", icon = "🔔", tone = Tone.Primary, blocks = listOf(
                    Block.Text("Then I can brief you, spot what matters, and keep the noise away."),
                    Block.Buttons(listOf(ButtonSpec("Grant access", Action.RequestPermission(PERMISSION_NOTIFICATIONS), ButtonStyle.Filled))),
                )),
            )
        }
        val items = NotificationFeed.items.value
        ToolOutcome(if (items.isEmpty()) "No notifications right now." else items.take(25).joinToString("\n") {
            "[${it.app}] ${it.title}: ${it.text.replace('\n', ' ')} (${minutesAgo(it.time)})"
        })
    }

    val remember = tool("remember", "Remembering", "Save a durable fact or preference about the user for future conversations.",
        Risk.Act, listOf("fact"), { string("fact", "Short fact, e.g. 'Prefers metric units'") }) { a ->
        settings.remember(a.s("fact").orEmpty())
        ToolOutcome("Saved to memory.")
    }

    val all: List<Tool> = listOf(
        openApp, findApps, timer, alarm, flashlight, volume, media, status, webSearch, openUrl, navigate,
        findContact, dial, sms, calendar, settingsPanel, notifications, remember,
    )

    // ---- Helpers -----------------------------------------------------------------------------------

    fun permissionCard(icon: String, title: String, why: String, permission: String) = Surface(
        title = title, icon = icon, tone = Tone.Primary, blocks = listOf(
            Block.Text(why),
            Block.Buttons(listOf(ButtonSpec("Allow", Action.RequestPermission(permission), ButtonStyle.Filled))),
        ),
    )

    private fun queryContacts(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 6) out += c.getString(0) to c.getString(1)
        }
        return out.distinct()
    }

    fun batteryPercent(): Int = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    fun isCharging(): Boolean = context.getSystemService(BatteryManager::class.java).isCharging

    fun nextAlarm(): String? = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime?.let {
        java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).let { z ->
            val today = java.time.LocalDate.now()
            val day = when (z.toLocalDate()) { today -> ""; today.plusDays(1) -> "tomorrow "; else -> z.format(DateTimeFormatter.ofPattern("EEE ")) }
            day + z.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    fun statusText(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "online"
        }
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return buildString {
            appendLine("Time: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm"))}")
            appendLine("Battery: ${batteryPercent()}%${if (isCharging()) " (charging)" else ""}")
            appendLine("Network: $net")
            appendLine("Media volume: $media%")
            appendLine("Ringer: ${when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }}")
            appendLine("Flashlight: ${if (torchOn) "on" else "off"}")
            append("Next alarm: ${nextAlarm() ?: "none"}")
        }
    }

    private fun minutesAgo(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
    }

    companion object {
        const val PERMISSION_NOTIFICATIONS = "morph.notification_access"
    }
}
