package com.swararia.audiolab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.swararia.audiolab.foh.FaderRules
import com.swararia.audiolab.foh.FaderState
import com.swararia.audiolab.foh.FohSafeModeController
import com.swararia.audiolab.foh.FohSafeModeRules
import com.swararia.audiolab.foh.FohSafeModeSettings
import com.swararia.audiolab.foh.GraphicEq31AudioProcessor
import com.swararia.audiolab.foh.GraphicEqRules
import com.swararia.audiolab.foh.GraphicEqState
import com.swararia.audiolab.foh.LocalAudioSignalBus
import com.swararia.audiolab.foh.LocalAudioSignalTapProcessor
import com.swararia.audiolab.foh.LocalSignalRules
import com.swararia.audiolab.foh.MonoSumAudioProcessor
import com.swararia.audiolab.foh.SoundboardRules
import com.swararia.audiolab.remote.RemoteCommandExecutionRequest
import com.swararia.audiolab.remote.RemoteCommandExecutionResult
import com.swararia.audiolab.remote.RemoteQueueEntry
import com.swararia.audiolab.remote.RemoteServerPlaybackSnapshot
import com.swararia.audiolab.remote.RemoteServicePlaybackHost
import kotlinx.coroutines.*

/** One service player/notification. Preview temporarily borrows it; local context returns PAUSED. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var appPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var faderState = FaderState()
    private val fadeController = PlaybackFadeController(
        handler = handler,
        host = object : PlaybackFadeHost {
            override val isPreviewActive: Boolean
                get() = savedLocal != null
            override val isFocusPausedByLoss: Boolean
                get() = focusPausedByLoss
            override fun getPlayer(): ExoPlayer? = appPlayer
            override fun effectiveTargetVolume(): Float = effectiveTargetVolume()
            override fun currentFaderGain(): Float = faderState.effectiveGain
            override fun applySessionVolumeUpdate(clampedVolume: Float): Float {
                faderState = FaderRules.withPosition(faderState, FaderRules.positionFromGain(clampedVolume))
                    .copy(muted = clampedVolume <= 0f)
                return effectiveTargetVolume()
            }
            override fun applyCurrentMode() = applyMode(mode)
            override fun cancelPadDuckRamp() = this@PlaybackService.cancelPadDuckRamp()
            override fun abandonAudioFocus() = abandonPlaybackAudioFocus()
            override fun publishPlaybackState() = publishState()
        },
        initialNominalVolume = faderState.effectiveGain,
    )
    private var monoSumEnabled = false
    private var eqState = GraphicEqState()
    private var padDuckActive = false
    private var focusGuardEnabled = false
    private var focusDucked = false
    private var focusPausedByLoss = false
    private var hasAudioFocus = false
    private var padDuckTick: Runnable? = null
    private val playbackPrefs by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var remoteWifiLock: WifiManager.WifiLock? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PlaybackPreferences.KEY_FADE_IN_MS || key == PlaybackPreferences.KEY_FADE_OUT_MS) {
            handler.post { reloadFadeSettings(prefs) }
        } else if (key == FohSafeModeController.KEY_SAFE_ENABLED || key == FohSafeModeController.KEY_FOCUS_LOCK) {
            handler.post { reloadSafeFocusSetting(prefs) }
        }
    }
    private var mode = PlaybackMode.CONTINUE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var previewOwner: PreviewSession
    private val lease = PreviewLease()
    private var savedLocal: PreviewLocalQueue<MediaItem>? = null
    private var savedExplicitlyStopped = false
    private var changing = false
    private var previewPrepared = false
    private var previewPhase = ""
    private var prepareStarted = 0L
    private var readyRecorded = false
    private var playingRecorded = false
    private var requestStarted = 0L
    private var requestDispatched = 0L
    private var resolvingForeground = false
    private var remoteServerForegroundActive = false
    private var remoteEndpointLabel = "LAN"
    private val monoSumProcessor = MonoSumAudioProcessor()
    private val eqProcessor = GraphicEq31AudioProcessor()
    private val signalTapProcessor = LocalAudioSignalTapProcessor()
    private var signalTickRunning = false
    private val signalTick = object : Runnable {
        override fun run() {
            val player = appPlayer
            val localPlaying = savedLocal == null && player?.isPlaying == true
            val currentGain = if (savedLocal == null) (player?.volume ?: 0f).coerceIn(0f, 1f) else 0f
            val raw = if (localPlaying) signalTapProcessor.pollNextFrame() else null
            val next = LocalSignalRules.stepBallistics(
                previous = LocalAudioSignalBus.state.value,
                rawFrame = raw,
                playing = localPlaying,
                postFaderGain = currentGain,
                muted = faderState.muted,
                nowMs = SystemClock.uptimeMillis(),
            )
            LocalAudioSignalBus.publish(next)
            if (localPlaying || next.active) {
                handler.postDelayed(this, SIGNAL_TICK_MS)
            } else {
                signalTickRunning = false
            }
        }
    }

    private val remotePlaybackHost = object : RemoteServicePlaybackHost {
        override fun currentSnapshot(): RemoteServerPlaybackSnapshot = buildRemoteSnapshot()

        override fun executeRemoteCommand(
            request: RemoteCommandExecutionRequest,
            queueEntries: List<RemoteQueueEntry>,
        ): RemoteCommandExecutionResult {
            if (savedLocal != null) {
                return RemoteCommandExecutionResult(applied = false, errorCode = "preview_active")
            }
            val player = appPlayer ?: return RemoteCommandExecutionResult(applied = false, errorCode = "player_unavailable")
            when (request.action) {
                "select_track" -> {
                    val idx = queueEntries.indexOfFirst { it.numericId == request.trackNumericId }
                    if (idx < 0) return RemoteCommandExecutionResult(applied = false, errorCode = "unknown_track")
                    val items = toMediaItems(queueEntries)
                    fadeController.explicitlyStopped = false
                    fadeController.requestSwitchTrackWithFade(
                        PendingFadeOutAction.ReplaceQueue(items, idx, 0L, autoPlay = request.autoPlay),
                        immediate = !(player.isPlaying || fadeController.fadePhase == FadePhase.FADE_IN),
                    )
                }
                "play" -> {
                    if (player.mediaItemCount == 0) {
                        if (queueEntries.isEmpty()) {
                            return RemoteCommandExecutionResult(applied = false, errorCode = "empty_queue")
                        }
                        player.setMediaItems(toMediaItems(queueEntries), 0, 0L)
                        applyMode(mode)
                        player.prepare()
                    }
                    fadeController.explicitlyStopped = false
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        player.prepare()
                    }
                    fadeController.requestPlayWithFade()
                }
                "pause" -> {
                    fadeController.explicitlyStopped = false
                    fadeController.requestPauseWithFade()
                }
                "stop" -> {
                    fadeController.requestStopWithFade(resetToCue = true)
                }
                "fade_out" -> {
                    if (!fadeController.startFade()) return RemoteCommandExecutionResult(applied = false, errorCode = "cannot_fade")
                }
                "prev" -> {
                    if (player.mediaItemCount == 0) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "empty_queue")
                    }
                    val autoPlay = player.playWhenReady || player.isPlaying
                    fadeController.explicitlyStopped = false
                    fadeController.requestSwitchTrackWithFade(
                        PendingFadeOutAction.SeekPrevious(autoPlay = autoPlay),
                        immediate = !(player.isPlaying || fadeController.fadePhase == FadePhase.FADE_IN),
                    )
                }
                "next" -> {
                    if (player.mediaItemCount == 0 || !player.hasNextMediaItem()) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "no_next_track")
                    }
                    val autoPlay = player.playWhenReady || player.isPlaying
                    fadeController.explicitlyStopped = false
                    fadeController.requestSwitchTrackWithFade(
                        PendingFadeOutAction.SeekNext(autoPlay = autoPlay),
                        immediate = !(player.isPlaying || fadeController.fadePhase == FadePhase.FADE_IN),
                    )
                }
                else -> return RemoteCommandExecutionResult(applied = false, errorCode = "unsupported_action")
            }
            publishState()
            return RemoteCommandExecutionResult(applied = true, updatedPlayback = buildRemoteSnapshot())
        }
    }

    private fun timing(stage: String, began: Long) {
        if (began > 0L) android.util.Log.d("SwarariaTiming", "$stage=${((System.nanoTime() - began) / 1_000_000L).coerceAtLeast(0)}")
    }
    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                fadeController.explicitlyStopped = false
                focusPausedByLoss = false
                requestPlaybackAudioFocus()
            } else if (!focusPausedByLoss) {
                abandonPlaybackAudioFocus()
            }
            if (!playWhenReady && !fadeController.isInternalOp) fadeController.cancelFade()
            publishState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && !fadeController.isInternalOp && fadeController.fadePhase == FadePhase.FADE_OUT &&
                appPlayer?.playbackState == Player.STATE_READY
            ) {
                fadeController.cancelFade()
            }
            if (savedLocal != null && previewPrepared && !changing) {
                if (isPlaying && !playingRecorded) {
                    playingRecorded = true
                    timing("preview_prepare_to_playing_ms", prepareStarted)
                    timing("preview_start_ms", requestStarted)
                    android.util.Log.d("SwarariaTiming", "preview_tap_to_playing_ms=${((SystemClock.elapsedRealtimeNanos() - requestDispatched) / 1_000_000L).coerceAtLeast(0)}")
                }
                previewPhase = if (isPlaying) "playing" else if (appPlayer?.playbackState == Player.STATE_BUFFERING) "buffering" else "paused"
            }
            publishState()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!fadeController.isInternalOp && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                fadeController.cancelFade()
            }
            if (savedLocal == null || changing || !previewPrepared) {
                if (savedLocal == null) publishState()
                return
            }
            when (playbackState) {
                Player.STATE_ENDED, Player.STATE_IDLE -> finishPreview()
                Player.STATE_READY -> {
                    if (!readyRecorded) { readyRecorded = true; timing("preview_prepare_to_ready_ms", prepareStarted) }
                    previewPhase = if (appPlayer?.isPlaying == true) "playing" else "paused"; publishState()
                }
                Player.STATE_BUFFERING -> { previewPhase = if (readyRecorded) "buffering" else "connecting"; publishState() }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            // Never log exception text/URI/headers. Invalidate even when no Activity is alive.
            if (savedLocal != null && !changing) { previewOwner.previewError(); finishPreview(keepError = true) }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!fadeController.isInternalOp && fadeController.fadePhase == FadePhase.FADE_OUT) {
                fadeController.cancelFade()
            }
            publishState()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!fadeController.isInternalOp && fadeController.fadePhase == FadePhase.FADE_OUT && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                fadeController.cancelFade()
            }
            publishState()
        }
    }
    private fun isApp(controller: MediaSession.ControllerInfo): Boolean = controller.packageName == packageName && controller.uid == Process.myUid()
    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!isApp(controller) && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (isApp(controller)) commands.add(PlaybackCommands.setMode).add(PlaybackCommands.fade)
                .add(PlaybackCommands.immediatePause).add(PlaybackCommands.setFadeSettings)
                .add(PlaybackCommands.setFader).add(PlaybackCommands.toggleMute)
                .add(PlaybackCommands.toggleDuck).add(PlaybackCommands.resetFader)
                .add(PlaybackCommands.setMonoSum).add(PlaybackCommands.setPadDuck)
                .add(PlaybackCommands.setFocusGuard).add(PlaybackCommands.setEq31)
                .add(PreviewProtocol.start).add(PreviewProtocol.stop).add(PreviewProtocol.forget)
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(playerCommands).build()
        }
        // Pinned Media3 1.8 callback: arbitrate before a controller mutates the shared timeline.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (savedLocal != null) {
                when (playerCommand) {
                    Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM -> {
                        if (!isApp(controller)) return SessionError.ERROR_PERMISSION_DENIED
                        finishPreview()
                    }
                    Player.COMMAND_STOP -> finishPreview()
                    Player.COMMAND_PLAY_PAUSE -> if (!previewPrepared) return SessionError.ERROR_INVALID_STATE
                }
            }
            return SessionResult.RESULT_SUCCESS
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (!isApp(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED, stateExtras()))
            if (customCommand.customAction == PreviewProtocol.START) return Futures.immediateFuture(beginPreview(args))
            val result = when (customCommand.customAction) {
                PreviewProtocol.STOP -> if (lease.matches(args.getString(PreviewProtocol.TOKEN), args.getLong(PreviewProtocol.GENERATION))) {
                    finishPreview(); SessionResult.RESULT_SUCCESS
                } else SessionError.ERROR_BAD_VALUE
                PreviewProtocol.FORGET -> {
                    val id = args.getString(PreviewProtocol.ID)
                    if (id?.toLongOrNull()?.let { it > 0 } != true) SessionError.ERROR_BAD_VALUE
                    else { forgetDeleted(id); SessionResult.RESULT_SUCCESS }
                }
                PlaybackCommands.ACTION_SET_MODE -> {
                    val parsed = PlaybackMode.entries.firstOrNull { it.name == args.getString(PlaybackCommands.EXTRA_MODE) }
                    if (parsed == null) SessionError.ERROR_BAD_VALUE else {
                        applyMode(parsed)
                        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(PlaybackCommands.EXTRA_MODE, parsed.name).apply()
                        SessionResult.RESULT_SUCCESS
                    }
                }
                PlaybackCommands.ACTION_FADE -> if (fadeController.fadePhase == FadePhase.FADE_OUT) {
                    fadeController.completeFadeOutImmediately()
                    SessionResult.RESULT_SUCCESS
                } else if (fadeController.startFade()) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionError.ERROR_INVALID_STATE
                }
                PlaybackCommands.ACTION_IMMEDIATE_PAUSE -> {
                    fadeController.immediatePause()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_FADE_SETTINGS -> {
                    val fadeIn = FadeRules.clampDurationMs(args.getLong(PlaybackCommands.EXTRA_FADE_IN_MS, fadeController.fadeSettings.fadeInMs))
                    val fadeOut = FadeRules.clampDurationMs(args.getLong(PlaybackCommands.EXTRA_FADE_OUT_MS, fadeController.fadeSettings.fadeOutMs))
                    playbackPrefs.edit()
                        .putLong(PlaybackPreferences.KEY_FADE_IN_MS, fadeIn)
                        .putLong(PlaybackPreferences.KEY_FADE_OUT_MS, fadeOut)
                        .apply()
                    fadeController.fadeSettings = PlaybackSettingsState(fadeInMs = fadeIn, fadeOutMs = fadeOut)
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_FADER -> {
                    val pos = args.getFloat(PlaybackCommands.EXTRA_FADER_POS, faderState.position)
                    applyFaderState(FaderRules.withPosition(faderState, pos))
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_TOGGLE_MUTE -> {
                    applyFaderState(FaderRules.toggleMute(faderState))
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_TOGGLE_DUCK -> {
                    applyFaderState(FaderRules.toggleDuck(faderState))
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_RESET_FADER -> {
                    applyFaderState(FaderRules.resetUnity())
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_MONO_SUM -> {
                    monoSumEnabled = args.getBoolean(PlaybackCommands.EXTRA_MONO_SUM, !monoSumEnabled)
                    monoSumProcessor.monoEnabled = monoSumEnabled
                    playbackPrefs.edit().putBoolean(KEY_MONO_SUM, monoSumEnabled).apply()
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_PAD_DUCK -> {
                    val requestedDuck = args.getBoolean(PlaybackCommands.EXTRA_PAD_DUCK, false)
                    if (requestedDuck != padDuckActive) {
                        padDuckActive = requestedDuck
                        applyPadDuckTransition()
                    }
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_FOCUS_GUARD -> {
                    val guarded = args.getBoolean(PlaybackCommands.EXTRA_FOCUS_GUARD, focusGuardEnabled)
                    if (guarded != focusGuardEnabled) {
                        focusGuardEnabled = guarded
                        if (focusGuardEnabled && focusDucked) {
                            focusDucked = false
                            applyEffectiveNominalNow()
                        }
                    }
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_EQ_31 -> {
                    val rawJson = args.getString(PlaybackCommands.EXTRA_EQ_31_JSON)
                    val parsed = GraphicEqRules.decodeJson(rawJson)
                    eqState = parsed
                    eqProcessor.updateState(parsed)
                    PlaybackPreferences.writeGraphicEq(playbackPrefs, parsed)
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                else -> SessionError.ERROR_NOT_SUPPORTED
            }
            return Futures.immediateFuture(SessionResult(result, stateExtras()))
        }
    }
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(2102).build())
        val began = System.nanoTime()
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 200, 600)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(monoSumProcessor, eqProcessor, signalTapProcessor))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        timing("playback_player_init_ms", began)
        appPlayer = player
        monoSumEnabled = playbackPrefs.getBoolean(KEY_MONO_SUM, false)
        monoSumProcessor.monoEnabled = monoSumEnabled
        eqState = PlaybackPreferences.readGraphicEq(playbackPrefs)
        eqProcessor.updateState(eqState, snapImmediately = true)
        playbackPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        reloadFadeSettings(playbackPrefs)
        reloadSafeFocusSetting(playbackPrefs)
        previewOwner = PreviewSession((application as SwarariaApp).container.searchRepository, scope, {},
            onResolved = ::preparePreview, onChanged = {
                if (::previewOwner.isInitialized) {
                    if (previewOwner.preview.value.error != null && savedLocal != null) finishPreview(keepError = true)
                    else publishState()
                }
            })
        player.addListener(playerListener)
        applyMode(PlaybackMode.fromStored(playbackPrefs.getString(PlaybackCommands.EXTRA_MODE, null)))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, fadeController.wrapPlayer(player)).setSessionActivity(open).setCallback(callback).setSessionExtras(stateExtras()).build()
        (application as? SwarariaApp)?.container?.remoteServerController?.attachPlaybackHost(remotePlaybackHost)
    }
    private fun beginPreview(args: Bundle): SessionResult {
        val id = args.getString(PreviewProtocol.ID).orEmpty()
        val title = args.getString(PreviewProtocol.TITLE).orEmpty()
        val token = args.getString(PreviewProtocol.TOKEN).orEmpty()
        if (!Regex("[A-Za-z0-9_-]{11}").matches(id) || title.length > 200 || title.any { it.isISOControl() } ||
            previewOwner.preview.value.loading || IngestStatus.state.value.busy || !lease.begin(token, args.getLong(PreviewProtocol.GENERATION)))
            return SessionResult(SessionError.ERROR_BAD_VALUE, stateExtras())
        finishPreview()
        val player = appPlayer ?: return SessionResult(SessionError.ERROR_INVALID_STATE, stateExtras())
        requestStarted = System.nanoTime()
        requestDispatched = lease.generation // Remote tap timestamp uses the same elapsed-realtime clock.
        android.util.Log.d("SwarariaTiming", "preview_dispatch_to_service_ms=${((SystemClock.elapsedRealtimeNanos() - requestDispatched) / 1_000_000L).coerceAtLeast(0)}")
        savedLocal = PreviewLocalQueue((0 until player.mediaItemCount).map(player::getMediaItemAt), player.currentMediaItem?.mediaId,
            player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0)) { it.mediaId }
        eqProcessor.setPreviewBypass(true)
        savedExplicitlyStopped = fadeController.explicitlyStopped
        changing = true
        fadeController.cancelFade(); player.pause(); player.stop(); player.clearMediaItems()
        changing = false
        previewPrepared = false; readyRecorded = false; playingRecorded = false; previewPhase = "resolving"
        try {
            // Promote while the user request is still foreground, before asynchronous native resolution.
            startService(Intent(this, PlaybackService::class.java))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("preview_resolving", "Preview", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val cancel = PendingIntent.getService(this, 22, Intent(this, PlaybackService::class.java)
                .setAction(ACTION_CANCEL_PREVIEW)
                .setData(Uri.parse("swararia-preview://cancel/${lease.token}/${lease.generation}"))
                .putExtra(PreviewProtocol.TOKEN, lease.token).putExtra(PreviewProtocol.GENERATION, lease.generation),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            val previewNotification = NotificationCompat.Builder(this, "preview_resolving").setSmallIcon(R.drawable.ic_audio)
                .setContentTitle(title).setContentText("Resolving Preview…").setContentIntent(open)
                .addAction(R.drawable.ic_audio, "Cancel Preview", cancel).setOngoing(true).build()
            startPlaybackForeground(2102, previewNotification)
            resolvingForeground = true
            previewOwner.preview(SearchHit(id, title, null, null, null, "https://www.youtube.com/watch?v=$id"))
        } catch (_: Exception) { finishPreview(); return SessionResult(SessionError.ERROR_INVALID_STATE, stateExtras()) }
        publishState()
        return SessionResult(SessionResult.RESULT_SUCCESS, stateExtras())
    }
    private fun preparePreview(media: PreviewMedia) {
        if (savedLocal == null) return
        PreviewLease.validate(media)
        val player = appPlayer ?: return
        changing = true
        fadeController.cancelFade(); player.pause(); player.stop()
        // A public MediaItem prevents signed URLs appearing in controller timelines/notifications.
        // The URL and allowlisted headers live only in this ephemeral DataSource closure.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(5000).setReadTimeoutMs(8000).setDefaultRequestProperties(media.headers)
        val source = ResolvingDataSource.Factory(http) { spec -> spec.withUri(Uri.parse(media.url)) }
        val item = MediaItem.Builder().setMediaId("preview:${media.videoId}").setUri("https://www.youtube.com/watch?v=${media.videoId}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).setArtist("Preview · maks. 120 detik").build())
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(OnlinePreviewRules.PREVIEW_MS).build()).build()
        player.setPauseAtEndOfMediaItems(false)
        player.repeatMode = Player.REPEAT_MODE_OFF; player.shuffleModeEnabled = false
        // Explicit ProgressiveMediaSource does not apply MediaItem clipping; wrap it explicitly.
        val progressive = ProgressiveMediaSource.Factory(source).createMediaSource(item)
        player.setMediaSource(androidx.media3.exoplayer.source.ClippingMediaSource.Builder(progressive)
            .setEndPositionMs(OnlinePreviewRules.PREVIEW_MS).build())
        prepareStarted = System.nanoTime(); previewPrepared = true; previewPhase = "connecting"
        changing = false
        // Preview bypasses fade ramps but always honors operator master fader and mute.
        fadeController.withInternalGuard { player.volume = fadeController.captureNominalVolume() }
        player.prepare(); player.play(); publishState()
        // The real media notification replaces the resolving notification using the SAME ID.
    }
    private fun forgetDeleted(id: String) {
        savedLocal = savedLocal?.forgetDeleted(id)
        val player = appPlayer ?: return
        if (savedLocal == null) {
            if (player.currentMediaItem?.mediaId == id) fadeController.cancelFade()
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId == id) player.removeMediaItem(index)
            }
        }
    }
    private fun finishPreview(keepError: Boolean = false) {
        val local = savedLocal
        val restoredExplicitStop = savedExplicitlyStopped
        savedLocal = null // Clear before synchronous Player callbacks / owner callbacks.
        savedExplicitlyStopped = false
        eqProcessor.setPreviewBypass(false)
        if (!keepError && ::previewOwner.isInitialized) previewOwner.cancelPreview()
        previewPrepared = false; previewPhase = if (keepError) "error" else ""
        fadeController.nominalVolume = effectiveTargetVolume()
        val player = appPlayer
        if (local != null && player != null) {
            changing = true
            fadeController.cancelFade(); player.pause(); player.stop(); player.clearMediaItems()
            if (local.items.isNotEmpty()) {
                player.setMediaItems(local.items, local.restoreIndex, local.restorePosition)
                player.prepare() // Ready for an explicit future Play, still playWhenReady=false.
            }
            applyMode(mode)
            fadeController.explicitlyStopped = restoredExplicitStop
            fadeController.withInternalGuard { player.volume = fadeController.nominalVolume }
            // No play(): STOP/end/error must never resume the saved local queue.
            changing = false
        } else if (player != null) {
            fadeController.withInternalGuard { player.volume = fadeController.nominalVolume }
        }
        if (resolvingForeground) {
            resolvingForeground = false
            getSystemService(NotificationManager::class.java).cancel(2102)
            if (remoteServerForegroundActive) {
                ensureRemoteServerForeground()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        publishState()
    }
    private fun applyMode(selected: PlaybackMode) {
        val player = appPlayer ?: return
        player.setPauseAtEndOfMediaItems(savedLocal == null && selected.pauseAtItemEnd)
        player.repeatMode = if (savedLocal == null && selected.repeatCurrent) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        mode = selected; publishState()
    }
    private fun stateExtras() = Bundle().apply {
        putInt(PlaybackCommands.EXTRA_PROTOCOL, 1)
        putString(PlaybackCommands.EXTRA_MODE, mode.name)
        putBoolean(PlaybackCommands.EXTRA_FADE_BUSY, fadeController.isFadingOut)
        putLong(PlaybackCommands.EXTRA_FADE_IN_MS, fadeController.fadeSettings.fadeInMs)
        putLong(PlaybackCommands.EXTRA_FADE_OUT_MS, fadeController.fadeSettings.fadeOutMs)
        putFloat(PlaybackCommands.EXTRA_FADER_POS, faderState.normalizedPosition)
        putBoolean(PlaybackCommands.EXTRA_FADER_MUTED, faderState.muted)
        putBoolean(PlaybackCommands.EXTRA_FADER_DUCKED, faderState.ducked)
        putFloat(PlaybackCommands.EXTRA_FADER_PRE_DUCK, faderState.preDuckPosition)
        putBoolean(PlaybackCommands.EXTRA_MONO_SUM, monoSumEnabled)
        putBoolean(PlaybackCommands.EXTRA_PAD_DUCK, padDuckActive)
        putBoolean(PlaybackCommands.EXTRA_FOCUS_GUARD, focusGuardEnabled)
        putString(PlaybackCommands.EXTRA_EQ_31_JSON, GraphicEqRules.encodeJson(eqState))
        if (::previewOwner.isInitialized) {
            val state = previewOwner.preview.value
            state.hit?.let { putString(PreviewProtocol.ID, it.videoId); putString(PreviewProtocol.TITLE, it.title) }
            putString(PreviewProtocol.TOKEN, lease.token); putLong(PreviewProtocol.GENERATION, lease.generation)
            putString(PreviewProtocol.PHASE, if (state.canceling) "canceling" else previewPhase)
            putBoolean(PreviewProtocol.ERROR, state.error != null)
        }
    }
    private fun ensureSignalTicker() {
        val localPlaying = savedLocal == null && appPlayer?.isPlaying == true
        if ((localPlaying || LocalAudioSignalBus.state.value.active) && !signalTickRunning) {
            signalTickRunning = true
            handler.removeCallbacks(signalTick)
            handler.post(signalTick)
        }
    }

    private fun publishState() {
        session?.setSessionExtras(stateExtras())
        (application as? SwarariaApp)?.container?.remoteServerController?.notifyPlaybackChanged(buildRemoteSnapshot())
        ensureSignalTicker()
    }

    private fun toMediaItems(entries: List<RemoteQueueEntry>): List<MediaItem> = entries.map { entry ->
        MediaItem.Builder()
            .setMediaId(entry.numericId.toString())
            .setUri(Uri.parse(entry.uriString))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.artist?.trim()?.takeIf { it.isNotEmpty() })
                    .setAlbumTitle(entry.album?.trim()?.takeIf { it.isNotEmpty() })
                    .setGenre(entry.genre?.trim()?.takeIf { it.isNotEmpty() })
                    .build(),
            )
            .build()
    }

    private fun buildRemoteSnapshot(): RemoteServerPlaybackSnapshot {
        val player = appPlayer ?: return RemoteServerPlaybackSnapshot(online = false, transportState = "STOPPED")
        if (savedLocal != null) {
            return RemoteServerPlaybackSnapshot(
                online = false,
                playing = false,
                transportState = "PREVIEW_ACTIVE",
                currentTimeSec = null,
                durationSec = null,
                currentTrackNumericId = null,
                previewActive = true,
            )
        }
        val playing = player.isPlaying
        val isFading = fadeController.isFadingOut
        val transportState = when {
            isFading -> "FADING"
            playing -> "PLAYING"
            player.playbackState == Player.STATE_BUFFERING -> "BUFFERING"
            fadeController.explicitlyStopped || player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0 -> "STOPPED"
            else -> "PAUSED"
        }
        val posSec = if (fadeController.explicitlyStopped || player.mediaItemCount == 0) 0.0
        else player.currentPosition.coerceAtLeast(0L) / 1000.0
        val durMs = player.duration
        val durSec = if (durMs > 0L && durMs != C.TIME_UNSET) durMs / 1000.0 else null
        val curId = player.currentMediaItem?.mediaId?.toLongOrNull()
        return RemoteServerPlaybackSnapshot(
            online = true,
            playing = playing,
            transportState = transportState,
            currentTimeSec = posSec,
            durationSec = durSec,
            currentTrackNumericId = curId,
            previewActive = false,
        )
    }

    private fun ensureRemoteServerForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REMOTE_SERVER_CHANNEL, "FOH Remote Server", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            23,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, REMOTE_SERVER_CHANNEL)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("SWR Player Server FOH aktif")
            .setContentText("Terbuka di https://$remoteEndpointLabel")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startPlaybackForeground(REMOTE_SERVER_NOTIFICATION_ID, notification)
    }

    private fun startPlaybackForeground(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
    }
    private fun reloadFadeSettings(prefs: SharedPreferences) {
        val next = PlaybackPreferences.readState(prefs)
        fadeController.fadeSettings = next
        android.util.Log.d("SwarariaFade", "reloadFadeSettings fadeInMs=${next.fadeInMs} fadeOutMs=${next.fadeOutMs}")
        publishState()
    }

    private fun reloadSafeFocusSetting(prefs: SharedPreferences) {
        val next = FohSafeModeRules.shouldIgnoreExternalFocusLoss(
            FohSafeModeSettings(
                enabled = prefs.getBoolean(FohSafeModeController.KEY_SAFE_ENABLED, false),
                autoDnd = prefs.getBoolean(FohSafeModeController.KEY_AUTO_DND, true),
                focusLock = prefs.getBoolean(FohSafeModeController.KEY_FOCUS_LOCK, true),
            ),
        )
        if (next != focusGuardEnabled) {
            focusGuardEnabled = next
            if (focusGuardEnabled && focusDucked) {
                focusDucked = false
                applyEffectiveNominalNow()
            }
            publishState()
        }
    }

    private fun effectiveTargetVolume(): Float {
        val base = faderState.effectiveGain
        val padFactor = if (padDuckActive) SoundboardRules.AUTO_DUCK_GAIN else 1f
        val focusFactor = if (focusDucked && !focusGuardEnabled) 0.25f else 1f
        return (base * padFactor * focusFactor).coerceIn(0f, 1f)
    }

    private fun cancelPadDuckRamp() {
        padDuckTick?.let { handler.removeCallbacks(it) }
        padDuckTick = null
    }

    private fun applyEffectiveNominalNow() {
        val target = effectiveTargetVolume()
        fadeController.nominalVolume = target
        if (!fadeController.isFadeActive && savedLocal == null) {
            cancelPadDuckRamp()
            appPlayer?.volume = target
        }
    }

    private fun applyPadDuckTransition() {
        val target = effectiveTargetVolume()
        fadeController.updateNominalForTransition(target)
        if (fadeController.fadePhase != FadePhase.NONE || savedLocal != null) return
        val player = appPlayer ?: return
        cancelPadDuckRamp()
        if (!player.isPlaying) {
            player.volume = target
            return
        }
        val startVol = player.volume.coerceIn(0f, 1f)
        val durationMs = if (padDuckActive) 85L else 140L
        if (kotlin.math.abs(startVol - target) < 0.004f) {
            player.volume = target
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        val ramp = object : Runnable {
            override fun run() {
                if (padDuckTick !== this || fadeController.fadePhase != FadePhase.NONE || savedLocal != null) return
                val elapsed = SystemClock.uptimeMillis() - startedAt
                if (elapsed >= durationMs) {
                    padDuckTick = null
                    player.volume = fadeController.nominalVolume
                } else {
                    val t = elapsed.toFloat() / durationMs.toFloat()
                    player.volume = (startVol + (fadeController.nominalVolume - startVol) * t).coerceIn(0f, 1f)
                    handler.postDelayed(this, 16L)
                }
            }
        }
        padDuckTick = ramp
        handler.post(ramp)
    }

    private fun ensureAudioFocusRequest(): AudioFocusRequest {
        audioFocusRequest?.let { return it }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusListener, handler)
            .build()
            .also { audioFocusRequest = it }
    }

    private fun requestPlaybackAudioFocus() {
        if (hasAudioFocus) return
        val manager = audioManager ?: return
        val result = runCatching { manager.requestAudioFocus(ensureAudioFocusRequest()) }.getOrNull()
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true
        }
    }

    private fun abandonPlaybackAudioFocus() {
        val req = audioFocusRequest
        if (hasAudioFocus && req != null) {
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
        hasAudioFocus = false
        focusDucked = false
        focusPausedByLoss = false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        if (focusGuardEnabled) {
            if (focusDucked) {
                focusDucked = false
                applyEffectiveNominalNow()
            }
            focusPausedByLoss = false
            return
        }
        val player = appPlayer ?: return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                val resume = focusPausedByLoss
                focusPausedByLoss = false
                if (focusDucked) {
                    focusDucked = false
                    applyEffectiveNominalNow()
                }
                if (resume && !player.playWhenReady) {
                    fadeController.requestPlayWithFade(immediate = true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                focusDucked = false
                focusPausedByLoss = false
                abandonPlaybackAudioFocus()
                fadeController.cancelFade()
                fadeController.withInternalGuard { player.pause() }
                publishState()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusDucked = false
                if (player.isPlaying || player.playWhenReady) {
                    focusPausedByLoss = true
                    fadeController.cancelFade()
                    fadeController.withInternalGuard { player.pause() }
                    publishState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!focusDucked) {
                    focusDucked = true
                    applyEffectiveNominalNow()
                }
            }
        }
    }

    /**
     * Applies operator fader changes immediately, canceling any active automated fade ramp so
     * manual console moves never fight Fade In/Out transitions.
     */
    private fun applyFaderState(next: FaderState) {
        faderState = next
        fadeController.onOperatorFaderUpdated()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = if (isApp(controllerInfo) || controllerInfo.isTrusted) session else null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_PREVIEW -> {
                if (lease.matches(intent.getStringExtra(PreviewProtocol.TOKEN), intent.getLongExtra(PreviewProtocol.GENERATION, 0))) finishPreview()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_SERVER_START -> {
                val rawEndpoint = intent.getStringExtra(EXTRA_REMOTE_ENDPOINT).orEmpty()
                remoteEndpointLabel = rawEndpoint.filter { !it.isISOControl() }.take(32).ifBlank { "LAN" }
                remoteServerForegroundActive = true
                acquireRemoteWifiLock()
                ensureRemoteServerForeground()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_SERVER_STOP -> {
                remoteServerForegroundActive = false
                releaseRemoteWifiLock()
                getSystemService(NotificationManager::class.java).cancel(REMOTE_SERVER_NOTIFICATION_ID)
                val playerActive = appPlayer?.let {
                    it.isPlaying || it.playWhenReady || it.playbackState == Player.STATE_BUFFERING
                } == true
                if (!playerActive && savedLocal == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                return START_NOT_STICKY
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY // Signed sources and in-flight work are deliberately not persisted.
    }
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (resolvingForeground && !previewPrepared && savedLocal != null) return
        super.onUpdateNotification(session, startInForegroundRequired)
        if (previewPrepared && startInForegroundRequired) resolvingForeground = false
        if (remoteServerForegroundActive && !startInForegroundRequired && appPlayer?.isPlaying != true && savedLocal == null) {
            ensureRemoteServerForeground()
        }
    }
    override fun isPlaybackOngoing(): Boolean = remoteServerForegroundActive || savedLocal != null || super.isPlaybackOngoing()
    private fun acquireRemoteWifiLock() {
        runCatching {
            if (remoteWifiLock?.isHeld == true) return
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            remoteWifiLock = wifiManager.createWifiLock(lockMode, "$packageName:remote-foh").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseRemoteWifiLock() {
        runCatching {
            if (remoteWifiLock?.isHeld == true) remoteWifiLock?.release()
        }
        remoteWifiLock = null
    }

    override fun onDestroy() {
        releaseRemoteWifiLock()
        (application as? SwarariaApp)?.container?.remoteServerController?.detachPlaybackHost(remotePlaybackHost)
        playbackPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        abandonPlaybackAudioFocus()
        padDuckActive = false
        cancelPadDuckRamp()
        scope.cancel()
        if (::previewOwner.isInitialized) previewOwner.close()
        signalTickRunning = false
        handler.removeCallbacks(signalTick)
        LocalAudioSignalBus.reset()
        fadeController.cancelPostFadeVolumeRestore()
        fadeController.cancelFade(); handler.removeCallbacksAndMessages(null)
        appPlayer?.removeListener(playerListener); session?.release(); session = null
        appPlayer?.release(); appPlayer = null
        super.onDestroy()
    }
    companion object {
        private const val PREFERENCES = "native_playback"
        private const val KEY_MONO_SUM = "foh_mono_sum"
        private const val SIGNAL_TICK_MS = 33L
        private const val ACTION_CANCEL_PREVIEW = "com.swararia.audiolab.CANCEL_PREVIEW"
        private const val REMOTE_SERVER_CHANNEL = "remote_server"
        private const val REMOTE_SERVER_NOTIFICATION_ID = 2103
        const val ACTION_REMOTE_SERVER_START = "com.swararia.audiolab.REMOTE_SERVER_START"
        const val ACTION_REMOTE_SERVER_STOP = "com.swararia.audiolab.REMOTE_SERVER_STOP"
        const val EXTRA_REMOTE_ENDPOINT = "com.swararia.audiolab.REMOTE_ENDPOINT"
    }
}
