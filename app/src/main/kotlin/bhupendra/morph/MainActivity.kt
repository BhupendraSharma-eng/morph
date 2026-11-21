package bhupendra.morph

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bhupendra.morph.data.Onboarding
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.ui.ActivitySheet
import bhupendra.morph.ui.Demo
import bhupendra.morph.ui.AppMenuSheet
import bhupendra.morph.ui.AppsSheet
import bhupendra.morph.ui.LocalAppMenu
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.ui.HomeScreen
import bhupendra.morph.ui.HomeViewModel
import bhupendra.morph.ui.MorphTheme
import bhupendra.morph.ui.SettingsSheet
import bhupendra.morph.ui.UiEvent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()
    private var showApps by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var showActivity by mutableStateOf(false)

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.canvas += CanvasItem.Notice("No worries — you can allow it later in settings.", error = false)
        vm.refreshGlance()
        vm.graph.onboarding.refresh()
    }
    private val askRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.graph.onboarding.refresh() }

    /** Home follows the wallpaper, not the system theme: light text and icons on dark wallpapers, and vice versa. */
    private var wallpaperDark by mutableStateOf(true)
    private val wallpaperListener = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) applyWallpaperTone()
    }

    private fun applyWallpaperTone() {
        val colors = runCatching {
            android.app.WallpaperManager.getInstance(this).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        wallpaperDark = colors?.let { (it.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0 }
            ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val bars = if (wallpaperDark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyWallpaperTone()
        super.onCreate(savedInstanceState)
        runCatching {
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, android.os.Handler(mainLooper))
        }

        setContent {
            MorphTheme(dark = wallpaperDark) {
              androidx.compose.runtime.CompositionLocalProvider(LocalAppMenu provides { vm.appMenu = it }) {
                HomeScreen(
                    vm = vm,
                    hasMic = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    onOpenApps = { showApps = true },
                    onOpenSettings = { showSettings = true },
                )
                if (showApps) AppsSheet(vm, onDismiss = { showApps = false })
                if (showSettings) SettingsSheet(
                    vm,
                    onDismiss = { showSettings = false },
                    onGrantNotifications = ::openNotificationAccess,
                    onRequestPermission = { askPermission.launch(it) },
                    onMakeDefault = ::requestHomeRole,
                    onOpenActivity = { showSettings = false; showActivity = true },
                )
                if (showActivity) ActivitySheet(vm, onDismiss = { showActivity = false })
                vm.appMenu?.let { AppMenuSheet(it, vm, onDismiss = { vm.appMenu = null }) }
                androidx.compose.runtime.LaunchedEffect(Demo.sheet) {
                    when (Demo.sheet) {
                        "activity" -> { showSettings = false; showActivity = true }
                        "settings" -> { showActivity = false; showSettings = true }
                        null -> { showActivity = false; showSettings = false }
                    }
                }
              }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.events.collect { e ->
                    when (e) {
                        is UiEvent.RequestPermission -> when (e.permission) {
                            DeviceTools.PERMISSION_NOTIFICATIONS -> openNotificationAccess()
                            Onboarding.HOME_ROLE -> requestHomeRole()
                            bhupendra.morph.operator.OperatorTool.ENABLE ->
                                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            else -> askPermission.launch(e.permission)
                        }
                        is UiEvent.StartActivity -> runCatching { startActivity(e.intent) }
                        UiEvent.OpenSettings -> showSettings = true
                        UiEvent.OpenApps -> showApps = true
                        UiEvent.OpenActivity -> { showSettings = false; showActivity = true }
                        UiEvent.NeedMicPermission -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshGlance()
        vm.graph.catalog.refresh()
        vm.graph.onboarding.refresh()   // e.g. returning from the notification-access screen
    }

    /** Pressing Home while already home resets to a calm state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            showApps = false
            showSettings = false
            showActivity = false
            vm.appMenu = null
            vm.onHome()
        }
    }

    private fun openNotificationAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            askRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            vm.canvas += CanvasItem.Notice("Morph is already your home screen ✨", error = false)
        }
    }
}
