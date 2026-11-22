package bhupendra.morph.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
    // "Clear N that can wait": the rows being cleared sweep out one after another, then the clear happens.
    val sweep = remember { mutableStateOf<Set<String>>(emptySet()) }
    val sweepScope = rememberCoroutineScope()
    val outerAction = onAction
    @Suppress("NAME_SHADOWING")
    val onAction: (Action) -> Unit = { a ->
        if (a is Action.DismissNotifications && sweep.value.isEmpty()) {
            sweep.value = a.keys.toSet()
            sweepScope.launch {
                kotlinx.coroutines.delay(SWEEP_STAGGER_MS * 4 + LEAVE_MS + 120)
                outerAction(a); sweep.value = emptySet()
            }
        } else outerAction(a)
    }
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
                is Block -> androidx.compose.runtime.CompositionLocalProvider(LocalSweep provides sweep.value) {
                    BlockView(g, colors, catalog, themedIcons, onAction, rawAction = outerAction)
                }
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
private fun BlockView(b: Block, colors: ToneColors, catalog: AppCatalog, themed: Boolean, onAction: (Action) -> Unit,
                      rawAction: (Action) -> Unit = onAction) {
    val haptics = LocalHapticFeedback.current
    when (b) {
        is Block.Heading -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.eyebrow?.let { Text(it, style = MaterialTheme.typography.labelLargeEmphasized, color = colors.accent) }
            Text(b.text, style = MaterialTheme.typography.headlineSmallEmphasized, color = colors.content)
        }
        is Block.Text -> Text(richText(b.text), style = MaterialTheme.typography.bodyLarge, color = colors.content)
        is Block.Metric -> MetricTile(b, colors)
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, item ->
                androidx.compose.runtime.key(item.title, item.dismiss) { ListRow(item, i == 0, i == b.items.lastIndex, colors, onAction, rawAction, i) }
            }
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
private fun ListRow(
    item: bhupendra.morph.core.surface.Item, first: Boolean, last: Boolean, colors: ToneColors, onAction: (Action) -> Unit,
    /** Bypasses the card's sweep routing: a row's own ✕ animates itself, then clears. */
    rawAction: (Action) -> Unit = onAction,
    index: Int = 0,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val exit = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    var gone by remember { mutableStateOf(false) }
    val particles = remember { Particle.burst(kotlin.random.Random.nextInt()) }   // rolled once, reused every frame
    val sparkle = remember { androidx.compose.ui.graphics.Path() }
    val burstColors = listOf(colors.accent, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary, colors.content)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Battery saver or "remove animations" on: no particles, and a quicker, plainer exit.
    val lite = remember {
        context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true ||
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // Tilt, slide and shrink away while a little burst of shapes pops from the ✕; then the gap closes.
    suspend fun leave(delayMs: Long) {
        kotlinx.coroutines.delay(delayMs)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        if (!lite) scope.launch { burst.snapTo(0f); burst.animateTo(1f, tween(BURST_MS, easing = LinearOutSlowInEasing)) }
        exit.animateTo(1f, tween(if (lite) LEAVE_MS / 2 else LEAVE_MS, easing = FastOutSlowInEasing))
        gone = true
    }
    val dismissKeys = (item.dismiss as? Action.DismissNotifications)?.keys
    val sweeping = LocalSweep.current
    LaunchedEffect(sweeping) {
        if (dismissKeys != null && sweeping.isNotEmpty() && sweeping.containsAll(dismissKeys) && exit.value == 0f) leave(index * SWEEP_STAGGER_MS)
    }
    androidx.compose.animation.AnimatedVisibility(!gone, enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + androidx.compose.animation.fadeOut()) {
    Box(Modifier.fillMaxWidth().drawWithContent {
        drawContent()
        if (burst.value in 0.001f..0.999f) drawBurst(Offset(size.width - 26.dp.toPx(), size.height / 2), burst.value, particles, burstColors, sparkle)
    }) {
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
            .graphicsLayer {
                val e = exit.value
                translationX = e * size.width * 0.6f; rotationZ = e * 7f
                scaleX = 1f - 0.1f * e; scaleY = 1f - 0.1f * e; alpha = 1f - e
            }
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
            IconButton(onClick = {
                if (exit.value == 0f) scope.launch {
                    leave(0)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    rawAction(d)
                }
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp), tint = colors.content.copy(alpha = 0.6f))
            }
        }
    }
    }
    }
}

/** Keys being swept away by a "clear" on this card; rows whose notifications are all in it animate out in turn. */
private val LocalSweep = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }
private const val LEAVE_MS = 340
private const val BURST_MS = 650
private const val SWEEP_STAGGER_MS = 90L

/** One bit of the burst, rolled once per row. */
private class Particle(val cos: Float, val sin: Float, val speed: Float, val size: Float, val spin: Float, val kind: Int) {
    companion object {
        fun burst(seed: Int): List<Particle> {
            val rnd = kotlin.random.Random(seed)
            return List(14) { i ->
                val a = (rnd.nextFloat() * 300f - 240f) * (Math.PI / 180f).toFloat()   // mostly up and to the sides
                Particle(kotlin.math.cos(a), kotlin.math.sin(a), 260f + rnd.nextFloat() * 420f, 3.5f + rnd.nextFloat() * 4f,
                    rnd.nextFloat() * 360f, i % 3)
            }
        }
    }
}

/**
 * A small celebratory pop: dots, rounded squares and 4-point sparkles thrown out from [origin] with a touch of
 * gravity, spinning and fading. Draw-phase only (no recomposition), nothing allocated per frame — [path] is reused.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBurst(
    origin: Offset, t: Float, particles: List<Particle>, palette: List<Color>, path: androidx.compose.ui.graphics.Path,
) {
    val time = t * BURST_MS / 1000f
    val fall = 900f * time * time
    val alpha = (1f - t) * 0.95f
    particles.forEachIndexed { i, q ->
        val p = Offset(origin.x + q.cos * q.speed * time, origin.y + q.sin * q.speed * time + fall)
        val c = palette[i % palette.size].copy(alpha = alpha)
        val r = q.size * density * (1f - 0.5f * t)
        val spin = q.spin + t * 540f
        when (q.kind) {
            0 -> drawCircle(c, r, p)
            1 -> rotate(spin, p) { drawRoundRect(c, Offset(p.x - r, p.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), androidx.compose.ui.geometry.CornerRadius(r * 0.45f)) }
            else -> rotate(spin, p) {
                path.reset()
                path.moveTo(p.x, p.y - 1.8f * r); path.quadraticTo(p.x, p.y, p.x + 1.8f * r, p.y)
                path.quadraticTo(p.x, p.y, p.x, p.y + 1.8f * r); path.quadraticTo(p.x, p.y, p.x - 1.8f * r, p.y)
                path.quadraticTo(p.x, p.y, p.x, p.y - 1.8f * r); path.close()
                drawPath(path, c)
            }
        }
    }
}
