package com.swararia.audiolab

import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.mutableLongStateOf
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
    const val ACTION_SET_FADER = "com.swararia.audiolab.SET_FADER"
    const val ACTION_TOGGLE_MUTE = "com.swararia.audiolab.TOGGLE_MUTE"
    const val ACTION_TOGGLE_DUCK = "com.swararia.audiolab.TOGGLE_DUCK"
    const val ACTION_RESET_FADER = "com.swararia.audiolab.RESET_FADER"
    const val ACTION_SET_MONO_SUM = "com.swararia.audiolab.SET_MONO_SUM"
    const val ACTION_SET_PAD_DUCK = "com.swararia.audiolab.SET_PAD_DUCK"
    const val ACTION_SET_FOCUS_GUARD = "com.swararia.audiolab.SET_FOCUS_GUARD"
    const val EXTRA_MODE = "com.swararia.audiolab.playback.MODE"
    const val EXTRA_FADE_BUSY = "com.swararia.audiolab.playback.FADE_BUSY"
    const val EXTRA_FADE_IN_MS = "com.swararia.audiolab.playback.FADE_IN_MS"
    const val EXTRA_FADE_OUT_MS = "com.swararia.audiolab.playback.FADE_OUT_MS"
    const val EXTRA_FADER_POS = "com.swararia.audiolab.playback.FADER_POS"
    const val EXTRA_FADER_MUTED = "com.swararia.audiolab.playback.FADER_MUTED"
    const val EXTRA_FADER_DUCKED = "com.swararia.audiolab.playback.FADER_DUCKED"
    const val EXTRA_FADER_PRE_DUCK = "com.swararia.audiolab.playback.FADER_PRE_DUCK"
    const val EXTRA_MONO_SUM = "com.swararia.audiolab.playback.MONO_SUM"
    const val EXTRA_PAD_DUCK = "com.swararia.audiolab.playback.PAD_DUCK"
    const val EXTRA_FOCUS_GUARD = "com.swararia.audiolab.playback.FOCUS_GUARD"
    const val EXTRA_PROTOCOL = "com.swararia.audiolab.playback.PROTOCOL"
    val setMode = SessionCommand(ACTION_SET_MODE, Bundle.EMPTY)
    val fade = SessionCommand(ACTION_FADE, Bundle.EMPTY)
    val immediatePause = SessionCommand(ACTION_IMMEDIATE_PAUSE, Bundle.EMPTY)
    val setFadeSettings = SessionCommand(ACTION_SET_FADE_SETTINGS, Bundle.EMPTY)
    val setFader = SessionCommand(ACTION_SET_FADER, Bundle.EMPTY)
    val toggleMute = SessionCommand(ACTION_TOGGLE_MUTE, Bundle.EMPTY)
    val toggleDuck = SessionCommand(ACTION_TOGGLE_DUCK, Bundle.EMPTY)
    val resetFader = SessionCommand(ACTION_RESET_FADER, Bundle.EMPTY)
    val setMonoSum = SessionCommand(ACTION_SET_MONO_SUM, Bundle.EMPTY)
    val setPadDuck = SessionCommand(ACTION_SET_PAD_DUCK, Bundle.EMPTY)
    val setFocusGuard = SessionCommand(ACTION_SET_FOCUS_GUARD, Bundle.EMPTY)
}

private data class PlaybackControlState(
    val mode: PlaybackMode? = null,
    val busy: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val faderState: FaderState = FaderState(),
    val monoSum: Boolean = false,
    val padDucked: Boolean = false,
    val focusGuard: Boolean = false,
    val modeSupported: Boolean = false,
    val fadeSupported: Boolean = false,
    val faderSupported: Boolean = false,
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
    val faderState = if (knownProtocol) {
        FaderState(
            position = FaderRules.clampPosition(extras.getFloat(PlaybackCommands.EXTRA_FADER_POS, FaderRules.DEFAULT_POSITION)),
            muted = extras.getBoolean(PlaybackCommands.EXTRA_FADER_MUTED, false),
            ducked = extras.getBoolean(PlaybackCommands.EXTRA_FADER_DUCKED, false),
            preDuckPosition = FaderRules.clampPosition(extras.getFloat(PlaybackCommands.EXTRA_FADER_PRE_DUCK, FaderRules.DEFAULT_POSITION)),
        )
    } else FaderState()
    return PlaybackControlState(
        mode = mode,
        busy = knownProtocol && extras.getBoolean(PlaybackCommands.EXTRA_FADE_BUSY, false),
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        faderState = faderState,
        monoSum = knownProtocol && extras.getBoolean(PlaybackCommands.EXTRA_MONO_SUM, false),
        padDucked = knownProtocol && extras.getBoolean(PlaybackCommands.EXTRA_PAD_DUCK, false),
        focusGuard = knownProtocol && extras.getBoolean(PlaybackCommands.EXTRA_FOCUS_GUARD, false),
        modeSupported = knownProtocol && mode != null && isSessionCommandAvailable(PlaybackCommands.setMode),
        fadeSupported = knownProtocol && isSessionCommandAvailable(PlaybackCommands.fade),
        faderSupported = knownProtocol && isSessionCommandAvailable(PlaybackCommands.setFader),
        playing = isPlaying,
        connected = true,
    )
}

