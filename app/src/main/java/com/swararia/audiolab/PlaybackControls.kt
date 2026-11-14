package com.swararia.audiolab

import android.os.Bundle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Shared app-only command protocol. Mode/fade truth always comes from service extras. */
object PlaybackCommands {
    const val ACTION_SET_MODE = "com.swararia.audiolab.SET_PLAYBACK_MODE"
    const val ACTION_FADE = "com.swararia.audiolab.FADE_PAUSE"
    const val ACTION_IMMEDIATE_PAUSE = "com.swararia.audiolab.IMMEDIATE_PAUSE"
    const val ACTION_SET_FADE_SETTINGS = "com.swararia.audiolab.SET_FADE_SETTINGS"
    const val EXTRA_MODE = "com.swararia.audiolab.playback.MODE"
    const val EXTRA_FADE_BUSY = "com.swararia.audiolab.playback.FADE_BUSY"
    const val EXTRA_FADE_IN_MS = "com.swararia.audiolab.playback.FADE_IN_MS"
    const val EXTRA_FADE_OUT_MS = "com.swararia.audiolab.playback.FADE_OUT_MS"
    const val EXTRA_PROTOCOL = "com.swararia.audiolab.playback.PROTOCOL"
    val setMode = SessionCommand(ACTION_SET_MODE, Bundle.EMPTY)
    val fade = SessionCommand(ACTION_FADE, Bundle.EMPTY)
    val immediatePause = SessionCommand(ACTION_IMMEDIATE_PAUSE, Bundle.EMPTY)
    val setFadeSettings = SessionCommand(ACTION_SET_FADE_SETTINGS, Bundle.EMPTY)
}

private data class PlaybackControlState(
    val mode: PlaybackMode? = null,
    val busy: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val modeSupported: Boolean = false,
    val fadeSupported: Boolean = false,
    val playing: Boolean = false,
    val connected: Boolean = false,
)

private fun MediaController?.readPlaybackControlState(): PlaybackControlState {
    if (this == null || !isConnected) return PlaybackControlState()
    val extras = sessionExtras
    val knownProtocol = extras.getInt(PlaybackCommands.EXTRA_PROTOCOL, 0) == 1
    val mode = if (knownProtocol) PlaybackMode.entries.firstOrNull {
        it.name == extras.getString(PlaybackCommands.EXTRA_MODE)
    } else null
    val fadeInMs = if (knownProtocol) FadeRules.clampDurationMs(extras.getLong(PlaybackCommands.EXTRA_FADE_IN_MS, 0L)) else 0L
    val fadeOutMs = if (knownProtocol) FadeRules.clampDurationMs(extras.getLong(PlaybackCommands.EXTRA_FADE_OUT_MS, 0L)) else 0L
    return PlaybackControlState(
        mode = mode,
        busy = knownProtocol && extras.getBoolean(PlaybackCommands.EXTRA_FADE_BUSY, false),
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        modeSupported = knownProtocol && mode != null && isSessionCommandAvailable(PlaybackCommands.setMode),
        fadeSupported = knownProtocol && isSessionCommandAvailable(PlaybackCommands.fade),
        playing = isPlaying,
        connected = true,
    )
}

data class LocalPlaybackControlUi(
    val mode: PlaybackMode?,
    val fadeBusy: Boolean,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val modeSupported: Boolean,
    val fadeSupported: Boolean,
    val playing: Boolean,
    val connected: Boolean,
    val pending: Boolean,
    val statusText: String?,
    val isError: Boolean,
    val onSelectMode: (PlaybackMode) -> Unit,
    val onFadeOut: () -> Unit,
) {
    val manualFadeOutMs: Long get() = FadeRules.manualDurationMs(fadeOutMs)
}

