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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private var pendingPermissionAction: (() -> Unit)? = null
    private var controller by mutableStateOf<MediaController?>(null)
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
                        leavePlaylist = { whenOnlineIdle { model.leavePlaylist() } },
                        previewSong = { hit -> whenOnlineIdle { model.preview(hit) } },
                        stopPreview = { model.cancelPreview() }, previewError = { model.previewError() }),
                    onDownload = { url, authorized -> whenOnlineIdle {
                        withNotificationPermission { whenOnlineIdle {
                            runCatching { IngestService.download(this, url, authorized) }
                                .onFailure { model.message(it.message ?: "Unduhan tidak dapat dimulai.") }
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
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            if (!isDestroyed) runCatching { future.get() }.onSuccess { controller = it }
                .onFailure { model.message("Pemutar belum terhubung: ${it.cause?.message ?: it.message.orEmpty()}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playTrack(track: AudioTrack, tracks: List<AudioTrack>) {
        model.cancelPreview()
        val player = controller ?: error("Pemutar masih menghubungkan. Coba lagi sebentar.")
        check(player.isConnected) { "Pemutar terputus. Tutup dan buka aplikasi untuk menghubungkan lagi." }
        val queue = tracks.map { item ->
            MediaItem.Builder().setMediaId(item.id.toString()).setUri(Uri.parse(item.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(UiPresentation.title(item.displayName)).build()).build()
        }
        player.setMediaItems(queue, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0)
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null; controller = null
        super.onDestroy()
    }
}
