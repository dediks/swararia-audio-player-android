package com.swararia.audiolab

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private var pendingPermissionAction: (() -> Unit)? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var deletionPort: PlaybackDeletion.Queue? = null
    private var deletionListener: Player.Listener? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        action?.invoke()
    }
    private val chooseAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) whenOnlineIdle {
            withNotificationPermission {
                // The picker/permission dialog can outlive the state seen by the button.
                whenOnlineIdle {
                    runCatching { IngestService.importAudio(this, uri) }
                        .onFailure { model.message(it.message ?: "Impor tidak dapat dimulai.") }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, AppViewModelFactory((application as SwarariaApp).container))[AppViewModel::class.java]
        connectPlayer()
        setContent {
            AudioTheme {
                val tracks by model.tracks.collectAsStateWithLifecycle()
                val ingest by model.ingest.collectAsStateWithLifecycle()
                val search by model.search.collectAsStateWithLifecycle()
                val preview by model.preview.collectAsStateWithLifecycle()
                val lastRename by model.lastRename.collectAsStateWithLifecycle()
                val organizerBusy by model.organizerBusy.collectAsStateWithLifecycle()
                val operationBusy by model.operationBusy.collectAsStateWithLifecycle()
                val jobs by model.jobs.collectAsStateWithLifecycle()
                val snackbars = remember { SnackbarHostState() }
                LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
                LaunchedEffect(tracks, controller) {
                    val player = controller
                    if (player != null && player.isConnected) {
                        val titles = tracks.associateBy { it.id.toString() }
                        for (index in 0 until player.mediaItemCount) {
                            val item = player.getMediaItemAt(index)
                            val updated = titles[item.mediaId] ?: continue
                            val title = UiPresentation.title(updated.displayName)
                            if (item.mediaMetadata.title?.toString() != title) {
                                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(
                                    item.mediaMetadata.buildUpon().setTitle(title).build()).build())
                            }
                        }
                    }
                }
                SwarariaUi(
                    tracks = tracks, controller = controller, ingest = ingest, search = search,
                    lastRename = lastRename, organizerBusy = organizerBusy, snackbarHostState = snackbars,
                    searchActions = SearchActions(
                        preview = preview,
                        search = { query, provider, kind, sort -> whenOnlineIdle { model.search(query, provider, kind, sort) } },
                        sort = { model.sortSearch(it) }, loadMore = { whenOnlineIdle { model.loadMore() } },
                        retry = { whenOnlineIdle { model.retrySearch() } },
                        openPlaylist = { hit -> whenOnlineIdle { model.openPlaylist(hit) } },
                        leavePlaylist = { model.leavePlaylist() },
                        previewSong = { hit -> withNotificationPermission { whenOnlineIdle { if (pauseForPreview()) model.preview(hit) } } },
                        stopPreview = { model.cancelPreview() }, previewError = { model.previewError() }),
                    onDownload = { url, authorized -> whenOnlineIdle {
                        withNotificationPermission { whenOnlineIdle {
                            model.enqueueDownloads(listOf(DownloadRequest(url, "Audio dari link")), authorized) {
                                IngestService.startBatch(this, it)
                            }
                        } }
                    } },
                    onImport = { whenOnlineIdle { chooseAudio.launch(arrayOf("audio/*")) } },
                    onUpdate = { whenOnlineIdle {
                        withNotificationPermission { whenOnlineIdle {
                            runCatching { IngestService.update(this) }
                                .onFailure { model.message(it.message ?: "Pembaruan tidak dapat dimulai.") }
                        } }
                    } },
                    onCancel = { IngestService.cancel(this) },
                    onPlay = { track -> withNotificationPermission {
                        runCatching { playTrack(track, tracks) }
                            .onFailure { model.message(it.message ?: "Audio tidak dapat diputar.") }
                    } },
                    onRename = { id, title, done -> model.rename(id, title, done) },
                    onUndo = { model.undoRename() },
                    onSearch = { query, provider -> whenOnlineIdle { model.search(query, provider) } },
                    onCancelSearch = { model.cancelSearch() },
                    upgrade = UpgradeUiActions(
                        jobs = jobs, operationBusy = operationBusy,
                        onDownloadBatch = { hits, authorized -> whenOnlineIdle {
                            withNotificationPermission { whenOnlineIdle {
                                model.enqueueDownloads(hits.filter { it.kind == SearchKind.SONGS }.map {
                                    DownloadRequest(it.url, it.title, it.thumbnailUrl)
                                }, authorized) { IngestService.startBatch(this, it) }
                            } }
                        } },
                        onCancelBatch = {
                            val batchId = model.ingest.value.batchId ?: model.jobs.value.firstOrNull()?.batchId
                            if (batchId != null) model.cancelQueuedBatch(batchId)
                            if (model.ingest.value.busy && model.ingest.value.batchId != null) IngestService.cancel(this)
                        },
                        onRetryFailed = { id -> whenOnlineIdle {
                            withNotificationPermission { whenOnlineIdle {
                                model.retryDownloads(id) { IngestService.startBatch(this, it) }
                            } }
                        } },
                        onDelete = { track, done -> deleteTrack(track, done) },
                    ),
                )
            }
        }
    }

    private fun whenOnlineIdle(action: () -> Unit) {
        if (!model.canStartOnlineOperation()) {
            model.message("Tunggu pencarian atau proses audio selesai terlebih dahulu.")
            return
        }
        model.cancelPreview()
        action()
    }

    private fun connectPlayer() {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(player: MediaController) {
                    if (controller !== player || isDestroyed) return
                    deletionPort?.let { model.playbackDeletion.disconnect(it) }
                    deletionPort = null
                    deletionListener?.let(player::removeListener); deletionListener = null
                    controller = null
                    val stale = controllerFuture; controllerFuture = null
                    stale?.let(MediaController::releaseFuture)
                    window.decorView.postDelayed({ if (!isDestroyed) connectPlayer() }, 1000L)
                }
            }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (!isDestroyed) runCatching { future.get() }.onSuccess { player ->
                controller = player
                val port = object : PlaybackDeletion.Queue {
                    override fun snapshot(): List<String>? =
                        if (player.isConnected && player.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
                            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } else null
                    override fun removeAt(index: Int): Boolean {
                        if (!player.isConnected || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
                        player.removeMediaItem(index)
                        return true
                    }
                }
                deletionPort = port
                model.playbackDeletion.connect(port)
                val listener = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        model.playbackDeletion.reconcile()
                    }
                }
                deletionListener = listener
                player.addListener(listener)
            }
                .onFailure { model.message("Pemutar belum terhubung: ${it.cause?.message ?: it.message.orEmpty()}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pauseForPreview(): Boolean {
        val player = controller
        if (player == null || !player.isConnected || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            model.message("Player belum terhubung. Coba lagi sebentar."); return false
        }
        player.pause()
        return true
    }

    private fun deleteTrack(track: AudioTrack, done: () -> Unit) {
        if (!model.canStartOnlineOperation()) { model.message("Tunggu proses yang sedang berjalan."); return }
        val player = controller
        if (player == null || !player.isConnected) { model.message("Player belum terhubung. Coba lagi."); return }
        val queued = (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == track.id.toString() }
        if (queued && !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            model.message("Queue player belum dapat diperbarui."); return
        }
        if (player.currentMediaItem?.mediaId == track.id.toString()) {
            if (!player.isCommandAvailable(Player.COMMAND_STOP) || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                model.message("Hentikan audio sebelum Delete."); return
            }
            player.pause(); player.stop()
        }
        // Hidden saved local context lives in the service during Preview. Remove the explicit ID
        // there BEFORE durable deletion, so restoring after end/error cannot resurrect it.
        val forgotten = player.sendCustomCommand(PreviewProtocol.forget, Bundle().apply {
            putString(PreviewProtocol.ID, track.id.toString())
        })
        forgotten.addListener({
            if (runCatching { forgotten.get().resultCode }.getOrNull() == androidx.media3.session.SessionResult.RESULT_SUCCESS)
                model.delete(track.id, done)
            else model.message("Queue player belum dapat diperbarui. Coba Delete lagi.")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playTrack(track: AudioTrack, tracks: List<AudioTrack>) {
        model.cancelPreview()
        val player = controller ?: error("Pemutar masih menghubungkan. Coba lagi sebentar.")
        check(player.isConnected) { "Pemutar terputus. Tutup dan buka aplikasi untuk menghubungkan lagi." }
        check(model.playbackDeletion.canPlay(track.id)) { "Audio sedang atau sudah dihapus." }
        val playable = tracks.filter { model.playbackDeletion.canPlay(it.id) }
        val queue = playable.map { item ->
            MediaItem.Builder().setMediaId(item.id.toString()).setUri(Uri.parse(item.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(UiPresentation.title(item.displayName)).build()).build()
        }
        player.setMediaItems(queue, playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0)
        player.prepare(); player.play()
    }

    private fun withNotificationPermission(action: () -> Unit) {
        val preferences = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("asked_notifications", false)) {
            preferences.edit().putBoolean("asked_notifications", true).apply()
            pendingPermissionAction = action
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }

    override fun onDestroy() {
        deletionPort?.let { model.playbackDeletion.disconnect(it) }
        deletionPort = null
        deletionListener?.let { controller?.removeListener(it) }
        deletionListener = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null; controller = null
        super.onDestroy()
    }
}
