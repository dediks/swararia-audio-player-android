package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

/** Presentation only: storage, playback-session ownership and ingest remain outside this UI. */
@Composable
fun SwarariaUi(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist> = emptyList(),
    controller: MediaController?,
    ingest: IngestState,
    search: YoutubeSearchState,
    lastRename: RenameRecord?,
    organizerBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    searchActions: SearchActions,
    onDownload: (String, Boolean) -> Unit,
    onImport: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onPlayQueue: (AudioTrack, List<AudioTrack>) -> Unit = { track, _ -> onPlay(track) },
    onCueQueue: (AudioTrack, List<AudioTrack>) -> Unit = { _, _ -> },
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    onRename: (Long, String, () -> Unit) -> Unit,
    onUndo: () -> Unit,
    onSearch: (String, SearchProvider) -> Unit,
    onCancelSearch: () -> Unit,
    upgrade: UpgradeUiActions,
    desktopRemote: DesktopRemoteSession,
    remoteServerState: RemoteServerUiState = RemoteServerUiState(),
    remoteServerActions: RemoteServerUiActions = RemoteServerUiActions(),
) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var miniPlayerCollapsed by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AudioTrack?>(null) }
    var deleting by remember { mutableStateOf<AudioTrack?>(null) }
    var deletingBatch by remember { mutableStateOf<List<AudioTrack>?>(null) }
    var pendingQueueSwitch by remember { mutableStateOf<PendingQueueSwitch?>(null) }
    val pages = rememberSaveableStateHolder()
    val playback = rememberSwarariaPlayback(controller)
    val current = tracks.firstOrNull { it.id.toString() == playback.mediaId }
    val previewActive = searchActions.preview.let { it.hit != null || it.loading || it.media != null || it.error != null }
    val onlineBusy = ingest.busy || upgrade.operationBusy || search.busy || searchActions.preview.loading
    val navigate: (Int) -> Unit = { target ->
        if (target != 3 && destination != 3 && (target != destination || fullPlayer)) searchActions.stopPreview()
        fullPlayer = false
        destination = target
    }
    val playQueuedTrack: (AudioTrack, List<AudioTrack>?, Boolean) -> Unit = { track, queue, cueOnly ->
        if (playback.connected) {
            if (!cueOnly && queue == null && track.id.toString() == playback.mediaId) {
                searchActions.stopPreview()
                toggleSwarariaPlayback(controller)
            } else if (!cueOnly && queue != null && track.id.toString() == playback.mediaId && !playback.wantsPlay) {
                searchActions.stopPreview()
                toggleSwarariaPlayback(controller)
            } else if (playback.wantsPlay) {
                pendingQueueSwitch = PendingQueueSwitch(
                    targetTitle = UiPresentation.title(track.displayName),
                    track = track,
                    queue = queue.orEmpty(),
                )
            } else {
                searchActions.stopPreview()
                if (cueOnly) onCueQueue(track, queue.orEmpty())
                else if (queue != null) onPlayQueue(track, queue)
                else onPlay(track)
            }
        }
    }
    val playTrack: (AudioTrack) -> Unit = { track -> playQueuedTrack(track, null, false) }
    LaunchedEffect(previewActive) { if (previewActive) fullPlayer = false }
    BackHandler(enabled = fullPlayer || destination != 0 || previewActive) {
        if (destination != 3) searchActions.stopPreview()
        if (fullPlayer) fullPlayer = false else destination = 0
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!fullPlayer || previewActive) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    if (destination == 3 && previewActive)
                        Text("Pemutar HP • terpisah dari Desktop", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                    // One slot: preview always replaces, never accompanies, local audio.
                    // On the Remote tab (destination == 3), local playback auto-collapses into the top banner pill so bottom decks never double-stack.
                    if (previewActive) SearchPreviewPlayer(searchActions.preview, controller,
                        searchActions.stopPreview, searchActions.previewError,
                        onRetry = { if (!onlineBusy) searchActions.preview.hit?.let(searchActions.previewSong) })
                    else if (playback.mediaId != null) {
                        if (destination != 3) {
                            MiniPlayer(
                                current = current,
                                playback = playback,
                                controller = controller,
                                collapsed = miniPlayerCollapsed,
                                onToggleCollapse = { miniPlayerCollapsed = !miniPlayerCollapsed },
                                onOpen = { fullPlayer = true },
                            )
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
                        NavigationBarItem(selected = destination == 0, onClick = { navigate(0) },
                            icon = { Icon(UiIcons.Library, null) }, label = { Text("Library") }, colors = navigationColors())
                        NavigationBarItem(selected = destination == 1, onClick = { navigate(1) },
                            icon = { Icon(UiIcons.Search, null) }, label = { Text("Search") }, colors = navigationColors())
                        NavigationBarItem(selected = destination == 2, onClick = { navigate(2) },
                            icon = { Icon(UiIcons.Download, null) }, label = { Text("Downloads") }, colors = navigationColors())
                        NavigationBarItem(selected = destination == 3, onClick = { navigate(3) },
                            icon = { Icon(UiIcons.Link, null) }, label = { Text("Remote") }, colors = navigationColors())
                    }
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (fullPlayer && !previewActive) FocusedPlayer(
            tracks = tracks, current = current, playback = playback, controller = controller,
            modifier = contentModifier, onBack = { fullPlayer = false },
            onRename = { editing = it }, onSettings = { settings = true },
        ) else pages.SaveableStateProvider(destination) {
            when (destination) {
                0 -> LibraryPage(
                    tracks = tracks,
                    playlists = playlists,
                    currentId = playback.mediaId,
                    wantsPlay = playback.wantsPlay,
                    connected = playback.connected,
                    ingest = ingest,
                    search = search,
                    organizerBusy = organizerBusy || upgrade.operationBusy,
                    modifier = contentModifier,
                    onAdd = { navigate(1) },
                    onPlay = playTrack,
                    onPlayQueue = { track, queue -> playQueuedTrack(track, queue, false) },
                    onCueQueue = { track, queue -> playQueuedTrack(track, queue, true) },
                    onGuardPlayOrCue = { showTitle, track, queue, cueOnly ->
                        if (playback.connected) {
                            if (playback.wantsPlay) {
                                pendingQueueSwitch = PendingQueueSwitch(
                                    targetTitle = showTitle,
                                    track = track,
                                    queue = queue,
                                )
                            } else {
                                searchActions.stopPreview()
                                if (cueOnly) onCueQueue(track, queue) else onPlayQueue(track, queue)
                            }
                        }
                    },
                    playlistActions = playlistActions,
                    onRename = { editing = it },
                    onHistory = { history = true },
                    onSettings = { settings = true },
                    onDelete = { deleting = it },
                    onDeleteBatch = { selected -> deletingBatch = selected },
                    onImport = { if (!onlineBusy) onImport() },
                )
                1 -> AddAudioScreen(ingest = ingest, search = search, controller = controller, actions = searchActions,
                    modifier = contentModifier, upgrade = upgrade,
                    onDownload = { url, authorized -> if (!onlineBusy && authorized) { searchActions.stopPreview(); onDownload(url, true) } },
                    onDownloadBatch = { hits, authorized ->
                        if (!onlineBusy && authorized && hits.isNotEmpty()) {
                            searchActions.stopPreview()
                            upgrade.onDownloadBatch(hits, true)
                            navigate(2)
                        }
                    },
                    onImport = { if (!onlineBusy) onImport() },
                    onCancel = { if (ingest.busy && ingest.cancelable) onCancel() },
                    onCancelSearch = onCancelSearch, onSettings = { settings = true })
                3 -> RemoteTabScreen(
                    desktopRemote = desktopRemote,
                    serverState = remoteServerState,
                    serverActions = remoteServerActions,
                    modifier = contentModifier,
                    localAudioBanner = {
                        if (!previewActive && playback.mediaId != null) {
                            RemoteLocalAudioPill(
                                current = current,
                                playback = playback,
                                controller = controller,
                                onOpen = { fullPlayer = true },
                            )
                        }
                    },
                )
                else -> DownloadsScreen(upgrade, ingest, contentModifier, onSettings = { settings = true },
                    onCancelIngest = { if (ingest.busy && ingest.cancelable) onCancel() })
            }
        }
    }
    editing?.let { track -> key(track.id, track.displayName) {
        RenameAudioDialog(track, organizerBusy || upgrade.operationBusy, { editing = null }, onRename)
    } }
    deleting?.let { track -> key(track.id) {
        DeleteAudioDialog(track, organizerBusy || upgrade.operationBusy, { deleting = null }, upgrade.onDelete)
    } }
    deletingBatch?.let { batch ->
        DeleteBatchAudioDialog(
            tracks = batch,
            busy = organizerBusy || upgrade.operationBusy,
            onDismiss = { deletingBatch = null },
            onDeleteBatch = upgrade.onDeleteBatch,
        )
    }
    pendingQueueSwitch?.let { pending ->
        val activeTitle = current?.let { UiPresentation.title(it.displayName) }
            ?: playback.title?.takeIf { it.isNotBlank() }?.let(UiPresentation::title)
            ?: "Audio aktif"
        LocalTrackSwitchGuardDialog(
            targetTitle = pending.targetTitle,
            activeTitle = activeTitle,
            cueButtonLabel = "Load Cue (00:00 · Standby)",
            detailText = "\"$activeTitle\" sedang live. Memuat \"${pending.targetTitle}\" akan mengganti antrean aktif:",
            onCueOnly = {
                pendingQueueSwitch = null
                searchActions.stopPreview()
                onCueQueue(pending.track, pending.queue)
            },
            onCutAndPlay = {
                pendingQueueSwitch = null
                searchActions.stopPreview()
                if (pending.queue.isNotEmpty()) onPlayQueue(pending.track, pending.queue)
                else onPlay(pending.track)
            },
            onDismiss = { pendingQueueSwitch = null },
        )
    }
    if (history) RenameHistoryDialog(lastRename, organizerBusy, { history = false }, onUndo)
    if (settings) EngineSettingsDialog(ingest, search.busy || searchActions.preview.loading || upgrade.operationBusy,
        onDismiss = { settings = false }, onUpdate = { if (!onlineBusy) onUpdate() },
        onCancel = { if (ingest.busy && ingest.cancelable) onCancel() })
}

private data class PendingQueueSwitch(
    val targetTitle: String,
    val track: AudioTrack,
    val queue: List<AudioTrack>,
)

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
