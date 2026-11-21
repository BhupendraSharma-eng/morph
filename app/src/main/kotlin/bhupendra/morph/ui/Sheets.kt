package bhupendra.morph.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bhupendra.morph.data.Brain
import kotlinx.coroutines.launch

@Composable
fun SettingsSheet(
    vm: HomeViewModel,
    onDismiss: () -> Unit,
    onGrantNotifications: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onMakeDefault: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val store = vm.graph.settings
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var brain by remember(s.loaded) { mutableStateOf(s.brain) }
    var baseUrl by remember(s.loaded) { mutableStateOf(s.baseUrl) }
    var model by remember(s.loaded) { mutableStateOf(s.model) }
    var fastModel by remember(s.loaded) { mutableStateOf(s.fastModel) }
    var key by remember { mutableStateOf("") }
    var name by remember(s.loaded) { mutableStateOf(s.userName) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(1800); saved = false } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Make Morph yours", style = MaterialTheme.typography.headlineMediumEmphasized)

            Section("Brain") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Brain.entries.forEach { b ->
                        ToggleButton(checked = brain == b, onCheckedChange = {
                            if (brain != b) { brain = b; baseUrl = b.baseUrl; model = b.model; fastModel = b.fastModel }
                        }) { Text(b.label) }
                    }
                }
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                EffortSlider(s.mainEffort) { e -> scope.launch { store.setMainEffort(e) } }
                var decisionModel by remember(s.loaded) { mutableStateOf(s.decisionModel) }
                OutlinedTextField(decisionModel, { decisionModel = it; scope.launch { store.setDecisionModel(it) } },
                    label = { Text("Decision model (instant yes/no & choices)") },
                    supportingText = { Text(if (s.brain == Brain.OpenRouter) "Emergency triage, notification ranking, quick choices — ~0.5 s, output free"
                        else "Needs OpenRouter; other brains answer the same questions with the fast model") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                OutlinedTextField(fastModel, { fastModel = it }, label = { Text("Fast model (background work)") },
                    supportingText = { Text("Auto-reply verdicts, triage and search digests") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                if (brain.needsKey) OutlinedTextField(
                    key, { key = it },
                    label = { Text(if (s.apiKey.isNotBlank() && s.brain == brain) "API key (saved — paste to replace)" else "API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        scope.launch {
                            store.saveBrain(brain, baseUrl, model, key.ifBlank { if (brain == s.brain) null else "" }, fastModel)
                            key = ""; saved = true
                        }
                    }, shapes = ButtonDefaults.shapes()) { Text("Save brain") }
                    Text(
                        when {
                            saved -> "Saved ✓"
                            s.aiReady -> "Connected to ${s.brain.label}"
                            else -> "Not connected"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (s.aiReady || saved) scheme.primary else scheme.error,
                    )
                }
            }

            Section("Autonomy") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bhupendra.morph.core.agent.Autonomy.entries.forEach { a ->
                        ToggleButton(checked = s.autonomy == a, onCheckedChange = { scope.launch { store.setAutonomy(a) } }) { Text(a.label) }
                    }
                }
                Text(s.autonomy.blurb, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Text("Always: passwords are never typed, every action is in Activity, and Stop works anytime.",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }

            Section("You") {
                OutlinedTextField(name, { name = it }, label = { Text("What should I call you?") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                Button(onClick = { scope.launch { store.setName(name); vm.refreshGlance() } }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors()) { Text("Save name") }
            }

            Section("Look") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Themed icons", style = MaterialTheme.typography.titleMedium)
                        Text("Tint app icons with your wallpaper colors", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    Switch(s.themedIcons, { scope.launch { store.setThemedIcons(it) } })
                }
            }

            Section("Senses") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SenseButton("🔔 Notifications", onGrantNotifications)
                    SenseButton("📇 Contacts") { onRequestPermission(android.Manifest.permission.READ_CONTACTS) }
                    SenseButton("🎙️ Microphone") { onRequestPermission(android.Manifest.permission.RECORD_AUDIO) }
                    SenseButton("🏠 Make default home", onMakeDefault)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    SenseButton("✨ Make Morph your assistant") {
                        // The assistant role can't be requested in-app; this opens Default apps > Digital assistant.
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }

            Section("Auto-replies") {
                val rules by vm.graph.rules.rules.collectAsState()
                if (rules.isEmpty()) Text("None yet. Try “while I'm at the gym, tell people I'll reply after 8”.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                rules.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(r.describe(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(r.enabled, { vm.graph.rules.setEnabled(r.id, it) })
                        androidx.compose.material3.TextButton(onClick = { vm.graph.rules.remove(r.id) }) { Text("Delete") }
                    }
                }
                SenseButton("🔔 Allow urgent alerts") { onRequestPermission(android.Manifest.permission.POST_NOTIFICATIONS) }
            }

            Section("Activity") {
                val ledger by vm.graph.ledger.entries.collectAsState()
                Row(
                    Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenActivity).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("📜", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text("Everything I've done", style = MaterialTheme.typography.bodyLargeEmphasized)
                        Text(ledger.firstOrNull()?.let { "${ledger.size} actions · latest: ${it.what}" } ?: "Nothing yet",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurfaceVariant)
                }
            }

            val hidden by vm.graph.catalog.hidden.collectAsState()
            if (hidden.isNotEmpty()) Section("Hidden apps") {
                hidden.forEach { app ->
                    Row(
                        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.TextButton(onClick = { vm.graph.catalog.setHidden(app, false) }) { Text("Show") }
                    }
                }
            }

            Section("Memory") {
                if (s.memories.isEmpty()) Text("Nothing yet. Tell me things like “I go to the gym at 7”.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                s.memories.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.TextButton(onClick = { scope.launch { store.forget(m) } }) { Text("Forget") }
                    }
                }
            }
            HorizontalDivider()
            Text("Morph 0.1 · prototype", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SenseButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.filledTonalButtonColors()) { Text(label) }
}

private val EFFORT_STOPS = listOf<String?>(null, "low", "medium", "high")

/** Thinking effort for the main model: Auto · Low · Medium · High. Saved when you let go. */
@Composable
private fun EffortSlider(current: String?, onChange: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var pos by remember(current) { mutableStateOf(EFFORT_STOPS.indexOf(current).coerceAtLeast(0).toFloat()) }
    val label = listOf("Auto", "Low", "Medium", "High")[pos.toInt()]
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Thinking effort", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(label, style = MaterialTheme.typography.titleMediumEmphasized, color = scheme.primary)
        }
        androidx.compose.material3.Slider(
            value = pos,
            onValueChange = { pos = Math.round(it).toFloat() },
            onValueChangeFinished = { onChange(EFFORT_STOPS[pos.toInt()]) },
            valueRange = 0f..3f, steps = 2,
        )
        Text(
            when (label) {
                "Auto" -> "The model decides how long to think."
                "Low" -> "Fastest replies; best for quick commands."
                "Medium" -> "Balanced speed and depth."
                else -> "Deepest reasoning for complex, multi-step requests; slower."
            } + " Applies to the main model (OpenRouter/OpenAI); operator steps stay quick and use this when they get stuck.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}
