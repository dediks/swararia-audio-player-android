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
) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AudioTrack?>(null) }
    val pages = rememberSaveableStateHolder()
    val playback = rememberSwarariaPlayback(controller)
    val current = tracks.firstOrNull { it.id.toString() == playback.mediaId }
    val playTrack: (AudioTrack) -> Unit = { track ->
        if (playback.connected) {
            if (track.id.toString() == playback.mediaId) toggleSwarariaPlayback(controller)
            else onPlay(track)
        }
    }

    BackHandler(enabled = fullPlayer || destination != 0) {
        if (fullPlayer) fullPlayer = false else destination = 0
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!fullPlayer) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (playback.mediaId != null) MiniPlayer(
                    current, playback, controller, onOpen = { fullPlayer = true }
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ) {
                    NavigationBarItem(
                        selected = destination == 0,
                        onClick = { destination = 0 },
                        icon = { Icon(UiIcons.Library, contentDescription = null) },
                        label = { Text("Pustaka") },
                        colors = navigationColors(),
                    )
                    NavigationBarItem(
                        selected = destination == 1,
                        onClick = { destination = 1 },
                        icon = { Icon(UiIcons.Plus, contentDescription = null) },
                        label = { Text("Tambah audio") },
                        colors = navigationColors(),
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (fullPlayer) FocusedPlayer(
            tracks = tracks, current = current, playback = playback, controller = controller,
            modifier = contentModifier, onBack = { fullPlayer = false },
            onRename = { editing = it }, onSettings = { settings = true },
        ) else pages.SaveableStateProvider(destination) {
            if (destination == 0) LibraryPage(
                tracks = tracks, currentId = playback.mediaId, connected = playback.connected,
                ingest = ingest, search = search, organizerBusy = organizerBusy,
                modifier = contentModifier, onAdd = { destination = 1 }, onPlay = playTrack,
                onRename = { editing = it }, onHistory = { history = true },
                onSettings = { settings = true },
            ) else AddAudioScreen(
                ingest = ingest, search = search, controller = controller, actions = searchActions, modifier = contentModifier,
                onDownload = { url, authorized -> if (!ingest.busy && !search.busy && !searchActions.preview.loading && authorized) onDownload(url, true) },
                onImport = { if (!ingest.busy && !search.busy && !searchActions.preview.loading) onImport() },
                onCancel = { if (ingest.busy && ingest.cancelable) onCancel() },
                onCancelSearch = onCancelSearch, onSettings = { settings = true },
            )
        }
    }
    editing?.let { track ->
        key(track.id, track.displayName) {
            RenameAudioDialog(track, organizerBusy, onDismiss = { editing = null }, onRename = onRename)
        }
    }
    if (history) RenameHistoryDialog(lastRename, organizerBusy, onDismiss = { history = false }, onUndo = onUndo)
    if (settings) EngineSettingsDialog(ingest, search.busy || searchActions.preview.loading, onDismiss = { settings = false },
        onUpdate = { if (!ingest.busy && !search.busy && !searchActions.preview.loading) onUpdate() },
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
