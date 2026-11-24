package bhupendra.morphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The maker's mark: sending "bhupendra" on home. */
object EasterEgg {
    const val WORD = "bhupendra"
    const val URL = "https://github.com/BhupendraSharma-eng"
    fun matches(text: String) = text.trim().lowercase() == WORD
}

private class Piece(val shape: Int, val color: Int, val x: Float, val delay: Float, val size: Float, val spin: Float, val sway: Float)

/**
 * A shower of Morphy's expressive shapes in the wallpaper's colours, tumbling down the home screen. One Canvas, one
 * clock: ~40 pre-built paths transformed per frame, nothing recomposes. Plays once per [trigger] change.
 */
@Composable
fun ShapeShower(trigger: Int) {
    if (trigger == 0) return
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer, scheme.tertiaryContainer, scheme.inversePrimary)
    // Unit-size outlines of the friendly shapes (same ones the app icons wear).
    val paths: List<Path> = remember {
        FriendlyShapes.map { poly ->
            val p = android.graphics.Path()
            poly.cubics.firstOrNull()?.let { p.moveTo(it.anchor0X, it.anchor0Y) }
            poly.cubics.forEach { c -> p.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y) }
            p.close()
            val b = android.graphics.RectF().also { p.computeBounds(it, true) }
            p.transform(android.graphics.Matrix().apply {
                setRectToRect(b, android.graphics.RectF(-0.5f, -0.5f, 0.5f, 0.5f), android.graphics.Matrix.ScaleToFit.FILL)
            })
            p.asComposePath()
        }
    }
    val pieces = remember(trigger) {
        val r = Random(trigger)
        List(42) {
            Piece(r.nextInt(paths.size), r.nextInt(colors.size), r.nextFloat(), r.nextFloat() * 0.45f,
                28f + r.nextFloat() * 46f, (r.nextFloat() - 0.5f) * 900f, 0.02f + r.nextFloat() * 0.05f)
        }
    }
    val t = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { t.animateTo(1f, tween(3200, easing = LinearEasing)) }
    if (t.value >= 1f) return

    Canvas(Modifier.fillMaxSize()) {
        val time = t.value
        pieces.forEach { p ->
            val life = ((time - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (life <= 0f) return@forEach
            val fall = life * life * 0.6f + life * 0.6f                       // gentle start, then gravity
            val x = (p.x + sin(life * 2 * PI.toFloat() * 1.5f + p.x * 10f) * p.sway) * size.width
            val y = -80f + fall * (size.height + 160f)
            val px = p.size * density / 2.6f
            val pop = if (life < 0.08f) life / 0.08f else 1f                   // pops into existence
            val alpha = if (life > 0.8f) (1f - life) / 0.2f else 1f
            translate(x, y) {
                rotate(p.spin * life, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    scale(px * pop, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawPath(paths[p.shape], colors[p.color], alpha = alpha.coerceIn(0f, 1f))
                    }
                }
            }
        }
    }
}
