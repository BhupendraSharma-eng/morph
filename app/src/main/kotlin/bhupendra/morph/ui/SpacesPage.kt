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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import bhupendra.morph.core.surface.Action
import bhupendra.morph.data.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spaces: Android widgets and pinned Morph cards, one swipe right of home. Reorder in Edit mode by dragging the
 * handle (widgets keep their own taps and long-presses the rest of the time). No resizing, by design.
 */
@Composable
fun SpacesPage(vm: HomeViewModel, onAddWidget: (AppWidgetProviderInfo) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spaces = vm.graph.spaces
    val settings by vm.graph.settings.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { 12.dp.toPx() }

    Column(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.55f)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spaces", style = MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface)
                Text("Widgets and cards you keep", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (spaces.tiles.isNotEmpty()) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            FilledTonalButton(onClick = { picking = true }) {
                Icon(Icons.Rounded.Add, null); Text("Widget", Modifier.padding(start = 6.dp))
            }
        }

        if (spaces.tiles.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                Text("Add widgets from your apps, or long-press any card in a conversation and choose Pin to Spaces.",
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(spaces.tiles, key = { it.key }) { tile ->
                val dragging = tile.key == draggingKey
                Column(
                    (if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 16f
                    } else Modifier.animateItem()).fillMaxWidth(),
                ) {
                    if (editing) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = scheme.onSurface,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHighest)
                                .padding(8.dp)
                                .pointerInput(tile.key) {
                                    detectDragGestures(
                                        onDragStart = { draggingKey = tile.key; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragEnd = { draggingKey = null; dragOffset = 0f },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Swap with a neighbour once we're past half of it.
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
                        Text(tileLabel(tile, vm), Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FilledTonalIconButton(onClick = { spaces.remove(tile) }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                    Box {
                        when (tile) {
                            is Tile.Widget -> WidgetTile(vm, tile.appWidgetId)
                            is Tile.Card -> SurfaceCard(tile.surface, vm.graph.catalog, settings.themedIcons, onAction = { a ->
                                vm.perform(a)
                                if (a is Action.Say) vm.goHome()   // the answer appears on home
                            })
                        }
                        // While editing, tiles are for arranging: their own buttons and widget taps are paused.
                        if (editing) Box(Modifier.matchParentSize().clickable(enabled = true, onClick = {}))
                    }
                }
            }
        }
    }

    if (picking) WidgetPicker(vm, onDismiss = { picking = false }) { info -> picking = false; onAddWidget(info) }
}

private fun tileLabel(tile: Tile, vm: HomeViewModel): String = when (tile) {
    is Tile.Card -> tile.surface.title ?: "Pinned card"
    is Tile.Widget -> vm.graph.spaces.widgets.getAppWidgetInfo(tile.appWidgetId)
        ?.loadLabel(vm.graph.app.packageManager) ?: "Widget"
}

/** A hosted Android widget: full width, at the height the widget asks for. */
@Composable
private fun WidgetTile(vm: HomeViewModel, appWidgetId: Int) {
    val context = LocalContext.current
    val spaces = vm.graph.spaces
    val info = remember(appWidgetId) { spaces.widgets.getAppWidgetInfo(appWidgetId) } ?: run {
        Text("This widget's app is gone.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val density = LocalDensity.current
    val heightDp = with(density) { info.minHeight.toDp() }.coerceAtLeast(72.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))) {
        val widthDp = maxWidth
        AndroidView(
            factory = { spaces.host.createView(context, appWidgetId, info).apply { setPadding(0, 0, 0, 0) } },
            update = { v -> v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.value, heightDp.value))) },
            modifier = Modifier.fillMaxWidth().height(heightDp),
        )
    }
}

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
