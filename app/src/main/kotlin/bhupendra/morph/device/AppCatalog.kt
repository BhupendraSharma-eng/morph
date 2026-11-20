package bhupendra.morph.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
class AppIcon(val full: ImageBitmap, val monochrome: ImageBitmap?)

internal fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

class AppCatalog(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)
    private val icons = LruCache<String, AppIcon>(256)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    init {
        refresh()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
            override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
            override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val list = launcherApps.profiles.flatMap { user ->
                launcherApps.getActivityList(null, user).map {
                    AppEntry(it.label.toString(), it.applicationInfo.packageName, it.componentName, user, it)
                }
            }.filter { it.pkg != context.packageName }
                .sortedBy { it.norm }
            _apps.value = list
        }
    }

    private fun launches(e: AppEntry) = prefs.getInt(e.pkg, 0)

    /** Fuzzy search: prefix > word-prefix > acronym > substring > subsequence, tie-broken by how often you open it. */
    fun search(query: String, limit: Int = 12): List<AppEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return _apps.value.mapNotNull { e -> score(e, q)?.let { e to it } }
            .sortedWith(compareByDescending<Pair<AppEntry, Double>> { it.second }.thenByDescending { launches(it.first) })
            .take(limit).map { it.first }
    }

    private fun score(e: AppEntry, q: String): Double? {
        val n = e.norm
        val boost = minOf(launches(e), 50) / 100.0
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

    fun frequent(limit: Int): List<AppEntry> =
        _apps.value.filter { launches(it) > 0 }.sortedByDescending(::launches).take(limit)

    fun launch(e: AppEntry): Boolean = runCatching {
        launcherApps.startMainActivity(e.component, e.user, null, null)
        prefs.edit { putInt(e.pkg, launches(e) + 1) }
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

    fun cachedIcon(e: AppEntry): AppIcon? = icons.get(e.key)

    /** Call off the main thread. */
    fun loadIcon(e: AppEntry, sizePx: Int): AppIcon {
        icons.get(e.key)?.let { return it }
        val d = e.info.getIcon(context.resources.displayMetrics.densityDpi)
        val icon = if (d is AdaptiveIconDrawable) {
            // Draw the raw layers full-bleed; the UI clips them to an expressive shape.
            fun layers(vararg ds: android.graphics.drawable.Drawable?): Bitmap {
                val bmp = createBitmap(sizePx, sizePx)
                val c = Canvas(bmp)
                val inset = (sizePx * 0.25f).toInt()
                ds.forEach { it?.apply { setBounds(-inset, -inset, sizePx + inset, sizePx + inset); draw(c) } }
                return bmp
            }
            AppIcon(
                full = layers(d.background, d.foreground).asImageBitmap(),
                monochrome = d.monochrome?.let { layers(it).asImageBitmap() },
            )
        } else {
            AppIcon(d.toBitmap(sizePx, sizePx).asImageBitmap(), null)
        }
        icons.put(e.key, icon)
        return icon
    }
}
