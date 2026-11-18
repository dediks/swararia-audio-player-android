package com.swararia.audiolab

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

internal enum class FadePhase { NONE, FADE_IN, FADE_OUT }

internal sealed interface PendingFadeOutAction {
    fun preservesPausedIntent(): Boolean = false

    data object Pause : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = true
    }

    data object StopPlayer : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = true
    }

    data object StopAndReset : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = true
    }

    data class SeekNext(val autoPlay: Boolean) : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = !autoPlay
    }

    data class SeekPrevious(val autoPlay: Boolean) : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = !autoPlay
    }

    data class SeekQueueItem(val index: Int, val positionMs: Long, val autoPlay: Boolean) : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = !autoPlay
    }

    data class ReplaceQueue(
        val items: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long,
        val autoPlay: Boolean,
    ) : PendingFadeOutAction {
        override fun preservesPausedIntent(): Boolean = !autoPlay
    }
}

internal interface PlaybackFadeHost {
    val isPreviewActive: Boolean
    val isFocusPausedByLoss: Boolean
    fun getPlayer(): ExoPlayer?
    fun effectiveTargetVolume(): Float
    fun currentFaderGain(): Float
    fun applySessionVolumeUpdate(clampedVolume: Float): Float
    fun applyCurrentMode()
    fun cancelPadDuckRamp()
    fun abandonAudioFocus()
    fun publishPlaybackState()
}

/**
 * Coordinates automated FOH Fade In / Fade Out ramps, switch-action coalescing,
 * and [ForwardingPlayer] interception for local queue playback.
 */
