package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
    onRename: (AudioTrack) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (AudioTrack) -> Unit,
    onDeleteBatch: (List<AudioTrack>) -> Unit = {},
    onImport: () -> Unit,
) {
    val state = rememberLibraryBrowserState(tracks = tracks, playlists = playlists)
    val section = state.section
    val groupMode = state.groupMode
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val brandManager = remember(context) {
        (context.applicationContext as? SwarariaApp)?.container?.brandSkinManager
    }
    val brand = FohTheme.brand

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        alphabetical = state.alphabetical,
                        importEnabled = !ingest.busy && !search.busy && !organizerBusy,
                        actionBusy = organizerBusy,
                        onSelectSection = state::selectSection,
                        onImport = onImport,
                        onToggleSelectionMode = state::toggleSelectionMode,
                        onCreatePlaylist = { state.dialog = LibraryDialog.CreatePlaylist },
                        onQueryChange = { state.query = it },
                        onSelectGroupMode = state::selectGroupMode,
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
                                    onDismiss = { brandManager?.dismissWelcomeBanner() },
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
                            items(sortedMatching, key = { it.id }, contentType = { "track-row" }) { track ->
                                LibraryAudioRow(
                                    track = track,
                                    selected = track.id.toString() == currentId,
                                    wantsPlay = wantsPlay,
                                    connected = connected,
                                    renameEnabled = !organizerBusy,
                                    selectionChecked = if (state.selectionMode) (track.id in state.selectedIds) else null,
                                    playlistBadge = badgeByTrackId[track.id],
                                    onToggleSelect = { state.toggleTrackSelection(track.id) },
                                    onLongPress = { state.startSelectionWith(track.id) },
                                    onPlay = { onPlayQueue(track, sortedMatching) },
                                    onCue = { onCueQueue(track, sortedMatching) },
                                    onTogglePlayPause = onToggleCurrentPlayPause,
                                    onOpenPlayer = onOpenPlayer,
                                    onRename = { onRename(track) },
                                    onAddToPlaylist = { state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                    onDelete = { onDelete(track) },
                                )
                            }
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
                                    items(
                                        group.items,
                                        key = { "grp:${groupMode.name}:${group.key}:${it.id}" },
                                        contentType = { "track-row" },
                                    ) { track ->
                                        LibraryAudioRow(
                                            track = track,
                                            selected = track.id.toString() == currentId,
                                            wantsPlay = wantsPlay,
                                            connected = connected,
                                            renameEnabled = !organizerBusy,
                                            selectionChecked = if (state.selectionMode) (track.id in state.selectedIds) else null,
                                            playlistBadge = badgeByTrackId[track.id],
                                            onToggleSelect = { state.toggleTrackSelection(track.id) },
                                            onLongPress = { state.startSelectionWith(track.id) },
                                            onPlay = { onPlayQueue(track, group.items) },
                                            onCue = { onCueQueue(track, group.items) },
                                            onTogglePlayPause = onToggleCurrentPlayPause,
                                            onOpenPlayer = onOpenPlayer,
                                            onRename = { onRename(track) },
                                            onAddToPlaylist = { state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                            onDelete = { onDelete(track) },
                                        )
                                    }
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
                        } else {
                            itemsIndexed(
                                activePlaylistTracks,
                                key = { _, track -> "pl:${activePlaylist.id}:${track.id}" },
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
                                    reorderControls = if (state.reorderMode) ReorderRowControls(
                                        canMoveUp = index > 0 && !organizerBusy,
                                        canMoveDown = index < activePlaylistTracks.lastIndex && !organizerBusy,
                                        onMoveUp = { moveTrack(-1) },
                                        onMoveDown = { moveTrack(1) },
                                        onRemove = { playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id) },
                                    ) else null,
                                    onPlay = { onPlayQueue(track, activePlaylistTracks) },
                                    onCue = { onCueQueue(track, activePlaylistTracks) },
                                    onTogglePlayPause = onToggleCurrentPlayPause,
                                    onOpenPlayer = onOpenPlayer,
                                    onRename = { onRename(track) },
                                    onAddToPlaylist = { state.dialog = LibraryDialog.PickPlaylistForTrack(track) },
                                    onRemoveFromPlaylist = {
                                        playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id)
                                    },
                                    onDelete = { onDelete(track) },
                                )
                            }
                            if (state.reorderMode || activePlaylistTracks.size > 1) {
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

@Composable
private fun LibraryDialogHost(
    dialog: LibraryDialog?,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    selectedIds: Set<Long>,
    organizerBusy: Boolean,
    playlistActions: PlaylistUiActions,
    onPlaylistCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        LibraryDialog.CreatePlaylist -> CreateOrRenamePlaylistDialog(
            title = "Buat playlist baru",
            confirmLabel = "Buat",
            busy = organizerBusy,
            onDismiss = onDismiss,
            onConfirm = { name ->
                playlistActions.onCreatePlaylist(name, null)
                onPlaylistCreated()
            },
        )
        is LibraryDialog.RenamePlaylist -> key(dialog.playlist.id, dialog.playlist.name) {
            CreateOrRenamePlaylistDialog(
                title = "Ganti nama playlist",
                confirmLabel = "Simpan",
                initialName = dialog.playlist.name,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onConfirm = { name -> playlistActions.onRenamePlaylist(dialog.playlist.id, name) },
            )
        }
        is LibraryDialog.DeletePlaylist -> key(dialog.playlist.id) {
            DeletePlaylistDialog(
                playlist = dialog.playlist,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onDelete = { playlistId -> playlistActions.onDeletePlaylist(playlistId) },
            )
        }
        is LibraryDialog.PickPlaylistForTrack -> key(dialog.track.id) {
            PlaylistPickerDialog(
                track = dialog.track,
                playlists = playlists,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onToggleMembership = { playlistId, add ->
                    if (add) playlistActions.onAddTrackToPlaylist(playlistId, dialog.track.id)
                    else playlistActions.onRemoveTrackFromPlaylist(playlistId, dialog.track.id)
                },
                onCreatePlaylistWithTrack = { name, trackId ->
                    playlistActions.onCreatePlaylist(name, trackId)
                },
            )
        }
        LibraryDialog.BatchAddToPlaylist -> {
            val batchTracks = remember(tracks, selectedIds) { tracks.filter { it.id in selectedIds } }
            BatchPlaylistPickerDialog(
                selectedTracks = batchTracks,
                playlists = playlists,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onAddTracksToPlaylist = { playlistId, ids ->
                    playlistActions.onAddTracksToPlaylist(playlistId, ids)
                },
                onCreatePlaylistWithTracks = { name, ids ->
                    playlistActions.onCreatePlaylistWithTracks(name, ids)
                },
            )
        }
        is LibraryDialog.AddTracksToPlaylist -> {
            playlists.firstOrNull { it.id == dialog.playlistId }?.let { targetPlaylist ->
                AddTracksToPlaylistDialog(
                    playlist = targetPlaylist,
                    tracks = tracks,
                    busy = organizerBusy,
                    onDismiss = onDismiss,
                    onToggleTrack = { trackId, add ->
                        if (add) playlistActions.onAddTrackToPlaylist(targetPlaylist.id, trackId)
                        else playlistActions.onRemoveTrackFromPlaylist(targetPlaylist.id, trackId)
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyPlaceholder(hasTracks: Boolean, onAdd: () -> Unit, onClearQuery: () -> Unit) {
    if (!hasTracks) EmptyMessage("Pustaka masih kosong", "Tambahkan tautan berizin atau impor audio dari HP.", UiIcons.Library) {
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tambah audio") }
    } else EmptyMessage("Audio tidak ditemukan", "Coba nama lain atau hapus kata pencarian.", UiIcons.Search) {
        TextButton(onClick = onClearQuery, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hapus pencarian") }
    }
}

@Composable
private fun LibraryGroupHeader(
    group: LibraryTrackGroup<AudioTrack>,
    collapsed: Boolean,
    connected: Boolean,
    onToggle: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (collapsed) UiIcons.ChevronRight else UiIcons.ChevronDown,
                contentDescription = if (collapsed) "Buka grup ${group.label}" else "Tutup grup ${group.label}",
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    UiPresentation.groupSummary(group.items.size, group.totalDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connected && group.items.isNotEmpty()) {
                GlyphButton(UiIcons.Play, "Putar grup ${group.label}", onPlayAll)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val inSelection = selectionChecked != null
    val activeContainer = when {
        highlighted -> FohTheme.colors.safeBg
        selectionChecked == true -> FohTheme.colors.safeBg
        selected && wantsPlay -> FohTheme.colors.safeBg
        selected -> FohTheme.colors.warnBg
        else -> FohTheme.colors.surfaceCard
    }
    val borderStroke = when {
        highlighted || selectionChecked == true || (selected && wantsPlay) -> BorderStroke(1.dp, FohTheme.colors.safeBorder)
        selected -> BorderStroke(1.dp, FohTheme.colors.warnBorder)
        else -> BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.7f))
    }

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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = UiPresentation.trackSubtitleWithQuality(
                        track.displayName,
                        track.artist,
                        track.sizeBytes,
                        track.durationMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlistBadge != null || reorderBadge != null) {
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
                    Box {
                        GlyphButton(UiIcons.More, "Menu audio ${UiPresentation.title(track.displayName)}", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
            }
        }
    }
}

@Composable
private fun LibraryMiniBadge(
    label: String,
    containerColor: Color,
    borderColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun RundownReorderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    containerColor: Color,
    borderColor: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) containerColor else FohTheme.colors.surfaceDeep.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (enabled) borderColor else FohTheme.colors.borderSubtle.copy(alpha = 0.4f),
        ),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = 0.28f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun WelcomeBrandBanner(
    brand: BrandSkinConfig,
    onSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.safeBg,
        border = BorderStroke(1.dp, foh.safeBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrestigeBrandEmblem(
                logoKey = brand.customLogoKey,
                size = 28.dp,
                shimmerEnabled = brand.prestigeAnimations,
            )
            Text(
                text = "Pasang Logo & Nama Rental",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = foh.safeAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onSetup,
                shape = RoundedCornerShape(999.dp),
                color = foh.safeAccent,
            ) {
                Text(
                    text = "Atur",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = foh.surfaceDeep,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            CompactCircleButton(
                icon = UiIcons.Close,
                description = "Tutup kartu sambutan",
                onClick = onDismiss,
                size = 30.dp,
                iconSize = 16.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
