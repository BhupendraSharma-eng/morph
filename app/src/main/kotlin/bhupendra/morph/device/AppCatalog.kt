package bhupendra.morph.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val user: UserHandle,
    internal val info: LauncherActivityInfo,
) {
    internal val norm = normalize(label)
    internal val words = norm.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
    val key: String get() = "${component.flattenToShortString()}#${user.hashCode()}"
}

/** Icon split into adaptive layers so the UI can clip it into any Material shape. */
/**
 * Fastest-path-first bookkeeping: operator mode (which takes over the phone) is the last resort, so before it
 * runs we check whether the agent looked at the faster, app-provided routes. See OperatorTool.
 */
object ToolTiers {
    @Volatile private var lookedAt = 0L
    @Volatile private var bounced: Pair<String, Long>? = null
    private const val WINDOW_MS = 3 * 60_000L

    fun lookedAtAppActions() { lookedAt = android.os.SystemClock.uptimeMillis() }
    fun recentlyLooked() = android.os.SystemClock.uptimeMillis() - lookedAt < WINDOW_MS

    /** True once per task (within a few minutes): the first operator call for it may be sent back with options. */
    fun shouldBounce(task: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val b = bounced
        if (b != null && b.first == task.trim().lowercase() && now - b.second < WINDOW_MS) return false
        bounced = task.trim().lowercase() to now
        return true
    }
}

/** An app icon pre-masked to its Material shape. [tintable] = a monochrome glyph meant to be tinted (themed icons). */
class AppIcon(val image: ImageBitmap, val tintable: Boolean)

/**
 * Where the last app tile tap happened, so launches can zoom out of the icon. Set by AppTile right before its
 * onClick; read once by [AppCatalog.launch] (stale after a second — e.g. launches the agent makes later).
 */
object LaunchOrigin {
    private var view: android.view.View? = null
    private var rect: android.graphics.Rect? = null
    private var at = 0L
    fun set(v: android.view.View, r: android.graphics.Rect) { view = v; rect = r; at = android.os.SystemClock.uptimeMillis() }
    fun take(): Pair<android.view.View, android.graphics.Rect>? {
        val v = view; val r = rect
        view = null; rect = null
        return if (v != null && r != null && android.os.SystemClock.uptimeMillis() - at < 1000) v to r else null
    }
}

internal fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

/** An app shortcut ("New chat", "Scan QR"…) — searchable and launchable like an app. */
data class ShortcutEntry(val label: String, val app: AppEntry, internal val info: ShortcutInfo) {
    internal val norm = normalize(label)
    val key: String get() = "${app.key}/${info.id}"
}

