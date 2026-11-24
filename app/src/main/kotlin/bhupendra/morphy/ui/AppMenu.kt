package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bhupendra.morphy.device.AppEntry
import bhupendra.morphy.device.Hit

/** Long-press on any app: its shortcuts, plus info / hide / uninstall. */
@Composable
fun AppMenuSheet(app: AppEntry, vm: HomeViewModel, onDismiss: () -> Unit) {
    val catalog = vm.graph.catalog
    val settings by vm.graph.settings.state.collectAsState()
    val all by catalog.shortcuts.collectAsState()
    val shortcuts = remember(all, app.key) { catalog.shortcutsFor(app) }
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppTile(app, catalog, settings.themedIcons, onClick = { catalog.launch(app); onDismiss() }, showLabel = false, iconSize = 64.dp)
                Column {
                    Text(app.label, style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(if (catalog.isWork(app)) "Work profile · ${app.pkg}" else app.pkg,
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }

            if (shortcuts.isNotEmpty()) Column {
                shortcuts.forEach { s ->
                    val hit = Hit.Shortcut(s)
                    Row(
                        Modifier.fillMaxWidth().clickable { catalog.launchShortcut(s); onDismiss() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ShortcutIcon(hit, vm)
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (!catalog.canUseShortcuts) {
                Text("Make Morphy your default home to see this app's shortcuts here.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuButton("Info", Icons.Rounded.Info, Modifier.weight(1f)) { catalog.openAppInfo(app); onDismiss() }
                MenuButton("Hide", Icons.Rounded.VisibilityOff, Modifier.weight(1f)) { catalog.setHidden(app, true); onDismiss() }
                MenuButton("Uninstall", Icons.Rounded.Delete, Modifier.weight(1f)) { catalog.uninstall(app); onDismiss() }
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.filledTonalButtonColors(), modifier = modifier) {
        Icon(icon, null, Modifier.padding(end = 6.dp)); Text(label)
    }
}
