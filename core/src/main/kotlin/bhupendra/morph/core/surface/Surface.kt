package bhupendra.morph.core.surface

import java.util.UUID

/**
 * A Surface is a small, self-contained piece of UI the agent composes at runtime.
 * The agent never writes code — it picks from this vocabulary, and the launcher renders it.
 * New block types can be added here + in the renderer without touching the agent loop.
 */
data class Surface(
    val id: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val icon: String? = null,
    val tone: Tone = Tone.Neutral,
    val blocks: List<Block>,
)

enum class Tone { Neutral, Primary, Secondary, Tertiary, Error;
    companion object {
        fun parse(s: String?): Tone = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: Neutral
    }
}

sealed interface Block {
    /** Big expressive headline, optionally with a small eyebrow label above it. */
    data class Heading(val text: String, val eyebrow: String? = null) : Block
    data class Text(val text: String) : Block
    /** A single hero number/value, e.g. "82%" / "Battery". */
    data class Metric(val value: String, val label: String, val icon: String? = null) : Block
    data class ListBlock(val items: List<Item>) : Block
    /** Quick follow-ups. Tapping a chip performs its action (default: say the label). */
    data class Chips(val options: List<Option>) : Block
    data class Buttons(val buttons: List<ButtonSpec>) : Block
    /** Row of installed apps, rendered with their real icons. */
    data class Apps(val apps: List<String>) : Block
    data class Progress(val value: Float, val label: String? = null) : Block
}

data class Item(val title: String, val subtitle: String? = null, val icon: String? = null, val trailing: String? = null, val action: Action? = null)
data class Option(val label: String, val action: Action)
data class ButtonSpec(val label: String, val action: Action, val style: ButtonStyle = ButtonStyle.Tonal)

enum class ButtonStyle { Filled, Tonal, Outlined;
    companion object {
        fun parse(s: String?): ButtonStyle = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: Tonal
    }
}

/** What happens when the user touches something on a surface. */
sealed interface Action {
    /** Feed text back to the agent as if the user said it. */
    data class Say(val text: String) : Action
    data class Launch(val app: String) : Action
    data class OpenUrl(val url: String) : Action
    /** Run a tool directly, without a model round-trip. */
    data class RunTool(val name: String, val argsJson: String) : Action
    data class RequestPermission(val permission: String) : Action
    /** Do what tapping this notification in the shade would do; fall back to opening [app]. */
    data class OpenNotification(val key: String, val app: String) : Action
    data object OpenSettings : Action
    data object Dismiss : Action
}
