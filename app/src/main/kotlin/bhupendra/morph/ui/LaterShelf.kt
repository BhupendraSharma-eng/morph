package bhupendra.morph.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bhupendra.morph.data.LaterItem

/** Spaces → Later header: the section title, a one-line AI digest of the shelf, and "Clear all". */
@Composable
internal fun LaterHeader(vm: HomeViewModel, items: List<LaterItem>) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(items.size) { vm.refreshLaterDigest() }
    Column(Modifier.padding(start = 8.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Later", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            TextButton(onClick = { vm.graph.later.doneAll(items.map { it.key }) }) { Text("Clear all") }
        }
        val digest = vm.laterDigest
            ?: items.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }.take(3)
                .joinToString(" · ") { "${it.key} ${it.value}" }
        Text(digest, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One app (or one chat) on the shelf: newest line on top; tap opens it, ✓ clears the group, the chevron lists each
 * item with its own open / ✓.
 */
@Composable
internal fun LaterGroup(vm: HomeViewModel, group: List<LaterItem>) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val top = group.first()
    var open by rememberSaveable(top.thread ?: top.pkg) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerLowest.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { if (group.size == 1) vm.openLater(top) else open = !open }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShapedEmoji(top.app.firstOrNull()?.uppercase() ?: "•", shapeFor(top.app), size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(if (group.size > 1) "${top.app} · ${group.size}" else top.title.ifBlank { top.app },
                    style = MaterialTheme.typography.bodyLargeEmphasized, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line(top, withTitle = group.size > 1), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (group.size > 1) IconButton(onClick = { open = !open }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ExpandMore, if (open) "Show less" else "Show all", Modifier.rotate(if (open) 180f else 0f), tint = scheme.onSurfaceVariant)
            }
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.graph.later.doneAll(group.map { it.key }) },
                shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(36.dp),
            ) { Icon(Icons.Rounded.Check, "Done", Modifier.size(18.dp)) }
        }
        if (open) group.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openLater(item) }.padding(start = 54.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line(item, withTitle = true), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (item.openedAt != null) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.graph.later.done(item.key) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Check, "Done", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun line(i: LaterItem, withTitle: Boolean): String {
    val body = (if (i.messages.size > 1) i.messages.joinToString(" · ") else i.text.lineSequence().firstOrNull()).orEmpty()
    return if (withTitle && i.title.isNotBlank()) "${i.title}: $body" else body
}
