package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import com.swararia.audiolab.brandskin.BrandSkinRules
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

internal data class ReorderRowControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
internal fun LibraryPage(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist> = emptyList(),
    currentId: String?,
    wantsPlay: Boolean = false,
    connected: Boolean,
    ingest: IngestState,
    search: YoutubeSearchState,
    organizerBusy: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onPlayQueue: (AudioTrack, List<AudioTrack>) -> Unit = { track, _ -> onPlay(track) },
    onCueQueue: (AudioTrack, List<AudioTrack>) -> Unit = { _, _ -> },
    onGuardPlayOrCue: (String, AudioTrack, List<AudioTrack>, Boolean) -> Unit = { _, track, queue, cueOnly ->
        if (cueOnly) onCueQueue(track, queue) else onPlayQueue(track, queue)
    },
    onToggleCurrentPlayPause: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onOpenStageRta: () -> Unit = {},
    onOpenBrandStudio: () -> Unit = {},
    activeDownloadCount: Int = 0,
    failedDownloadCount: Int = 0,
    onOpenDownloads: () -> Unit = {},
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    onInspectDetails: (AudioTrack) -> Unit = {},
    onRename: (AudioTrack) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (AudioTrack) -> Unit,
    onDeleteBatch: (List<AudioTrack>) -> Unit = {},
    onImport: () -> Unit,
    onDismissBrandWelcome: () -> Unit = {},
) {
    val state = rememberLibraryBrowserState(tracks = tracks, playlists = playlists)
    val section = state.section
    val groupMode = state.groupMode
    val viewType = state.viewType
    val activePlaylist = state.activePlaylist

    BackHandler(enabled = state.selectionMode && section == LibrarySectionTab.ALL_TRACKS) {
        state.exitSelectionMode()
    }
    BackHandler(enabled = section == LibrarySectionTab.PLAYLISTS && state.selectedPlaylistId != null && !state.selectionMode) {
        state.closePlaylist()
    }

    val trackById = state.trackById
    val badgeByTrackId = state.badgeByTrackId
    val sortedMatching = state.sortedMatching
    val activePlaylistTracks = state.activePlaylistTracks
    val visiblePlaylists = state.visiblePlaylists
    val groupedTracks = state.groupedTracks
    val activeTrackId = remember(currentId) { currentId?.toLongOrNull() }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val gridColumns = remember(screenWidthDp) { UiPresentation.songGridColumnCount(screenWidthDp) }
    val brand = FohTheme.brand
    val rowVerticalSpacing = if (viewType == LibrarySongViewType.COMPACT && !state.reorderMode) 5.dp else 8.dp

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(rowVerticalSpacing),
        ) {
            if (!(section == LibrarySectionTab.PLAYLISTS && activePlaylist != null)) {
                item(key = "library-header", contentType = "library-header") {
                    LibraryCompactHeader(
                        section = section,
                        trackCount = tracks.size,
                        playlistCount = playlists.size,
                        selectionMode = state.selectionMode,
                        query = state.query,
                        groupMode = groupMode,
                        viewType = viewType,
                        alphabetical = state.alphabetical,
                        importEnabled = !ingest.busy && !search.busy && !organizerBusy,
                        actionBusy = organizerBusy,
                        onSelectSection = state::selectSection,
                        onImport = onImport,
                        onToggleSelectionMode = state::toggleSelectionMode,
                        onCreatePlaylist = { state.dialog = LibraryDialog.CreatePlaylist },
                        onQueryChange = { state.query = it },
                        onSelectGroupMode = state::selectGroupMode,
                        onSelectViewType = state::selectViewType,
                        onToggleSort = { state.alphabetical = it },
                        onHistory = onHistory,
                        onSettings = onSettings,
                        onAdd = onAdd,
                        activeDownloadCount = activeDownloadCount,
                        failedDownloadCount = failedDownloadCount,
                        onOpenDownloads = onOpenDownloads,
                        onOpenStageRta = onOpenStageRta,
                        onOpenBrandStudio = onOpenBrandStudio,
                        welcomeBanner = if (BrandSkinRules.shouldShowWelcomeBrandCard(brand)) {
                            {
                                WelcomeBrandBanner(
                                    brand = brand,
                                    onSetup = onOpenBrandStudio,
                                    onDismiss = onDismissBrandWelcome,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            if (ingest.busy || search.busy) item(key = "active-operation", contentType = "banner") {
                Surface(onClick = onAdd, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicatorCompact()
                        Text(
                            if (ingest.busy) ingest.stage else "Mencari di ${search.provider.label}…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(UiIcons.ChevronRight, "Lihat proses", Modifier.size(20.dp))
                    }
                }
            }
            if (!connected && tracks.isNotEmpty()) item(key = "player-disconnected", contentType = "banner") {
                Text(
                    "Pemutar belum terhubung. Library & rundown tetap dapat diatur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (organizerBusy) {
                item(key = "rename-progress", contentType = "progress") {
                    FohIndeterminateBar(
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                    )
                }
            }

            when (section) {
                LibrarySectionTab.ALL_TRACKS -> {
                    if (groupMode == LibraryGroupMode.ALL) {
                        if (sortedMatching.isEmpty()) {
                            item(key = "library-empty", contentType = "empty") {
                                LibraryEmptyPlaceholder(
                                    hasTracks = tracks.isNotEmpty(),
                                    onAdd = onAdd,
                                    onClearQuery = { state.query = "" },
                                )
                            }
                        } else {
                            libraryTracksItems(
                                tracks = sortedMatching,
                                viewType = viewType,
                                gridColumns = gridColumns,
                                keyPrefix = "all",
                                currentId = currentId,
                                wantsPlay = wantsPlay,
                                connected = connected,
                                organizerBusy = organizerBusy,
                                inSelectionMode = state.selectionMode,
                                selectedIds = state.selectedIds,
                                badgeByTrackId = badgeByTrackId,
                                onToggleSelect = state::toggleTrackSelection,
                                onLongPress = state::startSelectionWith,
                                onPlay = { track -> onPlayQueue(track, sortedMatching) },
                                onCue = { track -> onCueQueue(track, sortedMatching) },
                                onTogglePlayPause = onToggleCurrentPlayPause,
                                onOpenPlayer = onOpenPlayer,
                                onInspectDetails = onInspectDetails,
                                onRename = onRename,
                                onAddToPlaylist = { track -> state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                onDelete = onDelete,
                            )
                        }
                    } else {
                        if (groupedTracks.isEmpty()) {
                            item(key = "grouped-empty", contentType = "empty") {
                                LibraryEmptyPlaceholder(
                                    hasTracks = tracks.isNotEmpty(),
                                    onAdd = onAdd,
                                    onClearQuery = { state.query = "" },
                                )
                            }
                        } else {
                            groupedTracks.forEach { group ->
                                val collapsed = group.key in state.collapsedGroups
                                item(key = "grp-header:${groupMode.name}:${group.key}", contentType = "group-header") {
                                    LibraryGroupHeader(
                                        group = group,
                                        collapsed = collapsed,
                                        connected = connected,
                                        onToggle = { state.toggleGroupCollapsed(group.key) },
                                        onPlayAll = {
                                            if (group.items.isNotEmpty()) onPlayQueue(group.items.first(), group.items)
                                        },
                                    )
                                }
                                if (!collapsed) {
                                    libraryTracksItems(
                                        tracks = group.items,
                                        viewType = viewType,
                                        gridColumns = gridColumns,
                                        keyPrefix = "grp:${groupMode.name}:${group.key}",
                                        currentId = currentId,
                                        wantsPlay = wantsPlay,
                                        connected = connected,
                                        organizerBusy = organizerBusy,
                                        inSelectionMode = state.selectionMode,
                                        selectedIds = state.selectedIds,
                                        badgeByTrackId = badgeByTrackId,
                                        onToggleSelect = state::toggleTrackSelection,
                                        onLongPress = state::startSelectionWith,
                                        onPlay = { track -> onPlayQueue(track, group.items) },
                                        onCue = { track -> onCueQueue(track, group.items) },
                                        onTogglePlayPause = onToggleCurrentPlayPause,
                                        onOpenPlayer = onOpenPlayer,
                                        onInspectDetails = onInspectDetails,
                                        onRename = onRename,
                                        onAddToPlaylist = { track -> state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                        onDelete = onDelete,
                                    )
                                }
                            }
                        }
                    }
                }

                LibrarySectionTab.PLAYLISTS -> {
                    if (activePlaylist != null) {
                        item(key = "playlist-detail-header", contentType = "playlist-detail-header") {
                            PlaylistRundownHeader(
                                playlist = activePlaylist,
                                tracks = activePlaylistTracks,
                                connected = connected,
                                busy = organizerBusy,
                                reorderMode = state.reorderMode,
                                viewType = viewType,
                                onBack = state::closePlaylist,
                                onLoadCue = {
                                    if (activePlaylistTracks.isNotEmpty()) {
                                        onGuardPlayOrCue(activePlaylist.name, activePlaylistTracks.first(), activePlaylistTracks, true)
                                    }
                                },
                                onPlayShow = {
                                    if (activePlaylistTracks.isNotEmpty()) {
                                        onGuardPlayOrCue(activePlaylist.name, activePlaylistTracks.first(), activePlaylistTracks, false)
                                    }
                                },
                                onAddTracks = { state.dialog = LibraryDialog.AddTracksToPlaylist(activePlaylist.id) },
                                onToggleReorder = state::toggleReorderMode,
                                onSelectViewType = state::selectViewType,
                                onRename = { state.dialog = LibraryDialog.RenamePlaylist(activePlaylist) },
                                onDelete = { state.dialog = LibraryDialog.DeletePlaylist(activePlaylist) },
                            )
                        }
                        if (activePlaylistTracks.isEmpty()) {
                            item(key = "playlist-empty", contentType = "empty") {
                                EmptyMessage(
                                    "Playlist masih kosong",
                                    "Tambahkan track dari library untuk menyusun rundown show ini.",
                                    UiIcons.Queue,
                                ) {
                                    Button(
                                        onClick = { state.dialog = LibraryDialog.AddTracksToPlaylist(activePlaylist.id) },
                                        enabled = tracks.isNotEmpty() && !organizerBusy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Add Tracks") }
                                }
                            }
                        } else if (state.reorderMode) {
                            itemsIndexed(
                                activePlaylistTracks,
                                key = { _, track -> "pl:${activePlaylist.id}:reorder:${track.id}" },
                                contentType = { _, _ -> "track-row" },
                            ) { index, track ->
                                val moveTrack: (Int) -> Unit = { delta ->
                                    val baseIds = activePlaylistTracks.map { it.id }
                                    val moved = LibraryRules.movePlaylistItem(baseIds, index, delta)
                                    if (moved != baseIds) {
                                        state.markTrackMoved(track.id)
                                        playlistActions.onReorderPlaylistTracks(activePlaylist.id, moved)
                                    }
                                }
                                val justMoved = state.lastMovedTrackId == track.id
                                LibraryAudioRow(
                                    track = track,
                                    selected = track.id.toString() == currentId,
                                    wantsPlay = wantsPlay,
                                    connected = connected,
                                    renameEnabled = !organizerBusy,
                                    leadingIndex = index + 1,
                                    highlighted = justMoved,
                                    reorderBadge = if (justMoved) String.format(Locale.ROOT, "Dipindah ke #%02d", index + 1) else null,
                                    reorderControls = ReorderRowControls(
                                        canMoveUp = index > 0 && !organizerBusy,
                                        canMoveDown = index < activePlaylistTracks.lastIndex && !organizerBusy,
                                        onMoveUp = { moveTrack(-1) },
                                        onMoveDown = { moveTrack(1) },
                                        onRemove = { playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id) },
                                    ),
                                    onPlay = { onPlayQueue(track, activePlaylistTracks) },
                                    onCue = { onCueQueue(track, activePlaylistTracks) },
                                    onTogglePlayPause = onToggleCurrentPlayPause,
                                    onOpenPlayer = onOpenPlayer,
                                    onInspectDetails = { onInspectDetails(track) },
                                    onRename = { onRename(track) },
                                    onAddToPlaylist = { state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                    onRemoveFromPlaylist = {
                                        playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id)
                                    },
                                    onDelete = { onDelete(track) },
                                )
                            }
                            if (activePlaylistTracks.size > 1) {
                                item(key = "rundown-sync-notice", contentType = "banner") {
                                    RundownSyncNoticeBanner()
                                }
                            }
                        } else {
                            libraryTracksItems(
                                tracks = activePlaylistTracks,
                                viewType = viewType,
                                gridColumns = gridColumns,
                                keyPrefix = "pl:${activePlaylist.id}",
                                currentId = currentId,
                                wantsPlay = wantsPlay,
                                connected = connected,
                                organizerBusy = organizerBusy,
                                inSelectionMode = false,
                                selectedIds = emptySet(),
                                badgeByTrackId = emptyMap(),
                                showLeadingIndex = true,
                                onToggleSelect = {},
                                onLongPress = {},
                                onPlay = { track -> onPlayQueue(track, activePlaylistTracks) },
                                onCue = { track -> onCueQueue(track, activePlaylistTracks) },
                                onTogglePlayPause = onToggleCurrentPlayPause,
                                onOpenPlayer = onOpenPlayer,
                                onInspectDetails = onInspectDetails,
                                onRename = onRename,
                                onAddToPlaylist = { track -> state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                onRemoveFromPlaylist = { track ->
                                    playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id)
                                },
                                onDelete = onDelete,
                            )
                            if (activePlaylistTracks.size > 1) {
                                item(key = "rundown-sync-notice", contentType = "banner") {
                                    RundownSyncNoticeBanner()
                                }
                            }
                        }
                    } else {
                        if (visiblePlaylists.isEmpty()) {
                            item(key = "playlists-empty", contentType = "empty") {
                                if (playlists.isEmpty()) {
                                    EmptyMessage(
                                        "Belum ada playlist",
                                        "Buat playlist show untuk mengatur rundown event dan Load Cue cepat.",
                                        UiIcons.Queue,
                                    ) {
                                        Button(
                                            onClick = { state.dialog = LibraryDialog.CreatePlaylist },
                                            enabled = !organizerBusy,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("New Playlist") }
                                    }
                                } else {
                                    EmptyMessage("Playlist tidak ditemukan", "Coba kata lain atau hapus pencarian.", UiIcons.Search) {
                                        TextButton(onClick = { state.query = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text("Hapus pencarian")
                                        }
                                    }
                                }
                            }
                        } else {
                            items(
                                visiblePlaylists,
                                key = { "playlist-card:${it.id}" },
                                contentType = { "playlist-card" },
                            ) { playlist ->
                                val plTracks = remember(playlist, trackById) { playlist.trackIds.mapNotNull(trackById::get) }
                                val plDuration = remember(plTracks) { plTracks.totalDurationMs() }
                                val activeShow = remember(activeTrackId, playlist.trackIds) {
                                    activeTrackId != null && activeTrackId in playlist.trackIds
                                }
                                PlaylistShowCard(
                                    playlist = playlist,
                                    tracks = plTracks,
                                    trackById = trackById,
                                    totalDurationMs = plDuration,
                                    isActiveShow = activeShow,
                                    connected = connected,
                                    busy = organizerBusy,
                                    onOpen = { state.openPlaylist(playlist.id) },
                                    onLoadCue = {
                                        if (plTracks.isNotEmpty()) {
                                            onGuardPlayOrCue(playlist.name, plTracks.first(), plTracks, true)
                                        }
                                    },
                                    onPlayShow = {
                                        if (plTracks.isNotEmpty()) {
                                            onGuardPlayOrCue(playlist.name, plTracks.first(), plTracks, false)
                                        }
                                    },
                                    onAddTracks = { state.dialog = LibraryDialog.AddTracksToPlaylist(playlist.id) },
                                    onRename = { state.dialog = LibraryDialog.RenamePlaylist(playlist) },
                                    onDelete = { state.dialog = LibraryDialog.DeletePlaylist(playlist) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.selectionMode && section == LibrarySectionTab.ALL_TRACKS) {
            val visibleIds = remember(sortedMatching) { sortedMatching.map { it.id } }
            LibraryBatchActionBar(
                selectedCount = state.selectedIds.size,
                totalVisibleCount = visibleIds.size,
                busy = organizerBusy,
                onToggleSelectAll = { state.toggleSelectAllVisible(visibleIds) },
                onAddToPlaylist = { state.dialog = LibraryDialog.BatchAddToPlaylist },
                onDeleteSelected = {
                    val batch = tracks.filter { it.id in state.selectedIds }
                    if (batch.isNotEmpty()) onDeleteBatch(batch)
                },
                onCancel = state::exitSelectionMode,
            )
        }
    }

    LibraryDialogHost(
        dialog = state.dialog,
        tracks = tracks,
        playlists = playlists,
        selectedIds = state.selectedIds,
        organizerBusy = organizerBusy,
        playlistActions = playlistActions,
        onPlaylistCreated = { state.selectSection(LibrarySectionTab.PLAYLISTS) },
        onDismiss = { state.dialog = null },
    )
}

private fun LazyListScope.libraryTracksItems(
    tracks: List<AudioTrack>,
    viewType: LibrarySongViewType,
    gridColumns: Int,
    keyPrefix: String,
    currentId: String?,
    wantsPlay: Boolean,
    connected: Boolean,
    organizerBusy: Boolean,
    inSelectionMode: Boolean,
    selectedIds: Set<Long>,
    badgeByTrackId: Map<Long, String>,
    showLeadingIndex: Boolean = false,
    onToggleSelect: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onCue: (AudioTrack) -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onInspectDetails: (AudioTrack) -> Unit,
    onRename: (AudioTrack) -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    onRemoveFromPlaylist: ((AudioTrack) -> Unit)? = null,
    onDelete: (AudioTrack) -> Unit,
) {
    when (viewType) {
        LibrarySongViewType.GRID -> {
            val cols = gridColumns.coerceAtLeast(2)
            val rows = tracks.chunked(cols)
            itemsIndexed(
                rows,
                key = { rowIndex, row -> "$keyPrefix:grid:$rowIndex:${row.first().id}" },
                contentType = { _, _ -> "track-grid-row" },
            ) { rowIndex, rowTracks ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowTracks.forEachIndexed { colIndex, track ->
                        val itemIndex = rowIndex * cols + colIndex
                        LibraryGridAudioCard(
                            track = track,
                            selected = track.id.toString() == currentId,
                            wantsPlay = wantsPlay,
                            connected = connected,
                            renameEnabled = !organizerBusy,
                            leadingIndex = if (showLeadingIndex) itemIndex + 1 else null,
                            selectionChecked = if (inSelectionMode) (track.id in selectedIds) else null,
                            playlistBadge = badgeByTrackId[track.id],
                            onToggleSelect = { onToggleSelect(track.id) },
                            onLongPress = { onLongPress(track.id) },
                            onPlay = { onPlay(track) },
                            onCue = { onCue(track) },
                            onTogglePlayPause = onTogglePlayPause,
                            onOpenPlayer = onOpenPlayer,
                            onInspectDetails = { onInspectDetails(track) },
                            onRename = { onRename(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onRemoveFromPlaylist = onRemoveFromPlaylist?.let { fn -> { fn(track) } },
                            onDelete = { onDelete(track) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(cols - rowTracks.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        LibrarySongViewType.COMPACT -> {
            itemsIndexed(
                tracks,
                key = { _, track -> "$keyPrefix:compact:${track.id}" },
                contentType = { _, _ -> "track-compact-row" },
            ) { index, track ->
                LibraryCompactAudioRow(
                    track = track,
                    selected = track.id.toString() == currentId,
                    wantsPlay = wantsPlay,
                    connected = connected,
                    renameEnabled = !organizerBusy,
                    leadingIndex = if (showLeadingIndex) index + 1 else null,
                    selectionChecked = if (inSelectionMode) (track.id in selectedIds) else null,
                    playlistBadge = badgeByTrackId[track.id],
                    onToggleSelect = { onToggleSelect(track.id) },
                    onLongPress = { onLongPress(track.id) },
                    onPlay = { onPlay(track) },
                    onCue = { onCue(track) },
                    onTogglePlayPause = onTogglePlayPause,
                    onOpenPlayer = onOpenPlayer,
                    onInspectDetails = { onInspectDetails(track) },
                    onRename = { onRename(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onRemoveFromPlaylist = onRemoveFromPlaylist?.let { fn -> { fn(track) } },
                    onDelete = { onDelete(track) },
                )
            }
        }
        LibrarySongViewType.LIST -> {
            itemsIndexed(
                tracks,
                key = { _, track -> "$keyPrefix:list:${track.id}" },
                contentType = { _, _ -> "track-row" },
            ) { index, track ->
                LibraryAudioRow(
                    track = track,
                    selected = track.id.toString() == currentId,
                    wantsPlay = wantsPlay,
                    connected = connected,
                    renameEnabled = !organizerBusy,
                    leadingIndex = if (showLeadingIndex) index + 1 else null,
                    selectionChecked = if (inSelectionMode) (track.id in selectedIds) else null,
                    playlistBadge = badgeByTrackId[track.id],
                    onToggleSelect = { onToggleSelect(track.id) },
                    onLongPress = { onLongPress(track.id) },
                    onPlay = { onPlay(track) },
                    onCue = { onCue(track) },
                    onTogglePlayPause = onTogglePlayPause,
                    onOpenPlayer = onOpenPlayer,
                    onInspectDetails = { onInspectDetails(track) },
                    onRename = { onRename(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onRemoveFromPlaylist = onRemoveFromPlaylist?.let { fn -> { fn(track) } },
                    onDelete = { onDelete(track) },
                )
            }
        }
    }
}

@Composable
internal fun LibraryTrackActionMenuButton(
    track: AudioTrack,
    selected: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    compact: Boolean = false,
    onShowDetails: () -> Unit,
    onCue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val desc = "Menu audio ${UiPresentation.title(track.displayName)}"
    Box {
        if (compact) {
            CompactCircleButton(
                icon = UiIcons.More,
                description = desc,
                onClick = { menu = true },
                size = 30.dp,
                iconSize = 17.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            GlyphButton(UiIcons.More, desc, { menu = true })
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Detail lagu") },
                leadingIcon = { Icon(UiIcons.Info, null) },
                onClick = { menu = false; onShowDetails() },
            )
            if (!selected) {
                DropdownMenuItem(
                    text = { Text("Muat ke Standby (Cue)") },
                    leadingIcon = { Icon(UiIcons.Next, null) },
                    enabled = connected,
                    onClick = { menu = false; onCue() },
                )
            }
            DropdownMenuItem(
                text = { Text("Masukkan ke playlist…") },
                leadingIcon = { Icon(UiIcons.Queue, null) },
                enabled = renameEnabled,
                onClick = { menu = false; onAddToPlaylist() },
            )
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Keluarkan dari playlist") },
                    leadingIcon = { Icon(UiIcons.Close, null) },
                    enabled = renameEnabled,
                    onClick = { menu = false; onRemoveFromPlaylist() },
                )
            }
            DropdownMenuItem(
                text = { Text("Ubah nama") },
                leadingIcon = { Icon(UiIcons.Rename, null) },
                enabled = renameEnabled,
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(UiIcons.Delete, null) },
                enabled = renameEnabled,
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun LibraryAudioRow(
    track: AudioTrack,
    selected: Boolean,
    wantsPlay: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    leadingIndex: Int? = null,
    selectionChecked: Boolean? = null,
    highlighted: Boolean = false,
    playlistBadge: String? = null,
    reorderBadge: String? = null,
    reorderControls: ReorderRowControls? = null,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPlay: () -> Unit,
    onCue: () -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onInspectDetails: () -> Unit = {},
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val inSelection = selectionChecked != null
    val (activeContainer, borderStroke) = trackSelectionPalette(
        selected = selected,
        wantsPlay = wantsPlay,
        selectionChecked = selectionChecked,
        highlighted = highlighted,
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = activeContainer,
        border = borderStroke,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .semantics { this.selected = selected || selectionChecked == true }
                .combinedClickable(
                    enabled = inSelection || (connected && reorderControls == null),
                    onClickLabel = when {
                        inSelection -> "Pilih ${UiPresentation.title(track.displayName)}"
                        selected && wantsPlay -> "Buka pemutar penuh ${UiPresentation.title(track.displayName)}"
                        selected -> "Lanjutkan ${UiPresentation.title(track.displayName)}"
                        else -> "Putar ${UiPresentation.title(track.displayName)}"
                    },
                    onClick = { if (inSelection) onToggleSelect() else onPlay() },
                    onLongClick = { if (reorderControls == null) onLongPress() },
                )
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (inSelection) {
                Checkbox(
                    checked = selectionChecked == true,
                    onCheckedChange = null,
                )
            } else if (leadingIndex != null) {
                Text(
                    text = String.format(Locale.ROOT, "%02d", leadingIndex),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (highlighted || selected) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FohAnimatedTrackArtwork(
                artworkKey = track.artworkKey,
                active = selected,
                playing = wantsPlay,
                size = 46.dp,
                cornerRadius = 9.dp,
            )

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        (selected && wantsPlay) || highlighted -> FohTheme.colors.safeAccent
                        selected -> FohTheme.colors.warnAccent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val creatorLine = listOfNotNull(
                    LibraryRules.sanitizedTag(track.artist),
                    LibraryRules.sanitizedTag(track.album),
                ).joinToString(" · ")
                if (creatorLine.isNotEmpty()) {
                    Text(
                        text = creatorLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = UiPresentation.qualitySummary(track.displayName, track.sizeBytes, track.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                val qualityBadge = UiPresentation.highBitrateBadge(track.displayName, track.sizeBytes, track.durationMs)
                if (playlistBadge != null || reorderBadge != null || qualityBadge != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (reorderBadge != null) {
                            LibraryMiniBadge(
                                label = reorderBadge,
                                containerColor = FohTheme.colors.safeBg,
                                borderColor = FohTheme.colors.safeBorder,
                                contentColor = FohTheme.colors.safeAccent,
                            )
                        } else if (playlistBadge != null) {
                            LibraryMiniBadge(
                                label = "↳ $playlistBadge",
                                containerColor = FohTheme.colors.surfaceDeep,
                                borderColor = FohTheme.colors.borderSubtle,
                                contentColor = FohTheme.colors.safeAccent,
                            )
                        }
                        if (qualityBadge != null) {
                            LibraryMiniBadge(
                                label = qualityBadge,
                                containerColor = FohTheme.colors.safeBg,
                                borderColor = FohTheme.colors.safeBorder,
                                contentColor = FohTheme.colors.safeAccent,
                            )
                        }
                    }
                }
            }

            if (reorderControls != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RundownReorderIconButton(
                        icon = UiIcons.ArrowUp,
                        description = "Naikkan urutan",
                        enabled = reorderControls.canMoveUp,
                        containerColor = FohTheme.colors.surfaceDeep,
                        borderColor = FohTheme.colors.borderSubtle,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = reorderControls.onMoveUp,
                    )
                    RundownReorderIconButton(
                        icon = UiIcons.ArrowDown,
                        description = "Turunkan urutan",
                        enabled = reorderControls.canMoveDown,
                        containerColor = FohTheme.colors.surfaceDeep,
                        borderColor = FohTheme.colors.borderSubtle,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = reorderControls.onMoveDown,
                    )
                    RundownReorderIconButton(
                        icon = UiIcons.Close,
                        description = "Keluarkan dari playlist",
                        enabled = renameEnabled,
                        containerColor = FohTheme.colors.alertBg,
                        borderColor = FohTheme.colors.alertBorder,
                        tint = FohTheme.colors.alertAccent,
                        onClick = reorderControls.onRemove,
                    )
                }
            } else if (!inSelection) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selected) {
                        CompactCircleButton(
                            icon = if (wantsPlay) UiIcons.Pause else UiIcons.Play,
                            description = if (wantsPlay) "Jeda audio" else "Putar audio",
                            onClick = onTogglePlayPause,
                            enabled = connected,
                            size = 32.dp,
                            iconSize = 16.dp,
                            containerColor = if (wantsPlay) FohTheme.colors.safeBg else FohTheme.colors.warnBg,
                            contentColor = if (wantsPlay) FohTheme.colors.safeAccent else FohTheme.colors.warnAccent,
                        )
                        CompactCircleButton(
                            icon = UiIcons.ChevronUp,
                            description = "Buka pemutar lengkap",
                            onClick = onOpenPlayer,
                            enabled = connected,
                            size = 32.dp,
                            iconSize = 18.dp,
                            containerColor = FohTheme.colors.surfaceDeep,
                            contentColor = FohTheme.colors.safeAccent,
                        )
                    }
                    LibraryTrackActionMenuButton(
                        track = track,
                        selected = selected,
                        connected = connected,
                        renameEnabled = renameEnabled,
                        compact = false,
                        onShowDetails = onInspectDetails,
                        onCue = onCue,
                        onAddToPlaylist = onAddToPlaylist,
                        onRemoveFromPlaylist = onRemoveFromPlaylist,
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}
