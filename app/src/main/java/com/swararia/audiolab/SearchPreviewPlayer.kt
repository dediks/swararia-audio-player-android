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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One bounded non-caching stream. Reuses its player until this playback slot is disposed. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun SearchPreviewPlayer(state: PreviewState, controller: MediaController?, onStop: () -> Unit, onError: () -> Unit,
    onRetry: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    val error by rememberUpdatedState(onError)
    val latestState by rememberUpdatedState(state)
    val media = state.media
    val source = remember(context) {
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(8000).setReadTimeoutMs(8000)
    }
    val player = remember(context) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
    }
    var phase by remember(player) { mutableIntStateOf(Player.STATE_IDLE) }
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableStateOf<Long?>(null) }
    var seek by remember(player) { mutableStateOf<Float?>(null) }
    val activePreviewPlayer by rememberUpdatedState<ExoPlayer?>(player)
    val stopNow: () -> Unit = { player.stop(); stop() }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Do not wait for lifecycle-suspended state collection to stop actual audio.
                activePreviewPlayer?.stop()
                stop()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    DisposableEffect(player) {
        var recorded = 0L
        val listener = object : Player.Listener {
            override fun onEvents(target: Player, events: Player.Events) {
                phase = player.playbackState
                playing = player.isPlaying
                duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
                position = player.currentPosition.coerceAtLeast(0L)
                val began = latestState.requestStartedNanos
                if (playing && began > 0 && recorded != began) {
                    recorded = began
                    android.util.Log.d("SwarariaTiming", "preview_start_ms=${((System.nanoTime() - began) / 1_000_000L).coerceAtLeast(0)}")
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { player.stop(); stop() }
            }
            override fun onPlayerError(failure: PlaybackException) { player.stop(); error() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(controller, player) {
        val mainListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { player.stop(); stop() }
            }
        }
        controller?.addListener(mainListener)
        onDispose { controller?.removeListener(mainListener) }
    }
    LaunchedEffect(player, media, state.requestStartedNanos, owner) {
        position = 0L; duration = null; seek = null
        player.stop()
        player.clearMediaItems()
        if (media != null) {
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()
            else {
                if (controller != null && controller.isConnected && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) controller.pause()
                // Headers are already allowlisted by the repository. Never persist/log this URL.
                source.setDefaultRequestProperties(media.headers)
                player.setMediaItem(MediaItem.Builder().setUri(media.url).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(OnlinePreviewRules.PREVIEW_MS).build()).build())
                player.prepare()
                player.play()
            }
        }
    }
    LaunchedEffect(player, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (player.isPlaying) position = player.currentPosition.coerceAtLeast(0L)
                delay(250)
            }
        }
    }
    val limit = minOf(OnlinePreviewRules.PREVIEW_MS,
        duration ?: state.hit?.durationSeconds?.times(1000L)?.takeIf { it > 0 } ?: OnlinePreviewRules.PREVIEW_MS)
        .coerceAtLeast(1L)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.hit?.let { YoutubeThumbnail(it.thumbnailUrl, it.title, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) }
                Column(Modifier.weight(1f)) {
                    Text(when {
                        state.canceling -> "Menutup Preview…"
                        state.loading -> "Connecting…"
                        state.error != null -> "Preview gagal"
                        phase == Player.STATE_BUFFERING -> "Buffering…"
                        playing -> "PREVIEW"
                        else -> "Preview dijeda"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(state.hit?.title ?: media?.title.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.loading || phase == Player.STATE_BUFFERING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else if (media != null) GlyphButton(if (playing) UiIcons.Pause else UiIcons.Play,
                    if (playing) "Pause Preview" else "Play Preview", {
                        if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                            if (player.playWhenReady) player.pause() else player.play()
                        }
                    })
                GlyphButton(UiIcons.Close, "Stop Preview", stopNow)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
            }
            if (media != null) {
                Slider(value = (seek ?: position.toFloat()).coerceIn(0f, limit.toFloat()),
                    onValueChange = { seek = it }, valueRange = 0f..limit.toFloat(),
                    enabled = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.isCurrentMediaItemSeekable,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi Preview" },
                    onValueChangeFinished = { seek?.let { player.seekTo(it.toLong().coerceIn(0L, limit)) }; seek = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LibraryRules.durationLabel(seek?.toLong() ?: position.coerceAtMost(limit)), style = MaterialTheme.typography.bodySmall)
                    Text(LibraryRules.durationLabel(limit), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
