package bhupendra.morph.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app drawer's position, 0 = hidden, 1 = open. Driven directly by the finger while dragging and settled by a
 * spring on release, so the drawer always follows your hand. Read only inside draw/layout lambdas where possible,
 * so dragging redraws without recomposing.
 */
@Stable
class DrawerState(private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(0f)
        private set
    internal var heightPx = 2000f
    internal var flingPx = 1500f
    private var settling: Job? = null

    val isOpen: Boolean get() = value >= 1f

    fun dragBy(dy: Float) {
        settling?.cancel()
        value = (value - dy / heightPx).coerceIn(0f, 1f)
    }

    /**
     * Release: a flick decides; otherwise distance does, biased toward the direction you were going —
     * opening commits past 25%, closing from open past 25% of the way down.
     */
    fun settle(velocityY: Float) {
        val target = when {
            velocityY < -flingPx -> 1f
            velocityY > flingPx -> 0f
            velocityY < 0f -> if (value > 0.25f) 1f else 0f
            else -> if (value < 0.75f) 0f else 1f
        }
        animateTo(target, -velocityY / heightPx)
    }

    fun open() = animateTo(1f, 0f)
    fun close() = animateTo(0f, 0f)
    fun snapClosed() { settling?.cancel(); value = 0f }

    private fun animateTo(target: Float, velocity: Float) {
        settling?.cancel()
        // animate() needs a frame clock; the ViewModel scope has none, so run on Compose's UI dispatcher.
        settling = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            animate(value, target, velocity, spring(dampingRatio = 0.86f, stiffness = 520f)) { v, _ -> value = v.coerceIn(0f, 1f) }
        }
    }
}

/** Something that wants vertical swipes. [drag] returns true to claim the gesture (it then gets the rest of it). */
interface SwipeHandler {
    fun drag(dy: Float): Boolean
    fun release(velocityY: Float)
}

/**
 * Home: up opens the drawer (following the finger), down pulls the notification shade.
 * The first movement decides which, so a gesture never does both.
 */
class HomeSwipe(
    private val drawer: DrawerState,
    private val shadeAfterPx: Float,
    private val shadeFlingPx: Float,
    private val expandShade: () -> Unit,
) : SwipeHandler {
    private enum class Mode { None, Drawer, Shade }
    private var mode = Mode.None
    private var pulled = 0f
    private var fired = false

    override fun drag(dy: Float): Boolean {
        if (mode == Mode.None) mode = if (dy < 0f || drawer.value > 0f) Mode.Drawer else Mode.Shade
        if (mode == Mode.Drawer) drawer.dragBy(dy)
        else {
            pulled += dy
            if (!fired && pulled > shadeAfterPx) { fired = true; expandShade() }
        }
        return true
    }

    override fun release(velocityY: Float) {
        when (mode) {
            Mode.Drawer -> drawer.settle(velocityY)
            Mode.Shade -> if (!fired && velocityY > shadeFlingPx) expandShade()
            Mode.None -> Unit
        }
        mode = Mode.None; pulled = 0f; fired = false
    }
}

/** Inside the drawer: pulling down (from the top of the list, or on the header) closes it. */
class DrawerSwipe(private val drawer: DrawerState) : SwipeHandler {
    private var active = false
    override fun drag(dy: Float): Boolean {
        if (!active && dy <= 0f && drawer.isOpen) return false   // swiping up on an open drawer: nothing to do
        active = true
        drawer.dragBy(dy)
        return true
    }
    override fun release(velocityY: Float) { if (active) drawer.settle(velocityY); active = false }
}

/**
 * Gives a [SwipeHandler] the part of a scroll that a list *couldn't use*: overscroll at the list's edge.
 * Only when this gesture hasn't scrolled the list at all — reaching the end mid-scroll never pops anything open.
 * Once claimed, the handler owns the rest of the gesture (the list stops moving).
 */
class SwipeConnection(private val handler: SwipeHandler) : NestedScrollConnection {
    private var owning = false
    private var scrolled = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (owning && source == NestedScrollSource.UserInput) { handler.drag(available.y); return Offset(0f, available.y) }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y != 0f) scrolled = true
        if (!scrolled && !owning && available.y != 0f && handler.drag(available.y)) {
            owning = true
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val was = owning
        if (owning) handler.release(available.y)
        owning = false; scrolled = false
        return if (was) available else Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        owning = false; scrolled = false
        return Velocity.Zero
    }
}

/**
 * Vertical swipes on areas that don't scroll. Claims a drag only after the touch slop is crossed *vertically*,
 * so horizontal gestures (dismissing cards, sliders) and taps are untouched, and it yields to any child that
 * scrolls (those report through [SwipeConnection] instead).
 */
fun Modifier.verticalSwipe(handler: SwipeHandler): Modifier = pointerInput(handler) {
    val tracker = VelocityTracker()
    var owning = false
    var y = 0f   // finger position in a fixed frame: this node may itself be moving with the finger
    detectVerticalDragGestures(
        onDragStart = { tracker.resetTracking(); owning = false; y = 0f },
        onDragEnd = { if (owning) handler.release(tracker.calculateVelocity().y); owning = false },
        onDragCancel = { if (owning) handler.release(0f); owning = false },
    ) { change, dy ->
        y += dy
        tracker.addPosition(change.uptimeMillis, Offset(0f, y))
        if (owning || handler.drag(dy)) { owning = true; change.consume() }
    }
}

/**
 * The app drawer: an in-window panel (not a dialog), so opening it costs no new window and it can track the finger.
 * It's composed shortly after launch while hidden, so the first open has its tiles and icons ready.
 */
@Composable
fun AppDrawer(vm: HomeViewModel) {
    val drawer = vm.drawer
    val visible by remember { derivedStateOf { drawer.value > 0f } }
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1200); warm = true }
    if (!warm && !visible) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    val settings by vm.graph.settings.state.collectAsState()
    val apps by vm.graph.catalog.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) { if (query.isBlank()) apps else vm.graph.catalog.search(query, 60) }
    val grid = rememberLazyGridState()
    val swipe = remember(drawer) { DrawerSwipe(drawer) }
    val connection = remember(swipe) { SwipeConnection(swipe) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Opening drops the home input's focus so its keyboard goes away (swiping up from a focused input bar).
    // Closing resets the drawer for next time: no stale search, keyboard down, back at the top.
    LaunchedEffect(visible) {
        focus.clearFocus(force = true); keyboard?.hide()
        if (!visible) { query = ""; if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0) }
    }
    BackHandler(enabled = visible) { focus.clearFocus(); drawer.close() }

    Box(Modifier.fillMaxSize().onSizeChanged {
        drawer.heightPx = it.height.toFloat().coerceAtLeast(1f)
        drawer.flingPx = with(density) { 350.dp.toPx() }
    }) {
        if (visible) Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = drawer.value }
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { drawer.close() } }
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (1f - drawer.value) * size.height }
                .background(scheme.surfaceContainerLow, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .nestedScroll(connection)
                .verticalSwipe(swipe)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(scheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
            }
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search ${apps.size} apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.key }) { app ->
                    AppTile(app, vm.graph.catalog, settings.themedIcons, modifier = Modifier.animateItem(), onClick = {
                        vm.graph.catalog.launch(app)
                        // The app is zooming in from the icon; tuck the drawer away underneath it.
                        scope.launch { delay(400); drawer.snapClosed() }
                    })
                }
            }
        }
    }
}
