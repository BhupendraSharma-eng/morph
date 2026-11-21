package bhupendra.morph.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import bhupendra.morph.auto.Emergency
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.auto.UrgentAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    hasMic: () -> Boolean,
    onOpenApps: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings by vm.graph.settings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme

    // New content always slides in right above the input bar.
    LaunchedEffect(vm.canvas.size) { if (vm.canvas.isNotEmpty()) listState.animateScrollToItem(0) }
    BackHandler(enabled = vm.input.isNotEmpty() || vm.mood != AgentMood.Idle) { vm.stop(); vm.input = "" }

    // Swipe up → apps (the drawer follows your finger); swipe down → notification shade.
    // A conversation long enough to scroll owns its swipes: at its ends it just stretches ("that's everything"), and
    // the drawer comes from the input bar, the shade from the clock. Otherwise, swipe anywhere.
    // Plain areas use verticalSwipe; scrollables hand over only the overscroll they can't use (SwipeConnection).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val homeSwipe = remember(vm.drawer) {
        HomeSwipe(vm.drawer, shadeAfterPx = with(density) { 56.dp.toPx() }, shadeFlingPx = with(density) { 700.dp.toPx() }) {
            bhupendra.morph.device.SystemUi.expandNotifications(context)
        }
    }
    val homeScroll = remember(homeSwipe) { SwipeConnection(homeSwipe) }
    // A conversation only owns swipes when there's something to scroll; one that fits on screen has no "more" to
    // nudge about, so the whole home stays a swipe surface.
    val freeSwipe by remember { androidx.compose.runtime.derivedStateOf { !listState.canScrollForward && !listState.canScrollBackward } }

    // Pages: home, then Conversations to its right (swipe left to get there). Home / back / resuming return to home.
    val pager = androidx.compose.foundation.pager.rememberPagerState { 2 }
    val pageScope = rememberCoroutineScope()
    LaunchedEffect(pager) { vm.homeRequests.collect { if (pager.currentPage != 0) pager.animateScrollToPage(0) } }
    BackHandler(enabled = pager.currentPage != 0) { pageScope.launch { pager.animateScrollToPage(0) } }

    Box(
        Modifier
            .fillMaxSize()
            // Legibility scrim over the live wallpaper, heavier where content lives.
            .background(Brush.verticalGradient(listOf(
                scheme.scrim.copy(alpha = 0.10f), scheme.surface.copy(alpha = 0.45f), scheme.surface.copy(alpha = 0.82f),
            )))
    ) {
      androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        if (page == 1) { ConversationsPage(vm, onDone = { pageScope.launch { pager.animateScrollToPage(0) } }); return@HorizontalPager }
        Box(
            Modifier
                .fillMaxSize()
                .verticalSwipe(homeSwipe)
                // Double-tap empty home to sleep. Cards, chips and buttons handle their own taps first, so only taps on
                // the wallpaper, clock and blank space reach this.
                .pointerInput(settings.doubleTapSleep) {
                    if (settings.doubleTapSleep) detectTapGestures(onDoubleTap = { vm.sleepScreen() })
                }
        ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
            Header(
                onOpenSettings = onOpenSettings,
                onClear = { vm.clearCanvas() },
                showClear = vm.canvas.isNotEmpty(),
                expanded = vm.canvas.isEmpty() && !typing,
            )

            val realEmergency by UrgentAlert.active.collectAsState()
            val emergency = Demo.emergency ?: realEmergency.takeUnless { Demo.active }
            AnimatedVisibility(emergency != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                emergency?.let { EmergencyBanner(it) }
            }

            // What people need from you — first thing you see when you pick up the phone.
            val realForYou by vm.graph.forYou.items.collectAsState()
            val forYou = if (Demo.active) Demo.forYou else realForYou
            AnimatedVisibility(forYou.isNotEmpty() && emergency == null,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(if (freeSwipe) Modifier.nestedScroll(homeScroll) else Modifier) {
                    ForYouCard(forYou, vm, compact = vm.canvas.isNotEmpty())
                }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .then(if (freeSwipe) Modifier.nestedScroll(homeScroll) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                items(vm.canvas.asReversed(), key = { it.key }) { item ->
                    Box(Modifier.animateItem(
                        fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    )) {
                        Entrance(item.key, vm.entered) {
                            Box(Modifier.copyOnLongPress(item)) { CanvasItemView(item, vm, settings.themedIcons) }
                        }
                    }
                }
                if (vm.canvas.isEmpty()) {
                    vm.glance?.let { g ->
                        item(key = "glance") {
                            Entrance("glance", vm.entered) {
                                SurfaceCard(g, vm.graph.catalog, settings.themedIcons, onAction = { vm.perform(it) })
                            }
                        }
                    }
                }
            }

            // The input bar is always a launch pad: swipe up from it for apps (even out of a long text field).
            Box(Modifier.nestedScroll(homeScroll)) { IntentBar(vm, settings.themedIcons, hasMic, onOpenApps) }
        }
        }
      }
        // Easter egg: a shower of Morph's shapes, with a little haptic flourish.
        ShapeShower(vm.showerTrigger)
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        LaunchedEffect(vm.showerTrigger) {
            if (vm.showerTrigger > 0) repeat(4) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
                kotlinx.coroutines.delay(90L + it * 40L)
            }
        }
        AppDrawer(vm)
    }
}

