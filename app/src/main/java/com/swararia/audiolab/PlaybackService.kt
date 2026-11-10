package com.swararia.audiolab

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var appPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeTick: Runnable? = null
    private var originalVolume: Float? = null
    private var mode = PlaybackMode.CONTINUE

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) cancelFade()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) cancelFade()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancelFade()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = cancelFade()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = cancelFade()
    }

    private fun isApp(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == packageName && controller.uid == Process.myUid()

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!isApp(controller) && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (isApp(controller)) {
                commands.add(PlaybackCommands.setMode).add(PlaybackCommands.fade)
            }
            // The native flags belong to the mode protocol; other controllers cannot desynchronise them.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            val result = if (!isApp(controller)) {
                SessionResult.RESULT_ERROR_PERMISSION_DENIED
            } else when (customCommand.customAction) {
                PlaybackCommands.ACTION_SET_MODE -> {
                    val requested = args.getString(PlaybackCommands.EXTRA_MODE)
                    val parsed = PlaybackMode.entries.firstOrNull { it.name == requested }
                    if (parsed == null) SessionResult.RESULT_ERROR_BAD_VALUE else {
                        applyMode(parsed)
                        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(PlaybackCommands.EXTRA_MODE, parsed.name).apply()
                        SessionResult.RESULT_SUCCESS
                    }
                }
                PlaybackCommands.ACTION_FADE -> if (startFade()) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE
                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return Futures.immediateFuture(SessionResult(result, stateExtras()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        appPlayer = player
        player.addListener(playerListener)
        applyMode(PlaybackMode.fromStored(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PlaybackCommands.EXTRA_MODE, null)))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open)
            .setCallback(callback).setSessionExtras(stateExtras()).build()
    }

    private fun applyMode(selected: PlaybackMode) {
        val player = appPlayer ?: return
        // Native end-of-item pausing prevents even a brief start of the following item.
        player.setPauseAtEndOfMediaItems(selected.pauseAtItemEnd)
        player.repeatMode = if (selected.repeatCurrent) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        mode = selected
        publishState()
    }

    private fun stateExtras() = Bundle().apply {
        putInt(PlaybackCommands.EXTRA_PROTOCOL, 1)
        putString(PlaybackCommands.EXTRA_MODE, mode.name)
        putBoolean(PlaybackCommands.EXTRA_FADE_BUSY, originalVolume != null)
    }

    private fun publishState() { session?.setSessionExtras(stateExtras()) }

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
                if (elapsed >= FadeRules.DURATION_MS) {
                    player.pause()
                    cancelFade() // Idempotent if the pause listener already restored volume.
                } else handler.postDelayed(this, minOf(25L, FadeRules.DURATION_MS - elapsed))
            }
        }
        fadeTick = tick
        publishState()
        handler.post(tick)
        return true
    }

    private fun cancelFade() {
        fadeTick?.let { handler.removeCallbacks(it) }
        fadeTick = null
        val restore = originalVolume ?: return
        originalVolume = null
        appPlayer?.volume = restore
        publishState()
        // Never call play(): cancellation must not undo a pause, focus loss, stop or track change.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (isApp(controllerInfo) || controllerInfo.isTrusted) session else null

    override fun onDestroy() {
        cancelFade()
        handler.removeCallbacksAndMessages(null)
        appPlayer?.removeListener(playerListener)
        session?.release()
        session = null
        appPlayer?.release()
        appPlayer = null
        super.onDestroy()
    }

    private companion object { const val PREFERENCES = "native_playback" }
}
