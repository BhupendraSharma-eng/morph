package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bhupendra.morphy.data.CanvasItem
import bhupendra.morphy.data.ConversationMeta
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Past conversations: search them, tap one to continue exactly where it left off. Lives one swipe left of home.
 * Long-press to delete (a sideways swipe on a row would fight the page swipe).
 */
@Composable
fun ConversationsPage(vm: HomeViewModel, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val all by vm.graph.conversations.list.collectAsState()
    val working by vm.graph.runtime.working.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(query, all) { vm.graph.conversations.search(query) }
    val zone = remember { ZoneId.systemDefault() }
    val byDay = remember(shown) { shown.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() } }
    val current = vm.canvas.firstOrNull { it is CanvasItem.UserEcho } as? CanvasItem.UserEcho
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize()
            .background(scheme.surface.copy(alpha = 0.55f))
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Conversations", style = MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface)
                Text(if (all.isEmpty()) "Nothing saved yet" else "${all.size} saved", style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { focus.clearFocus(); vm.clearCanvas(); onDone() }) {
                Icon(Icons.Rounded.Add, null); Text("New", Modifier.padding(start = 6.dp))
            }
        }
        TextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search conversations") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true, shape = CircleShape,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (current != null && query.isBlank()) item(key = "now") {
                Header("Now")
                ConversationRow(
                    title = "Current conversation", preview = current.text, time = "open", working = false, first = true, last = true,
                    highlight = true, onOpen = { focus.clearFocus(); onDone() }, onDelete = null,
                )
            }
            if (shown.isEmpty()) item(key = "empty") {
                Text(
                    if (query.isNotBlank()) "No conversation mentions \"$query\"."
                    else "When you clear a conversation (✕) or come back after a while, it's saved here.",
                    Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                )
            }
            byDay.forEach { (day, list) ->
                item(key = "h$day") { Header(dayLabel(day)) }
                items(list, key = { it.id }) { m ->
                    val i = list.indexOf(m)
                    ConversationRow(
                        title = m.title, preview = m.preview,
                        time = if (m.id in working) "Working…" else timeLabel(m, zone), working = m.id in working,
                        first = i == 0, last = i == list.lastIndex, highlight = false,
                        onOpen = { focus.clearFocus(); query = ""; vm.resumeConversation(m.id) },
                        onDelete = { scope.launch { vm.graph.conversations.delete(m.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String, preview: String, time: String, working: Boolean, first: Boolean, last: Boolean, highlight: Boolean,
    onOpen: () -> Unit, onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val big = 20.dp; val small = 6.dp
    Box {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    if (highlight) scheme.primaryContainer else scheme.surfaceContainerHigh,
                    RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small),
                )
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onDelete?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true } },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (highlight) scheme.onPrimaryContainer else scheme.onSurface)
                if (working) androidx.compose.material3.LoadingIndicator(Modifier.padding(end = 6.dp).size(20.dp))
                Text(time, style = MaterialTheme.typography.labelMedium,
                    color = when { highlight -> scheme.onPrimaryContainer; working -> scheme.primary; else -> scheme.onSurfaceVariant })
            }
            Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (highlight) scheme.onPrimaryContainer.copy(alpha = 0.85f) else scheme.onSurfaceVariant)
        }
        if (onDelete != null) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

private fun timeLabel(m: ConversationMeta, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(m.updatedAt).atZone(zone)
    return t.format(DateTimeFormatter.ofPattern(if (t.toLocalDate() == LocalDate.now()) "h:mm a" else "d MMM"))
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"))
    }
}
