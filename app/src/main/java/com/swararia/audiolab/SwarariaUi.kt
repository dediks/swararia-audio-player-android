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
    onRename: (Long, String, () -> Unit) -> Unit,
    onUndo: () -> Unit,
    onSearch: (String, SearchProvider) -> Unit,
    onCancelSearch: () -> Unit,
    upgrade: UpgradeUiActions,
) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AudioTrack?>(null) }
    var deleting by remember { mutableStateOf<AudioTrack?>(null) }
    val pages = rememberSaveableStateHolder()
    val playback = rememberSwarariaPlayback(controller)
    val current = tracks.firstOrNull { it.id.toString() == playback.mediaId }
    val previewActive = searchActions.preview.let { it.loading || it.media != null || it.error != null }
    val onlineBusy = ingest.busy || upgrade.operationBusy || search.busy || searchActions.preview.loading
    val navigate: (Int) -> Unit = { target ->
        if (target != destination || fullPlayer) searchActions.stopPreview()
        fullPlayer = false
        destination = target
    }
    val playTrack: (AudioTrack) -> Unit = { track ->
        if (playback.connected) {
            searchActions.stopPreview()
            if (track.id.toString() == playback.mediaId) toggleSwarariaPlayback(controller)
            else onPlay(track)
        }
    }
    LaunchedEffect(previewActive) { if (previewActive) fullPlayer = false }
    BackHandler(enabled = fullPlayer || destination != 0 || previewActive) {
        searchActions.stopPreview()
        if (fullPlayer) fullPlayer = false else destination = 0
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                // One slot: preview always replaces, never accompanies, local audio.
                if (previewActive) SearchPreviewPlayer(searchActions.preview, controller,
                    searchActions.stopPreview, searchActions.previewError,
                    onRetry = { if (!onlineBusy) searchActions.preview.hit?.let(searchActions.previewSong) })
                else if (playback.mediaId != null) {
                    if (!fullPlayer) MiniPlayer(current, playback, controller, onOpen = { fullPlayer = true })
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
                    NavigationBarItem(selected = destination == 0, onClick = { navigate(0) },
                        icon = { Icon(UiIcons.Library, null) }, label = { Text("Library") }, colors = navigationColors())
                    NavigationBarItem(selected = destination == 1, onClick = { navigate(1) },
                        icon = { Icon(UiIcons.Search, null) }, label = { Text("Search") }, colors = navigationColors())
                    NavigationBarItem(selected = destination == 2, onClick = { navigate(2) },
                        icon = { Icon(UiIcons.Download, null) }, label = { Text("Downloads") }, colors = navigationColors())
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
                0 -> LibraryPage(tracks = tracks, currentId = playback.mediaId, connected = playback.connected,
                    ingest = ingest, search = search, organizerBusy = organizerBusy || upgrade.operationBusy,
                    modifier = contentModifier, onAdd = { navigate(1) }, onPlay = playTrack,
                    onRename = { editing = it }, onHistory = { history = true }, onSettings = { settings = true },
                    onDelete = { deleting = it }, onImport = { if (!onlineBusy) onImport() })
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
    if (history) RenameHistoryDialog(lastRename, organizerBusy, { history = false }, onUndo)
    if (settings) EngineSettingsDialog(ingest, search.busy || searchActions.preview.loading || upgrade.operationBusy,
        onDismiss = { settings = false }, onUpdate = { if (!onlineBusy) onUpdate() },
        onCancel = { if (ingest.busy && ingest.cancelable) onCancel() })
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
