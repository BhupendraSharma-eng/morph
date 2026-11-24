package bhupendra.morphy.core.agent

import bhupendra.morphy.core.surface.Surface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How much a tool can change the world. The agent loop uses this to decide what needs consent. */
enum class Risk {
    /** Reads state only. */
    Read,
    /** Changes local device state in a reversible way (torch, volume, timer). */
    Act,
    /** Hands off to another app where the user makes the final decision (SMS compose, dialer). */
    Handoff,
}

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject,
    val risk: Risk = Risk.Read,
    /**
     * A terminal tool ends the turn: if every call in a round is terminal (e.g. render_ui),
     * the agent stops without another model round-trip. Halves latency for UI answers.
     */
    val terminal: Boolean = false,
    /** Short human label shown while the tool runs ("Setting a timer"). */
    val label: String = name,
    /**
     * Output contains text written by third parties (web pages, notifications, messages). Once a turn has
     * read such content, every state-changing tool in that turn needs the user's explicit approval.
     */
    val untrustedOutput: Boolean = false,
)

/** Shows a question surface and waits for the user's answer (null = dismissed). */
fun interface UserPrompter {
    suspend fun ask(surface: bhupendra.morphy.core.surface.Surface): JsonObject?
}

/**
 * Approves a state-changing tool call after untrusted content entered the turn. Returns the (possibly
 * user-edited) arguments to run with, or null if the user declined.
 */
fun interface Gatekeeper {
    suspend fun approve(spec: ToolSpec, args: JsonObject): JsonObject?
}

data class ToolOutcome(
    /** Text fed back to the model. */
    val result: String,
    /** Optional surface shown to the user immediately. */
    val surface: Surface? = null,
)

interface Tool {
    val spec: ToolSpec
    suspend fun run(args: JsonObject): ToolOutcome
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()

    init { tools.forEach(::register) }

    fun register(tool: Tool) { byName[tool.spec.name] = tool }
    operator fun get(name: String): Tool? = byName[name]
    val specs: List<ToolSpec> get() = byName.values.map { it.spec }
}

/** Small DSL for writing JSON schemas compactly. */
fun schema(required: List<String> = emptyList(), props: SchemaBuilder.() -> Unit): JsonObject {
    val b = SchemaBuilder().apply(props)
    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(b.props))
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
}

class SchemaBuilder {
    internal val props = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    fun string(name: String, description: String, enum: List<String>? = null) {
        props[name] = buildJsonObject {
            put("type", "string"); put("description", description)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
    }
    fun integer(name: String, description: String) {
        props[name] = buildJsonObject { put("type", "integer"); put("description", description) }
    }
    fun boolean(name: String, description: String) {
        props[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
    }
    fun raw(name: String, schema: JsonObject) { props[name] = schema }
}