@UnstableApi
internal class PlaybackFadeController(
    private val handler: Handler,
    private val host: PlaybackFadeHost,
    initialNominalVolume: Float,
) {
    private var fadeTick: Runnable? = null
    private var postFadeRestoreTick: Runnable? = null
    private var originalVolume: Float? = null
    private var pendingFadeAction: PendingFadeOutAction? = null
    private var internalFadeOpDepth = 0
    private var pendingSwitchArmedAtUptimeMs = 0L
    private var pendingPauseArmedAtUptimeMs = 0L

    var fadePhase: FadePhase = FadePhase.NONE
        private set
    var nominalVolume: Float = initialNominalVolume
    var fadeSettings: PlaybackSettingsState = PlaybackSettingsState()
    var explicitlyStopped: Boolean = false

    val isInternalOp: Boolean
        get() = internalFadeOpDepth > 0

    val isFadingOut: Boolean
        get() = fadePhase == FadePhase.FADE_OUT || originalVolume != null

    val isFadeActive: Boolean
        get() = fadePhase != FadePhase.NONE || originalVolume != null

    inline fun withInternalGuard(block: () -> Unit) {
        internalFadeOpDepth++
        try {
            block()
        } finally {
            internalFadeOpDepth--
        }
    }

    fun captureNominalVolume(): Float {
        nominalVolume = host.effectiveTargetVolume()
        return nominalVolume
    }

    fun updateNominalForTransition(target: Float) {
        nominalVolume = target
        if (originalVolume != null) originalVolume = target
    }

    fun onOperatorFaderUpdated() {
        cancelPostFadeVolumeRestore()
        host.cancelPadDuckRamp()
        nominalVolume = host.effectiveTargetVolume()
        if (isFadeActive) {
            cancelFade(restoreVolume = false)
        }
        host.getPlayer()?.volume = nominalVolume
        host.publishPlaybackState()
    }

    fun wrapPlayer(exo: ExoPlayer): ForwardingPlayer = FadingSessionPlayer(exo)

    fun cancelPostFadeVolumeRestore() {
        postFadeRestoreTick?.let { handler.removeCallbacks(it) }
        postFadeRestoreTick = null
    }

    private fun clearFadeTickerOnly() {
        cancelPostFadeVolumeRestore()
        fadeTick?.let { handler.removeCallbacks(it) }
        fadeTick = null
        originalVolume = null
        fadePhase = FadePhase.NONE
        pendingFadeAction = null
        pendingSwitchArmedAtUptimeMs = 0L
        pendingPauseArmedAtUptimeMs = 0L
    }

    private fun schedulePostFadeVolumeRestore(player: ExoPlayer, targetVolume: Float) {
        cancelPostFadeVolumeRestore()
        val target = targetVolume.coerceIn(0f, 1f)
        val restore = Runnable {
            postFadeRestoreTick = null
            if (fadePhase == FadePhase.NONE && !player.playWhenReady) {
                player.volume = target
            }
        }
        postFadeRestoreTick = restore
        // Wait briefly so AudioSink finishes draining before nominal gain is restored for next cue.
        handler.postDelayed(restore, POST_FADE_VOLUME_RESTORE_DELAY_MS)
    }

    fun completeFadeOutImmediately() {
        val finish = pendingFadeAction ?: PendingFadeOutAction.Pause
        cancelFade(restoreVolume = false)
        executePendingFadeOutAction(finish)
    }

    fun immediatePause() {
        val player = host.getPlayer()
        cancelFade(restoreVolume = false)
        withInternalGuard { player?.pause() }
        player?.let { schedulePostFadeVolumeRestore(it, nominalVolume) }
        host.publishPlaybackState()
    }

    private fun startFadeRamp(
        phase: FadePhase,
        fromVolume: Float,
        toVolume: Float,
        durationMs: Long,
        actionAfterOut: PendingFadeOutAction? = null,
    ) {
        val player = host.getPlayer() ?: return
        cancelPostFadeVolumeRestore()
        host.cancelPadDuckRamp()
        fadeTick?.let { handler.removeCallbacks(it) }
        fadeTick = null
        val clampedDuration = FadeRules.clampDurationMs(durationMs)
        if (clampedDuration <= 0L) {
            fadePhase = FadePhase.NONE
            originalVolume = null
            pendingFadeAction = null
            pendingSwitchArmedAtUptimeMs = 0L
            pendingPauseArmedAtUptimeMs = 0L
            player.volume = toVolume.coerceIn(0f, 1f)
            if (phase == FadePhase.FADE_OUT && actionAfterOut != null) {
                executePendingFadeOutAction(actionAfterOut)
            }
            host.publishPlaybackState()
            return
        }
        var started = SystemClock.uptimeMillis()
        val postedAt = started
        var audioStarted = player.isPlaying || fromVolume > 0.01f
        var loggedMidpoint = false
        fadePhase = phase
        originalVolume = if (phase == FadePhase.FADE_OUT) nominalVolume else null
        pendingFadeAction = if (phase == FadePhase.FADE_OUT) actionAfterOut else null
        if (phase == FadePhase.FADE_OUT && (
                actionAfterOut is PendingFadeOutAction.ReplaceQueue ||
                    actionAfterOut is PendingFadeOutAction.SeekQueueItem
                )
        ) {
            pendingSwitchArmedAtUptimeMs = SystemClock.uptimeMillis()
        } else {
            pendingSwitchArmedAtUptimeMs = 0L
        }
        pendingPauseArmedAtUptimeMs = if (phase == FadePhase.FADE_OUT && actionAfterOut == PendingFadeOutAction.Pause) {
            SystemClock.uptimeMillis()
        } else {
            0L
        }
        player.volume = fromVolume.coerceIn(0f, 1f)
        android.util.Log.d(
            "SwarariaFade",
            "start phase=$phase from=$fromVolume to=$toVolume durationMs=$clampedDuration isPlaying=${player.isPlaying} action=$actionAfterOut",
        )
        val tick = object : Runnable {
            override fun run() {
                if (fadeTick !== this || fadePhase != phase) return
                if (host.isPreviewActive) {
                    cancelFade()
                    return
                }
                if (phase == FadePhase.FADE_IN && !player.playWhenReady) {
                    cancelFade()
                    return
                }
                val now = SystemClock.uptimeMillis()
                if (phase == FadePhase.FADE_IN && !audioStarted) {
                    if (player.isPlaying || now - postedAt >= 200L) {
                        audioStarted = true
                        started = now
                        android.util.Log.d("SwarariaFade", "audioStarted phase=$phase isPlaying=${player.isPlaying}")
                    } else {
                        handler.postDelayed(this, 16L)
                        return
                    }
                }
                val elapsed = now - started
                val vol = FadeRules.rampVolumeAt(fromVolume, toVolume, elapsed, clampedDuration).coerceIn(0f, 1f)
                player.volume = vol
                if (!loggedMidpoint && elapsed * 2 >= clampedDuration && elapsed < clampedDuration) {
                    loggedMidpoint = true
                    android.util.Log.d("SwarariaFade", "mid phase=$phase elapsed=$elapsed vol=$vol")
                }
                if (elapsed >= clampedDuration) {
                    fadeTick = null
                    val finishedAction = pendingFadeAction
                    pendingFadeAction = null
                    pendingSwitchArmedAtUptimeMs = 0L
                    pendingPauseArmedAtUptimeMs = 0L
                    fadePhase = FadePhase.NONE
                    originalVolume = null
                    android.util.Log.d("SwarariaFade", "done phase=$phase vol=${player.volume} action=$finishedAction")
                    if (phase == FadePhase.FADE_OUT && finishedAction != null) {
                        executePendingFadeOutAction(finishedAction)
                    } else {
                        player.volume = nominalVolume
                    }
                    host.publishPlaybackState()
                } else {
                    handler.postDelayed(this, minOf(20L, clampedDuration - elapsed))
                }
            }
        }
        fadeTick = tick
        host.publishPlaybackState()
        handler.post(tick)
    }

    fun requestPlayWithFade(immediate: Boolean = false) {
        val player = host.getPlayer() ?: return
        cancelPostFadeVolumeRestore()
        explicitlyStopped = false
        if (FadeRules.shouldBypassFade(host.isPreviewActive, immediate)) {
            cancelFade()
            withInternalGuard { player.play() }
            host.publishPlaybackState()
            return
        }
        val targetNominal = captureNominalVolume()
        if (fadePhase == FadePhase.FADE_OUT) {
            val currentVol = player.volume.coerceIn(0f, targetNominal)
            val fadeInMs = fadeSettings.fadeInMs
            val targetDuration = if (fadeInMs > 0L) {
                FadeRules.proportionalDurationMs(fadeInMs, currentVol, targetNominal, targetNominal)
            } else {
                FadeRules.proportionalDurationMs(150L, currentVol, targetNominal, targetNominal)
            }
            clearFadeTickerOnly()
            withInternalGuard { player.play() }
            startFadeRamp(FadePhase.FADE_IN, currentVol, targetNominal, targetDuration)
            return
        }
        if (fadePhase == FadePhase.FADE_IN && player.playWhenReady) {
            return
        }
        val fadeInMs = fadeSettings.fadeInMs
        if (fadeInMs <= 0L || targetNominal <= 0.001f) {
            cancelFade()
            withInternalGuard { player.play() }
            host.publishPlaybackState()
            return
        }
        if (player.isPlaying && fadePhase == FadePhase.NONE) return
        clearFadeTickerOnly()
        player.volume = 0f
        withInternalGuard { player.play() }
        startFadeRamp(FadePhase.FADE_IN, 0f, targetNominal, fadeInMs)
    }

    private inline fun launchFadeOutOrRun(
        action: PendingFadeOutAction,
        immediate: Boolean,
        runNow: () -> Unit,
    ) {
        val player = host.getPlayer() ?: return
        if (FadeRules.shouldBypassFade(host.isPreviewActive, immediate) || fadePhase == FadePhase.FADE_OUT) {
            cancelFade(restoreVolume = false)
            runNow()
            return
        }
        val fadeOutMs = fadeSettings.fadeOutMs
        val shouldFade = (player.isPlaying || fadePhase == FadePhase.FADE_IN) && fadeOutMs > 0L
        if (!shouldFade) {
            cancelFade(restoreVolume = false)
            runNow()
            return
        }
        val targetNominal = captureNominalVolume()
        val startVol = if (fadePhase == FadePhase.FADE_IN) player.volume.coerceIn(0f, targetNominal) else targetNominal
        if (startVol <= 0.001f) {
            cancelFade(restoreVolume = false)
            runNow()
            return
        }
        val duration = if (fadePhase == FadePhase.FADE_IN) {
            FadeRules.proportionalDurationMs(fadeOutMs, startVol, 0f, targetNominal)
        } else {
            fadeOutMs
        }
        clearFadeTickerOnly()
        startFadeRamp(FadePhase.FADE_OUT, startVol, 0f, duration, action)
    }

    fun requestPauseWithFade(immediate: Boolean = false) {
        val player = host.getPlayer() ?: return
        explicitlyStopped = false
        launchFadeOutOrRun(PendingFadeOutAction.Pause, immediate) {
            withInternalGuard { player.pause() }
            player.volume = captureNominalVolume()
            host.publishPlaybackState()
        }
    }

    fun requestStopWithFade(resetToCue: Boolean, immediate: Boolean = false) {
        val player = host.getPlayer() ?: return
        val action = if (resetToCue) PendingFadeOutAction.StopAndReset else PendingFadeOutAction.StopPlayer
        val stopImmediate = immediate || (!player.isPlaying && !player.playWhenReady && fadePhase != FadePhase.FADE_IN)
        launchFadeOutOrRun(action, stopImmediate) {
            executePendingFadeOutAction(action)
        }
    }

    fun requestSwitchTrackWithFade(action: PendingFadeOutAction, immediate: Boolean = false) {
        explicitlyStopped = false
        launchFadeOutOrRun(action, immediate) {
            executeSwitchActionNow(action)
        }
    }

    private fun executePendingFadeOutAction(action: PendingFadeOutAction) {
        val player = host.getPlayer() ?: return
        when (action) {
            PendingFadeOutAction.Pause -> {
                withInternalGuard { player.pause() }
                player.volume = 0f
                schedulePostFadeVolumeRestore(player, nominalVolume)
            }
            PendingFadeOutAction.StopPlayer -> {
                withInternalGuard { player.stop() }
                player.volume = 0f
                schedulePostFadeVolumeRestore(player, nominalVolume)
            }
            PendingFadeOutAction.StopAndReset -> {
                withInternalGuard {
                    player.pause()
                    player.seekTo(0L)
                    explicitlyStopped = true
                }
                player.volume = 0f
                schedulePostFadeVolumeRestore(player, nominalVolume)
            }
            is PendingFadeOutAction.SeekNext,
            is PendingFadeOutAction.SeekPrevious,
            is PendingFadeOutAction.SeekQueueItem,
            is PendingFadeOutAction.ReplaceQueue -> {
                executeSwitchActionNow(action)
                return
            }
        }
        host.publishPlaybackState()
    }

    private fun executeSwitchActionNow(action: PendingFadeOutAction) {
        val player = host.getPlayer() ?: return
        cancelPostFadeVolumeRestore()
        val fadeInMs = if (!host.isPreviewActive) fadeSettings.fadeInMs else 0L
        val targetNominal = captureNominalVolume()
        var shouldAutoPlay = false
        withInternalGuard {
            when (action) {
                is PendingFadeOutAction.SeekNext -> {
                    shouldAutoPlay = action.autoPlay
                    player.seekToNextMediaItem()
                }
                is PendingFadeOutAction.SeekPrevious -> {
                    shouldAutoPlay = action.autoPlay
                    player.seekToPreviousMediaItem()
                }
                is PendingFadeOutAction.SeekQueueItem -> {
                    shouldAutoPlay = action.autoPlay
                    player.seekTo(action.index, action.positionMs)
                    player.prepare()
                }
                is PendingFadeOutAction.ReplaceQueue -> {
                    shouldAutoPlay = action.autoPlay
                    player.volume = if (shouldAutoPlay && fadeInMs > 0L) 0f else targetNominal
                    player.setMediaItems(action.items, action.startIndex, action.startPositionMs)
                    host.applyCurrentMode()
                    player.prepare()
                }
                PendingFadeOutAction.Pause,
                PendingFadeOutAction.StopPlayer,
                PendingFadeOutAction.StopAndReset -> Unit
            }
            if (shouldAutoPlay) {
                player.volume = if (fadeInMs > 0L) 0f else targetNominal
                player.play()
            } else {
                player.pause()
                player.volume = targetNominal
            }
        }
        if (shouldAutoPlay && fadeInMs > 0L && !host.isPreviewActive) {
            startFadeRamp(FadePhase.FADE_IN, 0f, targetNominal, fadeInMs)
        } else {
            player.volume = targetNominal
        }
        host.publishPlaybackState()
    }

    fun startFade(): Boolean {
        val player = host.getPlayer() ?: return false
        val activePlayback = player.isPlaying || fadePhase == FadePhase.FADE_IN
        val busyFadingOut = isFadingOut
        if (!FadeRules.canStart(activePlayback, busyFadingOut)) return false
        val manualMs = fadeSettings.effectiveManualFadeOutMs
        val targetNominal = captureNominalVolume()
        val initial = if (fadePhase == FadePhase.FADE_IN) player.volume.coerceIn(0f, targetNominal) else targetNominal
        if (initial <= 0.001f) {
            cancelFade(restoreVolume = false)
            executePendingFadeOutAction(PendingFadeOutAction.Pause)
            return true
        }
        val duration = if (fadePhase == FadePhase.FADE_IN) {
            FadeRules.proportionalDurationMs(manualMs, initial, 0f, targetNominal)
        } else {
            manualMs
        }
        clearFadeTickerOnly()
        startFadeRamp(FadePhase.FADE_OUT, initial, 0f, duration, PendingFadeOutAction.Pause)
        return true
    }

    fun cancelFade(restoreVolume: Boolean = true) {
        cancelPostFadeVolumeRestore()
        host.cancelPadDuckRamp()
        val wasBusy = isFadingOut
        fadeTick?.let { handler.removeCallbacks(it) }
        fadeTick = null
        val restore = originalVolume ?: nominalVolume
        originalVolume = null
        fadePhase = FadePhase.NONE
        pendingFadeAction = null
        pendingSwitchArmedAtUptimeMs = 0L
        pendingPauseArmedAtUptimeMs = 0L
        if (restoreVolume) {
            host.getPlayer()?.volume = restore.coerceIn(0f, 1f)
        }
        if (wasBusy) host.publishPlaybackState()
        // Never call play(): cancellation must not undo a pause, focus loss, stop or track change.
    }

    private inner class FadingSessionPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        override fun setVolume(volume: Float) {
            if (fadePhase != FadePhase.NONE) {
                return
            }
            if (!host.isPreviewActive) {
                nominalVolume = host.applySessionVolumeUpdate(volume.coerceIn(0f, 1f))
                super.setVolume(nominalVolume)
                host.publishPlaybackState()
                return
            }
            super.setVolume(volume.coerceIn(0f, 1f).coerceAtMost(host.currentFaderGain()))
        }

        override fun play() {
            if (host.isPreviewActive) {
                super.play()
                return
            }
            val currentPending = pendingFadeAction
            if (fadePhase == FadePhase.FADE_OUT &&
                (currentPending is PendingFadeOutAction.ReplaceQueue || currentPending is PendingFadeOutAction.SeekQueueItem) &&
                SystemClock.uptimeMillis() - pendingSwitchArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
            ) {
                pendingFadeAction = when (currentPending) {
                    is PendingFadeOutAction.ReplaceQueue -> currentPending.copy(autoPlay = true)
                    is PendingFadeOutAction.SeekQueueItem -> currentPending.copy(autoPlay = true)
                    else -> currentPending
                }
                return
            }
            requestPlayWithFade()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (host.isPreviewActive) {
                super.setPlayWhenReady(playWhenReady)
                return
            }
            if (playWhenReady) play() else pause()
        }

        override fun pause() {
            if (host.isPreviewActive) {
                super.pause()
                return
            }
            if (host.isFocusPausedByLoss) host.abandonAudioFocus()
            requestPauseWithFade()
        }

        override fun stop() {
            if (host.isPreviewActive) {
                super.stop()
                return
            }
            if (host.isFocusPausedByLoss) host.abandonAudioFocus()
            requestStopWithFade(resetToCue = false)
        }

        override fun prepare() {
            if (!host.isPreviewActive && fadePhase == FadePhase.FADE_OUT && (
                    pendingFadeAction is PendingFadeOutAction.ReplaceQueue ||
                        pendingFadeAction is PendingFadeOutAction.SeekQueueItem
                    ) && SystemClock.uptimeMillis() - pendingSwitchArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
            ) {
                return
            }
            super.prepare()
        }

        override fun seekToNext() {
            seekToNextMediaItem()
        }

        override fun seekToNextMediaItem() {
            if (host.isPreviewActive || !exo.hasNextMediaItem()) {
                super.seekToNextMediaItem()
                return
            }
            val autoPlay = exo.playWhenReady || exo.isPlaying
            requestSwitchTrackWithFade(
                PendingFadeOutAction.SeekNext(autoPlay = autoPlay),
                immediate = !(exo.isPlaying || fadePhase == FadePhase.FADE_IN),
            )
        }

        override fun seekToPrevious() {
            seekToPreviousMediaItem()
        }

        override fun seekToPreviousMediaItem() {
            if (host.isPreviewActive) {
                super.seekToPreviousMediaItem()
                return
            }
            val autoPlay = exo.playWhenReady || exo.isPlaying
            requestSwitchTrackWithFade(
                PendingFadeOutAction.SeekPrevious(autoPlay = autoPlay),
                immediate = !(exo.isPlaying || fadePhase == FadePhase.FADE_IN),
            )
        }

        override fun seekTo(positionMs: Long) {
            if (!host.isPreviewActive && positionMs == 0L && fadePhase == FadePhase.FADE_OUT) {
                if (pendingFadeAction == PendingFadeOutAction.Pause &&
                    SystemClock.uptimeMillis() - pendingPauseArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
                ) {
                    // Upgrade Pause -> StopAndReset when Cue 00:00 calls pause(); seekTo(0L) together.
                    pendingPauseArmedAtUptimeMs = 0L
                    pendingFadeAction = PendingFadeOutAction.StopAndReset
                    return
                }
                if (pendingFadeAction == PendingFadeOutAction.StopAndReset) {
                    // 2nd tap on Cue 00:00 -> hard cut immediately.
                    cancelFade(restoreVolume = false)
                    executePendingFadeOutAction(PendingFadeOutAction.StopAndReset)
                    return
                }
            }
            super.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (!host.isPreviewActive && fadePhase == FadePhase.FADE_OUT &&
                mediaItemIndex == exo.currentMediaItemIndex && positionMs == 0L
            ) {
                if (pendingFadeAction == PendingFadeOutAction.Pause &&
                    SystemClock.uptimeMillis() - pendingPauseArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
                ) {
                    pendingPauseArmedAtUptimeMs = 0L
                    pendingFadeAction = PendingFadeOutAction.StopAndReset
                    return
                }
                if (pendingFadeAction == PendingFadeOutAction.StopAndReset) {
                    cancelFade(restoreVolume = false)
                    executePendingFadeOutAction(PendingFadeOutAction.StopAndReset)
                    return
                }
            }
            if (!host.isPreviewActive && fadePhase == FadePhase.FADE_OUT &&
                pendingFadeAction == PendingFadeOutAction.Pause &&
                SystemClock.uptimeMillis() - pendingPauseArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS &&
                mediaItemIndex in 0 until exo.mediaItemCount
            ) {
                // Queue Cue-only called pause(); seekTo(otherIndex, 0L) synchronously.
                pendingPauseArmedAtUptimeMs = 0L
                pendingSwitchArmedAtUptimeMs = SystemClock.uptimeMillis()
                pendingFadeAction = PendingFadeOutAction.SeekQueueItem(mediaItemIndex, positionMs, autoPlay = false)
                return
            }
            if (!host.isPreviewActive && mediaItemIndex != exo.currentMediaItemIndex &&
                mediaItemIndex in 0 until exo.mediaItemCount
            ) {
                val autoPlay = FadeRules.shouldAutoPlayOnSwitch(
                    playWhenReady = exo.playWhenReady,
                    isPlaying = exo.isPlaying,
                    fadingOutToStopOrPause = fadePhase == FadePhase.FADE_OUT &&
                        pendingFadeAction?.preservesPausedIntent() == true,
                )
                requestSwitchTrackWithFade(
                    PendingFadeOutAction.SeekQueueItem(mediaItemIndex, positionMs, autoPlay = autoPlay),
                    immediate = !(exo.isPlaying || fadePhase == FadePhase.FADE_IN),
                )
                return
            }
            super.seekTo(mediaItemIndex, positionMs)
        }

        private fun interceptQueueReplacement(
            items: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            fallback: () -> Unit,
        ) {
            if (!host.isPreviewActive && fadePhase == FadePhase.FADE_OUT &&
                pendingFadeAction == PendingFadeOutAction.Pause &&
                SystemClock.uptimeMillis() - pendingPauseArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
            ) {
                // Library/Downloads Cue-only called pause(); setMediaItems(..., 0).
                pendingPauseArmedAtUptimeMs = 0L
                pendingSwitchArmedAtUptimeMs = SystemClock.uptimeMillis()
                pendingFadeAction = PendingFadeOutAction.ReplaceQueue(items, startIndex, startPositionMs, autoPlay = false)
                return
            }
            if (!host.isPreviewActive && (exo.isPlaying || fadePhase == FadePhase.FADE_IN || fadePhase == FadePhase.FADE_OUT)) {
                val autoPlay = FadeRules.shouldAutoPlayOnSwitch(
                    playWhenReady = exo.playWhenReady,
                    isPlaying = exo.isPlaying,
                    fadingOutToStopOrPause = fadePhase == FadePhase.FADE_OUT &&
                        pendingFadeAction?.preservesPausedIntent() == true,
                )
                requestSwitchTrackWithFade(
                    PendingFadeOutAction.ReplaceQueue(items, startIndex, startPositionMs, autoPlay = autoPlay),
                    immediate = !(exo.isPlaying || fadePhase == FadePhase.FADE_IN),
                )
                return
            }
            fallback()
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>) =
            interceptQueueReplacement(mediaItems.toList(), 0, 0L) { super.setMediaItems(mediaItems) }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            val idx = if (resetPosition) 0 else exo.currentMediaItemIndex.coerceAtLeast(0)
            val pos = if (resetPosition) 0L else exo.currentPosition.coerceAtLeast(0L)
            interceptQueueReplacement(mediaItems.toList(), idx, pos) { super.setMediaItems(mediaItems, resetPosition) }
        }

        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) = interceptQueueReplacement(mediaItems.toList(), startIndex, startPositionMs) {
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun setMediaItem(mediaItem: MediaItem) =
            interceptQueueReplacement(listOf(mediaItem), 0, 0L) { super.setMediaItem(mediaItem) }

        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
            interceptQueueReplacement(listOf(mediaItem), 0, startPositionMs) { super.setMediaItem(mediaItem, startPositionMs) }

        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
            val pos = if (resetPosition) 0L else exo.currentPosition.coerceAtLeast(0L)
            interceptQueueReplacement(listOf(mediaItem), 0, pos) { super.setMediaItem(mediaItem, resetPosition) }
        }
    }

    companion object {
        private const val SWITCH_MERGE_WINDOW_MS = 600L
        private const val POST_FADE_VOLUME_RESTORE_DELAY_MS = 80L
    }
}
