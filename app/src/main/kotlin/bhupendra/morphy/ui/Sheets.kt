package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bhupendra.morphy.data.Brain
import kotlinx.coroutines.launch

/** The pages of Settings: a short home list, then one focused page per area. */
private enum class SettingsPage(val icon: String, val title: String) {
    You("🙂", "You"), Brain("🧠", "Brain"), Voice("🎙️", "Voice & hands-free"), Usage("📊", "Usage & cost"),
    Autonomy("🛡️", "Autonomy & safety"), AutoReplies("💬", "Auto-replies"), Look("🏠", "Home & look"),
    Permissions("🔑", "Permissions & roles"), About("ℹ️", "About"),
}

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
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = page
            if (current == null) {
                Text("Settings", style = MaterialTheme.typography.headlineMediumEmphasized)
                SettingsHome(vm, s, onOpen = { page = it }, onOpenActivity = onOpenActivity)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = { page = null }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                    Text(current.title, style = MaterialTheme.typography.headlineSmallEmphasized)
                }
                when (current) {
                    SettingsPage.You -> YouPage(vm)
                    SettingsPage.Brain -> BrainPage(vm, onRequestPermission)
                    SettingsPage.Voice -> VoicePage(vm)
                    SettingsPage.Usage -> UsagePage(vm)
                    SettingsPage.Autonomy -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            bhupendra.morphy.core.agent.Autonomy.entries.forEach { a ->
                                ToggleButton(checked = s.autonomy == a, onCheckedChange = { scope.launch { store.setAutonomy(a) } }) { Text(a.label) }
                            }
                        }
                        Text(s.autonomy.blurb, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        Text("Always: passwords are never typed, every action is in Activity, and Stop works anytime.",
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    SettingsPage.AutoReplies -> AutoRepliesPage(vm, onRequestPermission)
                    SettingsPage.Look -> LookPage(vm)
                    SettingsPage.Permissions -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SenseButton("🔔 Notifications", onGrantNotifications)
                        SenseButton("📇 Contacts") { onRequestPermission(android.Manifest.permission.READ_CONTACTS) }
                        SenseButton("🎙️ Microphone") { onRequestPermission(android.Manifest.permission.RECORD_AUDIO) }
                        SenseButton("📊 Usage access") { vm.perform(bhupendra.morphy.core.surface.Action.RequestPermission(bhupendra.morphy.device.DeviceTools.PERMISSION_USAGE)) }
                        SenseButton("🏠 Make default home", onMakeDefault)
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        SenseButton("✨ Make Morphy your assistant") {
                            // The assistant role can't be requested in-app; this opens Default apps > Digital assistant.
                            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    SettingsPage.About -> {
                        Text("Morphy ${bhupendra.morphy.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text("Build ${bhupendra.morphy.BuildConfig.VERSION_CODE} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        Text("An AI-first home screen: say what you want, Morphy does it. Open source (MIT).",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The settings home: the AI switch, money at a glance, then the pages grouped by what you'd go there for. */
@Composable
private fun SettingsHome(vm: HomeViewModel, s: bhupendra.morphy.data.MorphySettings, onOpen: (SettingsPage) -> Unit, onOpenActivity: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    // Master switch: off = a plain launcher that never calls a model and works fully offline.
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("AI", style = MaterialTheme.typography.titleMedium)
            Text(if (s.aiEnabled) "On — Morphy can think, act and reply for you."
                else "Off — a plain launcher. Apps, search, calls, alarms, timers, music and torch still work, fully offline.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Switch(s.aiEnabled, { scope.launch { vm.graph.settings.setAiEnabled(it); vm.refreshGlance() } })
    }
    val usage by vm.graph.usage.summary.collectAsState()
    val ledger by vm.graph.ledger.entries.collectAsState()
    val rules by vm.graph.rules.rules.collectAsState()
    Group("Your assistant") {
        NavRow(SettingsPage.Brain, if (s.aiReady) "${s.brain.label} · ${s.model}" else "Not connected", onOpen)
        NavRow(SettingsPage.Voice, if (s.googleApiKey.isBlank()) "Add a Google AI key for voice" else "Voice ${s.ttsVoice} · hold the orb for hands-free", onOpen)
        NavRow(SettingsPage.Autonomy, s.autonomy.label, onOpen)
        NavRow(SettingsPage.You, (s.userName.ifBlank { "Your name" }) + " · " + plural(s.memories.size, "memory", "memories"), onOpen)
    }
    Group("Automation") {
        NavRow(SettingsPage.AutoReplies, if (rules.isEmpty()) "None yet" else "${rules.count { it.enabled }} on · ${rules.size} total", onOpen)
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenActivity).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("📜", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text("Activity", style = MaterialTheme.typography.bodyLargeEmphasized)
                Text(ledger.firstOrNull()?.let { "${ledger.size} actions · latest: ${it.what}" } ?: "Everything I've done shows here",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurfaceVariant)
        }
    }
    Group("Phone") {
        NavRow(SettingsPage.Look, "Icons, double-tap to sleep, hidden apps", onOpen)
        NavRow(SettingsPage.Permissions, "Notifications, contacts, mic, default home, assistant", onOpen)
    }
    Group("Morphy") {
        NavRow(SettingsPage.Usage, "${usd(usage.allTime.usd)} so far · ${usd(usage.thisMonth.usd)} this month · ${plural(usage.allTime.calls, "call", "calls")}", onOpen)
        NavRow(SettingsPage.About, "Morphy ${bhupendra.morphy.BuildConfig.VERSION_NAME}", onOpen)
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 2.dp))
        content()
    }
}

@Composable
private fun NavRow(page: SettingsPage, summary: String, onOpen: (SettingsPage) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(page.icon, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(page.title, style = MaterialTheme.typography.bodyLargeEmphasized)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun YouPage(vm: HomeViewModel) {
    val store = vm.graph.settings
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var name by remember(s.loaded) { mutableStateOf(s.userName) }
    OutlinedTextField(name, { name = it }, label = { Text("What should I call you?") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
    Button(onClick = { scope.launch { store.setName(name); vm.refreshGlance() } }, shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors()) { Text("Save name") }
    Section("What I remember") {
        if (s.memories.isEmpty()) Text("Nothing yet. Tell me things like “I go to the gym at 7”.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        s.memories.forEach { m ->
            Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = { scope.launch { store.forget(m) } }) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun BrainPage(vm: HomeViewModel, onRequestPermission: (String) -> Unit) {
    val store = vm.graph.settings
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var brain by remember(s.loaded) { mutableStateOf(s.brain) }
    var baseUrl by remember(s.loaded) { mutableStateOf(s.baseUrl) }
    var model by remember(s.loaded) { mutableStateOf(s.model) }
    var fastModel by remember(s.loaded) { mutableStateOf(s.fastModel) }
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(1800); saved = false } }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Brain.entries.forEach { b ->
            ToggleButton(checked = brain == b, onCheckedChange = {
                if (brain != b) { brain = b; baseUrl = b.baseUrl; model = b.model; fastModel = b.fastModel }
            }) { Text(b.label) }
        }
    }
    OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
    OutlinedTextField(fastModel, { fastModel = it }, label = { Text("Fast model (background work)") },
        supportingText = { Text("Auto-reply verdicts, triage and search digests") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
    if (brain.needsKey) OutlinedTextField(
        key, { key = it },
        label = { Text(if (s.apiKey.isNotBlank() && s.brain == brain) "API key (saved — paste to replace)" else "API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
    )
    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            scope.launch {
                store.saveBrain(brain, baseUrl, model, key.ifBlank { if (brain == s.brain) null else "" }, fastModel)
                key = ""; saved = true
            }
        }, shapes = ButtonDefaults.shapes()) { Text("Save brain") }
        Text(when { saved -> "Saved ✓"; s.aiReady -> "Connected to ${s.brain.label}"; else -> "Not connected" },
            style = MaterialTheme.typography.labelLarge, color = if (s.aiReady || saved) scheme.primary else scheme.error)
    }
    EffortSlider(s.mainEffort) { e -> scope.launch { store.setMainEffort(e) } }
    Section("Decisions") {
        var decisionModel by remember(s.loaded) { mutableStateOf(s.decisionModel) }
        OutlinedTextField(decisionModel, { decisionModel = it; scope.launch { store.setDecisionModel(it) } },
            label = { Text("Decision model (instant yes/no & choices)") },
            supportingText = { Text(if (s.brain == Brain.OpenRouter) "Emergency triage, notification ranking, quick choices — ~0.5 s"
                else "Needs OpenRouter; other brains answer the same questions with the fast model") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
        var localDecision by remember(s.loaded) { mutableStateOf(s.localDecisionUrl) }
        OutlinedTextField(localDecision, { localDecision = it; scope.launch { store.setLocalDecisionUrl(it) } },
            // Android 17 blocks apps from devices on your Wi-Fi without this; ask once a URL is entered.
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (!f.isFocused && localDecision.isNotBlank()) onRequestPermission("android.permission.ACCESS_LOCAL_NETWORK")
            },
            label = { Text("Local decision model (optional)") },
            placeholder = { Text("http://192.168.1.5:8080") },
            supportingText = { Text("A System One server on your network (e.g. Kev). Handles ranking, For you and wrap-ups; " +
                "emergencies stay on the model above. Falls back if unreachable.") },
            singleLine = true, shape = RoundedCornerShape(20.dp))
    }
}

@Composable
private fun VoicePage(vm: HomeViewModel) {
    val store = vm.graph.settings
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var gkey by remember { mutableStateOf("") }
    OutlinedTextField(gkey, { gkey = it },
        label = { Text(if (s.googleApiKey.isNotBlank()) "Google AI key (saved — paste to replace)" else "Google AI key") },
        supportingText = { Text("For Morphy's voice and hands-free (Gemini). Stays encrypted on this phone.") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        visualTransformation = PasswordVisualTransformation(),
        trailingIcon = { if (gkey.isNotBlank()) androidx.compose.material3.TextButton(onClick = { scope.launch { store.setGoogleApiKey(gkey); gkey = "" } }) { Text("Save") } })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Speak answers", style = MaterialTheme.typography.titleMedium)
            Text("When you ask by voice, Morphy answers out loud. Talk or type to cut it off.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Switch(s.speakReplies, { scope.launch { store.setSpeakReplies(it) } })
    }
    Section("Voice") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bhupendra.morphy.device.Speech.VOICES.forEach { v ->
                ToggleButton(checked = s.ttsVoice == v, onCheckedChange = {
                    scope.launch { store.setTtsVoice(v); kotlinx.coroutines.delay(150); vm.graph.speech.speak("Hi, I'm Morphy. This is how I sound.") }
                }) { Text(v) }
            }
        }
    }
    Section("Hands-free budget") {
        // Estimates, hence "≈": see Usage & cost for every call.
        val month by vm.graph.liveUsage.month.collectAsState()
        Text("This month: ${month.minutes} min · ≈ ${usd(month.usd)}" + if (s.liveCapUsd > 0) " of ${usd(s.liveCapUsd)}" else " (no cap)",
            style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bhupendra.morphy.data.LiveUsage.CAPS.forEach { cap ->
                ToggleButton(checked = s.liveCapUsd == cap, onCheckedChange = { scope.launch { store.setLiveCap(cap) } }) {
                    Text(if (cap == 0.0) "No cap" else "\$${"%.0f".format(cap)}/mo")
                }
            }
        }
        Text("Hold the orb on home to start hands-free; say “bye” or tap the orb to end.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** Every model call Morphy has made: totals, where the money goes (by kind and model), and the calls themselves. */
@Composable
private fun UsagePage(vm: HomeViewModel) {
    val u by vm.graph.usage.summary.collectAsState()
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("All time" to u.allTime, "This month" to u.thisMonth).forEach { (label, t) ->
            Column(Modifier.weight(1f).background(scheme.surfaceContainerHigh, RoundedCornerShape(20.dp)).padding(14.dp)) {
                Text(usd(t.usd), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text("$label · ${plural(t.calls, "call", "calls")}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text("${tokens(t.inTokens)} in · ${tokens(t.outTokens)} out", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
    Text("Since " + java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(
        java.time.Instant.ofEpochMilli(u.since).atZone(java.time.ZoneId.systemDefault())) +
        ". Costs are what the provider reported (OpenRouter, Jev); hands-free is estimated (≈). " +
        if (u.allTime.unpriced > 0) "${u.allTime.unpriced} calls had no price reported (e.g. Gemini direct or voice) and count as \$0." else "",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Section("Where it goes") {
        u.byKind.entries.sortedByDescending { it.value.usd }.forEach { (k, t) -> UsageRow(k.label, t) }
    }
    Section("By model") {
        u.byModel.entries.sortedByDescending { it.value.usd }.take(12).forEach { (m, t) -> UsageRow(m, t) }
    }
    Section("Recent calls") {
        if (u.recent.isEmpty()) Text("No model calls yet.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a") }
        u.recent.take(100).forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.kind.label} · ${r.model}", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(fmt.format(java.time.Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault())) +
                        " · ${tokens(r.inTokens.toLong())} in · ${tokens(r.outTokens.toLong())} out",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Text(r.costUsd?.let { (if (r.estimated) "≈" else "") + usd(it, precise = true) } ?: "—",
                    style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, t: bhupendra.morphy.data.UsageTotals) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${plural(t.calls, "call", "calls")} · ${tokens(t.inTokens)} in · ${tokens(t.outTokens)} out",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(usd(t.usd), style = MaterialTheme.typography.titleSmall)
    }
}

private fun usd(v: Double, precise: Boolean = false) = when {
    precise && v < 0.01 -> "\$" + "%.5f".format(v)
    else -> "\$" + "%.2f".format(v)
}
private fun plural(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
private fun tokens(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fk".format(n / 1e3); else -> "$n" }

@Composable
private fun AutoRepliesPage(vm: HomeViewModel, onRequestPermission: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rules by vm.graph.rules.rules.collectAsState()
    if (rules.isEmpty()) Text("None yet. Try “while I'm at the gym, tell people I'll reply after 8”.",
        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    rules.forEach { r ->
        Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.describe(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(r.enabled, { vm.graph.rules.setEnabled(r.id, it) })
            androidx.compose.material3.TextButton(onClick = { vm.graph.rules.remove(r.id) }) { Text("Delete") }
        }
    }
    SenseButton("🔔 Allow urgent alerts") { onRequestPermission(android.Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
private fun LookPage(vm: HomeViewModel) {
    val store = vm.graph.settings
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Themed icons", style = MaterialTheme.typography.titleMedium)
            Text("Tint app icons with your wallpaper colors", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Switch(s.themedIcons, { scope.launch { store.setThemedIcons(it) } })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Double-tap to sleep", style = MaterialTheme.typography.titleMedium)
            Text("Double-tap an empty part of home to turn the screen off. Unlock works as usual (fingerprint, face).",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Switch(s.doubleTapSleep, { scope.launch { store.setDoubleTapSleep(it) } })
    }
    val hidden by vm.graph.catalog.hidden.collectAsState()
    if (hidden.isNotEmpty()) Section("Hidden apps") {
        hidden.forEach { app ->
            Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = { vm.graph.catalog.setHidden(app, false) }) { Text("Show") }
            }
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
