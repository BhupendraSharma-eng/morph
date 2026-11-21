package bhupendra.morph.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.data.CanvasItem

/** A card as clean, shareable plain text: title, headings, text, metrics, list items, options. */
fun Surface.plainText(): String = buildString {
    title?.let { appendLine(listOfNotNull(icon, it).joinToString(" ")) }
    blocks.forEach { b ->
        when (b) {
            is Block.Heading -> { b.eyebrow?.let { appendLine(it) }; appendLine(b.text) }
            is Block.Text -> appendLine(b.text.replace("**", ""))
            is Block.Metric -> appendLine("${b.value} — ${b.label}")
            is Block.ListBlock -> b.items.forEach { i ->
                appendLine("• ${i.title}" + (i.subtitle?.let { " — $it" } ?: "") + (i.trailing?.let { " ($it)" } ?: ""))
            }
            is Block.Chips -> appendLine(b.options.joinToString(" · ") { it.label })
            is Block.Buttons -> appendLine(b.buttons.joinToString(" · ") { it.label })
            is Block.Apps -> appendLine(b.apps.joinToString(", "))
            is Block.Progress -> appendLine("${(b.value * 100).toInt()}%" + (b.label?.let { " $it" } ?: ""))
            is Block.Input -> appendLine("${b.label}: ${b.initial ?: b.options.joinToString("/")}")
        }
    }
}.trim()

fun CanvasItem.plainText(): String = when (this) {
    is CanvasItem.UserEcho -> text
    is CanvasItem.Reply -> text
    is CanvasItem.Card -> surface.plainText()
    is CanvasItem.Notice -> text
}

/** Android 13+ shows its own "Copied" confirmation, so no toast is needed. */
fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Morph", text))
}

/** Long-press anywhere on a message or card to copy it. Taps still reach buttons inside. */
@Composable
fun Modifier.copyOnLongPress(item: CanvasItem): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return this.pointerInput(item.key, item.plainText().length) {
        detectTapGestures(onLongPress = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            copyToClipboard(context, item.plainText())
        })
    }
}
