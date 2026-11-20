package bhupendra.morph.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.SurfaceCodec
import bhupendra.morph.core.surface.SurfaceParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

sealed interface CanvasItem {
    val key: String
    data class UserEcho(val text: String, override val key: String = UUID.randomUUID().toString()) : CanvasItem
    data class Reply(val text: String, val streaming: Boolean, override val key: String = UUID.randomUUID().toString()) : CanvasItem
    data class Card(val surface: Surface, override val key: String = surface.id) : CanvasItem
    data class Notice(val text: String, val error: Boolean, val action: Pair<String, Action>? = null,
                      override val key: String = UUID.randomUUID().toString()) : CanvasItem
}

/**
 * The home canvas, app-scoped and persisted. Lives outside any screen so background work
 * (auto-replies, proactive moments) can post to it even when the UI isn't running.
 * Mutate on the main thread only.
 */
class CanvasRepo(context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "canvas.json")
    val items = mutableStateListOf<CanvasItem>()

    init {
        runCatching { if (file.exists()) items.addAll(decode(file.readText())) }
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.Main) {
            snapshotFlow { items.toList() }
                .drop(1)
                .debounce(400)
                .collect { snapshot ->
                    val text = encode(snapshot.filterNot { it is CanvasItem.Reply && it.streaming }.takeLast(MAX))
                    withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
                }
        }
    }

    fun add(item: CanvasItem) {
        items += item
        if (items.size > MAX + 20) items.removeRange(0, items.size - MAX)
    }

    private fun encode(list: List<CanvasItem>): String = JsonArray(list.map { i ->
        when (i) {
            is CanvasItem.UserEcho -> buildJsonObject { put("k", "user"); put("key", i.key); put("text", i.text) }
            is CanvasItem.Reply -> buildJsonObject { put("k", "reply"); put("key", i.key); put("text", i.text) }
            is CanvasItem.Card -> buildJsonObject { put("k", "card"); put("key", i.key); put("surface", SurfaceCodec.encode(i.surface)) }
            is CanvasItem.Notice -> buildJsonObject {
                put("k", "notice"); put("key", i.key); put("text", i.text); put("error", i.error)
                i.action?.let { (label, a) -> put("label", label); put("action", SurfaceCodec.action(a)) }
            }
        }
    }).toString()

    private fun decode(s: String): List<CanvasItem> = (Json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val key = str("key") ?: UUID.randomUUID().toString()
        when (str("k")) {
            "user" -> CanvasItem.UserEcho(str("text").orEmpty(), key)
            "reply" -> CanvasItem.Reply(str("text").orEmpty(), streaming = false, key = key)
            "card" -> (o["surface"] as? JsonObject)?.let(SurfaceCodec::decode)?.let { CanvasItem.Card(it, key) }
            "notice" -> CanvasItem.Notice(
                str("text").orEmpty(), (o["error"] as? JsonPrimitive)?.booleanOrNull ?: false,
                str("label")?.let { l -> SurfaceParser.action(o["action"])?.let { l to it } }, key,
            )
            else -> null
        }
    }

    companion object { const val MAX = 80 }
}
