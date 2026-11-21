package bhupendra.morph.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bhupendra.morph.device.Hit
import bhupendra.morph.device.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Instant results above the intent bar: apps as tiles, then shortcuts, people and settings as rows. */
@Composable
fun SearchPanel(results: SearchResults, vm: HomeViewModel, themed: Boolean, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh.copy(alpha = 0.97f)) {
        // Scrolls when results outgrow the space above the keyboard; the input bar never moves.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            if (results.apps.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(results.apps, key = { it.key }) { app ->
                    AppTile(app, vm.graph.catalog, themed, iconSize = 52.dp, modifier = Modifier.animateItem(), onClick = {
                        vm.graph.catalog.launch(app); onDone()
                    })
                }
            }
            results.hits.forEach { hit -> HitRow(hit, vm, onDone) }
        }
    }
}

@Composable
private fun HitRow(hit: Hit, vm: HomeViewModel, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val search = vm.graph.search
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); search.open(hit); onDone() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (hit) {
            is Hit.Shortcut -> ShortcutIcon(hit, vm)
            is Hit.Contact -> ShapedEmoji(hit.name.firstOrNull()?.uppercase() ?: "👤", shapeFor(hit.name), size = 36.dp)
            is Hit.Setting -> ShapedEmoji(hit.emoji, shapeFor(hit.label), size = 36.dp)
        }
        Column(Modifier.weight(1f)) {
            val (title, sub) = when (hit) {
                is Hit.Shortcut -> hit.entry.label to hit.entry.app.label
                is Hit.Contact -> hit.name to (hit.number ?: "Contact")
                is Hit.Setting -> hit.label to "Settings"
            }
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        if (hit is Hit.Contact && hit.number != null) {
            FilledTonalIconButton(onClick = { search.call(hit); onDone() }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Call, "Call")
            }
            FilledTonalIconButton(onClick = { search.message(hit); onDone() }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Chat, "Message")
            }
        }
    }
}

@Composable
fun ShortcutIcon(hit: Hit.Shortcut, vm: HomeViewModel, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, hit.key) {
        value = withContext(Dispatchers.IO) { vm.graph.catalog.loadShortcutIcon(hit.entry, px) }
    }
    Box(
        Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(it, hit.entry.label, Modifier.size(size)) } ?: Text("↗")
    }
}
