package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class PlaybackUi(
    val id: Long? = null, val playing: Boolean = false, val wantsPlay: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long? = null, val buffering: Boolean = false,
    val hasPrevious: Boolean = false, val hasNext: Boolean = false, val error: String? = null
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun playbackState(controller: MediaController?): PlaybackUi {
    var state by remember(controller) { mutableStateOf(PlaybackUi()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        val player = controller ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (player.isConnected) state = PlaybackUi(
                    id = player.currentMediaItem?.mediaId?.toLongOrNull(), playing = player.isPlaying,
                    wantsPlay = player.playWhenReady, positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.takeIf { it > 0 }, buffering = player.playbackState == Player.STATE_BUFFERING,
                    hasPrevious = player.hasPreviousMediaItem(), hasNext = player.hasNextMediaItem(),
                    error = player.playerError?.let { "${it.errorCodeName}: ${it.message.orEmpty()}" }
                )
                delay(400)
            }
        }
    }
    return state
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlayerScreen(tracks: List<AudioTrack>, controller: MediaController?, modifier: Modifier = Modifier,
                 onPlay: (AudioTrack) -> Unit) {
    val playback = playbackState(controller)
    val selected = tracks.firstOrNull { it.id == playback.id }
    var seeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playback.id) { seeking = false; seekPosition = 0f }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Player", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected?.displayName ?: "Pilih audio dari library", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { controller?.stop() }, enabled = playback.id != null) { Text("Stop") }
                }
                Text(when {
                    controller == null -> "Menghubungkan pemutar..."
                    playback.buffering -> "Menyiapkan audio..."
                    playback.playing -> "Sedang diputar"
                    playback.id != null -> "Siap / dijeda"
                    else -> "File lokal bisa diputar tanpa internet"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val duration = playback.durationMs ?: 1L
                Slider(value = if (seeking) seekPosition else playback.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { seeking = true; seekPosition = it },
                    onValueChangeFinished = { controller?.seekTo(seekPosition.toLong()); seeking = false },
                    enabled = playback.durationMs != null, valueRange = 0f..duration.toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LibraryRules.durationLabel(if (seeking) seekPosition.toLong() else playback.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(LibraryRules.durationLabel(playback.durationMs), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = playback.hasPrevious,
                        modifier = Modifier.semantics { contentDescription = "Audio sebelumnya" }) { Text("←") }
                    Button(onClick = {
                        controller?.let { p ->
                            if (p.playWhenReady) p.pause() else {
                                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                                p.play()
                            }
                        }
                    }, enabled = playback.id != null, modifier = Modifier.widthIn(min = 112.dp)) { Text(if (playback.wantsPlay) "Jeda" else "Putar") }
                    TextButton(onClick = { controller?.seekToNextMediaItem() }, enabled = playback.hasNext,
                        modifier = Modifier.semantics { contentDescription = "Audio berikutnya" }) { Text("→") }
                }
                playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Library • ${tracks.size} file", style = MaterialTheme.typography.titleSmall)
        if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Belum ada audio.\nUnduh atau impor lewat tab Tambah.", style = MaterialTheme.typography.bodyMedium)
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(tracks, key = { it.id }) { track -> TrackRow(track, "Putar", track.id == playback.id) { onPlay(track) } }
        }
    }
}

@Composable
fun TrackRow(track: AudioTrack, actionLabel: String, selected: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val type = track.displayName.substringAfterLast('.', "audio").uppercase()
                Text("$type • ${LibraryRules.durationLabel(track.durationMs)} • ${android.text.format.Formatter.formatShortFileSize(context, track.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