/**
 * Springy pop-in for anything *new* on the canvas. Keys that already appeared (restored at launch, or scrolled
 * back into view) render settled — otherwise LazyColumn would replay the pop-in every time a card re-enters.
 */
@Composable
private fun Entrance(key: String, seen: MutableSet<String>, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (seen.add(key)) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) p.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f)) }
    Box(Modifier.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        val s = 0.88f + 0.12f * p.value
        scaleX = s; scaleY = s
        translationY = (1f - p.value) * 48f
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
    }) { content() }
}

/** Fling a card sideways to dismiss it. */
@Composable
private fun Dismissible(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 0.9f)
                rotationZ = offset.value / 60f
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offset.value) > size.width * 0.33f) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                offset.animateTo(if (offset.value > 0) size.width * 1.2f else -size.width * 1.2f, spring(stiffness = 600f))
                                onDismiss()
                            } else offset.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
                        }
                    },
                    onHorizontalDrag = { change, dx -> change.consume(); scope.launch { offset.snapTo(offset.value + dx) } },
                )
            }
    ) { content() }
}

@Composable
private fun CanvasItemView(item: CanvasItem, vm: HomeViewModel, themed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    when (item) {
        is CanvasItem.UserEcho -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(scheme.primary, RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        is CanvasItem.Reply -> Dismissible({ vm.dismiss(item.key) }) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    richText(item.text.ifEmpty { "…" } + if (item.streaming) " ▍" else ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(scheme.surfaceContainerHigh.copy(alpha = 0.95f), RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
        is CanvasItem.Card -> Dismissible({ vm.dismiss(item.key) }) {
            val waiting by vm.graph.runtime.waiting.collectAsState()
            SurfaceCard(
                item.surface, vm.graph.catalog, themed,
                onAction = { vm.perform(it, from = item.key) },
                onSubmit = { values -> vm.graph.runtime.answer(item.surface.id, values) },
                waiting = item.surface.id in waiting || item.surface.id in Demo.waiting.value,
            )
        }
        is CanvasItem.Notice -> Dismissible({ vm.dismiss(item.key) }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(if (item.error) scheme.errorContainer else scheme.secondaryContainer, RoundedCornerShape(28.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge,
                    color = if (item.error) scheme.onErrorContainer else scheme.onSecondaryContainer)
                item.action?.let { (label, action) ->
                    Button(onClick = { vm.perform(action) }, shapes = ButtonDefaults.shapes()) { Text(label) }
                }
            }
        }
    }
}

/** Someone needs you right now. Pulses until acknowledged. */
@Composable
private fun EmergencyBanner(e: Emergency) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "sos").animateFloat(
        1f, 1.03f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .fillMaxWidth()
            .background(scheme.error, RoundedCornerShape(32.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShapedEmoji("🚨", androidx.compose.material3.MaterialShapes.SoftBurst, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("${e.who} needs you", style = MaterialTheme.typography.titleLargeEmphasized, color = scheme.onError)
                Text("${e.reason} · ${e.app}", style = MaterialTheme.typography.labelLarge, color = scheme.onError.copy(alpha = 0.85f))
            }
        }
        Text("“${e.message}”", style = MaterialTheme.typography.bodyLarge, color = scheme.onError, maxLines = 4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    UrgentAlert.acknowledge(context)
                    e.open?.let { bhupendra.morph.device.PendingIntents.sendFromForeground(context, it) }
                },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.onError, contentColor = scheme.error),
                modifier = Modifier.weight(1f),
            ) { Text("Open chat") }
            Button(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); UrgentAlert.acknowledge(context) },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                modifier = Modifier.weight(1f),
            ) { Text("I'm on it") }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit, onClear: () -> Unit, showClear: Boolean, expanded: Boolean, modifier: Modifier = Modifier) {
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(1000L * (60 - value.second).coerceAtLeast(1)) }
    }
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val clock = remember(now.hour) {
        DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm")
    }
    // Expanded on a calm home: the big clock is the glance. Collapsed whenever something's happening: one slim row
    // with the date only — the status bar already shows the time, so it's never on screen twice while you work.
    Row(
        modifier.fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = if (expanded) 20.dp else 4.dp, bottom = 4.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AnimatedVisibility(expanded,
                enter = fadeIn() + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Top)) {
                Text(now.format(clock), style = MaterialTheme.typography.displayLargeEmphasized, color = scheme.onSurface)
            }
            Text(
                now.format(DateTimeFormatter.ofPattern(if (expanded) "EEEE, d MMMM" else "EEE, d MMM")),
                style = if (expanded) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleSmallEmphasized,
                color = scheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(showClear, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            FilledTonalIconButton(onClick = onClear, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear canvas") }
        }
        IconButton(onClick = onOpenSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings", tint = scheme.onSurfaceVariant) }
    }
}

@Composable
private fun IntentBar(vm: HomeViewModel, themed: Boolean, hasMic: () -> Boolean, onOpenApps: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val apps by vm.graph.catalog.apps.collectAsState()
    // Universal search runs off the main thread on every keystroke (debounced a hair so fast typing stays smooth).
    val results by produceState(bhupendra.morph.device.SearchResults.Empty, vm.input, apps) {
        if (vm.input.isBlank()) { value = bhupendra.morph.device.SearchResults.Empty; return@produceState }
        delay(40)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.graph.search.search(vm.input) }
    }
    val busy = vm.mood is AgentMood.Thinking || vm.mood is AgentMood.Acting || vm.mood == AgentMood.Speaking
    var focused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Instant local results while you type — no AI round-trip needed to open things.
        // weight(fill = false): the bar below is measured first and always keeps its height;
        // the panel gets only the space that's left and scrolls inside it.
        AnimatedVisibility(
            !results.isEmpty,
            modifier = Modifier.weight(1f, fill = false),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SearchPanel(results, vm, themed, onDone = { vm.input = ""; keyboard?.hide() })
        }

        AnimatedVisibility(vm.mood is AgentMood.Acting || vm.mood == AgentMood.Listening, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val label = when (val m = vm.mood) { is AgentMood.Acting -> m.label + "…"; AgentMood.Listening -> "Listening…"; else -> "" }
            Text(label, style = MaterialTheme.typography.labelLargeEmphasized, color = scheme.primary, modifier = Modifier.padding(start = 20.dp))
        }

        // Grows with long messages (up to 5 lines): a pill at one line, a soft card when taller. The orb and the
        // action button stay on the bottom row, like a messaging app.
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
            color = scheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                // The orb is the voice button: tap to talk, tap again to finish.
                Box(
                    Modifier.size(52.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (vm.mood == AgentMood.Listening) vm.stopListening() else vm.startListening(hasMic())
                    },
                    contentAlignment = Alignment.Center,
                ) { AgentOrb(vm.mood, vm.voiceLevel, size = 44.dp) }

                Spacer(Modifier.size(10.dp))
                Box(Modifier.weight(1f).heightIn(min = 52.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (vm.input.isEmpty()) Text(
                        if (vm.mood == AgentMood.Listening) "Go ahead, I'm listening" else if (focused) "Open, ask, or do anything…" else "What do you want to do?",
                        style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        singleLine = false,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.graph.prewarm() },
                    )
                }

                Spacer(Modifier.size(12.dp))   // breathing room between the text and the button
                val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                AnimatedContent(
                    targetState = when { busy -> 2; vm.input.isNotBlank() -> 1; else -> 0 },
                    transitionSpec = { (scaleIn(popSpec) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                    label = "barAction",
                ) { state ->
                    when (state) {
                        2 -> FilledTonalIconButton(onClick = { vm.stop() }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Stop, "Stop") }
                        1 -> FilledIconButton(
                            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.submit() },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(52.dp),
                        ) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                        else -> FilledTonalIconButton(
                            onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onOpenApps() },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(52.dp),
                        ) { Icon(Icons.Rounded.Apps, "All apps") }
                    }
                }
            }
        }
    }
}