/** Reads the current master fader snapshot from session extras, defaulting to Unity when disconnected. */
internal fun MediaController?.readFaderState(): FaderState =
    readPlaybackControlState().faderState

/** Observes [FaderState] while STARTED so compact status pills stay synchronized off the focused player. */
@Composable
internal fun rememberControllerFaderState(controller: MediaController?): FaderState {
    val owner = LocalLifecycleOwner.current
    var faderState by remember(controller) { mutableStateOf(controller.readFaderState()) }
    LaunchedEffect(controller, owner) {
        if (controller == null) {
            faderState = FaderState()
            return@LaunchedEffect
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                faderState = controller.readFaderState()
                delay(350)
            }
        }
    }
    return faderState
}

data class LocalPlaybackControlUi(
    val mode: PlaybackMode?,
    val fadeBusy: Boolean,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val faderState: FaderState = FaderState(),
    val monoSum: Boolean = false,
    val padDucked: Boolean = false,
    val focusGuard: Boolean = false,
    val modeSupported: Boolean,
    val fadeSupported: Boolean,
    val faderSupported: Boolean = false,
    val playing: Boolean,
    val connected: Boolean,
    val pending: Boolean,
    val statusText: String?,
    val isError: Boolean,
    val onSelectMode: (PlaybackMode) -> Unit,
    val onFadeOut: () -> Unit,
    val onSetFaderPosition: (Float) -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleDuck: () -> Unit = {},
    val onResetFader: () -> Unit = {},
    val onSetMonoSum: (Boolean) -> Unit = {},
    val onSetPadDuck: (Boolean) -> Unit = {},
    val onSetFocusGuard: (Boolean) -> Unit = {},
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
    var optimisticFader by remember(controller) { mutableStateOf<FaderState?>(null) }
    var lastFaderEditUptimeMs by remember(controller) { mutableLongStateOf(0L) }
    var optimisticMonoSum by remember(controller) { mutableStateOf<Boolean?>(null) }
    var lastMonoEditUptimeMs by remember(controller) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var commandJob by remember(controller) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var faderJob by remember(controller) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var queuedFaderCommand by remember(controller) { mutableStateOf<Pair<SessionCommand, Bundle>?>(null) }
    DisposableEffect(controller) {
        onDispose {
            commandJob?.cancel()
            faderJob?.cancel()
        }
    }

    // Media3 1.8 exposes the extras listener only at controller construction time, not addListener.
    // Bounded polling keeps this drop-in API independent of the parent's builder/listener ownership.
    // LaunchedEffect cancellation stops polling as soon as the bar leaves composition.
    LaunchedEffect(controller, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                state = controller.readPlaybackControlState()
                if (!state.connected || !state.modeSupported) expanded = false
                if (optimisticFader != null &&
                    faderJob?.isActive != true &&
                    queuedFaderCommand == null &&
                    (state.faderState == optimisticFader ||
                        SystemClock.uptimeMillis() - lastFaderEditUptimeMs > 500L)
                ) {
                    optimisticFader = null
                }
                if (optimisticMonoSum != null &&
                    (state.monoSum == optimisticMonoSum ||
                        SystemClock.uptimeMillis() - lastMonoEditUptimeMs > 500L)
                ) {
                    optimisticMonoSum = null
                }
                delay(200)
            }
        }
    }

    fun sendQuickCommand(command: SessionCommand, args: Bundle) {
        val target = controller ?: return
        if (!target.isConnected || !target.isSessionCommandAvailable(command)) return
        scope.launch {
            var future: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            try {
                future = target.sendCustomCommand(command, args)
                withTimeout(2000L) {
                    while (!future.isDone) delay(12)
                    future.get()
                }
                state = target.readPlaybackControlState()
            } catch (_: Exception) {
                if (future?.isDone == false) future.cancel(false)
            }
        }
    }

    fun dispatchFader(command: SessionCommand, args: Bundle, nextState: FaderState) {
        val target = controller ?: return
        if (!target.isConnected || !target.isSessionCommandAvailable(command)) return
        optimisticFader = nextState
        lastFaderEditUptimeMs = SystemClock.uptimeMillis()
        queuedFaderCommand = command to args
        if (faderJob?.isActive == true) return
        faderJob = scope.launch {
            try {
                while (isActive) {
                    val next = queuedFaderCommand ?: break
                    queuedFaderCommand = null
                    var future: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
                    try {
                        future = target.sendCustomCommand(next.first, next.second)
                        withTimeout(2000L) {
                            while (!future.isDone) delay(12)
                            future.get()
                        }
                    } catch (_: Exception) {
                        if (future?.isDone == false) future.cancel(false)
                    }
                    if (queuedFaderCommand != null) delay(12)
                }
                state = target.readPlaybackControlState()
            } finally {
                faderJob = null
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

    val activeFader = optimisticFader ?: state.faderState
    val activeMonoSum = optimisticMonoSum ?: state.monoSum
    val status = error ?: when {
        !state.connected -> "Playback disconnected"
        !state.modeSupported -> "Playback modes unavailable"
        state.busy -> "Fading out — ketuk Fade lagi untuk potong instan"
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
                    faderState = activeFader,
                    monoSum = activeMonoSum,
                    padDucked = state.padDucked,
                    focusGuard = state.focusGuard,
                    modeSupported = state.modeSupported,
                    fadeSupported = state.fadeSupported,
                    faderSupported = state.faderSupported,
                    playing = state.playing,
                    connected = state.connected,
                    pending = pending,
                    statusText = status,
                    isError = error != null,
                    onSelectMode = { mode ->
                        send(PlaybackCommands.setMode, Bundle().apply { putString(PlaybackCommands.EXTRA_MODE, mode.name) })
                    },
                    onFadeOut = { send(PlaybackCommands.fade, Bundle.EMPTY) },
                    onSetFaderPosition = { rawPos ->
                        val current = optimisticFader ?: state.faderState
                        val next = FaderRules.withPosition(current, rawPos)
                        dispatchFader(
                            PlaybackCommands.setFader,
                            Bundle().apply { putFloat(PlaybackCommands.EXTRA_FADER_POS, next.position) },
                            next,
                        )
                    },
                    onToggleMute = {
                        val current = optimisticFader ?: state.faderState
                        dispatchFader(
                            PlaybackCommands.toggleMute,
                            Bundle.EMPTY,
                            FaderRules.toggleMute(current),
                        )
                    },
                    onToggleDuck = {
                        val current = optimisticFader ?: state.faderState
                        dispatchFader(
                            PlaybackCommands.toggleDuck,
                            Bundle.EMPTY,
                            FaderRules.toggleDuck(current),
                        )
                    },
                    onResetFader = {
                        dispatchFader(
                            PlaybackCommands.resetFader,
                            Bundle.EMPTY,
                            FaderRules.resetUnity(),
                        )
                    },
                    onSetMonoSum = { enabled ->
                        optimisticMonoSum = enabled
                        lastMonoEditUptimeMs = SystemClock.uptimeMillis()
                        sendQuickCommand(
                            PlaybackCommands.setMonoSum,
                            Bundle().apply { putBoolean(PlaybackCommands.EXTRA_MONO_SUM, enabled) },
                        )
                    },
                    onSetPadDuck = { ducked ->
                        sendQuickCommand(
                            PlaybackCommands.setPadDuck,
                            Bundle().apply { putBoolean(PlaybackCommands.EXTRA_PAD_DUCK, ducked) },
                        )
                    },
                    onSetFocusGuard = { guarded ->
                        sendQuickCommand(
                            PlaybackCommands.setFocusGuard,
                            Bundle().apply { putBoolean(PlaybackCommands.EXTRA_FOCUS_GUARD, guarded) },
                        )
                    },
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
                    Icon(state.mode?.let(UiIcons::forPlaybackMode) ?: UiIcons.Loop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(state.mode?.label ?: "Mode")
                    Spacer(Modifier.width(8.dp)); Icon(UiIcons.ChevronDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    PlaybackMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(UiIcons.forPlaybackMode(mode), null, Modifier.size(18.dp)) },
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
