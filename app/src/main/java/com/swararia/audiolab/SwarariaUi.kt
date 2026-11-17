package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.swararia.audiolab.brandskin.BrandSkinStudioUiState
import com.swararia.audiolab.brandskin.BrandSkinUiActions

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
    onInspectTrack: suspend (AudioTrack) -> TrackTechnicalDetails? = { null },
    onSearch: (String, SearchProvider) -> Unit,
    onCancelSearch: () -> Unit,
    upgrade: UpgradeUiActions,
    desktopRemote: DesktopRemoteSession,
    remoteServerState: RemoteServerUiState = RemoteServerUiState(),
    remoteServerActions: RemoteServerUiActions = RemoteServerUiActions(),
    ytMusicState: YoutubeMusicAccountState = YoutubeMusicAccountState(),
    playbackSettings: PlaybackSettingsState = PlaybackSettingsState(),
    onSetPlaybackFadeInMs: (Long) -> Unit = {},
    onSetPlaybackFadeOutMs: (Long) -> Unit = {},
    ytMusicActions: YoutubeMusicAccountActions = YoutubeMusicAccountActions(),
    brandSkinActions: BrandSkinUiActions = BrandSkinUiActions(),
    brandSkinStudioState: BrandSkinStudioUiState = BrandSkinStudioUiState(),
) {
    var activeTab by rememberSaveable { mutableStateOf(MainRootTab.PUSTAKA) }
    var pustakaSubRoute by rememberSaveable { mutableStateOf(PustakaSubRoute.LIBRARY) }
    var downloadsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var hasManualNavigation by rememberSaveable { mutableStateOf(false) }
    var resolvedInitialTab by rememberSaveable { mutableStateOf(false) }
    var moreToolRoute by rememberSaveable { mutableStateOf(MoreToolRoute.HUB) }
    var playerViewMode by rememberSaveable { mutableStateOf(LocalPlayerViewMode.CUE_LIST) }
    var playerDeckCollapsed by rememberSaveable { mutableStateOf(false) }
    var playerKeepScreenOn by rememberSaveable { mutableStateOf(true) }
    var playerGuardEnabled by rememberSaveable { mutableStateOf(true) }
    var activeModal by remember { mutableStateOf<SwarariaModal?>(null) }
    val pages = rememberSaveableStateHolder()
    val playbackClock = remember { PlaybackClockState() }
    val playback = rememberSwarariaPlayback(controller, playbackClock)
    val current = remember(tracks, playback.mediaId) {
        tracks.firstOrNull { it.id.toString() == playback.mediaId }
    }
    val downloadedTrackIds = remember(tracks) { tracks.mapTo(HashSet()) { it.id } }
    val downloadedVideoIds = remember(tracks) {
        tracks.mapNotNullTo(HashSet()) { UiPresentation.sourceVideoId(it.displayName) }
    }
    val previewActive = searchActions.preview.let { it.hit != null || it.loading || it.media != null || it.error != null }
    val queueBusy = upgrade.operationBusy || search.busy || searchActions.preview.loading
    val searchBusyBlocked = ingest.engineBusy || queueBusy
    val onlineBusy = ingest.busy || queueBusy
    val brand = FohTheme.brand
    val view = LocalView.current
    val keepWakeLock = playerKeepScreenOn && (((activeTab == MainRootTab.LIVE_DECK || activeTab == MainRootTab.STAGE_RTA) && !previewActive) || playback.wantsPlay)
    DisposableEffect(view, keepWakeLock) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepWakeLock
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(playback.connected, playback.mediaId, playback.queue.size) {
        if (playback.connected && !resolvedInitialTab) {
            resolvedInitialTab = true
            if (!hasManualNavigation) {
                activeTab = LocalFohRules.initialRootTab(playback.mediaId != null || playback.queue.isNotEmpty())
            }
        }
    }
    val navigateTab: (MainRootTab) -> Unit = { target ->
        hasManualNavigation = true
        if (target != MainRootTab.PUSTAKA || pustakaSubRoute != PustakaSubRoute.ADD_AUDIO) {
            searchActions.stopPreview()
        }
        if (target == MainRootTab.PUSTAKA && activeTab == MainRootTab.PUSTAKA) {
            pustakaSubRoute = PustakaSubRoute.LIBRARY
        }
        if (target == MainRootTab.TOOLS && activeTab == MainRootTab.TOOLS) {
            moreToolRoute = MoreToolRoute.HUB
        }
        if (target != MainRootTab.STAGE_RTA && brand.cleanStageMode) {
            brandSkinActions.onSetCleanStageMode(false)
        }
        downloadsSheetOpen = false
        activeTab = target
    }
    val openBrandStudio: () -> Unit = {
        hasManualNavigation = true
        searchActions.stopPreview()
        downloadsSheetOpen = false
        activeTab = MainRootTab.TOOLS
        moreToolRoute = MoreToolRoute.BRAND_STUDIO
    }
    val executePlayOrCue: (AudioTrack, List<AudioTrack>?, Boolean) -> Unit = { track, queue, cueOnly ->
        searchActions.stopPreview()
        if (cueOnly) {
            onCueQueue(track, queue.orEmpty())
        } else {
            if (queue != null) onPlayQueue(track, queue) else onPlay(track)
            hasManualNavigation = true
            downloadsSheetOpen = false
            pustakaSubRoute = PustakaSubRoute.LIBRARY
            activeTab = MainRootTab.LIVE_DECK
        }
    }
    val dispatchGuardedSwitch: (String, AudioTrack, List<AudioTrack>?, Boolean) -> Unit = { targetTitle, track, queue, cueOnly ->
        if (playback.connected) {
            if (playerGuardEnabled && playback.wantsPlay) {
                activeModal = SwarariaModal.SwitchQueueGuard(
                    targetTitle = targetTitle,
                    track = track,
                    queue = queue.orEmpty(),
                )
            } else {
                executePlayOrCue(track, queue, cueOnly)
            }
        }
    }
    val playQueuedTrack: (AudioTrack, List<AudioTrack>?, Boolean) -> Unit = { track, queue, cueOnly ->
        if (playback.connected) {
            val sameTrack = track.id.toString() == playback.mediaId
            when {
                !cueOnly && sameTrack && !playback.wantsPlay -> {
                    searchActions.stopPreview()
                    toggleSwarariaPlayback(controller)
                    hasManualNavigation = true
                    downloadsSheetOpen = false
                    activeTab = MainRootTab.LIVE_DECK
                }
                !cueOnly && sameTrack -> {
                    hasManualNavigation = true
                    downloadsSheetOpen = false
                    activeTab = MainRootTab.LIVE_DECK
                }
                else -> dispatchGuardedSwitch(UiPresentation.title(track.displayName), track, queue, cueOnly)
            }
        }
    }
    val playTrack: (AudioTrack) -> Unit = { track -> playQueuedTrack(track, null, false) }
    val playDownloadPreview: (AudioTrack) -> Unit = { track ->
        if (playback.connected) {
            val sameTrack = track.id.toString() == playback.mediaId
            if (sameTrack) {
                searchActions.stopPreview()
                toggleSwarariaPlayback(controller)
                downloadsSheetOpen = false
                activeTab = MainRootTab.LIVE_DECK
            } else {
                dispatchGuardedSwitch(UiPresentation.title(track.displayName), track, listOf(track), false)
            }
        }
    }
    val guardedSearchActions = remember(searchActions, playerGuardEnabled, playback.wantsPlay) {
        searchActions.copy(
            previewSong = { hit ->
                if (playerGuardEnabled && playback.wantsPlay) {
                    activeModal = SwarariaModal.GuardPreviewConfirm(hit)
                } else {
                    searchActions.previewSong(hit)
                }
            },
        )
    }
    val stageFullscreen = activeTab == MainRootTab.STAGE_RTA && brand.cleanStageMode && !previewActive
    BackHandler(
        enabled = downloadsSheetOpen ||
            stageFullscreen ||
            (activeTab == MainRootTab.PUSTAKA && pustakaSubRoute != PustakaSubRoute.LIBRARY) ||
            (activeTab == MainRootTab.TOOLS && moreToolRoute != MoreToolRoute.HUB) ||
            activeTab != MainRootTab.PUSTAKA ||
            previewActive,
    ) {
        when {
            downloadsSheetOpen -> downloadsSheetOpen = false
            stageFullscreen -> brandSkinActions.onSetCleanStageMode(false)
            activeTab == MainRootTab.PUSTAKA && pustakaSubRoute != PustakaSubRoute.LIBRARY -> {
                searchActions.stopPreview()
                pustakaSubRoute = PustakaSubRoute.LIBRARY
            }
            activeTab == MainRootTab.TOOLS && moreToolRoute != MoreToolRoute.HUB -> {
                moreToolRoute = MoreToolRoute.HUB
            }
            activeTab != MainRootTab.PUSTAKA -> {
                searchActions.stopPreview()
                activeTab = MainRootTab.PUSTAKA
            }
            else -> searchActions.stopPreview()
        }
    }
    val activeDownloadCount = remember(upgrade.jobs, ingest.busy) {
        val jobActive = upgrade.jobs.count { it.status == "QUEUED" || it.status == "RUNNING" }
        if (jobActive > 0) jobActive else if (ingest.busy && ingest.batchId == null) 1 else 0
    }
    val failedDownloadCount = remember(upgrade.jobs) { upgrade.jobs.count { it.status == "FAILED" } }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!stageFullscreen) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    if (activeTab == MainRootTab.TOOLS && moreToolRoute == MoreToolRoute.REMOTE && previewActive)
                        Text("Pemutar HP • terpisah dari Desktop", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                    // One slot: preview always replaces, never accompanies, local audio.
                    if (previewActive) SearchPreviewPlayer(searchActions.preview, controller,
                        searchActions.stopPreview, searchActions.previewError,
                        onRetry = { if (!searchBusyBlocked) searchActions.preview.hit?.let(searchActions.previewSong) })
                    else if (playback.mediaId != null) {
                        val onRemoteTool = activeTab == MainRootTab.TOOLS && moreToolRoute == MoreToolRoute.REMOTE
                        if (activeTab != MainRootTab.LIVE_DECK && activeTab != MainRootTab.STAGE_RTA && !onRemoteTool) {
                            MiniPlayer(
                                current = current,
                                playback = playback,
                                controller = controller,
                                onOpen = {
                                    hasManualNavigation = true
                                    activeTab = MainRootTab.LIVE_DECK
                                },
                                positionMsProvider = { playbackClock.positionMs },
                            )
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
                        NavigationBarItem(
                            selected = activeTab == MainRootTab.PUSTAKA,
                            onClick = { navigateTab(MainRootTab.PUSTAKA) },
                            icon = { Icon(UiIcons.Library, null) },
                            label = { Text("Pustaka") },
                            colors = navigationColors(),
                        )
                        NavigationBarItem(
                            selected = activeTab == MainRootTab.LIVE_DECK,
                            onClick = { navigateTab(MainRootTab.LIVE_DECK) },
                            icon = { Icon(UiIcons.Play, null) },
                            label = { Text("Live Deck") },
                            colors = navigationColors(),
                        )
                        NavigationBarItem(
                            selected = activeTab == MainRootTab.STAGE_RTA,
                            onClick = { navigateTab(MainRootTab.STAGE_RTA) },
                            icon = { Icon(UiIcons.Rta, null) },
                            label = { Text("Stage RTA") },
                            colors = navigationColors(),
                        )
                        NavigationBarItem(
                            selected = activeTab == MainRootTab.TOOLS,
                            onClick = { navigateTab(MainRootTab.TOOLS) },
                            icon = { Icon(UiIcons.Palette, null) },
                            label = { Text("Alat & Skin") },
                            colors = navigationColors(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        pages.SaveableStateProvider(activeTab.name) {
            when (activeTab) {
                MainRootTab.PUSTAKA -> {
                    if (pustakaSubRoute == PustakaSubRoute.ADD_AUDIO) {
                        AddAudioScreen(
                            ingest = ingest,
                            search = search,
                            controller = controller,
                            actions = guardedSearchActions,
                            modifier = contentModifier,
                            upgrade = upgrade,
                            downloadedTrackIds = downloadedTrackIds,
                            downloadedVideoIds = downloadedVideoIds,
                            tracks = tracks,
                            activeDownloadCount = activeDownloadCount,
                            failedDownloadCount = failedDownloadCount,
                            onBack = {
                                searchActions.stopPreview()
                                pustakaSubRoute = PustakaSubRoute.LIBRARY
                            },
                            onOpenDownloads = { downloadsSheetOpen = true },
                            onDownload = { url, authorized ->
                                if (!queueBusy && authorized) {
                                    onDownload(url, true)
                                }
                            },
                            onDownloadBatch = { hits, authorized ->
                                if (!queueBusy && authorized && hits.isNotEmpty()) {
                                    upgrade.onDownloadBatch(hits, true)
                                    if (hits.size > 1) downloadsSheetOpen = true
                                }
                            },
                            onImport = { if (!onlineBusy) onImport() },
                            onCancel = { if (ingest.busy && ingest.cancelable) onCancel() },
                            onCancelSearch = onCancelSearch,
                            onSettings = { activeModal = SwarariaModal.EngineSettings },
                        )
                    } else {
                        LibraryPage(
                            tracks = tracks,
                            playlists = playlists,
                            currentId = playback.mediaId,
                            wantsPlay = playback.wantsPlay,
                            connected = playback.connected,
                            ingest = ingest,
                            search = search,
                            organizerBusy = organizerBusy || upgrade.operationBusy,
                            modifier = contentModifier,
                            onAdd = { pustakaSubRoute = PustakaSubRoute.ADD_AUDIO },
                            onPlay = playTrack,
                            onPlayQueue = { track, queue -> playQueuedTrack(track, queue, false) },
                            onCueQueue = { track, queue -> playQueuedTrack(track, queue, true) },
                            onGuardPlayOrCue = { showTitle, track, queue, cueOnly ->
                                dispatchGuardedSwitch(showTitle, track, queue, cueOnly)
                            },
                            onToggleCurrentPlayPause = { toggleSwarariaPlayback(controller) },
                            onOpenPlayer = { navigateTab(MainRootTab.LIVE_DECK) },
                            onOpenStageRta = { navigateTab(MainRootTab.STAGE_RTA) },
                            onOpenBrandStudio = openBrandStudio,
                            activeDownloadCount = activeDownloadCount,
                            failedDownloadCount = failedDownloadCount,
                            onOpenDownloads = { downloadsSheetOpen = true },
                            playlistActions = playlistActions,
                            onInspectDetails = { activeModal = SwarariaModal.TrackDetails(it) },
                            onRename = { activeModal = SwarariaModal.RenameTrack(it) },
                            onHistory = { activeModal = SwarariaModal.RenameHistory },
                            onSettings = { activeModal = SwarariaModal.EngineSettings },
                            onDelete = { activeModal = SwarariaModal.DeleteTrack(it) },
                            onDeleteBatch = { selected -> activeModal = SwarariaModal.DeleteBatch(selected) },
                            onImport = { if (!onlineBusy) onImport() },
                            onDismissBrandWelcome = brandSkinActions.onDismissWelcomeBanner,
                        )
                    }
                }
                MainRootTab.LIVE_DECK -> FocusedPlayer(
                    tracks = tracks,
                    current = current,
                    playback = playback,
                    controller = controller,
                    modifier = contentModifier,
                    onBack = { navigateTab(MainRootTab.PUSTAKA) },
                    onRename = { activeModal = SwarariaModal.RenameTrack(it) },
                    onSettings = { activeModal = SwarariaModal.EngineSettings },
                    onOpenStageRta = { navigateTab(MainRootTab.STAGE_RTA) },
                    viewMode = playerViewMode,
                    onSelectViewMode = { playerViewMode = it },
                    deckCollapsed = playerDeckCollapsed,
                    onToggleDeckCollapsed = { playerDeckCollapsed = !playerDeckCollapsed },
                    keepScreenOn = playerKeepScreenOn,
                    onToggleKeepScreenOn = { playerKeepScreenOn = !playerKeepScreenOn },
                    guardEnabled = playerGuardEnabled,
                    onToggleGuardEnabled = { playerGuardEnabled = !playerGuardEnabled },
                    positionMsProvider = { playbackClock.positionMs },
                )
                MainRootTab.STAGE_RTA -> StageRtaScreen(
                    current = current,
                    playback = playback,
                    controller = controller,
                    onClose = { navigateTab(MainRootTab.LIVE_DECK) },
                    onOpenBrandStudio = openBrandStudio,
                    brandSkinActions = brandSkinActions,
                    modifier = contentModifier,
                    showCloseButton = false,
                )
                MainRootTab.TOOLS -> MoreScreen(
                    tracks = tracks,
                    playlists = playlists,
                    lastRename = lastRename,
                    ytMusicState = ytMusicState,
                    playbackSettings = playbackSettings,
                    desktopRemote = desktopRemote,
                    remoteServerState = remoteServerState,
                    remoteServerActions = remoteServerActions,
                    jobs = upgrade.jobs,
                    activeTool = moreToolRoute,
                    onSelectTool = { moreToolRoute = it },
                    modifier = contentModifier,
                    onOpenSettings = { activeModal = SwarariaModal.EngineSettings },
                    onOpenRenameHistory = { activeModal = SwarariaModal.RenameHistory },
                    onOpenStageRta = { navigateTab(MainRootTab.STAGE_RTA) },
                    brandSkinActions = brandSkinActions,
                    brandSkinStudioState = brandSkinStudioState,
                    localAudioBanner = {
                        if (!previewActive && playback.mediaId != null) {
                            RemoteLocalAudioPill(
                                current = current,
                                playback = playback,
                                controller = controller,
                                onOpen = { navigateTab(MainRootTab.LIVE_DECK) },
                                positionMsProvider = { playbackClock.positionMs },
                            )
                        }
                    },
                    onPauseOtherAudio = {
                        searchActions.stopPreview()
                        controller?.takeIf { it.isConnected && (it.isPlaying || it.playWhenReady) }?.let {
                            pauseSwarariaPlaybackImmediately(it)
                        }
                    },
                )
            }
        }
    }
    if (downloadsSheetOpen) {
        DownloadsQueueSheet(
            upgrade = upgrade,
            ingest = ingest,
            tracks = tracks,
            playlists = playlists,
            playlistActions = playlistActions,
            ytMusic = ytMusicState,
            connected = playback.connected,
            currentPlayingTrackId = playback.mediaId,
            isPlayingActive = playback.wantsPlay,
            onSettings = { activeModal = SwarariaModal.EngineSettings },
            onCancelIngest = { if (ingest.busy && ingest.cancelable) onCancel() },
            onAddAudio = {
                downloadsSheetOpen = false
                activeTab = MainRootTab.PUSTAKA
                pustakaSubRoute = PustakaSubRoute.ADD_AUDIO
            },
            onPlayTrack = playDownloadPreview,
            onDismiss = { downloadsSheetOpen = false },
        )
    }
    when (val modal = activeModal) {
        is SwarariaModal.RenameTrack -> key(modal.track.id, modal.track.displayName) {
            RenameAudioDialog(modal.track, organizerBusy || upgrade.operationBusy, { activeModal = null }, onRename)
        }
        is SwarariaModal.DeleteTrack -> key(modal.track.id) {
            DeleteAudioDialog(modal.track, organizerBusy || upgrade.operationBusy, { activeModal = null }, upgrade.onDelete)
        }
        is SwarariaModal.DeleteBatch -> DeleteBatchAudioDialog(
            tracks = modal.tracks,
            busy = organizerBusy || upgrade.operationBusy,
            onDismiss = { activeModal = null },
            onDeleteBatch = upgrade.onDeleteBatch,
        )
        is SwarariaModal.TrackDetails -> key(modal.track.id, modal.track.displayName) {
            val playlistNames = remember(playlists, modal.track.id) {
                playlists.filter { modal.track.id in it.trackIds }.map { it.name }
            }
            TrackDetailDialog(
                track = modal.track,
                playlistNames = playlistNames,
                connected = playback.connected,
                renameEnabled = !(organizerBusy || upgrade.operationBusy),
                onInspect = onInspectTrack,
                onPlay = {
                    activeModal = null
                    playQueuedTrack(modal.track, tracks, false)
                },
                onCue = {
                    activeModal = null
                    playQueuedTrack(modal.track, tracks, true)
                },
                onRename = { activeModal = SwarariaModal.RenameTrack(modal.track) },
                onDismiss = { activeModal = null },
            )
        }
        is SwarariaModal.SwitchQueueGuard -> {
            val activeTitle = current?.let { UiPresentation.title(it.displayName) }
                ?: playback.title?.takeIf { it.isNotBlank() }?.let(UiPresentation::title)
                ?: "Audio aktif"
            LocalTrackSwitchGuardDialog(
                targetTitle = modal.targetTitle,
                activeTitle = activeTitle,
                cueButtonLabel = "Load Cue (00:00 · Standby)",
                detailText = "\"$activeTitle\" sedang live. Memuat \"${modal.targetTitle}\" akan mengganti antrean aktif:",
                onCueOnly = {
                    activeModal = null
                    executePlayOrCue(modal.track, modal.queue.takeIf { it.isNotEmpty() }, true)
                },
                onCutAndPlay = {
                    activeModal = null
                    executePlayOrCue(modal.track, modal.queue.takeIf { it.isNotEmpty() }, false)
                },
                onDismiss = { activeModal = null },
            )
        }
        SwarariaModal.RenameHistory -> RenameHistoryDialog(lastRename, organizerBusy, { activeModal = null }, onUndo)
        SwarariaModal.EngineSettings -> EngineSettingsDialog(
            ingest = ingest,
            searchBusy = search.busy || searchActions.preview.loading || upgrade.operationBusy,
            ytMusic = ytMusicState,
            playbackSettings = playbackSettings,
            onSetPlaybackFadeInMs = onSetPlaybackFadeInMs,
            onSetPlaybackFadeOutMs = onSetPlaybackFadeOutMs,
            ytMusicActions = ytMusicActions.copy(
                onOpenSignIn = { activeModal = SwarariaModal.YoutubeMusicSignIn },
                onOpenImportCookies = { activeModal = SwarariaModal.YoutubeMusicImportCookies },
            ),
            onDismiss = { activeModal = null },
            onUpdate = { if (!onlineBusy) onUpdate() },
            onCancel = { if (ingest.busy && ingest.cancelable) onCancel() },
        )
        SwarariaModal.YoutubeMusicSignIn -> YoutubeMusicSignInDialog(
            onDismiss = { activeModal = SwarariaModal.EngineSettings },
            onSwitchToImportCookies = { activeModal = SwarariaModal.YoutubeMusicImportCookies },
            onCompleteSignIn = { wwwCookies, musicCookies ->
                ytMusicActions.onCompleteWebViewSignIn(wwwCookies, musicCookies)
                activeModal = SwarariaModal.EngineSettings
            },
        )
        SwarariaModal.YoutubeMusicImportCookies -> ImportYoutubeMusicCookiesDialog(
            onDismiss = { activeModal = SwarariaModal.EngineSettings },
            onImportCookies = { raw ->
                ytMusicActions.onImportCookiesText(raw)
                activeModal = SwarariaModal.EngineSettings
            },
        )
        is SwarariaModal.GuardPreviewConfirm -> {
            val activeTitle = current?.let { UiPresentation.title(it.displayName) }
                ?: playback.title?.takeIf { it.isNotBlank() }?.let(UiPresentation::title)
                ?: "Audio aktif"
            AlertDialog(
                onDismissRequest = { activeModal = null },
                icon = { Icon(UiIcons.Shield, contentDescription = null, tint = FohTheme.colors.warnAccent) },
                title = { Text("Jeda Lagu Acara untuk Preview?") },
                text = {
                    Text(
                        text = "Preview \"${modal.hit.title}\" akan menghentikan pemutaran lagu \"$activeTitle\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetHit = modal.hit
                            activeModal = null
                            searchActions.previewSong(targetHit)
                        },
                    ) {
                        Text("Ya, Jeda & Preview")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeModal = null }) {
                        Text("Batal")
                    }
                },
            )
        }
        null -> Unit
    }
}

internal enum class PustakaSubRoute {
    LIBRARY,
    ADD_AUDIO,
}

private sealed interface SwarariaModal {
    data class RenameTrack(val track: AudioTrack) : SwarariaModal
    data class DeleteTrack(val track: AudioTrack) : SwarariaModal
    data class TrackDetails(val track: AudioTrack) : SwarariaModal
    data class DeleteBatch(val tracks: List<AudioTrack>) : SwarariaModal
    data class SwitchQueueGuard(
        val targetTitle: String,
        val track: AudioTrack,
        val queue: List<AudioTrack>,
    ) : SwarariaModal
    data class GuardPreviewConfirm(val hit: SearchHit) : SwarariaModal
    data object RenameHistory : SwarariaModal
    data object EngineSettings : SwarariaModal
    data object YoutubeMusicSignIn : SwarariaModal
    data object YoutubeMusicImportCookies : SwarariaModal
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
