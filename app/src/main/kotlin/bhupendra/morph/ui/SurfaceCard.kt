package bhupendra.morph.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.device.AppCatalog

data class ToneColors(val container: Color, val content: Color, val accent: Color, val tile: Color)

@Composable
fun toneColors(tone: Tone): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (tone) {
        Tone.Neutral -> ToneColors(c.surfaceContainerLow.copy(alpha = 0.94f), c.onSurface, c.primary, c.surfaceContainerHighest)
        Tone.Primary -> ToneColors(c.primaryContainer, c.onPrimaryContainer, c.primary, c.surfaceContainerLowest.copy(alpha = 0.6f))
        Tone.Secondary -> ToneColors(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.surfaceContainerLowest.copy(alpha = 0.6f))
        Tone.Tertiary -> ToneColors(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.surfaceContainerLowest.copy(alpha = 0.6f))
        Tone.Error -> ToneColors(c.errorContainer, c.onErrorContainer, c.error, c.surfaceContainerLowest.copy(alpha = 0.6f))
    }
}

/** Renders any agent-composed [Surface] with native Material 3 Expressive components. */
@Composable
fun SurfaceCard(
    surface: Surface,
    catalog: AppCatalog,
    themedIcons: Boolean,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    /** For question cards: called with the values (or null if dismissed). */
    onSubmit: ((kotlinx.serialization.json.JsonObject?) -> Unit)? = null,
    /** Whether the agent is still waiting on this question (false after a restart = expired). */
    waiting: Boolean = false,
) {
    val colors = toneColors(surface.tone)
    val form = rememberFormState(surface)
    val inputsEnabled = surface.form == null || waiting
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(32.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (surface.title != null || surface.icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                surface.icon?.let { ShapedEmoji(it, shapeFor(surface.id)) }
                surface.title?.let {
                    Text(it, style = MaterialTheme.typography.titleLargeEmphasized, color = colors.content)
                }
            }
        }

        // Consecutive metrics flow together as a row of tiles.
        val groups = remember(surface.blocks) { groupMetrics(surface.blocks) }
        groups.forEach { g ->
            when (g) {
                is List<*> -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    g.filterIsInstance<Block.Metric>().forEach { MetricTile(it, colors) }
                }
                is Block.Input -> InputView(g, form, colors, inputsEnabled)
                is Block -> BlockView(g, colors, catalog, themedIcons, onAction)
            }
        }

        surface.form?.let { f ->
            if (waiting && onSubmit != null) {
                val haptics = LocalHapticFeedback.current
                val inputs = remember(surface.blocks) { surface.blocks.filterIsInstance<Block.Input>() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    f.cancelLabel?.let { c ->
                        androidx.compose.material3.TextButton(onClick = { onSubmit(null) }) { Text(c, color = colors.content) }
                    }
                    Button(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSubmit(form.toJson()) },
                        enabled = !form.missingRequired(inputs),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    ) { Text(f.submitLabel, style = MaterialTheme.typography.labelLarge) }
                }
            } else {
                Text("This question expired — ask me again if you still need it.",
                    style = MaterialTheme.typography.labelMedium, color = colors.content.copy(alpha = 0.7f))
            }
        }
    }
}

private fun groupMetrics(blocks: List<Block>): List<Any> {
    val out = mutableListOf<Any>()
    val run = mutableListOf<Block.Metric>()
    blocks.forEach { b ->
        if (b is Block.Metric) run.add(b) else {
            if (run.isNotEmpty()) { out.add(run.toList()); run.clear() }
            out.add(b)
        }
    }
    if (run.isNotEmpty()) out.add(run.toList())
    return out
}

@Composable
private fun MetricTile(m: Block.Metric, colors: ToneColors) {
    Column(
        Modifier.background(colors.tile, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            m.icon?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(m.value, style = MaterialTheme.typography.headlineMediumEmphasized, color = colors.content)
        }
        if (m.label.isNotBlank()) Text(m.label, style = MaterialTheme.typography.labelMedium, color = colors.content.copy(alpha = 0.75f))
    }
}

