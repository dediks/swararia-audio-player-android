package com.swararia.audiolab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

internal data class SessionQueueItem(val index: Int, val mediaId: String, val title: String?)

internal data class SessionPlayback(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val wantsPlay: Boolean = false,
    val idle: Boolean = true,
    val ended: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val canPlay: Boolean = false,
    val canSeek: Boolean = false,
    val canStop: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val canChooseQueue: Boolean = false,
    val queue: List<SessionQueueItem> = emptyList(),
    val error: String? = null,
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun readSwarariaPlayback(p: MediaController): SessionPlayback {
    if (!p.isConnected) return SessionPlayback()
    val queue = if (p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) List(p.mediaItemCount) { index ->
        val item = p.getMediaItemAt(index)
        SessionQueueItem(index, item.mediaId, item.mediaMetadata.title?.toString())
    } else emptyList()
    return SessionPlayback(
        connected = true, mediaId = p.currentMediaItem?.mediaId,
        title = p.mediaMetadata.title?.toString(), currentIndex = p.currentMediaItemIndex,
        playing = p.isPlaying, wantsPlay = playbackRequestsPause(p), idle = p.playbackState == Player.STATE_IDLE,
        ended = p.playbackState == Player.STATE_ENDED,
        buffering = p.playbackState == Player.STATE_BUFFERING,
        positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET },
        canPlay = p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
        canSeek = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && p.isCurrentMediaItemSeekable,
        canStop = p.isCommandAvailable(Player.COMMAND_STOP),
        hasPrevious = p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && p.hasPreviousMediaItem(),
        hasNext = p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && p.hasNextMediaItem(),
        canChooseQueue = p.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM), queue = queue,
        error = p.playerError?.let { "${it.errorCodeName}: ${it.message.orEmpty()}" },
    )
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun rememberSwarariaPlayback(controller: MediaController?): SessionPlayback {
    var state by remember(controller) { mutableStateOf(SessionPlayback()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        val p = controller ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!p.isConnected) { state = SessionPlayback(); return@repeatOnLifecycle }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { state = readSwarariaPlayback(p) }
            }
            p.addListener(listener)
            try {
                state = readSwarariaPlayback(p)
                // Events handle paused/idle changes. The position ticker exists only while playing and visible.
                snapshotFlow { state.playing }.collectLatest { playing ->
                    if (playing) while (isActive) {
                        delay(500)
                        if (!p.isConnected) { state = SessionPlayback(); break }
                        state = state.copy(positionMs = p.currentPosition.coerceAtLeast(0))
                    }
                }
            } finally { p.removeListener(listener) }
        }
    }
    return state
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private inline fun connectedCommand(controller: MediaController?, command: Int, action: (MediaController) -> Unit) {
    val p = controller ?: return
    if (p.isConnected && p.isCommandAvailable(command)) action(p)
}

private fun playbackRequestsPause(p: Player): Boolean =
    p.playWhenReady && p.playbackState != Player.STATE_IDLE &&
        p.playbackState != Player.STATE_ENDED && p.playerError == null

internal fun toggleSwarariaPlayback(controller: MediaController?) {
    connectedCommand(controller, Player.COMMAND_PLAY_PAUSE) { p ->
        if (p.currentMediaItem != null) {
            if (playbackRequestsPause(p)) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED && p.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) p.seekToDefaultPosition()
                if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                p.play()
            }
        }
    }
}

private fun playbackTitle(current: AudioTrack?, playback: SessionPlayback): String =
    current?.let { UiPresentation.title(it.displayName) }
        ?: playback.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
        ?: if (playback.mediaId == null) "Pilih audio dari Pustaka" else "Audio tidak tersedia"

private fun playbackStatus(playback: SessionPlayback): String = when {
    !playback.connected -> "Pemutar belum terhubung"
    playback.error != null -> "Pemutaran bermasalah"
    playback.buffering -> "Menyiapkan audio…"
    playback.idle && playback.mediaId != null -> "Dihentikan"
    playback.playing -> "Audio lokal"
    playback.ended -> "Selesai · Audio lokal"
    playback.mediaId != null -> "Dijeda · Audio lokal"
    else -> "Pilih audio dari Pustaka"
}

