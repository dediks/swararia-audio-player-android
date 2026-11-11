package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Presentation only. Leaving composition stops polling, never service playback/resolution. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun SearchPreviewPlayer(state: PreviewState, controller: MediaController?, onStop: () -> Unit, onError: () -> Unit,
    onRetry: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val player = controller
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableStateOf<Long?>(null) }
    var seek by remember(state.hit?.videoId) { mutableStateOf<Float?>(null) }
    val active = state.hit != null && state.error == null && !state.loading && state.phase.isNotEmpty()
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(target: Player, events: Player.Events) {
                playing = target.isPlaying
                duration = target.duration.takeIf { it > 0 && it != C.TIME_UNSET }
                position = target.currentPosition.coerceAtLeast(0L)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                playing = player?.isPlaying == true
                position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
                delay(250)
            }
        }
    }
    val limit = minOf(OnlinePreviewRules.PREVIEW_MS, duration ?: OnlinePreviewRules.PREVIEW_MS).coerceAtLeast(1L)
    val waiting = state.loading || state.phase == "connecting" || state.phase == "buffering"
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.hit?.let { YoutubeThumbnail(it.thumbnailUrl, it.title, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) }
                Column(Modifier.weight(1f)) {
                    Text(when {
                        state.canceling -> "Menutup Preview…"
                        state.error != null -> "Preview gagal"
                        state.loading -> "Resolving…"
                        state.phase == "connecting" -> "Connecting…"
                        state.phase == "buffering" -> "Buffering…"
                        playing -> "PREVIEW"
                        else -> "Preview dijeda"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(state.hit?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (waiting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else if (active) GlyphButton(if (playing) UiIcons.Pause else UiIcons.Play,
                    if (playing) "Pause Preview" else "Play Preview", {
                        if (player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true) {
                            if (player.playWhenReady) player.pause() else player.play()
                        }
                    })
                GlyphButton(UiIcons.Close, "Stop Preview", onStop)
            }
            if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
            }
            if (active) {
                Slider(value = (seek ?: position.toFloat()).coerceIn(0f, limit.toFloat()),
                    onValueChange = { seek = it }, valueRange = 0f..limit.toFloat(),
                    enabled = player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true && player.isCurrentMediaItemSeekable,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi Preview" },
                    onValueChangeFinished = { seek?.let { player?.seekTo(it.toLong().coerceIn(0L, limit)) }; seek = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LibraryRules.durationLabel(seek?.toLong() ?: position.coerceAtMost(limit)), style = MaterialTheme.typography.bodySmall)
                    Text(LibraryRules.durationLabel(limit), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
