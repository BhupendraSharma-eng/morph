package bhupendra.morph.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import bhupendra.morph.core.automation.ReplyRule
import bhupendra.morph.data.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import kotlin.math.ceil

private const val COLS = 4
private val CELL_H = 96.dp
private val GAP = 10.dp

/**
 * Spaces: what Morph is keeping track of for you. Live sections first (what's running, who's waiting on you — both
 * derived, so they're never stale), then your board: things kept (watches that re-check themselves, saved links with
 * countdowns) and your widgets on a grid at their designed sizes. Every button here does something real.
 */
@Composable
fun SpacesPage(vm: HomeViewModel, onAddWidget: (AppWidgetProviderInfo) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spaces = vm.graph.spaces
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val rules by vm.graph.rules.rules.collectAsState()
    val working by vm.graph.runtime.working.collectAsState()
    val convos by vm.graph.conversations.list.collectAsState()
    val forYou by vm.graph.forYou.items.collectAsState()
    val later by vm.graph.later.items.collectAsState()
    val laterGroups = remember(later) { later.groupBy { it.thread ?: it.pkg }.values.toList() }
    val active = rules.filter { it.enabled && it.activeNow() }
    // Live clock for countdowns; ticks only while there's something counting down.
    val timers by vm.graph.tools.timers.collectAsState()
    val now by produceState(System.currentTimeMillis(), timers) {
        while (true) { value = System.currentTimeMillis(); if (timers.none { it.endsAt > value }) break; kotlinx.coroutines.delay(1000) }
    }
    val runningTimers = timers.filter { it.endsAt > now }
    val alarmAt = remember(now / 60_000) { vm.graph.tools.nextAlarmAt()?.takeIf { it - System.currentTimeMillis() in 0..(12 * Tile.HOUR) } }
    val workingTitles = working.map { id -> id to (convos.firstOrNull { it.id == id }?.title ?: "A conversation") }

    Column(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.55f)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spaces", style = MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface)
                Text("What I'm keeping track of", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (spaces.tiles.isNotEmpty()) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            if (!editing) FilledTonalButton(onClick = { picking = true }) { Icon(Icons.Rounded.Add, null); Text("Widget", Modifier.padding(start = 6.dp)) }
        }

        if (editing) { EditBoard(vm); return@Column }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellW = (maxWidth - 32.dp - GAP * (COLS - 1)) / COLS
            val board = remember(spaces.tiles.toList(), cellW) { layoutBoard(vm, spaces.tiles.toList(), cellW) }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                if (active.isNotEmpty() || workingTitles.isNotEmpty() || runningTimers.isNotEmpty() || alarmAt != null) {
                    item(key = "h-active") { Section("Active") }
                    items(runningTimers, key = { "timer-" + it.endsAt }) { t ->
                        val left = (t.endsAt - now) / 1000
                        LiveRow("⏱️", t.label, "%d:%02d left".format(left / 60, left % 60), "Open") {
                            vm.graph.tools.openClock(timers = true)
                        }
                    }
                    alarmAt?.let { at ->
                        item(key = "alarm") {
                            val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                            LiveRow("⏰", "Alarm at " + t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")),
                                "in " + countdown(at - now).removePrefix("due in "), "Open") { vm.graph.tools.openClock(timers = false) }
                        }
                    }
                    items(active, key = { "rule-" + it.id }) { r -> ActiveRule(vm, r) }
                    items(workingTitles, key = { "work-" + it.first }) { (id, title) -> WorkingRow(vm, id, title) }
                }
                if (forYou.isNotEmpty()) {
                    item(key = "h-wait") { Section("Waiting on you") }
                    items(forYou, key = { "fy-" + it.threadKey }) { ForYouRow(it, vm) }
                }
                if (later.isNotEmpty()) {
                    item(key = "h-later") { LaterHeader(vm, later) }
                    items(laterGroups, key = { "later-" + (it.first().thread ?: it.first().pkg) }) { g ->
                        androidx.compose.foundation.layout.Box(Modifier.animateItem()) { LaterGroup(vm, g) }
                    }
                }
                item(key = "h-board") { Section("Your board") }
                if (board.isEmpty()) item(key = "empty") {
                    Text("Ask me to keep an eye on something (\"watch this price\", \"save this link for later\"), long-press any " +
                        "card and choose Keep track of this, or add a widget.",
                        Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                }
                items(board, key = { it.key }) { row ->
                    when (row) {
                        is BoardRow.Kept -> KeptCard(vm, row.item)
                        is BoardRow.Widgets -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            row.cells.forEach { c ->
                                Box(Modifier.weight(c.cols.toFloat()).height(c.height)) { WidgetView(vm, c.id, c.height) }
                            }
                            val used = row.cells.sumOf { it.cols }
                            if (used < COLS) Spacer(Modifier.weight((COLS - used).toFloat()))
                        }
                    }
                }
            }
        }
    }

    if (picking) WidgetPicker(vm, onDismiss = { picking = false }) { info -> picking = false; onAddWidget(info) }
}

