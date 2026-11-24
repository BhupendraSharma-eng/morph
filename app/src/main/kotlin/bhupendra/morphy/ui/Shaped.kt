package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import bhupendra.morphy.device.AppCatalog
import bhupendra.morphy.device.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An emoji framed in an expressive Material shape. */
@Composable
fun ShapedEmoji(emoji: String, polygon: RoundedPolygon, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val shape = polygon.toShape()
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.48f).sp)
    }
}

/** What long-pressing any app tile does, anywhere in the UI (opens the app menu). */
val LocalAppMenu = androidx.compose.runtime.staticCompositionLocalOf<(AppEntry) -> Unit> { {} }

/** App icon clipped into a friendly Material shape, with a squishy spring press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: AppEntry,
    catalog: AppCatalog,
    themed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    showLabel: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val px = with(LocalDensity.current) { iconSize.roundToPx() }
    val icon by produceState(catalog.cachedIcon(app, themed), app.key, themed) {
        if (value == null) value = withContext(Dispatchers.IO) { catalog.loadIcon(app, px, themed) }
    }
    val mask = remember(app.pkg, px) { catalog.shapeMask(app, px) }
    val view = androidx.compose.ui.platform.LocalView.current
    val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
    val openMenu = LocalAppMenu.current
    val work = remember(app.key) { catalog.isWork(app) }

    Column(
        modifier
            .width(iconSize + 20.dp)
            .combinedClickable(
                interactionSource = interaction, indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    coords[0]?.takeIf { it.isAttached }?.boundsInWindow()?.let { b ->
                        bhupendra.morphy.device.LaunchOrigin.set(view, android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                    }
                    onClick()
                },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); openMenu(app) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(contentAlignment = Alignment.BottomEnd) {
        // Two plain bitmaps — the shape is baked in, so nothing is clipped per frame.
        Box(
            Modifier
                .size(iconSize)
                .onPlaced { coords[0] = it }
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            Image(mask, null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(
                if (themed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest))
            icon?.let { i ->
                Image(i.image, app.label, Modifier.fillMaxSize(),
                    colorFilter = if (i.tintable) ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer) else null)
            }
        }
        if (work) Text("💼", fontSize = (iconSize.value * 0.28f).sp,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape).padding(2.dp))
      }
        if (showLabel) Text(
            app.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