@Composable
private fun BlockView(b: Block, colors: ToneColors, catalog: AppCatalog, themed: Boolean, onAction: (Action) -> Unit) {
    val haptics = LocalHapticFeedback.current
    when (b) {
        is Block.Heading -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.eyebrow?.let { Text(it, style = MaterialTheme.typography.labelLargeEmphasized, color = colors.accent) }
            Text(b.text, style = MaterialTheme.typography.headlineSmallEmphasized, color = colors.content)
        }
        is Block.Text -> Text(richText(b.text), style = MaterialTheme.typography.bodyLarge, color = colors.content)
        is Block.Metric -> MetricTile(b, colors)
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, item -> ListRow(item, i == 0, i == b.items.lastIndex, colors, onAction) }
        }
        is Block.Chips -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.options.forEach { o ->
                Button(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(o.action) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(o.label, style = MaterialTheme.typography.labelLarge) }
            }
        }
        is Block.Buttons -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.buttons.forEach { spec -> ActionButton(spec, Modifier.weight(1f)) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAction(spec.action) } }
        }
        is Block.Apps -> {
            val all by catalog.apps.collectAsState()
            val resolved = remember(b.apps, all) { b.apps.mapNotNull { catalog.resolve(it) }.distinctBy { it.key } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(resolved, key = { it.key }) { app ->
                    AppTile(app, catalog, themed, onClick = { onAction(Action.Launch(app.pkg)) })
                }
            }
        }
        is Block.Input -> Unit   // rendered by SurfaceCard with its form state
        is Block.Progress -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearWavyProgressIndicator(progress = { b.value }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
            b.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.content) }
        }
    }
}

/**
 * One list row. Long text is clipped to keep lists scannable, but never lost: when it overflows, a chevron appears and
 * expands the row. Rows without an action expand on tap anywhere; rows with one keep tap = action and expand via the chevron.
 */
@Composable
private fun ListRow(item: bhupendra.morph.core.surface.Item, first: Boolean, last: Boolean, colors: ToneColors, onAction: (Action) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var expanded by rememberSaveable(item.title, item.subtitle) { mutableStateOf(false) }
    var titleCut by remember { mutableStateOf(false) }
    var subCut by remember { mutableStateOf(false) }
    // Compact rows stay one line each (they're the low-value ones); tap opens, ✕ clears.
    val canExpand = !item.compact && (expanded || titleCut || subCut)
    val toggle = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); expanded = !expanded }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.tile, RoundedCornerShape(if (first) 20.dp else 8.dp, if (first) 20.dp else 8.dp,
                if (last) 20.dp else 8.dp, if (last) 20.dp else 8.dp))
            .then(when {
                item.action != null -> Modifier.clickable { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(item.action!!) }
                canExpand -> Modifier.clickable(onClick = toggle)
                else -> Modifier
            })
            .animateContentSize()
            .padding(start = 14.dp, end = if (canExpand || item.dismiss != null) 4.dp else 14.dp,
                top = if (item.compact) 6.dp else 12.dp, bottom = if (item.compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (item.compact) 8.dp else 12.dp),
    ) {
        item.icon?.takeIf { it != "•" }?.let { ShapedEmoji(it, shapeFor(item.title), size = if (item.compact) 28.dp else 34.dp) }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = if (item.compact) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyLargeEmphasized,
                color = colors.content,
                maxLines = if (expanded) Int.MAX_VALUE else if (item.compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) titleCut = it.hasVisualOverflow })
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.content.copy(alpha = 0.72f),
                    maxLines = if (expanded) Int.MAX_VALUE else if (item.compact) 1 else 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r -> if (!expanded) subCut = r.hasVisualOverflow })
            }
        }
        item.trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.accent, maxLines = 1) }
        if (canExpand) {
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            IconButton(onClick = toggle, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ExpandMore, if (expanded) "Show less" else "Show more",
                    Modifier.rotate(turn), tint = colors.content.copy(alpha = 0.72f))
            }
        }
        item.dismiss?.let { d ->
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(d) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp), tint = colors.content.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ActionButton(spec: ButtonSpec, modifier: Modifier, onClick: () -> Unit) {
    val colors = when (spec.style) {
        ButtonStyle.Filled -> ButtonDefaults.buttonColors()
        ButtonStyle.Tonal -> ButtonDefaults.filledTonalButtonColors()
        ButtonStyle.Outlined -> ButtonDefaults.outlinedButtonColors()
    }
    Button(
        onClick = onClick, shapes = ButtonDefaults.shapes(), colors = colors, modifier = modifier,
        border = if (spec.style == ButtonStyle.Outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(spec.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Minimal **bold** support so model text reads naturally. */
fun richText(s: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    s.split("**").forEach { part ->
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
        bold = !bold
    }
}
