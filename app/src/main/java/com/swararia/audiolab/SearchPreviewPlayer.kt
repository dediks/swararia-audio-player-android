package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController

/** A separate, non-caching preview player. It never writes library files or resumes the main queue. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun SearchPreviewPlayer(state: PreviewState, controller: MediaController?, onStop: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    val error by rememberUpdatedState(onError)
    val media = state.media
    val player = remember(media?.url) {
        media?.let {
            val source = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000).setDefaultRequestProperties(it.headers)
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source))
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true).build().apply {
                    setMediaItem(MediaItem.Builder().setUri(it.url).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(OnlinePreviewRules.PREVIEW_MS).build()).build())
                }
        }
    }
    val activePreviewPlayer by rememberUpdatedState(player)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Lifecycle-aware state collection is suspended here: stop audio directly.
                activePreviewPlayer?.stop()
                stop()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) stop() }
            override fun onPlayerError(failure: PlaybackException) { error() }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener); player?.release() }
    }
    DisposableEffect(controller, player) {
        val mainListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && player != null) { player.pause(); stop() }
            }
        }
        controller?.addListener(mainListener)
        onDispose { controller?.removeListener(mainListener) }
    }
    LaunchedEffect(player) {
        if (player != null) {
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()
            else {
                if (controller != null && controller.isConnected && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) controller.pause()
                player.prepare(); player.play()
            }
        }
    }
    if (state.loading || media != null || state.error != null) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.loading) "Menyiapkan preview…" else if (state.error != null) "Preview belum tersedia" else "Preview online · maksimal 30 detik",
                    style = MaterialTheme.typography.labelLarge)
                state.hit?.let { Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.error != null) "Tutup" else "Hentikan preview") }
            }
        }
    }
}
