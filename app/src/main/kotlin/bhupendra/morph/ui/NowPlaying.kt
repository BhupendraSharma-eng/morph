package bhupendra.morph.ui

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
import androidx.compose.runtime.getValue
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
import bhupendra.morph.device.MediaHub
import bhupendra.morph.device.Player
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(main, 64.dp, 18.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (main.playing) "Playing on ${main.app}" else "Paused · ${main.app}",
                            style = MaterialTheme.typography.labelMedium, color = if (art != null) soft else scheme.primary, maxLines = 1)
                        Text(main.title, style = MaterialTheme.typography.titleMediumEmphasized, color = content,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        main.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Progress(main, content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); hub.previous(main) },
                        enabled = main.canPrev, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp), tint = if (main.canPrev) content else soft.copy(alpha = 0.3f))
                    }
                    FilledIconButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); hub.toggle(main) },
                        shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(64.dp),
                        colors = if (art != null) IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                            else IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(if (main.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (main.playing) "Pause" else "Play", Modifier.size(34.dp))
                    }
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); hub.next(main) },
                        enabled = main.canNext, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp), tint = if (main.canNext) content else soft.copy(alpha = 0.3f))
                    }
                }
            }
        }
        rest.forEach { p ->
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                Modifier.fillMaxWidth().clickable { hub.open(p) }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(p, 40.dp, 12.dp)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (p.playing) LinearWavyProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = content, trackColor = content.copy(alpha = 0.25f))
        else LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = content, trackColor = content.copy(alpha = 0.25f))
        Row(Modifier.fillMaxWidth()) {
            Text(clock(pos), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
            Box(Modifier.weight(1f))
            Text(clock(p.duration), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
