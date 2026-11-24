package bhupendra.morphy.assist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import bhupendra.morphy.agent.Busy
import bhupendra.morphy.core.surface.Action
import bhupendra.morphy.data.CanvasItem
import bhupendra.morphy.ui.AgentMood
import bhupendra.morphy.ui.AgentOrb
import bhupendra.morphy.ui.SurfaceCard
import bhupendra.morphy.ui.copyOnLongPress
import bhupendra.morphy.ui.richText

/**
 * The floating assistant: a single expressive card at the bottom of the screen, the app you were in still
 * visible above a soft scrim. Voice-first; replies and agent-built cards render inline.
 */
@Composable
fun AssistantPanel(s: AssistantSession) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(s.shownAt) { entered = false; entered = true }

    val all = s.graph.canvas.items
    val items = all.filter { it.key !in s.since }
    val waiting by s.graph.runtime.waiting.collectAsState()
    var fieldFocused by remember { mutableStateOf(false) }
    val settings by s.graph.settings.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(items.size, (items.lastOrNull() as? CanvasItem.Reply)?.text?.length) {
        if (items.isNotEmpty()) list.animateScrollToItem(items.lastIndex)
    }

    val mood = when {
        s.listening -> AgentMood.Listening
        else -> when (val b = s.busy) {
            Busy.Idle -> AgentMood.Idle
            Busy.Thinking -> AgentMood.Thinking
            is Busy.Acting -> AgentMood.Acting(b.label)
            Busy.Speaking -> AgentMood.Speaking
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to scheme.scrim.copy(alpha = 0.18f), 1f to scheme.scrim.copy(alpha = 0.55f)))
            .clickable(remember { MutableInteractionSource() }, indication = null) { s.hide() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(entered, enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 2 } + fadeIn()) {
            Surface(
                shape = RoundedCornerShape(36.dp),
                color = scheme.surfaceContainer,
                tonalElevation = 4.dp,
                shadowElevation = 24.dp,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .navigationBarsPadding()
                    // Only make room for *our* keyboard: another window's IME (e.g. Morphy's home bar) must not move us.
                    .then(if (fieldFocused) Modifier.imePadding() else Modifier)
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { /* swallow: don't dismiss */ },
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                    // Handle + header: swipe down to dismiss.
                    Column(
                        Modifier.fillMaxWidth().pointerInput(Unit) {
                            detectVerticalDragGestures { _, dy -> if (dy > 24) s.hide() }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).background(scheme.outlineVariant, CircleShape))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Morphy", style = MaterialTheme.typography.titleMediumEmphasized, color = scheme.onSurface)
                            Spacer(Modifier.size(8.dp))
                            s.screenApp?.let { app ->
                                Text(
                                    "Looking at $app",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSecondaryContainer,
                                    modifier = Modifier.background(scheme.secondaryContainer, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { s.openMorphy() }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Continue in Morphy", tint = scheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Conversation for this summon (also lands on the home canvas).
                    if (items.isEmpty()) {
                        Suggestions(s)
                    } else {
                        LazyColumn(
                            state = list,
                            modifier = Modifier.weight(1f, fill = false),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(items, key = { it.key }) { item ->
                                Box(Modifier.animateItem().copyOnLongPress(item)) {
                                    when (item) {
                                        is CanvasItem.UserEcho -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimary,
                                                modifier = Modifier.widthIn(max = 280.dp).background(scheme.primary, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                                                    .padding(horizontal = 16.dp, vertical = 10.dp))
                                        }
                                        is CanvasItem.Reply -> Text(
                                            richText(item.text.ifEmpty { "…" } + if (item.streaming) " ▍" else ""),
                                            style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                        is CanvasItem.Card -> SurfaceCard(
                                            item.surface, s.graph.catalog, settings.themedIcons,
                                            onAction = s::perform,
                                            onSubmit = { v -> s.graph.runtime.answer(item.surface.id, v) },
                                            waiting = item.surface.id in waiting,
                                        )
                                        is CanvasItem.Notice -> Text(item.text, style = MaterialTheme.typography.bodyMedium,
                                            color = if (item.error) scheme.error else scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Live status.
                    AnimatedVisibility(mood is AgentMood.Acting || mood == AgentMood.Listening,
                        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Text(
                            when (mood) { is AgentMood.Acting -> mood.label + "…"; AgentMood.Listening -> "Listening…"; else -> "" },
                            style = MaterialTheme.typography.labelLargeEmphasized, color = scheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                        )
                    }

                    // Input row: orb = mic.
                    Surface(shape = CircleShape, color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm); s.toggleMic()
                                },
                                contentAlignment = Alignment.Center,
                            ) { AgentOrb(mood, s.voiceLevel, size = 40.dp) }
                            Spacer(Modifier.size(8.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (s.input.isEmpty()) Text(
                                    if (s.listening) "Go ahead…" else if (s.hasScreen) "Ask about this screen, or anything" else "Ask or do anything",
                                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                                )
                                BasicTextField(
                                    value = s.input, onValueChange = { s.input = it }, singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                                    cursorBrush = SolidColor(scheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { s.submit() }),
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { fieldFocused = it.isFocused },
                                )
                            }
                            val working = mood is AgentMood.Thinking || mood is AgentMood.Acting || mood == AgentMood.Speaking
                            AnimatedContent(
                                targetState = when { working -> 2; s.input.isNotBlank() -> 1; else -> 0 },
                                transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send",
                            ) { st ->
                                when (st) {
                                    2 -> FilledTonalIconButton(onClick = { s.stop() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Stop, "Stop") }
                                    1 -> FilledIconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); s.submit() },
                                        shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                                    else -> Spacer(Modifier.size(0.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Context-aware starters: about the screen when we can see it, everyday things otherwise. */
@Composable
private fun Suggestions(s: AssistantSession) {
    val options = if (s.hasScreen) listOf(
        "Explain this screen" to "Explain what's on this screen",
        "Summarize this" to "Summarize what's on this screen",
        "Reply for me" to "Draft a good reply to this and send it",
        "Translate" to "Translate what's on this screen to English",
        "Remember this" to "Remember the important details on this screen",
    ) else listOf(
        "What did I miss?" to "Brief me on my notifications — what matters?",
        "Timer 10 min" to "timer 10 min",
        "Play some music" to "Play something relaxing",
        "Torch" to "torch",
    )
    FlowRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, prompt) ->
            Button(onClick = { s.perform(Action.Say(prompt)) }, shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.filledTonalButtonColors(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
