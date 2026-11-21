package bhupendra.morph.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.SurfaceCodec
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Something living on the Spaces page. */
sealed interface Tile {
    val key: String

    /** A real Android widget, hosted by Morph. */
    data class Widget(val appWidgetId: Int, override val key: String = "w$appWidgetId") : Tile

    /** A Morph card pinned from a conversation. It stays when the conversation is cleared. */
    data class Card(val surface: Surface, override val key: String = "c" + UUID.randomUUID().toString()) : Tile
}

/**
 * The Spaces page: Android widgets and pinned Morph cards in one list you reorder (no resizing — each widget is
 * full width at its preferred height). Persisted as JSON in app storage; widget ids belong to Morph's
 * AppWidgetHost, which is released when a widget tile is removed.
 */
class Spaces(private val context: Context, scope: CoroutineScope) {
    val host = AppWidgetHost(context, HOST_ID)
    val widgets: AppWidgetManager = AppWidgetManager.getInstance(context)
    private val file = File(context.filesDir, "spaces.json")

    /** In order, top to bottom. Mutate on the main thread. */
    val tiles = mutableStateListOf<Tile>()

    init {
        runCatching { if (file.exists()) tiles.addAll(decode(file.readText())) }
        // Drop widgets whose provider app was uninstalled while we weren't looking.
        tiles.removeAll { it is Tile.Widget && widgets.getAppWidgetInfo(it.appWidgetId) == null }
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.Main) {
            androidx.compose.runtime.snapshotFlow { tiles.toList() }.drop(1).debounce(300).collect { list ->
                val text = encode(list)
                withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
            }
        }
    }

    fun addWidget(appWidgetId: Int) { if (tiles.none { it is Tile.Widget && it.appWidgetId == appWidgetId }) tiles.add(0, Tile.Widget(appWidgetId)) }

    /** Pin a card from a conversation (newest on top). */
    fun pin(surface: Surface) = tiles.add(0, Tile.Card(surface))

    /** Pin plain text (a reply) as a simple card. */
    fun pinText(text: String) = pin(Surface(blocks = listOf(Block.Text(text))))

    fun remove(tile: Tile) {
        tiles.remove(tile)
        if (tile is Tile.Widget) runCatching { host.deleteAppWidgetId(tile.appWidgetId) }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in tiles.indices || to !in tiles.indices) return
        tiles.add(to, tiles.removeAt(from))
    }

    private fun encode(list: List<Tile>): String = JsonArray(list.map { t ->
        when (t) {
            is Tile.Widget -> buildJsonObject { put("t", "widget"); put("id", t.appWidgetId) }
            is Tile.Card -> buildJsonObject { put("t", "card"); put("key", t.key); put("surface", SurfaceCodec.encode(t.surface)) }
        }
    }).toString()

    private fun decode(s: String): List<Tile> = (Json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        when ((o["t"] as? JsonPrimitive)?.contentOrNull) {
            "widget" -> (o["id"] as? JsonPrimitive)?.intOrNull?.let { Tile.Widget(it) }
            "card" -> (o["surface"] as? JsonObject)?.let(SurfaceCodec::decode)?.let { surface ->
                Tile.Card(surface, (o["key"] as? JsonPrimitive)?.contentOrNull ?: ("c" + UUID.randomUUID()))
            }
            else -> null
        }
    }

    companion object { const val HOST_ID = 0x4D4F }   // "MO"
}
