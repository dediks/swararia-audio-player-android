package com.swararia.audiolab

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Only public identifiers cross the app-only protocol. Signed sources never leave service RAM. */
object PreviewProtocol {
    const val START = "com.swararia.preview.START"
    const val STOP = "com.swararia.preview.STOP"
    const val FORGET = "com.swararia.preview.FORGET_DELETED"
    const val TOKEN = "preview_token"
    const val GENERATION = "preview_generation"
    const val ID = "preview_id"
    const val TITLE = "preview_title"
    const val PHASE = "preview_phase"
    const val ERROR = "preview_error"
    val start = SessionCommand(START, Bundle.EMPTY)
    val stop = SessionCommand(STOP, Bundle.EMPTY)
    val forget = SessionCommand(FORGET, Bundle.EMPTY)
}

/** Retained VM adapter, application context only. Releasing a controller is NOT a playback stop. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PreviewRemote(context: Context) : PreviewPlayback {
    private val app = context.applicationContext
    private val state = MutableStateFlow(PreviewState(loading = true, phase = "connecting"))
    override val preview = state.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var closed = false
    private var pending: Pair<SessionCommand, Bundle>? = null
    private var requested: Bundle? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var lastExtras: Bundle = Bundle.EMPTY
    private var commandGeneration = 0L
    private var reconnectDelay = 1000L
    private val reconnect = Runnable { connect() }
    init { connect() }
    private fun scheduleReconnect() {
        if (closed) return
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8000L)
    }
    private fun connect() {
        if (closed) return
        val connecting = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    if (this@PreviewRemote.controller === controller) read(extras)
                }
                override fun onDisconnected(controller: MediaController) {
                    if (!closed && this@PreviewRemote.controller === controller) {
                        this@PreviewRemote.controller = null
                        commandGeneration++
                        requested = null // Never replay an already dispatched START after disconnection.
                        future?.let(MediaController::releaseFuture); future = null
                        state.value = PreviewState(loading = true, hit = state.value.hit, phase = "connecting")
                        scheduleReconnect()
                    }
                }
            }).buildAsync()
        future = connecting
        connecting.addListener({
            if (!closed && future === connecting) runCatching { connecting.get() }.onSuccess {
                controller = it
                reconnectDelay = 1000L
                read(it.sessionExtras)
                pending?.let { command -> pending = null; send(command.first, command.second) }
            }.onFailure {
                future = null
                state.value = PreviewState(hit = state.value.hit, error = "Player belum terhubung.")
                scheduleReconnect()
            }
        }, ContextCompat.getMainExecutor(app))
    }
    private fun read(extras: Bundle) {
        val requestedGeneration = requested?.getLong(PreviewProtocol.GENERATION) ?: 0L
        if (requestedGeneration > extras.getLong(PreviewProtocol.GENERATION)) return
        requested = null
        lastExtras = Bundle(extras)
        val id = extras.getString(PreviewProtocol.ID)
        val phase = extras.getString(PreviewProtocol.PHASE).orEmpty()
        val hit = id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let {
            SearchHit(it, extras.getString(PreviewProtocol.TITLE).orEmpty(), null, null, null, "https://www.youtube.com/watch?v=$it")
        }
        state.value = PreviewState(hit = hit, loading = phase == "resolving" || phase == "canceling",
            canceling = phase == "canceling", error = if (extras.getBoolean(PreviewProtocol.ERROR)) "Preview tidak dapat diputar. Coba lagi atau pilih lagu lain." else null,
            phase = phase)
    }
    private fun send(command: SessionCommand, args: Bundle) {
        val ticket = ++commandGeneration
        val player = controller
        if (player == null || !player.isConnected) { pending = command to args; return }
        if (!player.isSessionCommandAvailable(command)) {
            requested = null
            state.value = PreviewState(hit = state.value.hit, error = "Preview belum tersedia."); return
        }
        val result = player.sendCustomCommand(command, args)
        result.addListener({
            if (closed || this.controller !== player || ticket != commandGeneration) return@addListener
            if (runCatching { result.get().resultCode }.getOrDefault(SessionResult.RESULT_ERROR_UNKNOWN) != SessionResult.RESULT_SUCCESS) {
                requested = null
                state.value = PreviewState(hit = state.value.hit, error = "Preview tidak dapat dimulai. Coba lagi.")
            }
        }, ContextCompat.getMainExecutor(app))
    }
    override fun preview(hit: SearchHit) {
        if (hit.kind != SearchKind.SONGS || state.value.loading) return
        val generation = SystemClock.elapsedRealtimeNanos()
        state.value = PreviewState(loading = true, hit = hit, phase = "resolving")
        val args = Bundle().apply {
            putString(PreviewProtocol.TOKEN, UUID.randomUUID().toString())
            putLong(PreviewProtocol.GENERATION, generation)
            putString(PreviewProtocol.ID, hit.videoId)
            putString(PreviewProtocol.TITLE, hit.title.filterNot { it.isISOControl() }.take(200))
        }
        requested = args
        send(PreviewProtocol.start, args)
    }
    override fun cancelPreview() {
        val extras = requested ?: controller?.sessionExtras ?: lastExtras
        if (pending?.first == PreviewProtocol.start) { pending = null; requested = null; state.value = PreviewState(); return }
        if (extras?.getString(PreviewProtocol.TOKEN).isNullOrEmpty()) { state.value = PreviewState(); return }
        send(PreviewProtocol.stop, Bundle().apply {
            putString(PreviewProtocol.TOKEN, extras.getString(PreviewProtocol.TOKEN))
            putLong(PreviewProtocol.GENERATION, extras.getLong(PreviewProtocol.GENERATION))
        })
    }
    override fun previewError() = cancelPreview()
    override fun close() {
        closed = true; pending = null; requested = null; handler.removeCallbacksAndMessages(null)
        future?.let(MediaController::releaseFuture); future = null; controller = null
    }
}
