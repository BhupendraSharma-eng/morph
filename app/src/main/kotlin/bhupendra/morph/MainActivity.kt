package bhupendra.morph

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bhupendra.morph.data.Onboarding
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.assist.AssistBridge
import bhupendra.morph.ui.ActivitySheet
import bhupendra.morph.ui.Demo
import bhupendra.morph.ui.AppMenuSheet
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
        Startup.mark("activity onCreate")
        applyWallpaperTone()
        Startup.mark("wallpaper tone")
        super.onCreate(savedInstanceState)
        window.decorView.post { Startup.mark("first frame posted") }
        runCatching {
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, android.os.Handler(mainLooper))
        }

        setContent {
            MorphTheme(dark = wallpaperDark) {
              androidx.compose.runtime.CompositionLocalProvider(LocalAppMenu provides { vm.appMenu = it }) {
                HomeScreen(
                    onAddWidget = ::addWidget,
                    vm = vm,
                    hasMic = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    onOpenApps = { vm.drawer.open() },
                    onOpenSettings = { showSettings = true },
                )
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

        handleResume(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.events.collect { e ->
                    when (e) {
                        is UiEvent.RequestPermission -> when (e.permission) {
                            DeviceTools.PERMISSION_NOTIFICATIONS -> openNotificationAccess()
                            DeviceTools.PERMISSION_USAGE -> runCatching {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                            }.recoverCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                            Onboarding.HOME_ROLE -> requestHomeRole()
                            bhupendra.morph.operator.OperatorTool.ENABLE ->
                                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            else -> askPermission.launch(e.permission)
                        }
                        is UiEvent.StartActivity -> runCatching { startActivity(e.intent) }
                        UiEvent.OpenSettings -> showSettings = true
                        UiEvent.OpenApps -> vm.drawer.open()
                        UiEvent.OpenActivity -> { showSettings = false; showActivity = true }
                        UiEvent.NeedMicPermission -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    // ---- Widgets (Spaces) -----------------------------------------------------------------------------

    private var pendingWidgetId = -1
    private val bindWidget = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) configureOrAdd(pendingWidgetId) else cancelPendingWidget()
    }

    /** Add a widget: bind it (Android asks once for permission), run its setup screen if it has one, then show it. */
    private fun addWidget(info: android.appwidget.AppWidgetProviderInfo) {
        val spaces = vm.graph.spaces
        val id = spaces.host.allocateAppWidgetId()
        pendingWidgetId = id
        if (spaces.widgets.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) configureOrAdd(id)
        else bindWidget.launch(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile))
    }

    private fun configureOrAdd(id: Int) {
        val info = vm.graph.spaces.widgets.getAppWidgetInfo(id) ?: return cancelPendingWidget()
        val needsSetup = info.configure != null &&
            (info.widgetFeatures and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0
        if (!needsSetup) return widgetReady(id)
        runCatching { vm.graph.spaces.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_WIDGET_SETUP, null) }
            .onFailure { widgetReady(id) }
    }

    @Deprecated("AppWidgetHost's configure flow reports here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_WIDGET_SETUP) { if (resultCode == RESULT_OK) widgetReady(pendingWidgetId) else cancelPendingWidget(); return }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun widgetReady(id: Int) { vm.graph.spaces.addWidget(id); pendingWidgetId = -1 }
    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) runCatching { vm.graph.spaces.host.deleteAppWidgetId(pendingWidgetId) }
        pendingWidgetId = -1
    }

    override fun onStart() {
        super.onStart()
        runCatching { vm.graph.spaces.host.startListening() }   // widgets update while you can see them
    }

    override fun onStop() {
        runCatching { vm.graph.spaces.host.stopListening() }
        super.onStop()
    }

    override fun onPause() {
        AssistBridge.homeInFront = false
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AssistBridge.homeInFront = true
        AssistBridge.listenOnHome = {
            vm.drawer.close()
            vm.startListening(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        vm.refreshHabits()   // also redraws the glance
        vm.graph.media.start()   // live media sessions (needs notification access)
        vm.graph.catalog.refresh()
        vm.graph.onboarding.refresh()   // e.g. returning from the notification-access screen
        vm.graph.scope.launch { vm.graph.spaces.checkDue() }   // watches that came due while you were away
        vm.graph.forYou.dropStale()
        vm.graph.later.dropExpired()
        vm.graph.later.scheduleReminder()
    }

    /** Opened from a "finished / needs your answer" notification: go straight into that conversation. */
    private fun handleResume(intent: Intent?) {
        if (intent?.getStringExtra(bhupendra.morph.agent.TaskNotifier.EXTRA_PAGE) == bhupendra.morph.agent.TaskNotifier.PAGE_SPACES) {
            intent.removeExtra(bhupendra.morph.agent.TaskNotifier.EXTRA_PAGE)
            vm.showSpaces()
        }
        val id = intent?.getStringExtra(bhupendra.morph.agent.TaskNotifier.EXTRA_RESUME) ?: return
        intent.removeExtra(bhupendra.morph.agent.TaskNotifier.EXTRA_RESUME)
        vm.resumeConversation(id)
    }

    /** Pressing Home while already home resets to a calm state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResume(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.drawer.close()
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

private const val REQUEST_WIDGET_SETUP = 0x5AC