@Composable
internal fun MiniPlayer(current: AudioTrack?, playback: SessionPlayback, controller: MediaController?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Buka pemutar lengkap" }) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackArtwork(current?.artworkKey, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(playbackTitle(current, playback), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(playbackStatus(playback), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                GlyphButton(if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                    if (playback.wantsPlay) "Jeda audio" else "Lanjutkan audio", { toggleSwarariaPlayback(controller) },
                    enabled = playback.connected && playback.canPlay, filled = true)
                GlyphButton(UiIcons.Next, "Audio berikutnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
                }, enabled = playback.connected && playback.hasNext)
            }
            playback.durationMs?.let { duration ->
                LinearProgressIndicator(progress = { (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun FocusedPlayer(
    tracks: List<AudioTrack>,
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    modifier: Modifier,
    onBack: () -> Unit,
    onRename: (AudioTrack) -> Unit,
    onSettings: () -> Unit,
) {
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var seekPosition by remember(playback.mediaId, playback.currentIndex) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * .39f, 320.dp).coerceAtLeast(80.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(UiIcons.Back, "Kembali", onBack)
                Text("Now playing", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                BrandLogo(32.dp)
                Box {
                    GlyphButton(UiIcons.More, "Menu pemutar", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Ubah nama audio") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = current != null, onClick = { menu = false; current?.let(onRename) })
                        DropdownMenuItem(text = { Text("Pengaturan & tentang") }, leadingIcon = { Icon(UiIcons.Settings, null) },
                            onClick = { menu = false; onSettings() })
                    }
                }
            }
            Text("AUDIO LOKAL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TrackArtwork(current?.artworkKey,
                Modifier.size(coverSize).clip(RoundedCornerShape(24.dp)))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(playbackTitle(current, playback), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(current?.let { "${it.displayName.substringAfterLast('.', "Audio").uppercase()} · Disimpan di HP" }
                    ?: playbackStatus(playback), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth()) {
                val duration = playback.durationMs ?: 1L
                val displayed = if (playback.durationMs == null) 0f else (seekPosition ?: playback.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
                Slider(value = displayed, onValueChange = { seekPosition = it },
                    valueRange = 0f..duration.toFloat(), enabled = playback.connected && playback.canSeek && playback.durationMs != null,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi pemutaran audio" },
                    onValueChangeFinished = {
                        val requested = seekPosition
                        seekPosition = null
                        if (requested != null) connectedCommand(controller, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) { p ->
                            if (p.currentMediaItem?.mediaId == playback.mediaId && p.currentMediaItemIndex == playback.currentIndex) p.seekTo(requested.toLong())
                        }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LibraryRules.durationLabel(seekPosition?.toLong() ?: playback.positionMs.takeIf { playback.connected && playback.mediaId != null }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(LibraryRules.durationLabel(playback.durationMs), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (playback.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!playback.connected) Text("Pemutar belum terhubung. Kembali ke Pustaka untuk memilih audio setelah tersambung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(UiIcons.Previous, "Audio sebelumnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) { p -> if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() }
                }, modifier = Modifier.size(56.dp), enabled = playback.connected && playback.hasPrevious)
                GlyphButton(if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                    if (playback.wantsPlay) "Jeda audio" else "Putar atau lanjutkan audio", { toggleSwarariaPlayback(controller) },
                    modifier = Modifier.size(80.dp), enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                    filled = true, iconSize = 32.dp)
                GlyphButton(UiIcons.Next, "Audio berikutnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
                }, modifier = Modifier.size(56.dp), enabled = playback.connected && playback.hasNext)
            }
            TextButton(onClick = { connectedCommand(controller, Player.COMMAND_STOP) { it.stop() } },
                enabled = playback.connected && playback.mediaId != null && playback.canStop,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(UiIcons.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
            }
            PlaybackModeBar(controller, Modifier.fillMaxWidth())
            Surface(onClick = { queueOpen = true }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(UiIcons.Queue, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${playback.queue.size} audio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (queueOpen) QueueDialog(tracks, playback, controller, onDismiss = { queueOpen = false })
}

@Composable
private fun QueueDialog(tracks: List<AudioTrack>, playback: SessionPlayback, controller: MediaController?, onDismiss: () -> Unit) {
    val byId = remember(tracks) { tracks.associateBy { it.id.toString() } }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(UiIcons.Queue, null) }, title = { Text("Queue · ${playback.queue.size} audio") },
        text = {
            if (playback.queue.isEmpty()) Text("Antrean sesi pemutar belum tersedia. Pilih audio dari Pustaka.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(playback.queue, key = { it.index }) { entry ->
                    val track = byId[entry.mediaId]
                    val title = track?.let { UiPresentation.title(it.displayName) }
                        ?: entry.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) } ?: "Audio tidak tersedia"
                    val active = entry.index == playback.currentIndex
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(enabled = playback.connected && playback.canChooseQueue, onClickLabel = "Putar $title") {
                            connectedCommand(controller, Player.COMMAND_SEEK_TO_MEDIA_ITEM) { p ->
                                if (entry.index in 0 until p.mediaItemCount && p.getMediaItemAt(entry.index).mediaId == entry.mediaId) {
                                    if (p.currentMediaItemIndex == entry.index) toggleSwarariaPlayback(p)
                                    else {
                                        p.seekTo(entry.index, C.TIME_UNSET)
                                        if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                                        if (p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) p.play()
                                    }
                                }
                            }
                        }.semantics { selected = active }.padding(horizontal = 10.dp, vertical = 12.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${entry.index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 20.dp))
                        Text(title, style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (active) Icon(UiIcons.Check, "Audio saat ini", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}
