package bhupendra.morph.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

private fun shapeForMood(m: AgentMood): RoundedPolygon = when (m) {
    AgentMood.Idle -> MaterialShapes.Cookie9Sided
    AgentMood.Listening -> MaterialShapes.SoftBurst
    AgentMood.Speaking -> MaterialShapes.Flower
    AgentMood.Thinking, is AgentMood.Acting -> MaterialShapes.Sunny
}

/**
 * Morph's face. It morphs between Material shapes with spring physics as its mood changes,
 * breathes when idle, pulses with your voice, and spins up a LoadingIndicator while it works.
 */
@Composable
fun AgentOrb(mood: AgentMood, voiceLevel: Float, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val scheme = MaterialTheme.colorScheme
    var from by remember { mutableStateOf(shapeForMood(mood)) }
    var to by remember { mutableStateOf(shapeForMood(mood)) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(mood) {
        val target = shapeForMood(mood)
        if (target != to) {
            from = to; to = target
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
        }
    }
    val morph = remember(from, to) { Morph(from, to) }

    // Battery: an always-spinning orb kept the home screen rendering every frame, forever. Now it's only
    // alive while it means something (listening/speaking, or briefly after a state change), then rests still.
    var lively by remember { mutableStateOf(true) }
    LaunchedEffect(mood) {
        lively = true
        if (mood == AgentMood.Idle) { kotlinx.coroutines.delay(6_000); lively = false }
    }
    val animate = lively || mood == AgentMood.Listening || mood == AgentMood.Speaking
    var spin = 0f
    var breathe = 1f
    if (animate) {
        val infinite = rememberInfiniteTransition(label = "orb")
        spin = infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "spin").value
        breathe = infinite.animateFloat(0.94f, 1.0f, infiniteRepeatable(tween(2_200), RepeatMode.Reverse), label = "breathe").value
    }
    val level = remember { Animatable(0f) }
    LaunchedEffect(voiceLevel) { level.animateTo(voiceLevel, spring(dampingRatio = 0.6f, stiffness = 400f)) }

    val working = mood is AgentMood.Thinking || mood is AgentMood.Acting
    val path = remember { Path() }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (working) {
            LoadingIndicator(Modifier.fillMaxSize(), color = scheme.primary)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val scale = when (mood) {
                    AgentMood.Listening -> 0.86f + 0.22f * level.value
                    AgentMood.Idle -> breathe
                    else -> 1f
                }
                path.rewind()
                morph.toPath(progress.value, path)
                // Fit whatever coordinate space the polygon uses into this box, centered.
                val b = path.getBounds()
                if (b.width <= 0f || b.height <= 0f) return@Canvas
                path.translate(Offset(-b.center.x, -b.center.y))
                val k = this.size.minDimension * scale / maxOf(b.width, b.height)
                val half = maxOf(b.width, b.height) / 2f
                withTransform({
                    translate(this@Canvas.size.width / 2f, this@Canvas.size.height / 2f)
                    rotate(if (mood == AgentMood.Listening) 0f else spin, pivot = Offset.Zero)
                    scale(k, k, pivot = Offset.Zero)
                }) {
                    drawPath(path, Brush.linearGradient(
                        listOf(scheme.primary, scheme.tertiary, scheme.secondary),
                        start = Offset(-half, -half), end = Offset(half, half),
                    ))
                }
            }
        }
    }
}
