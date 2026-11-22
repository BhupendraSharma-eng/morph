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
    /** Present when the surface is a question the agent is waiting on (see ask_user). */
    val form: FormSpec? = null,
)

data class FormSpec(val submitLabel: String = "Done", val cancelLabel: String? = "Skip")

enum class InputKind { Text, LongText, Slider, Toggle, Choice, MultiChoice, Date, Time, Stepper, Rating;
    companion object {
        fun parse(s: String?): InputKind? = when (s?.lowercase()?.replace("-", "_")) {
            "text", "text_field", "textfield", "input" -> Text
            "long_text", "multiline", "textarea", "paragraph_input" -> LongText
            "slider", "range" -> Slider
            "toggle", "switch", "checkbox", "boolean" -> Toggle
            "choice", "radio", "single_choice", "select", "segmented" -> Choice
            "multi_choice", "multichoice", "checkboxes", "multi_select" -> MultiChoice
            "date" -> Date
            "time" -> Time
            "stepper", "number", "counter" -> Stepper
            "rating", "stars" -> Rating
            else -> null
        }
    }
}

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
    /** An interactive control. Its value is returned to the agent under [id] when the form is submitted. */
    data class Input(
        val id: String,
        val kind: InputKind,
        val label: String,
        val placeholder: String? = null,
        val min: Float? = null,
        val max: Float? = null,
        val step: Float? = null,
        val unit: String? = null,
        val options: List<String> = emptyList(),
        /** Initial value as text: "42", "true", "2026-11-21", "09:30", "a|b" for multi-choice. */
        val initial: String? = null,
        val required: Boolean = false,
    ) : Block
}

data class Item(
    val title: String, val subtitle: String? = null, val icon: String? = null, val trailing: String? = null, val action: Action? = null,
    /** Shows a small ✕ on the row that runs this (e.g. dismiss the notification) — a tap, not a swipe, so it never fights page swipes. */
    val dismiss: Action? = null,
    /** One line each for title and subtitle, tighter padding: for low-value rows that shouldn't take much room. */
    val compact: Boolean = false,
)
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
    /** Open one of Morph's own pages, e.g. "activity". */
    data class OpenPage(val page: String) : Action
    data object Dismiss : Action
    /** Clear these notifications from the shade (Morph is the notification listener, so it can). */
    data class DismissNotifications(val keys: List<String>) : Action
    /** Move these notifications to Spaces → Later: off the shade and home, kept so they aren't lost. */
    data class ShelveNotifications(val keys: List<String>) : Action
}
