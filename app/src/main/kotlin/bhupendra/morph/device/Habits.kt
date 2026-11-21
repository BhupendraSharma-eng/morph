package bhupendra.morph.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * What you actually do, learned on-device so suggestions and search get more personal over time.
 *
 *  - **Apps:** when you open each app. With usage access this is the system's record (WhatsApp opened from a
 *    notification counts too), otherwise Morph's own launches. Scored by frecency (3-day half-life), weighted
 *    toward apps you tend to use at this hour.
 *  - **Tasks:** things you ask Morph more than once ("timer 25 min", "call Mom") become one-tap suggestions.
 *  - **Picks:** which result you chose for what you typed, so "wh" means WhatsApp if that's what you always pick.
 *
 * Nothing leaves the phone; it's a few small JSON blobs in shared preferences.
 */
class Habits(private val context: Context) {
    private val prefs = context.getSharedPreferences("habits", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)

    private val launches = load("launches")        // pkg -> [ms]
    private val tasks = load("tasks")              // text -> [ms]
    private val picks = load("picks")              // query -> {key: n}
    @Volatile private var system: Map<String, List<Long>>? = null   // pkg -> [ms] from UsageStats
    private var systemAt = 0L
    private var ranked: Pair<Long, Map<String, Double>>? = null

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Re-reads the system's app usage (at most every few minutes). Safe to call on every resume. */
    suspend fun refreshUsage(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) { system = null; return@withContext }
        val now = System.currentTimeMillis()
        if (!force && system != null && now - systemAt < 5 * 60_000) return@withContext
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val out = HashMap<String, MutableList<Long>>()
        runCatching {
            val events = usm.queryEvents(now - WINDOW_MS, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = e.packageName
                // Coming back to the home screen ends a visit; the shade or keyboard in between doesn't.
                if (pkg == context.packageName) { last = null; continue }
                if (pkg in IGNORED || pkg == last) continue   // moving between screens inside one app is one open
                out.getOrPut(pkg) { mutableListOf() }.add(e.timeStamp)
                last = pkg
            }
        }
        system = out; systemAt = now
        synchronized(this@Habits) { ranked = null }
    }

    @Synchronized fun recordLaunch(pkg: String) { stamp(launches, pkg, "launches", 60); ranked = null }

    /** You asked Morph for [text]; repeated asks become suggestions. */
    @Synchronized fun recordTask(text: String) {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length !in 3..60) return
        stamp(tasks, t, "tasks", 20, maxKeys = 120)
    }

    /** You typed [query] and chose the result [key]. */
    @Synchronized fun recordPick(query: String, key: String) {
        val q = normalize(query).take(16)
        if (q.isEmpty()) return
        val o = picks.optJSONObject(q) ?: JSONObject().also { picks.put(q, it) }
        o.put(key, o.optInt(key) + 1)
        trim(picks, 300)
        save("picks", picks)
    }

    /** 0..~4: how strongly what you typed has meant [key] before. Exact query counts most; related ones a little. */
    @Synchronized fun pickBoost(query: String, key: String): Double {
        val q = normalize(query)
        if (q.isEmpty()) return 0.0
        var exact = 0; var near = 0
        picks.keys().forEach { s ->
            val n = picks.getJSONObject(s).optInt(key)
            if (n == 0) return@forEach
            if (s == q) exact += n else if (s.startsWith(q) || q.startsWith(s)) near += n
        }
        return min(3.0, exact * 1.0) + min(1.0, near * 0.25)
    }

    /** How much you use [pkg] around now, relative to your most-used app (0..1). */
    fun appWeight(pkg: String): Double {
        val r = scores()
        val top = r.values.maxOrNull() ?: return 0.0
        return if (top <= 0) 0.0 else (r[pkg] ?: 0.0) / top
    }

    /** Packages ranked by how likely you are to want them now. */
    fun topApps(limit: Int): List<String> = scores().entries.sortedByDescending { it.value }.take(limit).map { it.key }

    /** Things you've asked for at least twice in the last month, most likely-now first. */
    @Synchronized fun topTasks(limit: Int): List<String> {
        val now = System.currentTimeMillis()
        val hour = hourOf(now)
        return tasks.keys().asSequence().mapNotNull { t ->
            val at = times(tasks.getJSONArray(t)).filter { now - it < 30 * DAY }
            if (at.size < 2) null else t to at.sumOf { decay(now - it) * timeOfDay(hourOf(it), hour) }
        }.sortedByDescending { it.second }.take(limit).map { it.first }.toList()
    }

    @Synchronized private fun scores(): Map<String, Double> {
        val now = System.currentTimeMillis()
        ranked?.let { (at, m) -> if (now - at < 60_000) return m }
        val hour = hourOf(now)
        val source: Map<String, List<Long>> = system ?: launches.keys().asSequence().associateWith { times(launches.getJSONArray(it)) }
        val m = HashMap<String, Double>()
        source.forEach { (pkg, at) ->
            m[pkg] = at.filter { now - it < WINDOW_MS }.sumOf { decay(now - it) * timeOfDay(hourOf(it), hour) }
        }
        // Before there's history, the old lifetime launch counts are a weak hint.
        legacy.all.forEach { (pkg, n) -> if (n is Int) m[pkg] = (m[pkg] ?: 0.0) + min(n, 50) * 0.02 }
        m.values.removeAll { it <= 0.0 }
        ranked = now to m
        return m
    }

    private fun decay(ageMs: Long) = 0.5.pow(ageMs.toDouble() / HALF_LIFE)

    /** Same hour ×2.5, an hour either side ×1.6, otherwise ×1. */
    private fun timeOfDay(h: Int, now: Int): Double {
        val d = abs(h - now).let { min(it, 24 - it) }
        return when (d) { 0 -> 2.5; 1 -> 1.6; else -> 1.0 }
    }

    private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

    private fun stamp(store: JSONObject, key: String, name: String, keep: Int, maxKeys: Int = 400) {
        val now = System.currentTimeMillis()
        val list = (times(store.optJSONArray(key) ?: JSONArray()) + now).filter { now - it < WINDOW_MS * 2 }.takeLast(keep)
        store.put(key, JSONArray(list))
        trim(store, maxKeys)
        save(name, store)
    }

    /** Drops the stalest keys once a store grows past [max]. */
    private fun trim(store: JSONObject, max: Int) {
        if (store.length() <= max) return
        val newest = { k: String -> store.optJSONArray(k)?.let { if (it.length() > 0) it.getLong(it.length() - 1) else 0L } ?: 0L }
        store.keys().asSequence().toList().sortedBy(newest).take(store.length() - max).forEach(store::remove)
    }

    private fun times(a: JSONArray) = List(a.length()) { a.getLong(it) }
    private fun load(name: String) = runCatching { JSONObject(prefs.getString(name, "{}")!!) }.getOrDefault(JSONObject())
    private fun save(name: String, o: JSONObject) = prefs.edit().putString(name, o.toString()).apply()

    companion object {
        private const val DAY = 86_400_000L
        private const val WINDOW_MS = 21 * DAY
        private const val HALF_LIFE = 3.0 * DAY
        /** Not things you "open": the shade, other launchers, call plumbing, permission dialogs, the keyboard. */
        private val IGNORED = setOf(
            "com.android.systemui", "android", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.apps.nexuslauncher", "com.motorola.launcher3", "com.android.launcher3",
            "com.android.server.telecom", "com.google.android.inputmethod.latin",
        )
    }
}
