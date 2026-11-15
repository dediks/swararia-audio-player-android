package com.swararia.audiolab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
import kotlinx.coroutines.*

/** One service player/notification. Preview temporarily borrows it; local context returns PAUSED. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private enum class FadePhase { NONE, FADE_IN, FADE_OUT }

    private sealed interface PendingFadeOutAction {
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

    private var session: MediaSession? = null
    private var appPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeTick: Runnable? = null
    private var postFadeRestoreTick: Runnable? = null
    private var fadePhase = FadePhase.NONE
    private var faderState = FaderState()
    private var nominalVolume: Float = faderState.effectiveGain
    private var originalVolume: Float? = null
    private var pendingFadeAction: PendingFadeOutAction? = null
    private var internalFadeOpDepth = 0
    private var fadeSettings = PlaybackSettingsState()
    private val playbackPrefs by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PlaybackPreferences.KEY_FADE_IN_MS || key == PlaybackPreferences.KEY_FADE_OUT_MS) {
            handler.post { reloadFadeSettings(prefs) }
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
    private var explicitlyStopped = false
    private var remoteServerForegroundActive = false
    private var remoteEndpointLabel = "LAN"
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
                    explicitlyStopped = false
                    requestSwitchTrackWithFade(
                        PendingFadeOutAction.ReplaceQueue(items, idx, 0L, autoPlay = request.autoPlay),
                        immediate = !(player.isPlaying || fadePhase == FadePhase.FADE_IN),
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
                    explicitlyStopped = false
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        player.prepare()
                    }
                    requestPlayWithFade()
                }
                "pause" -> {
                    explicitlyStopped = false
                    requestPauseWithFade()
                }
                "stop" -> {
                    requestStopWithFade(resetToCue = true)
                }
                "fade_out" -> {
                    if (!startFade()) return RemoteCommandExecutionResult(applied = false, errorCode = "cannot_fade")
                }
                "prev" -> {
                    if (player.mediaItemCount == 0) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "empty_queue")
                    }
                    val autoPlay = player.playWhenReady || player.isPlaying
                    explicitlyStopped = false
                    requestSwitchTrackWithFade(
                        PendingFadeOutAction.SeekPrevious(autoPlay = autoPlay),
                        immediate = !(player.isPlaying || fadePhase == FadePhase.FADE_IN),
                    )
                }
                "next" -> {
                    if (player.mediaItemCount == 0 || !player.hasNextMediaItem()) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "no_next_track")
                    }
                    val autoPlay = player.playWhenReady || player.isPlaying
                    explicitlyStopped = false
                    requestSwitchTrackWithFade(
                        PendingFadeOutAction.SeekNext(autoPlay = autoPlay),
                        immediate = !(player.isPlaying || fadePhase == FadePhase.FADE_IN),
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
            if (playWhenReady) explicitlyStopped = false
            if (!playWhenReady && internalFadeOpDepth == 0) cancelFade()
            publishState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && internalFadeOpDepth == 0 && fadePhase == FadePhase.FADE_OUT &&
                appPlayer?.playbackState == Player.STATE_READY
            ) {
                cancelFade()
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
            if (internalFadeOpDepth == 0 && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                cancelFade()
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
            if (internalFadeOpDepth == 0 && fadePhase == FadePhase.FADE_OUT) {
                cancelFade()
            }
            publishState()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (internalFadeOpDepth == 0 && fadePhase == FadePhase.FADE_OUT && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                cancelFade()
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
                PlaybackCommands.ACTION_FADE -> if (fadePhase == FadePhase.FADE_OUT) {
                    val finish = pendingFadeAction ?: PendingFadeOutAction.Pause
                    cancelFade(restoreVolume = false)
                    executePendingFadeOutAction(finish)
                    SessionResult.RESULT_SUCCESS
                } else if (startFade()) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionError.ERROR_INVALID_STATE
                }
                PlaybackCommands.ACTION_IMMEDIATE_PAUSE -> {
                    val player = appPlayer
                    cancelFade(restoreVolume = false)
                    withInternalGuard { player?.pause() }
                    player?.let { schedulePostFadeVolumeRestore(it, nominalVolume) }
                    publishState()
                    SessionResult.RESULT_SUCCESS
                }
                PlaybackCommands.ACTION_SET_FADE_SETTINGS -> {
                    val fadeIn = FadeRules.clampDurationMs(args.getLong(PlaybackCommands.EXTRA_FADE_IN_MS, fadeSettings.fadeInMs))
                    val fadeOut = FadeRules.clampDurationMs(args.getLong(PlaybackCommands.EXTRA_FADE_OUT_MS, fadeSettings.fadeOutMs))
                    playbackPrefs.edit()
                        .putLong(PlaybackPreferences.KEY_FADE_IN_MS, fadeIn)
                        .putLong(PlaybackPreferences.KEY_FADE_OUT_MS, fadeOut)
                        .apply()
                    fadeSettings = PlaybackSettingsState(fadeInMs = fadeIn, fadeOutMs = fadeOut)
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
                .setAudioProcessors(arrayOf(signalTapProcessor))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        timing("playback_player_init_ms", began)
        appPlayer = player
        playbackPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        reloadFadeSettings(playbackPrefs)
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
        session = MediaSession.Builder(this, FadingSessionPlayer(player)).setSessionActivity(open).setCallback(callback).setSessionExtras(stateExtras()).build()
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
        savedExplicitlyStopped = explicitlyStopped
        changing = true
        cancelFade(); player.pause(); player.stop(); player.clearMediaItems()
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
        cancelFade(); player.pause(); player.stop()
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
        withInternalGuard { player.volume = captureNominalVolume(player) }
        player.prepare(); player.play(); publishState()
        // The real media notification replaces the resolving notification using the SAME ID.
    }
    private fun forgetDeleted(id: String) {
        savedLocal = savedLocal?.forgetDeleted(id)
        val player = appPlayer ?: return
        if (savedLocal == null) {
            if (player.currentMediaItem?.mediaId == id) cancelFade()
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
        if (!keepError && ::previewOwner.isInitialized) previewOwner.cancelPreview()
        previewPrepared = false; previewPhase = if (keepError) "error" else ""
        nominalVolume = faderState.effectiveGain
        val player = appPlayer
        if (local != null && player != null) {
            changing = true
            cancelFade(); player.pause(); player.stop(); player.clearMediaItems()
            if (local.items.isNotEmpty()) {
                player.setMediaItems(local.items, local.restoreIndex, local.restorePosition)
                player.prepare() // Ready for an explicit future Play, still playWhenReady=false.
            }
            applyMode(mode)
            explicitlyStopped = restoredExplicitStop
            withInternalGuard { player.volume = nominalVolume }
            // No play(): STOP/end/error must never resume the saved local queue.
            changing = false
        } else if (player != null) {
            withInternalGuard { player.volume = nominalVolume }
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
        putBoolean(PlaybackCommands.EXTRA_FADE_BUSY, fadePhase == FadePhase.FADE_OUT || originalVolume != null)
        putLong(PlaybackCommands.EXTRA_FADE_IN_MS, fadeSettings.fadeInMs)
        putLong(PlaybackCommands.EXTRA_FADE_OUT_MS, fadeSettings.fadeOutMs)
        putFloat(PlaybackCommands.EXTRA_FADER_POS, faderState.normalizedPosition)
        putBoolean(PlaybackCommands.EXTRA_FADER_MUTED, faderState.muted)
        putBoolean(PlaybackCommands.EXTRA_FADER_DUCKED, faderState.ducked)
        putFloat(PlaybackCommands.EXTRA_FADER_PRE_DUCK, faderState.preDuckPosition)
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
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
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
        val isFading = fadePhase == FadePhase.FADE_OUT || originalVolume != null
        val transportState = when {
            isFading -> "FADING"
            playing -> "PLAYING"
            player.playbackState == Player.STATE_BUFFERING -> "BUFFERING"
            explicitlyStopped || player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0 -> "STOPPED"
            else -> "PAUSED"
        }
        val posSec = if (explicitlyStopped || player.mediaItemCount == 0) 0.0
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
            .setContentTitle("Swararia Server FOH aktif")
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
    private var pendingSwitchArmedAtUptimeMs = 0L
    private var pendingPauseArmedAtUptimeMs = 0L

    private fun reloadFadeSettings(prefs: SharedPreferences) {
        fadeSettings = PlaybackPreferences.readState(prefs)
        android.util.Log.d("SwarariaFade", "reloadFadeSettings fadeInMs=${fadeSettings.fadeInMs} fadeOutMs=${fadeSettings.fadeOutMs}")
        publishState()
    }

    /**
     * Applies operator fader changes immediately, canceling any active automated fade ramp so
     * manual console moves never fight Fade In/Out transitions.
     */
    private fun applyFaderState(next: FaderState) {
        cancelPostFadeVolumeRestore()
        faderState = next
        nominalVolume = next.effectiveGain
        if (fadePhase != FadePhase.NONE || originalVolume != null) {
            cancelFade(restoreVolume = false)
        }
        appPlayer?.volume = nominalVolume
        publishState()
    }

    private fun captureNominalVolume(player: ExoPlayer): Float {
        nominalVolume = faderState.effectiveGain
        return nominalVolume
    }

    private inline fun withInternalGuard(block: () -> Unit) {
        internalFadeOpDepth++
        try {
            block()
        } finally {
            internalFadeOpDepth--
        }
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

    private fun startFadeRamp(
        phase: FadePhase,
        fromVolume: Float,
        toVolume: Float,
        durationMs: Long,
        actionAfterOut: PendingFadeOutAction? = null,
    ) {
        val player = appPlayer ?: return
        cancelPostFadeVolumeRestore()
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
            publishState()
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
                if (savedLocal != null) {
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
                    publishState()
                } else {
                    handler.postDelayed(this, minOf(20L, clampedDuration - elapsed))
                }
            }
        }
        fadeTick = tick
        publishState()
        handler.post(tick)
    }

    private fun cancelPostFadeVolumeRestore() {
        postFadeRestoreTick?.let { handler.removeCallbacks(it) }
        postFadeRestoreTick = null
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

    private fun requestPlayWithFade(immediate: Boolean = false) {
        val player = appPlayer ?: return
        cancelPostFadeVolumeRestore()
        explicitlyStopped = false
        if (FadeRules.shouldBypassFade(savedLocal != null, immediate)) {
            cancelFade()
            withInternalGuard { player.play() }
            publishState()
            return
        }
        val targetNominal = captureNominalVolume(player)
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
            publishState()
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
        val player = appPlayer ?: return
        if (FadeRules.shouldBypassFade(savedLocal != null, immediate) || fadePhase == FadePhase.FADE_OUT) {
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
        val targetNominal = captureNominalVolume(player)
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

    private fun requestPauseWithFade(immediate: Boolean = false) {
        val player = appPlayer ?: return
        explicitlyStopped = false
        launchFadeOutOrRun(PendingFadeOutAction.Pause, immediate) {
            withInternalGuard { player.pause() }
            player.volume = captureNominalVolume(player)
            publishState()
        }
    }

    private fun requestStopWithFade(resetToCue: Boolean, immediate: Boolean = false) {
        val player = appPlayer ?: return
        val action = if (resetToCue) PendingFadeOutAction.StopAndReset else PendingFadeOutAction.StopPlayer
        val stopImmediate = immediate || (!player.isPlaying && !player.playWhenReady && fadePhase != FadePhase.FADE_IN)
        launchFadeOutOrRun(action, stopImmediate) {
            executePendingFadeOutAction(action)
        }
    }

    private fun requestSwitchTrackWithFade(action: PendingFadeOutAction, immediate: Boolean = false) {
        explicitlyStopped = false
        launchFadeOutOrRun(action, immediate) {
            executeSwitchActionNow(action)
        }
    }

    private fun executePendingFadeOutAction(action: PendingFadeOutAction) {
        val player = appPlayer ?: return
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
        publishState()
    }

    private fun executeSwitchActionNow(action: PendingFadeOutAction) {
        val player = appPlayer ?: return
        cancelPostFadeVolumeRestore()
        val fadeInMs = if (savedLocal == null) fadeSettings.fadeInMs else 0L
        val targetNominal = captureNominalVolume(player)
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
                    applyMode(mode)
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
        if (shouldAutoPlay && fadeInMs > 0L && savedLocal == null) {
            startFadeRamp(FadePhase.FADE_IN, 0f, targetNominal, fadeInMs)
        } else {
            player.volume = targetNominal
        }
        publishState()
    }

    private fun startFade(): Boolean {
        val player = appPlayer ?: return false
        val activePlayback = player.isPlaying || fadePhase == FadePhase.FADE_IN
        val busyFadingOut = fadePhase == FadePhase.FADE_OUT || originalVolume != null
        if (!FadeRules.canStart(activePlayback, busyFadingOut)) return false
        val manualMs = fadeSettings.effectiveManualFadeOutMs
        val targetNominal = captureNominalVolume(player)
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

    private fun cancelFade(restoreVolume: Boolean = true) {
        cancelPostFadeVolumeRestore()
        val wasBusy = fadePhase == FadePhase.FADE_OUT || originalVolume != null
        fadeTick?.let { handler.removeCallbacks(it) }
        fadeTick = null
        val restore = originalVolume ?: nominalVolume
        originalVolume = null
        fadePhase = FadePhase.NONE
        pendingFadeAction = null
        pendingSwitchArmedAtUptimeMs = 0L
        pendingPauseArmedAtUptimeMs = 0L
        if (restoreVolume) {
            appPlayer?.volume = restore.coerceIn(0f, 1f)
        }
        if (wasBusy) publishState()
        // Never call play(): cancellation must not undo a pause, focus loss, stop or track change.
    }

    private inner class FadingSessionPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        override fun setVolume(volume: Float) {
            if (fadePhase != FadePhase.NONE) {
                return
            }
            if (savedLocal == null) {
                val clamped = volume.coerceIn(0f, 1f)
                faderState = FaderRules.withPosition(faderState, FaderRules.positionFromGain(clamped))
                    .copy(muted = clamped <= 0f)
                nominalVolume = faderState.effectiveGain
                super.setVolume(nominalVolume)
                publishState()
                return
            }
            super.setVolume(volume.coerceIn(0f, 1f).coerceAtMost(faderState.effectiveGain))
        }

        override fun play() {
            if (savedLocal != null) {
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
            if (savedLocal != null) {
                super.setPlayWhenReady(playWhenReady)
                return
            }
            if (playWhenReady) play() else pause()
        }

        override fun pause() {
            if (savedLocal != null) {
                super.pause()
                return
            }
            requestPauseWithFade()
        }

        override fun stop() {
            if (savedLocal != null) {
                super.stop()
                return
            }
            requestStopWithFade(resetToCue = false)
        }

        override fun prepare() {
            if (savedLocal == null && fadePhase == FadePhase.FADE_OUT && (
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
            if (savedLocal != null || !exo.hasNextMediaItem()) {
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
            if (savedLocal != null) {
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
            if (savedLocal == null && positionMs == 0L && fadePhase == FadePhase.FADE_OUT) {
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
            if (savedLocal == null && fadePhase == FadePhase.FADE_OUT &&
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
            if (savedLocal == null && fadePhase == FadePhase.FADE_OUT &&
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
            if (savedLocal == null && mediaItemIndex != exo.currentMediaItemIndex &&
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
            if (savedLocal == null && fadePhase == FadePhase.FADE_OUT &&
                pendingFadeAction == PendingFadeOutAction.Pause &&
                SystemClock.uptimeMillis() - pendingPauseArmedAtUptimeMs <= SWITCH_MERGE_WINDOW_MS
            ) {
                // Library/Downloads Cue-only called pause(); setMediaItems(..., 0).
                pendingPauseArmedAtUptimeMs = 0L
                pendingSwitchArmedAtUptimeMs = SystemClock.uptimeMillis()
                pendingFadeAction = PendingFadeOutAction.ReplaceQueue(items, startIndex, startPositionMs, autoPlay = false)
                return
            }
            if (savedLocal == null && (exo.isPlaying || fadePhase == FadePhase.FADE_IN || fadePhase == FadePhase.FADE_OUT)) {
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
                ensureRemoteServerForeground()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_SERVER_STOP -> {
                remoteServerForegroundActive = false
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
    override fun onDestroy() {
        (application as? SwarariaApp)?.container?.remoteServerController?.detachPlaybackHost(remotePlaybackHost)
        playbackPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        scope.cancel()
        if (::previewOwner.isInitialized) previewOwner.close()
        signalTickRunning = false
        handler.removeCallbacks(signalTick)
        LocalAudioSignalBus.reset()
        cancelPostFadeVolumeRestore()
        cancelFade(); handler.removeCallbacksAndMessages(null)
        appPlayer?.removeListener(playerListener); session?.release(); session = null
        appPlayer?.release(); appPlayer = null
        super.onDestroy()
    }
    companion object {
        private const val PREFERENCES = "native_playback"
        private const val SIGNAL_TICK_MS = 33L
        private const val SWITCH_MERGE_WINDOW_MS = 600L
        private const val POST_FADE_VOLUME_RESTORE_DELAY_MS = 80L
        private const val ACTION_CANCEL_PREVIEW = "com.swararia.audiolab.CANCEL_PREVIEW"
        private const val REMOTE_SERVER_CHANNEL = "remote_server"
        private const val REMOTE_SERVER_NOTIFICATION_ID = 2103
        const val ACTION_REMOTE_SERVER_START = "com.swararia.audiolab.REMOTE_SERVER_START"
        const val ACTION_REMOTE_SERVER_STOP = "com.swararia.audiolab.REMOTE_SERVER_STOP"
        const val EXTRA_REMOTE_ENDPOINT = "com.swararia.audiolab.REMOTE_ENDPOINT"
    }
}