// ---- Board layout: kept items full width; consecutive widgets packed into rows of 4 columns -------------

private sealed interface BoardRow {
    val key: String
    data class Kept(val item: Tile.Kept) : BoardRow { override val key get() = item.key }
    data class Widgets(val cells: List<Cell>) : BoardRow { override val key get() = "row-" + cells.joinToString("-") { it.id.toString() } }
}

private data class Cell(val id: Int, val cols: Int, val height: Dp)

private fun layoutBoard(vm: HomeViewModel, tiles: List<Tile>, cellW: Dp): List<BoardRow> {
    val density = vm.graph.app.resources.displayMetrics.density
    val out = mutableListOf<BoardRow>()
    var row = mutableListOf<Cell>()
    fun flush() {
        if (row.isEmpty()) return
        // A lone wide widget (e.g. a 3-column music bar) takes the whole row instead of leaving a gap.
        if (row.size == 1 && row[0].cols >= COLS - 1) row[0] = row[0].copy(cols = COLS)
        out += BoardRow.Widgets(row); row = mutableListOf()
    }
    tiles.forEach { t ->
        when (t) {
            is Tile.Kept -> { flush(); out += BoardRow.Kept(t) }
            is Tile.Widget -> {
                val info = vm.graph.spaces.widgets.getAppWidgetInfo(t.appWidgetId) ?: return@forEach
                // The size the widget was designed for: its grid span if it declares one, else derived from its minimums.
                val cols = (if (info.targetCellWidth > 0) info.targetCellWidth
                    else ceil(info.minWidth / density / cellW.value).toInt()).coerceIn(1, COLS)
                val rows = (if (info.targetCellHeight > 0) info.targetCellHeight
                    else ceil(info.minHeight / density / CELL_H.value).toInt()).coerceIn(1, 6)
                val cell = Cell(t.appWidgetId, cols, CELL_H * rows + GAP * (rows - 1))
                if (row.sumOf { it.cols } + cols > COLS) flush()
                row += cell
            }
        }
    }
    flush()
    return out
}

// ---- Live sections -------------------------------------------------------------------------------------

private fun ReplyRule.activeNow(): Boolean {
    val w = window ?: return true
    val (a, b) = w.split('-').map { runCatching { LocalTime.parse(it.trim()) }.getOrNull() ?: return true }
    val now = LocalTime.now()
    return if (a <= b) now >= a && now < b else now >= a || now < b
}

@Composable
private fun Section(text: String) {
    Text(text, Modifier.padding(start = 8.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LiveRow(icon: String, title: String, meta: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.primaryContainer).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized, color = scheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
        }
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ActiveRule(vm: HomeViewModel, r: ReplyRule) {
    val until = r.window?.substringAfter('-')?.trim()
    LiveRow("💬", r.describe(), if (until != null) "Active until $until" else "Always on", "Turn off") { vm.graph.rules.remove(r.id) }
}

@Composable
private fun WorkingRow(vm: HomeViewModel, id: String, title: String) {
    LiveRow("⏳", "Working on: $title", "Still running in the background", "Open") { vm.resumeConversation(id) }
}

// ---- Kept items ----------------------------------------------------------------------------------------

@Composable
private fun KeptCard(vm: HomeViewModel, item: Tile.Kept) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checking by vm.graph.spaces.checking.collectAsState()
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.icon, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                Text(
                    when (item.kind) {
                        Tile.Kept.Kind.Watch -> "Watching · " + if (item.lastChecked == 0L) "first check…" else "checked ${ago(now - item.lastChecked)} · every ${hours(item.everyMs)}"
                        Tile.Kept.Kind.Saved -> "Saved" + (item.deadline?.let { " · " + countdown(it - now) } ?: "")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.deadline != null && item.deadline - now < 2 * 24 * Tile.HOUR) scheme.error else scheme.onSurfaceVariant,
                )
            }
            if (checking == item.key) LoadingIndicator(Modifier.size(28.dp))
        }
        if (item.kind == Tile.Kept.Kind.Watch) item.lastResult?.let {
            Text(richText(it), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            if (item.lastChange > 0 && item.lastChecked > item.lastChange)
                Text("Last changed ${ago(now - item.lastChange)}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.url?.let { url ->
                Button(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) { Text("Open") }
            }
            if (item.kind == Tile.Kept.Kind.Watch) FilledTonalButton(enabled = checking != item.key, onClick = { scope.launch { vm.graph.spaces.check(item.key) } }) { Text("Check now") }
            OutlinedButton(onClick = { vm.input = "About \"${item.title}\": "; vm.goHome() }) { Text("Ask") }
        }
    }
}

private fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < Tile.HOUR -> "${ms / 60_000} min ago"
    ms < 24 * Tile.HOUR -> "${ms / Tile.HOUR} h ago"
    else -> "${ms / (24 * Tile.HOUR)} d ago"
}

private fun hours(ms: Long): String = (ms / Tile.HOUR).let { if (it % 24 == 0L && it >= 24) "${it / 24} d" else "$it h" }

