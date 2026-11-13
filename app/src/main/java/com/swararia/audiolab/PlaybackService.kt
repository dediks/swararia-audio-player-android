package com.swararia.audiolab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
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
    private var session: MediaSession? = null
    private var appPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeTick: Runnable? = null
    private var originalVolume: Float? = null
    private var mode = PlaybackMode.CONTINUE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var previewOwner: PreviewSession
    private val lease = PreviewLease()
    private var savedLocal: PreviewLocalQueue<MediaItem>? = null
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
                    cancelFade()
                    player.setMediaItems(items, idx, 0L)
                    applyMode(mode)
                    player.prepare()
                    if (request.autoPlay) player.play() else player.pause()
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
                    cancelFade()
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        player.prepare()
                    }
                    player.play()
                }
                "pause" -> {
                    explicitlyStopped = false
                    cancelFade()
                    player.pause()
                }
                "stop" -> {
                    cancelFade()
                    player.pause()
                    player.seekTo(0L)
                    explicitlyStopped = true
                }
                "fade_out" -> {
                    if (!startFade()) return RemoteCommandExecutionResult(applied = false, errorCode = "cannot_fade")
                }
                "prev" -> {
                    if (player.mediaItemCount == 0) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "empty_queue")
                    }
                    explicitlyStopped = false
                    cancelFade()
                    val wasPlaying = player.isPlaying
                    if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L)
                    if (wasPlaying) player.play()
                }
                "next" -> {
                    if (player.mediaItemCount == 0 || !player.hasNextMediaItem()) {
                        return RemoteCommandExecutionResult(applied = false, errorCode = "no_next_track")
                    }
                    explicitlyStopped = false
                    cancelFade()
                    val wasPlaying = player.isPlaying
                    player.seekToNextMediaItem()
                    if (wasPlaying) player.play()
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
            if (!playWhenReady) cancelFade()
            publishState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) cancelFade()
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
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancelFade()
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
            cancelFade()
            publishState()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            cancelFade()
            publishState()
        }
    }
    private fun isApp(controller: MediaSession.ControllerInfo): Boolean = controller.packageName == packageName && controller.uid == Process.myUid()
    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!isApp(controller) && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (isApp(controller)) commands.add(PlaybackCommands.setMode).add(PlaybackCommands.fade)
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
                PlaybackCommands.ACTION_FADE -> if (startFade()) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE
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
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        timing("playback_player_init_ms", began)
        appPlayer = player
        previewOwner = PreviewSession((application as SwarariaApp).container.searchRepository, scope, {},
            onResolved = ::preparePreview, onChanged = {
                if (::previewOwner.isInitialized) {
                    if (previewOwner.preview.value.error != null && savedLocal != null) finishPreview(keepError = true)
                    else publishState()
                }
            })
        player.addListener(playerListener)
        applyMode(PlaybackMode.fromStored(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PlaybackCommands.EXTRA_MODE, null)))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(callback).setSessionExtras(stateExtras()).build()
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
            startForeground(2102, NotificationCompat.Builder(this, "preview_resolving").setSmallIcon(R.drawable.ic_audio)
                .setContentTitle(title).setContentText("Resolving Preview…").setContentIntent(open)
                .addAction(R.drawable.ic_audio, "Cancel Preview", cancel).setOngoing(true).build())
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
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setDefaultRequestProperties(media.headers)
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
        player.prepare(); player.play(); publishState()
        // The real media notification replaces the resolving notification using the SAME ID.
    }
    private fun forgetDeleted(id: String) {
        savedLocal = savedLocal?.forgetDeleted(id)
        val player = appPlayer ?: return
        if (savedLocal == null) for (index in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(index).mediaId == id) player.removeMediaItem(index)
    }
    private fun finishPreview(keepError: Boolean = false) {
        val local = savedLocal
        savedLocal = null // Clear before synchronous Player callbacks / owner callbacks.
        if (!keepError && ::previewOwner.isInitialized) previewOwner.cancelPreview()
        previewPrepared = false; previewPhase = if (keepError) "error" else ""
        val player = appPlayer
        if (local != null && player != null) {
            changing = true
            cancelFade(); player.pause(); player.stop(); player.clearMediaItems()
            if (local.items.isNotEmpty()) {
                player.setMediaItems(local.items, local.restoreIndex, local.restorePosition)
                player.prepare() // Ready for an explicit future Play, still playWhenReady=false.
            }
            applyMode(mode)
            // No play(): STOP/end/error must never resume the saved local queue.
            changing = false
        }
        if (resolvingForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(2102)
            resolvingForeground = false
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
        putBoolean(PlaybackCommands.EXTRA_FADE_BUSY, originalVolume != null)
        if (::previewOwner.isInitialized) {
            val state = previewOwner.preview.value
            state.hit?.let { putString(PreviewProtocol.ID, it.videoId); putString(PreviewProtocol.TITLE, it.title) }
            putString(PreviewProtocol.TOKEN, lease.token); putLong(PreviewProtocol.GENERATION, lease.generation)
            putString(PreviewProtocol.PHASE, if (state.canceling) "canceling" else previewPhase)
            putBoolean(PreviewProtocol.ERROR, state.error != null)
        }
    }
    private fun publishState() {
        session?.setSessionExtras(stateExtras())
        (application as? SwarariaApp)?.container?.remoteServerController?.notifyPlaybackChanged(buildRemoteSnapshot())
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
        val isFading = originalVolume != null
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(REMOTE_SERVER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(REMOTE_SERVER_NOTIFICATION_ID, notification)
        }
    }
    private fun startFade(): Boolean {
        val player = appPlayer ?: return false
        if (!FadeRules.canStart(player.isPlaying, originalVolume != null)) return false
        val initial = player.volume
        val started = SystemClock.uptimeMillis()
        originalVolume = initial
        val tick = object : Runnable {
            override fun run() {
                if (fadeTick !== this || originalVolume == null) return
                if (!player.isPlaying) { cancelFade(); return }
                val elapsed = SystemClock.uptimeMillis() - started
                player.volume = FadeRules.volumeAt(initial, elapsed)
                if (elapsed >= FadeRules.DURATION_MS) { player.pause(); cancelFade() }
                else handler.postDelayed(this, minOf(25L, FadeRules.DURATION_MS - elapsed))
            }
        }
        fadeTick = tick; publishState(); handler.post(tick); return true
    }
    private fun cancelFade() {
        fadeTick?.let { handler.removeCallbacks(it) }; fadeTick = null
        val restore = originalVolume ?: return
        originalVolume = null; appPlayer?.volume = restore; publishState()
        // Never call play(): cancellation must not undo a pause, focus loss, stop or track change.
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
                if (appPlayer?.isPlaying != true && savedLocal == null) {
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
        scope.cancel()
        if (::previewOwner.isInitialized) previewOwner.close()
        cancelFade(); handler.removeCallbacksAndMessages(null)
        appPlayer?.removeListener(playerListener); session?.release(); session = null
        appPlayer?.release(); appPlayer = null
        super.onDestroy()
    }
    companion object {
        private const val PREFERENCES = "native_playback"
        private const val ACTION_CANCEL_PREVIEW = "com.swararia.audiolab.CANCEL_PREVIEW"
        private const val REMOTE_SERVER_CHANNEL = "remote_server"
        private const val REMOTE_SERVER_NOTIFICATION_ID = 2103
        const val ACTION_REMOTE_SERVER_START = "com.swararia.audiolab.REMOTE_SERVER_START"
        const val ACTION_REMOTE_SERVER_STOP = "com.swararia.audiolab.REMOTE_SERVER_STOP"
        const val EXTRA_REMOTE_ENDPOINT = "com.swararia.audiolab.REMOTE_ENDPOINT"
    }
}