class AppCatalog(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)
    private val hiddenPrefs = context.getSharedPreferences("hidden_apps", Context.MODE_PRIVATE)
    /** Your usage patterns: drive search order and the home screen's app suggestions. */
    val habits = Habits(context)
    private val icons = LruCache<String, AppIcon>(256)
    private val masks = LruCache<String, ImageBitmap>(32)
    private val shortcutIcons = LruCache<String, ImageBitmap>(128)

    private var everything: List<AppEntry> = emptyList()
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Visible apps (hidden ones excluded). */
    val apps: StateFlow<List<AppEntry>> = _apps
    private val _hidden = MutableStateFlow<List<AppEntry>>(emptyList())
    val hidden: StateFlow<List<AppEntry>> = _hidden
    private val _shortcuts = MutableStateFlow<List<ShortcutEntry>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutEntry>> = _shortcuts

    fun isWork(e: AppEntry) = e.user != Process.myUserHandle()
    private fun isHidden(e: AppEntry) = hiddenPrefs.getBoolean(e.key, false)

    fun setHidden(e: AppEntry, hide: Boolean) {
        hiddenPrefs.edit { if (hide) putBoolean(e.key, true) else remove(e.key) }
        publish()
    }

    private fun publish() {
        _apps.value = everything.filterNot(::isHidden)
        _hidden.value = everything.filter(::isHidden)
    }

    init {
        refresh()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = refresh()
            override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
            override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
            override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            // The system can drop this call mid-transfer (busy right after an install/update, binder buffer full):
            // keep the apps we already have and try again shortly instead of crashing the home screen.
            repeat(4) { attempt ->
                val list = runCatching {
                    launcherApps.profiles.flatMap { user ->
                        launcherApps.getActivityList(null, user).map {
                            AppEntry(it.label.toString(), it.applicationInfo.packageName, it.componentName, user, it)
                        }
                    }.filter { it.pkg != context.packageName }.sortedBy { it.norm }
                }.onFailure { android.util.Log.w("MorphApps", "app list failed (try ${attempt + 1}): ${it.message}") }.getOrNull()
                if (list != null) {
                    everything = list
                    publish()
                    _shortcuts.value = runCatching { loadShortcuts(list) }.getOrDefault(_shortcuts.value)
                    return@launch
                }
                kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
        }
    }

    /** Only the default home app may read other apps' shortcuts. */
    val canUseShortcuts: Boolean get() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    private fun loadShortcuts(apps: List<AppEntry>): List<ShortcutEntry> {
        if (!canUseShortcuts) return emptyList()
        val byPkg = apps.groupBy { it.pkg to it.user }
        return launcherApps.profiles.flatMap { user ->
            val q = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            runCatching { launcherApps.getShortcuts(q, user) }.getOrNull().orEmpty().mapNotNull { s ->
                val app = byPkg[s.`package` to user]?.firstOrNull() ?: return@mapNotNull null
                if (!s.isEnabled) return@mapNotNull null
                ShortcutEntry((s.shortLabel ?: s.longLabel ?: return@mapNotNull null).toString(), app, s)
            }
        }.filterNot { isHidden(it.app) }
    }

    fun shortcutsFor(e: AppEntry): List<ShortcutEntry> =
        _shortcuts.value.filter { it.app.key == e.key }.sortedBy { it.info.rank }.take(5)

    fun searchShortcuts(query: String, limit: Int = 4): List<ShortcutEntry> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        return _shortcuts.value.filter { it.norm.contains(q) || (normalize(it.app.label).startsWith(q) && q.length >= 3) }
            .sortedWith(compareByDescending<ShortcutEntry> { it.norm.startsWith(q) }.thenByDescending { launches(it.app) })
            .take(limit)
    }

    /**
     * App actions relevant to a free-text goal ("send Mom the photos on WhatsApp"): shortcuts of apps the goal names,
     * ranked by how many of the goal's words appear in the shortcut's label. Used by find_app_actions and to check
     * for a faster route before operator mode.
     */
    fun shortcutsForGoal(goal: String, limit: Int = 8): List<ShortcutEntry> {
        val g = normalize(goal)
        val words = g.split(' ').filter { it.length >= 3 }.toSet()
        if (words.isEmpty()) return emptyList()
        val namedApps = _apps.value.filter { a -> normalize(a.label).let { it.length >= 3 && g.contains(it) } }.map { it.key }.toSet()
        return _shortcuts.value.mapNotNull { s ->
            val labelWords = s.norm.split(' ').filter { it.length >= 3 }
            val hits = labelWords.count { w -> words.any { it == w || (w.length >= 4 && it.startsWith(w)) || (it.length >= 4 && w.startsWith(it)) } }
            val score = hits * 2 + (if (s.app.key in namedApps) 1 else 0)
            if (hits == 0 && s.app.key !in namedApps) null else s to score
        }.sortedWith(compareByDescending<Pair<ShortcutEntry, Int>> { it.second }.thenByDescending { launches(it.first.app) })
            .take(limit).map { it.first }
    }

    fun shortcutByKey(key: String): ShortcutEntry? = _shortcuts.value.firstOrNull { it.key == key }

    fun launchShortcut(s: ShortcutEntry): Boolean = runCatching {
        launcherApps.startShortcut(s.info, null, null)
        prefs.edit { putInt(s.app.pkg, launches(s.app) + 1) }
        true
    }.getOrDefault(false)

    /** Call off the main thread. */
    fun loadShortcutIcon(s: ShortcutEntry, sizePx: Int): ImageBitmap? {
        shortcutIcons.get(s.key)?.let { return it }
        val d = runCatching { launcherApps.getShortcutIconDrawable(s.info, context.resources.displayMetrics.densityDpi) }.getOrNull()
            ?: return null
        return d.toBitmap(sizePx, sizePx).asImageBitmap().also { shortcutIcons.put(s.key, it) }
    }

    fun uninstall(e: AppEntry) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun launches(e: AppEntry) = prefs.getInt(e.pkg, 0)

    /**
     * Fuzzy search: prefix > word-prefix > acronym > substring > subsequence. Within a tier, apps you use more (around
     * this time of day) come first; and what you picked for this exact query before can jump a tier, so it learns.
     */
    fun search(query: String, limit: Int = 12): List<AppEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return _apps.value.mapNotNull { e -> score(e, q)?.let { e to it + habits.pickBoost(q, hitKey(e)) } }
            .sortedWith(compareByDescending<Pair<AppEntry, Double>> { it.second }.thenByDescending { launches(it.first) })
            .take(limit).map { it.first }
    }

    /** The key [Habits.recordPick] remembers an app result under. */
    fun hitKey(e: AppEntry) = "a:" + e.key

    private fun score(e: AppEntry, q: String): Double? {
        val n = e.norm
        val boost = habits.appWeight(e.pkg) * 1.5
        return when {
            n == q -> 10.0
            n.startsWith(q) -> 8.0 + boost
            e.words.any { it.startsWith(q) } -> 6.0 + boost
            q.length >= 2 && e.words.joinToString("") { it.take(1) }.startsWith(q) -> 5.0 + boost
            n.contains(q) -> 4.0 + boost
            q.length >= 3 && isSubsequence(q, n.replace(" ", "")) -> 2.0 + boost
            else -> null
        }
    }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }

    /** Resolves "spotify", "com.spotify.music" or "Spotify Music" to an installed app. */
    fun resolve(nameOrPackage: String): AppEntry? {
        val apps = _apps.value
        return apps.firstOrNull { it.pkg == nameOrPackage }
            ?: apps.firstOrNull { it.norm == normalize(nameOrPackage) }
            ?: search(nameOrPackage, 1).firstOrNull()
    }

    private val audioApps = HashMap<String, Boolean>()

    /** Music/podcast apps (by the category they declare), cached per package. */
    fun isAudioApp(pkg: String): Boolean = synchronized(audioApps) {
        audioApps.getOrPut(pkg) {
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO }
                .getOrDefault(false)
        }
    }

    /** Your most-used music app, if any. */
    fun favouriteAudioApp(): AppEntry? {
        val byPkg = _apps.value.associateBy { it.pkg }
        return habits.topApps(40).firstNotNullOfOrNull { p -> byPkg[p]?.takeIf { isAudioApp(p) } }
            ?: _apps.value.firstOrNull { isAudioApp(it.pkg) }
    }

    /** The apps you're most likely to want right now, best first. */
    fun frequent(limit: Int): List<AppEntry> {
        val byPkg = _apps.value.filterNot(::isWork).associateBy { it.pkg }
        return habits.topApps(limit * 2).mapNotNull { byPkg[it] }.take(limit)
    }

    fun launch(e: AppEntry): Boolean = runCatching {
        // Tapped from a tile: the app grows out of its icon instead of the generic open transition.
        val origin = LaunchOrigin.take()
        val opts = origin?.let { (v, r) ->
            android.app.ActivityOptions.makeScaleUpAnimation(v, r.left, r.top, r.width(), r.height()).toBundle()
        }
        launcherApps.startMainActivity(e.component, e.user, origin?.second, opts)
        prefs.edit { putInt(e.pkg, launches(e) + 1) }
        habits.recordLaunch(e.pkg)
        true
    }.getOrDefault(false)

    fun openAppInfo(e: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun cachedIcon(e: AppEntry, themed: Boolean): AppIcon? = icons.get(iconKey(e, themed))
    private fun iconKey(e: AppEntry, themed: Boolean) = if (themed) "${e.key}#t" else e.key

    /** App icons are plain circles (the expressive shapes stay on the orb, avatars and the like). */
    private fun shapePath(@Suppress("UNUSED_PARAMETER") e: AppEntry, px: Int): android.graphics.Path =
        android.graphics.Path().apply { addOval(0f, 0f, px.toFloat(), px.toFloat(), android.graphics.Path.Direction.CW) }

    /** Solid shape mask (tinted with the tile colour at draw time). Cheap: one per shape and size. */
    fun shapeMask(e: AppEntry, px: Int): ImageBitmap {
        val key = "${shapeIndex(e)}@$px"
        masks.get(key)?.let { return it }
        val bmp = createBitmap(px, px)
        Canvas(bmp).drawPath(shapePath(e, px), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
        return bmp.asImageBitmap().also { masks.put(key, it) }
    }
    @Suppress("UNUSED_PARAMETER") private fun shapeIndex(e: AppEntry) = 0   // one shape for every icon: a circle

    /**
     * Call off the main thread. Renders the icon already cut to its expressive shape, so tiles draw a plain bitmap —
     * no per-frame path clipping while the drawer slides. Themed: the monochrome layer only (tinted at draw time).
     */
    fun loadIcon(e: AppEntry, sizePx: Int, themed: Boolean): AppIcon {
        val key = iconKey(e, themed)
        icons.get(key)?.let { return it }
        val d = e.info.getIcon(context.resources.displayMetrics.densityDpi)
        fun layers(vararg ds: android.graphics.drawable.Drawable?): Bitmap {
            val bmp = createBitmap(sizePx, sizePx)
            val c = Canvas(bmp)
            // Adaptive layers are drawn full-bleed (72 of 108 dp visible), then cut to the shape.
            val inset = (sizePx * 0.25f).toInt()
            ds.forEach { it?.apply { setBounds(-inset, -inset, sizePx + inset, sizePx + inset); draw(c) } }
            return bmp
        }
        val mono = (d as? AdaptiveIconDrawable)?.monochrome
        val icon = when {
            themed && mono != null -> AppIcon(masked(layers(mono), e, sizePx).asImageBitmap(), tintable = true)
            d is AdaptiveIconDrawable -> AppIcon(masked(layers(d.background, d.foreground), e, sizePx).asImageBitmap(), tintable = false)
            else -> AppIcon(masked(d.toBitmap(sizePx, sizePx), e, sizePx).asImageBitmap(), tintable = false)
        }
        icons.put(key, icon)
        return icon
    }

    private fun masked(src: Bitmap, e: AppEntry, px: Int): Bitmap {
        val out = createBitmap(px, px)
        val c = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawPath(shapePath(e, px), paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, px, px), paint)
        if (src != out) src.recycle()
        return out
    }
}
