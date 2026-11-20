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
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.ui.AppsSheet
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

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.canvas += CanvasItem.Notice("No worries — you can allow it later in settings.", error = false)
        vm.refreshGlance()
    }
    private val askRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MorphTheme {
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
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.events.collect { e ->
                    when (e) {
                        is UiEvent.RequestPermission ->
                            if (e.permission == DeviceTools.PERMISSION_NOTIFICATIONS) openNotificationAccess() else askPermission.launch(e.permission)
                        is UiEvent.StartActivity -> runCatching { startActivity(e.intent) }
                        UiEvent.OpenSettings -> showSettings = true
                        UiEvent.OpenApps -> showApps = true
                        UiEvent.NeedMicPermission -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshGlance()
        vm.graph.catalog.refresh()
    }

    /** Pressing Home while already home resets to a calm state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            showApps = false
            showSettings = false
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
