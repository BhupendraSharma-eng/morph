package bhupendra.morph.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bhupendra.morph.auto.ForYouItem
import bhupendra.morph.device.PendingIntents

/**
 * What people need from you, one line each, with replies you can send in a tap. Not a log — an action list:
 * reply (it's sent straight into the chat), open the conversation, or tick it off.
 */
@Composable
fun ForYouCard(items: List<ForYouItem>, vm: HomeViewModel) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) items else items.take(3)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(scheme.primaryContainer, RoundedCornerShape(32.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("For you", Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized, color = scheme.onPrimaryContainer)
            Text("${items.size}", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary,
                modifier = Modifier.background(scheme.primary, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp))
        }
        shown.forEach { item -> ForYouRow(item, vm) }
        if (items.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show less" else "${items.size - 3} more", color = scheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ForYouRow(item: ForYouItem, vm: HomeViewModel) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val forYou = vm.graph.forYou
    fun open() {
        val pi = forYou.openIntent(item)
        if (pi == null || !PendingIntents.sendFromForeground(context, pi)) vm.graph.catalog.resolve(item.pkg)?.let(vm.graph.catalog::launch)
    }
    Column(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerLowest.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .clickable { open() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShapedEmoji(item.who.firstOrNull()?.uppercase() ?: "•", shapeFor(item.who), size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(item.summary, style = MaterialTheme.typography.bodyLargeEmphasized, color = scheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.who} · ${item.app}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); forYou.done(item) },
                shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Rounded.Check, "Done") }
        }
        // The agent drew a control for a structured answer: your value becomes a reply in their language.
        val ask = item.ask
        val template = item.template
        if (ask != null && template != null) {
            val form = rememberFormState(bhupendra.morph.core.surface.Surface(id = "fy-${item.threadKey}-${item.at}", blocks = listOf(ask)))
            InputView(ask, form, toneColors(bhupendra.morph.core.surface.Tone.Neutral), enabled = true)
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    val text = template.replace("{value}", answerText(ask, form.values[ask.id]))
                    if (!forYou.quickReply(item, text)) open()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send", style = MaterialTheme.typography.labelLarge) }
        }
        if (item.replies.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.replies.forEach { r ->
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        // Straight into the chat; if the conversation's notification is gone, open it instead.
                        if (!forYou.quickReply(item, r)) open()
                    },
                    shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text(r, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Human-readable value for the reply ("8:30 PM", "Sat, 22 Nov", "3", "beach, hills"). */
private fun answerText(i: bhupendra.morph.core.surface.Block.Input, v: kotlinx.serialization.json.JsonElement?): String {
    val raw = when (v) {
        is kotlinx.serialization.json.JsonPrimitive -> v.content
        is kotlinx.serialization.json.JsonArray -> v.joinToString(", ") { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        else -> ""
    }
    return when (i.kind) {
        bhupendra.morph.core.surface.InputKind.Time -> runCatching {
            java.time.LocalTime.parse(raw).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
        }.getOrDefault(raw)
        bhupendra.morph.core.surface.InputKind.Date -> runCatching {
            java.time.LocalDate.parse(raw).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM"))
        }.getOrDefault(raw)
        else -> raw
    }
}