private fun countdown(ms: Long): String = when {
    ms < 0 -> "overdue"
    ms < Tile.HOUR -> "due in ${ms / 60_000} min"
    ms < 24 * Tile.HOUR -> "due in ${ms / Tile.HOUR} h"
    else -> "due in ${ms / (24 * Tile.HOUR)} days"
}

// ---- Widgets -------------------------------------------------------------------------------------------

@Composable
private fun WidgetView(vm: HomeViewModel, appWidgetId: Int, height: Dp) {
    val context = LocalContext.current
    val spaces = vm.graph.spaces
    val info = remember(appWidgetId) { spaces.widgets.getAppWidgetInfo(appWidgetId) } ?: return
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
        val w = maxWidth
        AndroidView(
            factory = { spaces.host.createView(context, appWidgetId, info).apply { setPadding(0, 0, 0, 0) } },
            update = { v -> v.updateAppWidgetSize(Bundle(), listOf(SizeF(w.value, height.value))) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---- Edit: a compact list to reorder and remove (live widgets would fight the drag) -----------------------

@Composable
private fun EditBoard(vm: HomeViewModel) {
    val scheme = MaterialTheme.colorScheme
    val spaces = vm.graph.spaces
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(spaces.tiles, key = { it.key }) { tile ->
            val dragging = tile.key == draggingKey
            Row(
                (if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16f }
                else Modifier.animateItem()).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DragHandle, "Drag to reorder", modifier = Modifier.size(48.dp).padding(12.dp).pointerInput(tile.key) {
                    detectDragGestures(
                        onDragStart = { draggingKey = tile.key; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = { draggingKey = null; dragOffset = 0f },
                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val items = list.layoutInfo.visibleItemsInfo
                        val cur = items.firstOrNull { it.key == tile.key } ?: return@detectDragGestures
                        val next = items.firstOrNull { it.index == cur.index + 1 }
                        val prev = items.firstOrNull { it.index == cur.index - 1 }
                        if (dragOffset > 0 && next != null && dragOffset > next.size / 2f) {
                            spaces.move(cur.index, cur.index + 1); dragOffset -= next.size + gapPx
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        } else if (dragOffset < 0 && prev != null && -dragOffset > prev.size / 2f) {
                            spaces.move(cur.index, cur.index - 1); dragOffset += prev.size + gapPx
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                })
                Column(Modifier.weight(1f)) {
                    Text(tileName(vm, tile), style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                    Text(when (tile) { is Tile.Widget -> "Widget"; is Tile.Kept -> if (tile.kind == Tile.Kept.Kind.Watch) "Watching" else "Saved" },
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { spaces.remove(tile) }) { Icon(Icons.Rounded.Close, "Remove") }
            }
        }
    }
}

private fun tileName(vm: HomeViewModel, tile: Tile): String = when (tile) {
    is Tile.Kept -> "${tile.icon} ${tile.title}"
    is Tile.Widget -> vm.graph.spaces.widgets.getAppWidgetInfo(tile.appWidgetId)?.loadLabel(vm.graph.app.packageManager) ?: "Widget"
}

// ---- Widget picker -------------------------------------------------------------------------------------

/** Every widget your apps offer, grouped by app, with previews. */
@Composable
private fun WidgetPicker(vm: HomeViewModel, onDismiss: () -> Unit, onPick: (AppWidgetProviderInfo) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val pm = context.packageManager
    var query by remember { mutableStateOf("") }
    val all by produceState<List<Pair<String, AppWidgetProviderInfo>>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            vm.graph.spaces.widgets.installedProviders.map { info ->
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                    .getOrDefault(info.provider.packageName)
                app to info
            }.sortedWith(compareBy({ it.first.lowercase() }, { it.second.loadLabel(pm) }))
        }
    }
    val shown = remember(all, query) {
        if (query.isBlank()) all else all.filter { (app, info) -> app.contains(query, true) || info.loadLabel(pm).contains(query, true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Text("Add a widget", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            TextField(
                value = query, onValueChange = { query = it }, placeholder = { Text("Search widgets") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = CircleShape,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var lastApp: String? = null
                shown.forEach { (app, info) ->
                    if (app != lastApp) { lastApp = app; item(key = "h-$app") {
                        Text(app, Modifier.padding(start = 8.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    } }
                    item(key = info.provider.flattenToString() + info.profile.hashCode()) {
                        val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info) {
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    val d = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
                                    d?.toBitmap(d.intrinsicWidth.coerceIn(1, 720), d.intrinsicHeight.coerceIn(1, 720))?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)
                                .clickable { onPick(info) }.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            preview?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 160.dp), alignment = Alignment.CenterStart) }
                            Text(info.loadLabel(context.packageManager), style = MaterialTheme.typography.bodyLargeEmphasized)
                            if (info.targetCellWidth > 0 && info.targetCellHeight > 0)
                                Text("${info.targetCellWidth} × ${info.targetCellHeight}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
