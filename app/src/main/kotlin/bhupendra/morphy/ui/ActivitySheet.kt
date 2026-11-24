package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bhupendra.morphy.agent.LedgerEntry
import bhupendra.morphy.agent.Origin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ActivityFilter(val label: String) { All("All"), Asked("You asked"), Automatic("Automatic") }

/** Everything Morphy has done — asked or automatic — grouped by day. Its own page, not a settings section. */
@Composable
fun ActivitySheet(vm: HomeViewModel, onDismiss: () -> Unit) {
    val realEntries by vm.graph.ledger.entries.collectAsState()
    val entries = if (Demo.active) Demo.ledger else realEntries
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    val scheme = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }

    val shown = remember(entries, filter) {
        entries.filter {
            when (filter) {
                ActivityFilter.All -> true
                ActivityFilter.Asked -> it.origin == Origin.User
                ActivityFilter.Automatic -> it.origin != Origin.User
            }
        }
    }
    val byDay = remember(shown) { shown.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Activity", style = MaterialTheme.typography.headlineMediumEmphasized)
                Text("Everything I've done for you — asked or automatic.", style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActivityFilter.entries.forEach { f ->
                        ToggleButton(checked = filter == f, onCheckedChange = { filter = f }) { Text(f.label) }
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (entries.isEmpty()) "Nothing yet. Every action I take shows up here." else "Nothing in this filter.",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                byDay.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(dayLabel(day), Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    }
                    items(list, key = { "${it.at}-${it.what}" }) { e ->
                        val idx = list.indexOf(e)
                        ActivityRow(e, zone, first = idx == 0, last = idx == list.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(e: LedgerEntry, zone: ZoneId, first: Boolean, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val big = 20.dp; val small = 6.dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ShapedEmoji(
            when (e.origin) { Origin.User -> "🙋"; Origin.Automation -> "🤖"; Origin.Proactive -> "✨" },
            shapeFor(e.what), size = 36.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.what, Modifier.weight(1f), style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = if (open) 4 else 1,
                    overflow = TextOverflow.Ellipsis)
                Text(Instant.ofEpochMilli(e.at).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Text(e.result, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                maxLines = if (open) 12 else 2, overflow = TextOverflow.Ellipsis)
            if (open && e.detail.isNotBlank() && e.detail != "{}") {
                Text(e.detail, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"))
    }
}