/** Insert in the focused player. Does not own/release the controller or alter its queue. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlaybackModeBar(
    controller: MediaController?,
    modifier: Modifier = Modifier,
    fohContent: (@Composable (LocalPlaybackControlUi) -> Unit)? = null,
) {
    val owner = LocalLifecycleOwner.current
    var state by remember(controller) { mutableStateOf(PlaybackControlState()) }
    var expanded by remember(controller) { mutableStateOf(false) }
    var pending by remember(controller) { mutableStateOf(false) }
    var error by remember(controller) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var commandJob by remember(controller) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(controller) {
        onDispose { commandJob?.cancel() }
    }

    // Media3 1.8 exposes the extras listener only at controller construction time, not addListener.
    // Bounded polling keeps this drop-in API independent of the parent's builder/listener ownership.
    // LaunchedEffect cancellation stops polling as soon as the bar leaves composition.
    LaunchedEffect(controller, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                state = controller.readPlaybackControlState()
                if (!state.connected || !state.modeSupported) expanded = false
                delay(200)
            }
        }
    }

    fun send(command: SessionCommand, args: Bundle) {
        val target = controller
        if (pending || target == null || !target.isConnected || !target.isSessionCommandAvailable(command)) {
            error = "Playback control unavailable."
            return
        }
        pending = true
        error = null
        commandJob = scope.launch {
            var future: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            try {
                future = target.sendCustomCommand(command, args)
                val result = withTimeout(5000L) {
                    while (!future.isDone) delay(25)
                    future.get()
                }
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    error = "Playback command was not applied. Try again."
                }
                state = target.readPlaybackControlState()
            } catch (cancelled: CancellationException) {
                // Timeout is a visible command failure; composition disposal remains cancellation.
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                    error = "Playback command timed out."
                } else throw cancelled
            } catch (_: Exception) {
                error = "Playback control disconnected or failed."
            } finally {
                if (future?.isDone == false) future.cancel(false)
                pending = false
            }
        }
    }

    val status = error ?: when {
        !state.connected -> "Playback disconnected"
        !state.modeSupported -> "Playback modes unavailable"
        state.busy -> "Fading out, then pausing"
        else -> null
    }
    if (fohContent != null) {
        Box(modifier = modifier) {
            fohContent(
                LocalPlaybackControlUi(
                    mode = state.mode,
                    fadeBusy = state.busy,
                    fadeInMs = state.fadeInMs,
                    fadeOutMs = state.fadeOutMs,
                    modeSupported = state.modeSupported,
                    fadeSupported = state.fadeSupported,
                    playing = state.playing,
                    connected = state.connected,
                    pending = pending,
                    statusText = status,
                    isError = error != null,
                    onSelectMode = { mode ->
                        send(PlaybackCommands.setMode, Bundle().apply { putString(PlaybackCommands.EXTRA_MODE, mode.name) })
                    },
                    onFadeOut = { send(PlaybackCommands.fade, Bundle.EMPTY) },
                )
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = state.modeSupported && !pending,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = state.mode?.label ?: "Unavailable"
                    },
                ) {
                    Icon(UiIcons.Loop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(state.mode?.label ?: "Mode")
                    Spacer(Modifier.width(8.dp)); Icon(UiIcons.ChevronDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    PlaybackMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label + if (mode == state.mode) " (selected)" else "") },
                            onClick = {
                                expanded = false
                                send(PlaybackCommands.setMode, Bundle().apply { putString(PlaybackCommands.EXTRA_MODE, mode.name) })
                            },
                            enabled = state.modeSupported && !pending,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { send(PlaybackCommands.fade, Bundle.EMPTY) },
                enabled = state.fadeSupported && state.playing && !state.busy && !pending,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    val labelSec = FadeRules.shortLabel(FadeRules.manualDurationMs(state.fadeOutMs))
                    stateDescription = if (state.busy) "Fading to pause" else "Fade out and pause over $labelSec"
                },
            ) {
                Icon(UiIcons.Fade, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                val labelSec = FadeRules.shortLabel(FadeRules.manualDurationMs(state.fadeOutMs))
                Text(if (state.busy) "Fading…" else "Fade $labelSec")
            }
        }
        if (status != null) Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
