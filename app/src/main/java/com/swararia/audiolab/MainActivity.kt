package com.swararia.audiolab

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
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
    private var pendingLegacyStorageAction: (() -> Unit)? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var deletionPort: PlaybackDeletion.Queue? = null
    private var deletionListener: Player.Listener? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        action?.invoke()
    }
    private val legacyStoragePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val action = pendingLegacyStorageAction
        pendingLegacyStorageAction = null
        val granted = results.values.all { it } || hasLegacyStoragePermission()
        if (granted) {
            action?.invoke()
        } else {
            model.message("Izin penyimpanan diperlukan di Android 8–9 untuk menyimpan file ke Music/SwarariaAudioLab.")
        }
    }
    private val chooseAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) whenOnlineIdle {
            withIngestPermissions {
                // The picker/permission dialog can outlive the state seen by the button.
                whenOnlineIdle {
                    runCatching { IngestService.importAudioBatch(this, uris) }
                        .onFailure { model.message(it.message ?: "Impor tidak dapat dimulai.") }
                }
            }
        }
    }

    private fun whenQueueReady(action: () -> Unit) {
        if (!model.canDispatchOrAppendDownload()) {
            model.message("Tunggu proses pencarian atau pengelola selesai."); return
        }
        // Keep an already-playing preview alive while queueing downloads in the background.
        action()
    }

    private fun whenQueueStartReady(action: () -> Unit) {
        if (!model.canStartOnlineOperation()) {
            model.message("Tunggu pencarian atau proses audio selesai terlebih dahulu."); return
        }
        action()
    }

    private fun syncControllerTrackTitles(player: MediaController?, tracks: List<AudioTrack>) {
        if (player == null || !player.isConnected || tracks.isEmpty()) return
        val titles = tracks.associateBy { it.id.toString() }
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val updated = titles[item.mediaId] ?: continue
            val title = UiPresentation.title(updated.displayName)
            if (item.mediaMetadata.title?.toString() != title) {
                player.replaceMediaItem(
                    index,
                    item.buildUpon().setMediaMetadata(
                        item.mediaMetadata.buildUpon().setTitle(title).build(),
                    ).build(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        model = ViewModelProvider(this, AppViewModelFactory((application as SwarariaApp).container))[AppViewModel::class.java]
        connectPlayer()
        setContent {
            val container = (application as SwarariaApp).container
            val brandSkinConfig by container.brandSkinManager.state.collectAsStateWithLifecycle()
            AudioTheme(skinConfig = brandSkinConfig) {
                val tracks by model.tracks.collectAsStateWithLifecycle()
                val playlists by model.playlists.collectAsStateWithLifecycle()
                val ingest by model.ingest.collectAsStateWithLifecycle()
                val search by model.search.collectAsStateWithLifecycle()
                val preview by model.preview.collectAsStateWithLifecycle()
                val lastRename by model.lastRename.collectAsStateWithLifecycle()
                val organizerBusy by model.organizerBusy.collectAsStateWithLifecycle()
                val operationBusy by model.operationBusy.collectAsStateWithLifecycle()
                val jobs by model.jobs.collectAsStateWithLifecycle()
                val remoteServerState by model.remoteServerState.collectAsStateWithLifecycle()
                val ytMusicState by model.ytMusicState.collectAsStateWithLifecycle()
                val downloadSettings by model.downloadSettings.collectAsStateWithLifecycle()
                val playbackSettings by model.playbackSettings.collectAsStateWithLifecycle()
                val snackbars = remember { SnackbarHostState() }
                LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
                LaunchedEffect(tracks, controller) {
                    syncControllerTrackTitles(controller, tracks)
                }
                LaunchedEffect(preview.hit, preview.loading, preview.error, tracks, controller) {
                    if (preview.hit == null && !preview.loading && preview.error == null) {
                        kotlinx.coroutines.delay(160L)
                        syncControllerTrackTitles(controller, tracks)
                    }
                }
                SwarariaUi(
                    tracks = tracks, playlists = playlists, controller = controller, ingest = ingest, search = search,
                    lastRename = lastRename, organizerBusy = organizerBusy, snackbarHostState = snackbars,
                    searchActions = SearchActions(
                        preview = preview,
                        search = { query, provider, kind, sort -> whenOnlineIdle { model.search(query, provider, kind, sort) } },
                        switchSearch = { query, provider, kind, sort ->
                            if (!ingest.busy && !operationBusy) model.switchSearch(query, provider, kind, sort)
                        },
                        sort = { model.sortSearch(it) }, loadMore = { whenOnlineIdle { model.loadMore() } },
                        retry = { whenOnlineIdle { model.retrySearch() } },
                        openPlaylist = { hit -> whenOnlineIdle { model.openPlaylist(hit) } },
                        leavePlaylist = { model.leavePlaylist() },
                        previewSong = { hit -> withNotificationPermission { whenOnlineIdle { if (pauseForPreview()) model.preview(hit) } } },
                        stopPreview = { model.cancelPreview() }, previewError = { model.previewError() }),
                    onDownload = { url, authorized -> whenQueueReady {
                        withIngestPermissions { whenQueueReady {
                            model.enqueueDownloads(listOf(DownloadRequest(url, "Audio dari link")), authorized) {
                                IngestService.ensureBatch(this, it)
                            }
                        } }
                    } },
                    onImport = { whenOnlineIdle { withLegacyStoragePermission { whenOnlineIdle { chooseAudio.launch(arrayOf("audio/*")) } } } },
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
                    onPlayQueue = { track, queue -> withNotificationPermission {
                        runCatching { playTrack(track, queue.ifEmpty { tracks }, autoPlay = true) }
                            .onFailure { model.message(it.message ?: "Audio tidak dapat diputar.") }
                    } },
                    onCueQueue = { track, queue -> withNotificationPermission {
                        runCatching { playTrack(track, queue.ifEmpty { tracks }, autoPlay = false) }
                            .onFailure { model.message(it.message ?: "Cue belum dapat dimuat.") }
                    } },
                    playlistActions = PlaylistUiActions(
                        onCreatePlaylist = { name, trackId -> model.createPlaylist(name, trackId) },
                        onCreatePlaylistWithTracks = { name, trackIds -> model.createPlaylistWithTracks(name, trackIds) },
                        onRenamePlaylist = { playlistId, name -> model.renamePlaylist(playlistId, name) },
                        onDeletePlaylist = { playlistId -> model.deletePlaylist(playlistId) },
                        onAddTrackToPlaylist = { playlistId, trackId -> model.addTrackToPlaylist(playlistId, trackId) },
                        onAddTracksToPlaylist = { playlistId, trackIds -> model.addTracksToPlaylist(playlistId, trackIds) },
                        onRemoveTrackFromPlaylist = { playlistId, trackId -> model.removeTrackFromPlaylist(playlistId, trackId) },
                        onReorderPlaylistTracks = { playlistId, orderedIds -> model.reorderPlaylistTracks(playlistId, orderedIds) },
                    ),
                    onRename = { id, title, done -> model.rename(id, title, done) },
                    onUndo = { model.undoRename() },
                    onSearch = { query, provider -> whenOnlineIdle { model.search(query, provider) } },
                    onCancelSearch = { model.cancelSearch() },
                    desktopRemote = model.desktopRemote,
                    remoteServerState = remoteServerState,
                    remoteServerActions = model.remoteServerActions,
                    ytMusicState = ytMusicState,
                    playbackSettings = playbackSettings,
                    onSetPlaybackFadeInMs = { fadeInMs ->
                        model.setPlaybackFadeInMs(fadeInMs)
                        syncFadeSettingsToService(playbackSettings.copy(fadeInMs = fadeInMs))
                    },
                    onSetPlaybackFadeOutMs = { fadeOutMs ->
                        model.setPlaybackFadeOutMs(fadeOutMs)
                        syncFadeSettingsToService(playbackSettings.copy(fadeOutMs = fadeOutMs))
                    },
                    ytMusicActions = YoutubeMusicAccountActions(
                        onCompleteWebViewSignIn = { www, music -> model.connectYtMusicWebView(www, music) },
                        onImportCookiesText = { raw -> model.importYtMusicCookies(raw) },
                        onToggleEnabled = { enabled -> model.setYtMusicEnabled(enabled) },
                        onVerifyAccount = { model.verifyYtMusicAccount() },
                        onDisconnect = { model.disconnectYtMusic() },
                    ),
                    upgrade = UpgradeUiActions(
                        jobs = jobs, operationBusy = operationBusy,
                        downloadSettings = downloadSettings,
                        onDownloadBatch = { hits, authorized -> whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.enqueueDownloads(hits.filter { it.kind == SearchKind.SONGS }.map {
                                    DownloadRequest(it.url, it.title, it.thumbnailUrl)
                                }, authorized) { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onCancelBatch = {
                            val batchIds = buildSet {
                                model.ingest.value.batchId?.let(::add)
                                model.jobs.value.filter { it.status == "QUEUED" || it.status == "RUNNING" }.mapTo(this) { it.batchId }
                            }
                            batchIds.forEach { model.cancelQueuedBatch(it) }
                            if (model.ingest.value.busy && model.ingest.value.batchId != null) IngestService.cancel(this)
                        },
                        onHaltQueue = {
                            if (model.ingest.value.busy && model.ingest.value.batchId != null) {
                                IngestService.pauseQueue(this)
                            }
                        },
                        onContinueQueue = { whenQueueStartReady {
                            withIngestPermissions { whenQueueStartReady {
                                model.continueDownloadQueue { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onSkipCurrentJob = { jobId ->
                            if (model.ingest.value.busy && model.ingest.value.batchId != null) {
                                IngestService.skipCurrent(this, jobId)
                            }
                        },
                        onMoveQueuedUp = { jobId -> model.moveDownloadJobUp(jobId) },
                        onMoveQueuedToFront = { jobId -> model.moveDownloadJobToFront(jobId) },
                        onRetryFailed = { id -> whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.retryDownloads(id) { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onRetryAllFailed = { whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.retryAllFailedDownloads { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onRetryJob = { jobId -> whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.retryDownloadJob(jobId) { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onRequeueJob = { jobId -> whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.retryDownloadJob(jobId) { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onRequeueJobs = { jobIds -> whenQueueReady {
                            withIngestPermissions { whenQueueReady {
                                model.requeueDownloadJobs(jobIds) { IngestService.ensureBatch(this, it) }
                            } }
                        } },
                        onCancelJob = { jobId -> model.cancelDownloadJob(jobId) },
                        onCancelJobs = { jobIds -> model.cancelDownloadJobs(jobIds) },
                        onRemoveJob = { jobId -> model.removeDownloadJob(jobId) },
                        onRemoveJobs = { jobIds -> model.removeDownloadJobs(jobIds) },
                        onClearFinished = { model.clearFinishedDownloads() },
                        onSetWifiOnly = { enabled -> model.setDownloadWifiOnly(enabled) },
                        onSetRateLimit = { limit -> model.setDownloadRateLimit(limit) },
                        onDelete = { track, done -> deleteTrack(track, done) },
                        onDeleteBatch = { selectedTracks, done -> deleteTracks(selectedTracks, done) },
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

    private fun pausePlaybackImmediately(player: MediaController) {
        pauseSwarariaPlaybackImmediately(player)
    }

    private fun syncFadeSettingsToService(settings: PlaybackSettingsState) {
        val player = controller ?: return
        if (!player.isConnected || !player.isSessionCommandAvailable(PlaybackCommands.setFadeSettings)) return
        player.sendCustomCommand(PlaybackCommands.setFadeSettings, Bundle().apply {
            putLong(PlaybackCommands.EXTRA_FADE_IN_MS, settings.fadeInMs)
            putLong(PlaybackCommands.EXTRA_FADE_OUT_MS, settings.fadeOutMs)
        })
    }

    private fun pauseForPreview(): Boolean {
        val player = controller
        if (player == null || !player.isConnected || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            model.message("Player belum terhubung. Coba lagi sebentar."); return false
        }
        pausePlaybackImmediately(player)
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
            pausePlaybackImmediately(player); player.stop()
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

    private fun playTrack(track: AudioTrack, tracks: List<AudioTrack>, autoPlay: Boolean = true) {
        model.cancelPreview()
        val player = controller ?: error("Pemutar masih menghubungkan. Coba lagi sebentar.")
        check(player.isConnected) { "Pemutar terputus. Tutup dan buka aplikasi untuk menghubungkan lagi." }
        check(model.playbackDeletion.canPlay(track.id)) { "Audio sedang atau sudah dihapus." }
        val playable = tracks.filter { model.playbackDeletion.canPlay(it.id) }
        val queue = playable.map { item ->
            MediaItem.Builder().setMediaId(item.id.toString()).setUri(Uri.parse(item.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(UiPresentation.title(item.displayName)).build()).build()
        }
        val targetIndex = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (autoPlay) {
            player.setMediaItems(queue, targetIndex, 0)
            player.prepare(); player.play()
        } else {
            player.pause()
            player.setMediaItems(queue, targetIndex, 0)
            player.prepare()
            model.message("Cue Standby 00:00: ${UiPresentation.title(track.displayName)}")
        }
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

    private fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun withLegacyStoragePermission(action: () -> Unit) {
        if (hasLegacyStoragePermission()) {
            action()
        } else {
            pendingLegacyStorageAction = action
            legacyStoragePermissions.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    private fun withIngestPermissions(action: () -> Unit) {
        withLegacyStoragePermission { withNotificationPermission(action) }
    }

    private fun deleteTracks(tracksToDelete: List<AudioTrack>, done: () -> Unit) {
        val distinct = tracksToDelete.distinctBy { it.id }
        if (distinct.isEmpty()) return
        if (!model.canStartOnlineOperation()) { model.message("Tunggu proses yang sedang berjalan."); return }
        val player = controller
        if (player == null || !player.isConnected) { model.message("Player belum terhubung. Coba lagi."); return }
        val targetIds = distinct.map { it.id.toString() }.toSet()
        val anyQueued = (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId in targetIds }
        if (anyQueued && !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            model.message("Queue player belum dapat diperbarui."); return
        }
        if (player.currentMediaItem?.mediaId in targetIds) {
            if (!player.isCommandAvailable(Player.COMMAND_STOP) || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                model.message("Hentikan audio sebelum Delete."); return
            }
            pausePlaybackImmediately(player); player.stop()
        }
        forgetDeletedSequence(player, distinct.map { it.id.toString() }, 0) {
            model.deleteBatch(distinct.map { it.id }, done)
        }
    }

    private fun forgetDeletedSequence(
        player: MediaController,
        ids: List<String>,
        index: Int,
        onReady: () -> Unit,
    ) {
        if (index >= ids.size) {
            onReady()
            return
        }
        val future = player.sendCustomCommand(PreviewProtocol.forget, Bundle().apply {
            putString(PreviewProtocol.ID, ids[index])
        })
        future.addListener({
            if (runCatching { future.get().resultCode }.getOrNull() == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                forgetDeletedSequence(player, ids, index + 1, onReady)
            } else {
                model.message("Queue player belum dapat diperbarui. Coba Delete lagi.")
            }
        }, ContextCompat.getMainExecutor(this))
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
