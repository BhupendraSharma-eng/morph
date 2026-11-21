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

    private var accessAt = 0L
    private var access = false

    /** Cached for 15 s: the glance asks on every redraw, and it's a binder call. */
    fun hasUsageAccess(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - accessAt < 15_000 && accessAt != 0L) return access
        access = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        accessAt = now
        return access
    }

    // ---- Suggestion chips: learn which ones you use, retire the ones you don't -----------------------------
    private val chips = load("chips")              // key -> {"shown": [epochDay…], "taps": [ms…]}
    private val alarms = load("alarms")            // "HH:MM" -> [ms]

    /** A chip was on screen today (counted once per day). */
    @Synchronized fun chipShown(key: String) {
        val day = System.currentTimeMillis() / DAY
        val o = chips.optJSONObject(key) ?: JSONObject().also { chips.put(key, it) }
        val days = o.optJSONArray("shown") ?: JSONArray().also { o.put("shown", it) }
        if (days.length() > 0 && days.getLong(days.length() - 1) == day) return
        o.put("shown", JSONArray(times(days).plus(day).takeLast(14)))
        save("chips", chips)
    }

    @Synchronized fun chipTapped(key: String) {
        val o = chips.optJSONObject(key) ?: JSONObject().also { chips.put(key, it) }
        o.put("taps", JSONArray((times(o.optJSONArray("taps") ?: JSONArray()) + System.currentTimeMillis()).takeLast(20)))
        save("chips", chips)
    }

    /**
     * 0.25..1.5: how much a chip has earned its place. Shown on 4+ of the last 14 days and never tapped → 0.25
     * (it sinks below the useful ones, and drops off once 4+ better ones exist). Tapped recently → up to 1.5.
     */
    @Synchronized fun chipWeight(key: String): Double {
        val o = chips.optJSONObject(key) ?: return 1.0
        val now = System.currentTimeMillis()
        val day = now / DAY
        val shown = times(o.optJSONArray("shown") ?: JSONArray()).count { day - it < 14 }
        val taps = times(o.optJSONArray("taps") ?: JSONArray()).count { now - it < 14 * DAY }
        return when {
            taps > 0 -> min(1.5, 1.0 + taps * 0.1)
            shown >= 4 -> 0.25
            else -> 1.0
        }
    }

    @Synchronized fun recordAlarm(hour: Int, minute: Int) = stamp(alarms, "%02d:%02d".format(hour, minute), "alarms", 20, maxKeys = 40)

    /** The wake-up time you set most in the last month (null until you've set one). */
    @Synchronized fun usualAlarm(): Pair<Int, Int>? {
        val now = System.currentTimeMillis()
        return alarms.keys().asSequence().map { k -> k to times(alarms.getJSONArray(k)).count { now - it < 30 * DAY } }
            .filter { it.second > 0 }.maxByOrNull { it.second }
            ?.first?.split(":")?.let { (h, m) -> h.toInt() to m.toInt() }
    }

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

    /** One app's use over a period, from the system (the same data Digital Wellbeing shows). */
    data class AppUsage(val pkg: String, val foregroundMs: Long, val opens: Int, val peakHour: Int?)

    /**
     * Screen time per app over the last [days] days (1 = since midnight): foreground time from UsageStats, opens and
     * the hour you open it most from the event log. Null without usage access.
     */
    suspend fun usage(days: Int): Pair<Long, List<AppUsage>>? = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext null
        val now = System.currentTimeMillis()
        val start = if (days <= 1) java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            else now - days * DAY
        val usm = context.getSystemService(UsageStatsManager::class.java)
        // Foreground time from resumed/paused pairs (aggregated stats bucket by whole days, which over-counts "today").
        val fg = HashMap<String, Long>(); val opens = HashMap<String, Int>(); val hours = HashMap<String, IntArray>()
        val resumedAt = HashMap<String, Long>()
        var last: String? = null
        runCatching {
            val ev = usm.queryEvents(start, now); val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                val pkg = e.packageName
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resumedAt.putIfAbsent(pkg, e.timeStamp)
                        if (pkg == context.packageName) { last = null; continue }
                        if (pkg in IGNORED || pkg == last) continue
                        opens[pkg] = (opens[pkg] ?: 0) + 1
                        hours.getOrPut(pkg) { IntArray(24) }[hourOf(e.timeStamp)]++
                        last = pkg
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        resumedAt.remove(pkg)?.let { fg[pkg] = (fg[pkg] ?: 0L) + (e.timeStamp - it) }
                }
            }
            resumedAt.forEach { (pkg, t) -> fg[pkg] = (fg[pkg] ?: 0L) + (now - t) }   // still open
        }
        val apps = (fg.keys + opens.keys).filter { it != context.packageName && it !in IGNORED }.map { pkg ->
            AppUsage(pkg, fg[pkg] ?: 0L, opens[pkg] ?: 0, hours[pkg]?.let { h -> h.indices.maxByOrNull { h[it] }?.takeIf { h[it] > 0 } })
        }.filter { it.foregroundMs > 30_000 || it.opens > 0 }.sortedByDescending { it.foregroundMs }
        apps.sumOf { it.foregroundMs } to apps
    }

    @Synchronized fun recordLaunch(pkg: String) { stamp(launches, pkg, "launches", 60); ranked = null }

    /** You asked Morph for [text]; repeated asks become suggestions. */
    @Synchronized fun recordTask(text: String) {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length !in 3..60) return
        stamp(tasks, t, "tasks", 20, maxKeys = 120)
        tasksCache = null
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
    private var tasksCache: Pair<Long, List<String>>? = null

    @Synchronized fun topTasks(limit: Int): List<String> {
        val now = System.currentTimeMillis()
        tasksCache?.let { (at, l) -> if (now - at < 60_000 && limit <= 5) return l.take(limit) }
        return computeTopTasks(maxOf(limit, 5), now).also { tasksCache = now to it }.take(limit)
    }

    private fun computeTopTasks(limit: Int, now: Long): List<String> {
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
