package bhupendra.morph.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.graphics.shapes.RoundedPolygon

@Composable
fun MorphTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    MaterialExpressiveTheme(
        // Colors come from the user's wallpaper — the home screen always matches their phone.
        colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        // Springy, physical motion everywhere: the core of M3 Expressive.
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        content = content,
    )
}

/** Soft, friendly shapes used to frame app icons and emoji. Assigned deterministically per item. */
val FriendlyShapes: List<RoundedPolygon> = listOf(
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst,
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Pill,
    MaterialShapes.Gem,
)

fun shapeFor(seed: String): RoundedPolygon = FriendlyShapes[(seed.hashCode() and 0x7fffffff) % FriendlyShapes.size]
