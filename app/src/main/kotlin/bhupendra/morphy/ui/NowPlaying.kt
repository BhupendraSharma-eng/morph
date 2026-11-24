package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bhupendra.morphy.device.MediaHub
import bhupendra.morphy.device.Player
import kotlinx.coroutines.delay

/**
 * Media, styled like media: the session that's actually playing gets a full player — artwork, progress and
 * controls — and anything paused sits underneath as a slim row you can resume with one tap.
 */
@Composable
fun NowPlaying(players: List<Player>, hub: MediaHub) {
    val main = players.firstOrNull() ?: return
    val rest = players.drop(1)
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val art = remember(main.art) { main.art?.asImageBitmap() }
    // Over artwork the text is white on a dark scrim; without artwork it's an ordinary surface.
    val content = if (art != null) Color.White else scheme.onSurface
    val soft = content.copy(alpha = 0.75f)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surfaceContainerHigh.copy(alpha = 0.97f))
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Box(Modifier.fillMaxWidth().clickable { hub.open(main) }) {
            if (art != null) {
                Image(art, null, Modifier.matchParentSize().blur(36.dp), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.7f)))))
            }
            // One compact row — art, what's playing, controls — with the progress line under it. Snug enough
            // that home fits on one screen alongside the glance.
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(main, 52.dp, 14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(main.title, style = MaterialTheme.typography.bodyLargeEmphasized, color = content,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(main.artist, main.app).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (main.canPrev) IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); hub.previous(main) },
                        modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(26.dp), tint = content)
                    }
                    FilledIconButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); hub.toggle(main) },
                        shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp),
                        colors = if (art != null) IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                            else IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(if (main.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (main.playing) "Pause" else "Play", Modifier.size(28.dp))
                    }
                    if (main.canNext) IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); hub.next(main) },
                        modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp), tint = content)
                    }
                }
                Box(Modifier.padding(end = 4.dp)) { Progress(main, content) }
            }
        }
        rest.forEach { p ->
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                Modifier.fillMaxWidth().clickable { hub.open(p) }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(p, 36.dp, 10.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLargeEmphasized, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (p.playing) "Playing · " else "Paused · ") + p.app, style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); hub.toggle(p) }) {
                    Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p.playing) "Pause" else "Resume", tint = scheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun Artwork(p: Player, size: Dp, corner: Dp) {
    val art = remember(p.art) { p.art?.asImageBitmap() }
    val shape = RoundedCornerShape(corner)
    if (art != null) Image(art, p.title, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** A wavy line while it plays (it's alive), a flat one when paused; ticks twice a second. */
@Composable
private fun Progress(p: Player, content: Color) {
    if (p.duration <= 0) return
    val pos by produceState(p.positionNow(), p) {
        while (true) { value = p.positionNow(); if (!p.playing) break; delay(500) }
    }
    val fraction = (pos.toFloat() / p.duration).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(clock(pos), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
        val bar = Modifier.weight(1f)
        if (p.playing) LinearWavyProgressIndicator(progress = { fraction }, bar, color = content, trackColor = content.copy(alpha = 0.25f))
        else LinearProgressIndicator(progress = { fraction }, bar, color = content, trackColor = content.copy(alpha = 0.25f))
        Text(clock(p.duration), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
