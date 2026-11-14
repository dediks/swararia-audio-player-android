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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
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
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    onRename: (AudioTrack) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (AudioTrack) -> Unit,
    onDeleteBatch: (List<AudioTrack>) -> Unit = {},
    onImport: () -> Unit,
) {
    var sectionName by rememberSaveable { mutableStateOf(LibrarySectionTab.ALL_TRACKS.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var groupModeName by rememberSaveable { mutableStateOf(LibraryGroupMode.ALL.name) }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }

    var creatingPlaylist by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<LibraryPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<LibraryPlaylist?>(null) }
    var pickingPlaylistTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var batchPlaylistOpen by remember { mutableStateOf(false) }
    var addingTracksPlaylistId by remember { mutableStateOf<Long?>(null) }

    val section = remember(sectionName) {
        LibrarySectionTab.entries.firstOrNull { it.name == sectionName } ?: LibrarySectionTab.ALL_TRACKS
    }
    val groupMode = remember(groupModeName) {
        LibraryGroupMode.entries.firstOrNull { it.name == groupModeName } ?: LibraryGroupMode.ALL
    }
    val activePlaylist = remember(playlists, selectedPlaylistId) {
        playlists.firstOrNull { it.id == selectedPlaylistId }
    }

    var optimisticOrder by remember(activePlaylist?.id) { mutableStateOf<List<Long>?>(null) }
    var lastMovedTrackId by remember(activePlaylist?.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(playlists, selectedPlaylistId) {
        if (selectedPlaylistId != null && playlists.none { it.id == selectedPlaylistId }) {
            selectedPlaylistId = null
            reorderMode = false
        }
    }
    LaunchedEffect(tracks) {
        val validIds = tracks.mapTo(HashSet()) { it.id }
        val filtered = selectedIds.filterTo(HashSet()) { it in validIds }
        if (filtered != selectedIds) {
            selectedIds = filtered
            if (filtered.isEmpty()) selectionMode = false
        }
    }
    LaunchedEffect(activePlaylist?.id, activePlaylist?.trackIds) {
        val persisted = activePlaylist?.trackIds
        if (persisted != null && optimisticOrder == persisted) {
            optimisticOrder = null
        }
    }
    LaunchedEffect(optimisticOrder) {
        if (optimisticOrder != null) {
            delay(1500L)
            optimisticOrder = null
        }
    }
    LaunchedEffect(lastMovedTrackId, reorderMode) {
        if (!reorderMode) {
            lastMovedTrackId = null
        } else if (lastMovedTrackId != null) {
            delay(2200L)
            lastMovedTrackId = null
        }
    }

    BackHandler(enabled = selectionMode && section == LibrarySectionTab.ALL_TRACKS) {
        selectionMode = false
        selectedIds = emptySet()
    }
    BackHandler(enabled = section == LibrarySectionTab.PLAYLISTS && selectedPlaylistId != null && !selectionMode) {
        reorderMode = false
        optimisticOrder = null
        selectedPlaylistId = null
    }

    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val membershipByTrackId = remember(playlists) {
        val map = HashMap<Long, MutableList<String>>()
        for (pl in playlists) {
            for (tid in pl.trackIds) {
                map.getOrPut(tid) { mutableListOf() }.add(pl.name)
            }
        }
        map
    }
    val sortedMatching = remember(tracks, playlists, query, alphabetical) {
        val matching = filterLibraryTracks(tracks, query, membershipByTrackId)
        sortLibraryTracks(matching, alphabetical)
    }
    val effectiveRundownIds = remember(activePlaylist?.trackIds, optimisticOrder) {
        val persisted = activePlaylist?.trackIds.orEmpty()
        val candidate = optimisticOrder
        if (candidate != null && candidate.toSet() == persisted.toSet() && candidate.size == persisted.size) candidate
        else persisted
    }
    val activePlaylistTracks = remember(effectiveRundownIds, trackById) {
        effectiveRundownIds.mapNotNull(trackById::get)
    }
    val visiblePlaylists = remember(playlists, trackById, query) {
        val q = query.trim()
        if (q.isEmpty()) playlists else playlists.filter { playlist ->
            playlist.name.contains(q, ignoreCase = true) ||
                playlist.trackIds.any { id -> trackById[id]?.let { matchesTrackQuery(it, q) } == true }
        }
    }
    val groupedTracks = remember(sortedMatching, groupMode) {
        when (groupMode) {
            LibraryGroupMode.ARTIST -> UiPresentation.groupItems(
                sortedMatching,
                keySelector = { LibraryRules.sanitizedTag(it.artist) ?: "Artis Tidak Diketahui" },
                durationSelector = { it.durationMs },
                fallbackLabel = "Artis Tidak Diketahui",
            )
            LibraryGroupMode.ALBUM -> UiPresentation.groupItems(
                sortedMatching,
                keySelector = { LibraryRules.sanitizedTag(it.album) ?: "Album Tidak Diketahui" },
                durationSelector = { it.durationMs },
                fallbackLabel = "Album Tidak Diketahui",
            )
            LibraryGroupMode.FORMAT -> UiPresentation.groupItems(
                sortedMatching,
                keySelector = { LibraryRules.audioFormatLabel(it.displayName) },
                durationSelector = { it.durationMs },
                fallbackLabel = "Audio",
            )
            LibraryGroupMode.ALL -> emptyList()
        }
    }

    val toggleTrackSelection: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val startSelectionWith: (Long) -> Unit = { id ->
        if (section == LibrarySectionTab.ALL_TRACKS) {
            selectionMode = true
            selectedIds = selectedIds + id
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!(section == LibrarySectionTab.PLAYLISTS && activePlaylist != null)) {
                item(key = "library-header") {
                    LibraryCompactHeader(
                        section = section,
                        trackCount = tracks.size,
                        playlistCount = playlists.size,
                        selectionMode = selectionMode,
                        query = query,
                        groupMode = groupMode,
                        alphabetical = alphabetical,
                        importEnabled = !ingest.busy && !search.busy && !organizerBusy,
                        actionBusy = organizerBusy,
                        onSelectSection = { target ->
                            sectionName = target.name
                            if (target == LibrarySectionTab.PLAYLISTS) {
                                selectionMode = false
                                selectedIds = emptySet()
                            } else {
                                reorderMode = false
                            }
                        },
                        onImport = onImport,
                        onToggleSelectionMode = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds = emptySet()
                            } else {
                                selectionMode = true
                            }
                        },
                        onCreatePlaylist = { creatingPlaylist = true },
                        onQueryChange = { query = it },
                        onSelectGroupMode = { groupModeName = it.name },
                        onToggleSort = { alphabetical = it },
                        onHistory = onHistory,
                        onSettings = onSettings,
                    )
                }
            }

            if (ingest.busy || search.busy) item(key = "active-operation") {
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
            if (!connected && tracks.isNotEmpty()) item(key = "player-disconnected") {
                Text(
                    "Pemutar belum terhubung. Library & rundown tetap dapat diatur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (organizerBusy) item(key = "rename-progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            when (section) {
                LibrarySectionTab.ALL_TRACKS -> {
                    if (groupMode == LibraryGroupMode.ALL) {
                        if (sortedMatching.isEmpty()) {
                            item(key = "library-empty") {
                                LibraryEmptyPlaceholder(
                                    hasTracks = tracks.isNotEmpty(),
                                    onAdd = onAdd,
                                    onClearQuery = { query = "" },
                                )
                            }
                        } else {
                            items(sortedMatching, key = { it.id }) { track ->
                                val badge = remember(membershipByTrackId, track.id) {
                                    UiPresentation.playlistMembershipBadge(membershipByTrackId[track.id].orEmpty())
                                }
                                LibraryAudioRow(
                                    track = track,
                                    selected = track.id.toString() == currentId,
                                    wantsPlay = wantsPlay,
                                    connected = connected,
                                    renameEnabled = !organizerBusy,
                                    selectionChecked = if (selectionMode) (track.id in selectedIds) else null,
                                    playlistBadge = badge,
                                    onToggleSelect = { toggleTrackSelection(track.id) },
                                    onLongPress = { startSelectionWith(track.id) },
                                    onPlay = { onPlayQueue(track, sortedMatching) },
                                    onCue = { onCueQueue(track, sortedMatching) },
                                    onRename = { onRename(track) },
                                    onAddToPlaylist = { pickingPlaylistTrack = track },
                                    onDelete = { onDelete(track) },
                                )
                            }
                        }
                    } else {
                        if (groupedTracks.isEmpty()) {
                            item(key = "grouped-empty") {
                                LibraryEmptyPlaceholder(
                                    hasTracks = tracks.isNotEmpty(),
                                    onAdd = onAdd,
                                    onClearQuery = { query = "" },
                                )
                            }
                        } else {
                            groupedTracks.forEach { group ->
                                val collapsed = group.key in collapsedGroups
                                item(key = "grp-header:${groupMode.name}:${group.key}") {
                                    LibraryGroupHeader(
                                        group = group,
                                        collapsed = collapsed,
                                        connected = connected,
                                        onToggle = {
                                            collapsedGroups = if (collapsed) collapsedGroups - group.key
                                            else collapsedGroups + group.key
                                        },
                                        onPlayAll = {
                                            if (group.items.isNotEmpty()) onPlayQueue(group.items.first(), group.items)
                                        },
                                    )
                                }
                                if (!collapsed) {
                                    items(group.items, key = { "grp:${groupMode.name}:${group.key}:${it.id}" }) { track ->
                                        val badge = remember(membershipByTrackId, track.id) {
                                            UiPresentation.playlistMembershipBadge(membershipByTrackId[track.id].orEmpty())
                                        }
                                        LibraryAudioRow(
                                            track = track,
                                            selected = track.id.toString() == currentId,
                                            wantsPlay = wantsPlay,
                                            connected = connected,
                                            renameEnabled = !organizerBusy,
                                            selectionChecked = if (selectionMode) (track.id in selectedIds) else null,
                                            playlistBadge = badge,
                                            onToggleSelect = { toggleTrackSelection(track.id) },
                                            onLongPress = { startSelectionWith(track.id) },
                                            onPlay = { onPlayQueue(track, group.items) },
                                            onCue = { onCueQueue(track, group.items) },
                                            onRename = { onRename(track) },
                                            onAddToPlaylist = { pickingPlaylistTrack = track },
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
                        item(key = "playlist-detail-header") {
                            PlaylistRundownHeader(
                                playlist = activePlaylist,
                                tracks = activePlaylistTracks,
                                connected = connected,
                                busy = organizerBusy,
                                reorderMode = reorderMode,
                                onBack = {
                                    reorderMode = false
                                    optimisticOrder = null
                                    selectedPlaylistId = null
                                },
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
                                onAddTracks = { addingTracksPlaylistId = activePlaylist.id },
                                onToggleReorder = { reorderMode = !reorderMode },
                                onRename = { renamingPlaylist = activePlaylist },
                                onDelete = { deletingPlaylist = activePlaylist },
                            )
                        }
                        if (activePlaylistTracks.isEmpty()) {
                            item(key = "playlist-empty") {
                                EmptyMessage(
                                    "Playlist masih kosong",
                                    "Tambahkan track dari library untuk menyusun rundown show ini.",
                                    UiIcons.Queue,
                                ) {
                                    Button(
                                        onClick = { addingTracksPlaylistId = activePlaylist.id },
                                        enabled = tracks.isNotEmpty() && !organizerBusy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Add Tracks") }
                                }
                            }
                        } else {
                            itemsIndexed(
                                activePlaylistTracks,
                                key = { _, track -> "pl:${activePlaylist.id}:${track.id}" },
                            ) { index, track ->
                                val moveTrack: (Int) -> Unit = { delta ->
                                    val baseIds = activePlaylistTracks.map { it.id }
                                    val moved = LibraryRules.movePlaylistItem(baseIds, index, delta)
                                    if (moved != baseIds) {
                                        optimisticOrder = moved
                                        lastMovedTrackId = track.id
                                        playlistActions.onReorderPlaylistTracks(activePlaylist.id, moved)
                                    }
                                }
                                val justMoved = lastMovedTrackId == track.id
                                LibraryAudioRow(
                                    track = track,
                                    selected = track.id.toString() == currentId,
                                    wantsPlay = wantsPlay,
                                    connected = connected,
                                    renameEnabled = !organizerBusy,
                                    leadingIndex = index + 1,
                                    highlighted = justMoved,
                                    reorderBadge = if (justMoved) String.format(Locale.ROOT, "Dipindah ke #%02d", index + 1) else null,
                                    reorderControls = if (reorderMode) ReorderRowControls(
                                        canMoveUp = index > 0 && !organizerBusy,
                                        canMoveDown = index < activePlaylistTracks.lastIndex && !organizerBusy,
                                        onMoveUp = { moveTrack(-1) },
                                        onMoveDown = { moveTrack(1) },
                                        onRemove = { playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id) },
                                    ) else null,
                                    onPlay = { onPlayQueue(track, activePlaylistTracks) },
                                    onCue = { onCueQueue(track, activePlaylistTracks) },
                                    onRename = { onRename(track) },
                                    onAddToPlaylist = { pickingPlaylistTrack = track },
                                    onRemoveFromPlaylist = {
                                        playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id)
                                    },
                                    onDelete = { onDelete(track) },
                                )
                            }
                            if (reorderMode || activePlaylistTracks.size > 1) {
                                item(key = "rundown-sync-notice") {
                                    RundownSyncNoticeBanner()
                                }
                            }
                        }
                    } else {
                        if (visiblePlaylists.isEmpty()) {
                            item(key = "playlists-empty") {
                                if (playlists.isEmpty()) {
                                    EmptyMessage(
                                        "Belum ada playlist",
                                        "Buat playlist show untuk mengatur rundown event dan Load Cue cepat.",
                                        UiIcons.Queue,
                                    ) {
                                        Button(
                                            onClick = { creatingPlaylist = true },
                                            enabled = !organizerBusy,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("New Playlist") }
                                    }
                                } else {
                                    EmptyMessage("Playlist tidak ditemukan", "Coba kata lain atau hapus pencarian.", UiIcons.Search) {
                                        TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text("Hapus pencarian")
                                        }
                                    }
                                }
                            }
                        } else {
                            items(visiblePlaylists, key = { "playlist-card:${it.id}" }) { playlist ->
                                val plTracks = remember(playlist, trackById) { playlist.trackIds.mapNotNull(trackById::get) }
                                val plDuration = remember(plTracks) { plTracks.totalDurationMs() }
                                val activeShow = remember(currentId, playlist.trackIds) {
                                    val activeTrackId = currentId?.toLongOrNull()
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
                                    onOpen = {
                                        reorderMode = false
                                        selectedPlaylistId = playlist.id
                                    },
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
                                    onAddTracks = { addingTracksPlaylistId = playlist.id },
                                    onRename = { renamingPlaylist = playlist },
                                    onDelete = { deletingPlaylist = playlist },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode && section == LibrarySectionTab.ALL_TRACKS) {
            val visibleIds = sortedMatching.map { it.id }
            LibraryBatchActionBar(
                selectedCount = selectedIds.size,
                totalVisibleCount = visibleIds.size,
                busy = organizerBusy,
                onToggleSelectAll = {
                    val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
                    selectedIds = if (allSelected) emptySet() else visibleIds.toSet()
                },
                onAddToPlaylist = { batchPlaylistOpen = true },
                onDeleteSelected = {
                    val batch = tracks.filter { it.id in selectedIds }
                    if (batch.isNotEmpty()) onDeleteBatch(batch)
                },
                onCancel = {
                    selectionMode = false
                    selectedIds = emptySet()
                },
            )
        }
    }

    if (creatingPlaylist) {
        CreateOrRenamePlaylistDialog(
            title = "Buat playlist baru",
            confirmLabel = "Buat",
            busy = organizerBusy,
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                playlistActions.onCreatePlaylist(name, null)
                sectionName = LibrarySectionTab.PLAYLISTS.name
            },
        )
    }
    renamingPlaylist?.let { playlist ->
        key(playlist.id, playlist.name) {
            CreateOrRenamePlaylistDialog(
                title = "Ganti nama playlist",
                confirmLabel = "Simpan",
                initialName = playlist.name,
                busy = organizerBusy,
                onDismiss = { renamingPlaylist = null },
                onConfirm = { name -> playlistActions.onRenamePlaylist(playlist.id, name) },
            )
        }
    }
    deletingPlaylist?.let { playlist ->
        key(playlist.id) {
            DeletePlaylistDialog(
                playlist = playlist,
                busy = organizerBusy,
                onDismiss = { deletingPlaylist = null },
                onDelete = { playlistId -> playlistActions.onDeletePlaylist(playlistId) },
            )
        }
    }
    pickingPlaylistTrack?.let { track ->
        key(track.id) {
            PlaylistPickerDialog(
                track = track,
                playlists = playlists,
                busy = organizerBusy,
                onDismiss = { pickingPlaylistTrack = null },
                onToggleMembership = { playlistId, add ->
                    if (add) playlistActions.onAddTrackToPlaylist(playlistId, track.id)
                    else playlistActions.onRemoveTrackFromPlaylist(playlistId, track.id)
                },
                onCreatePlaylistWithTrack = { name, trackId ->
                    playlistActions.onCreatePlaylist(name, trackId)
                },
            )
        }
    }
    if (batchPlaylistOpen) {
        val batchTracks = remember(tracks, selectedIds) { tracks.filter { it.id in selectedIds } }
        BatchPlaylistPickerDialog(
            selectedTracks = batchTracks,
            playlists = playlists,
            busy = organizerBusy,
            onDismiss = { batchPlaylistOpen = false },
            onAddTracksToPlaylist = { playlistId, ids ->
                playlistActions.onAddTracksToPlaylist(playlistId, ids)
            },
            onCreatePlaylistWithTracks = { name, ids ->
                playlistActions.onCreatePlaylistWithTracks(name, ids)
            },
        )
    }
    addingTracksPlaylistId?.let { targetPlaylistId ->
        playlists.firstOrNull { it.id == targetPlaylistId }?.let { targetPlaylist ->
            AddTracksToPlaylistDialog(
                playlist = targetPlaylist,
                tracks = tracks,
                busy = organizerBusy,
                onDismiss = { addingTracksPlaylistId = null },
                onToggleTrack = { trackId, add ->
                    if (add) playlistActions.onAddTrackToPlaylist(targetPlaylist.id, trackId)
                    else playlistActions.onRemoveTrackFromPlaylist(targetPlaylist.id, trackId)
                },
            )
        }
    }
}

@Composable
private fun CircularProgressIndicatorCompact() {
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
}

private fun matchesTrackQuery(track: AudioTrack, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return track.displayName.contains(q, ignoreCase = true) ||
        UiPresentation.title(track.displayName).contains(q, ignoreCase = true) ||
        (track.artist?.contains(q, ignoreCase = true) == true) ||
        (track.album?.contains(q, ignoreCase = true) == true) ||
        (track.genre?.contains(q, ignoreCase = true) == true) ||
        LibraryRules.audioFormatLabel(track.displayName).contains(q, ignoreCase = true)
}

private fun filterLibraryTracks(
    tracks: List<AudioTrack>,
    query: String,
    membershipByTrackId: Map<Long, List<String>> = emptyMap(),
): List<AudioTrack> {
    val q = query.trim()
    if (q.isEmpty()) return tracks
    return tracks.filter { track ->
        matchesTrackQuery(track, q) ||
            membershipByTrackId[track.id]?.any { it.contains(q, ignoreCase = true) } == true
    }
}

private fun sortLibraryTracks(tracks: List<AudioTrack>, alphabetical: Boolean): List<AudioTrack> =
    if (alphabetical) tracks.sortedWith(compareBy<AudioTrack> {
        UiPresentation.title(it.displayName).lowercase(Locale.ROOT)
    }.thenBy { it.id })
    else tracks.sortedWith(compareByDescending<AudioTrack> { it.addedAt }.thenByDescending { it.id })

private fun List<AudioTrack>.totalDurationMs(): Long = sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) }

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
        color = FohPalette.SurfaceCard,
        border = BorderStroke(1.dp, FohPalette.BorderSubtle),
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
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val inSelection = selectionChecked != null
    val activeContainer = when {
        highlighted -> FohPalette.SafeBg
        selectionChecked == true -> FohPalette.SafeBg
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> FohPalette.SurfaceCard
    }
    val borderStroke = when {
        highlighted || selectionChecked == true || selected -> BorderStroke(1.dp, FohPalette.SafeBorder)
        else -> BorderStroke(1.dp, FohPalette.BorderSubtle.copy(alpha = 0.7f))
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
                    onClickLabel = if (inSelection) "Pilih ${UiPresentation.title(track.displayName)}"
                    else "Putar ${UiPresentation.title(track.displayName)}",
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
                    color = if (highlighted || selected) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TrackArtwork(track.artworkKey, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected || highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = UiPresentation.trackSubtitle(track.displayName, track.artist, track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected || playlistBadge != null || reorderBadge != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) {
                            LibraryMiniBadge(
                                label = if (wantsPlay) "PLAYING" else "STANDBY",
                                containerColor = FohPalette.SafeBg,
                                borderColor = FohPalette.SafeBorder,
                                contentColor = FohPalette.SafeAccent,
                            )
                        }
                        if (reorderBadge != null) {
                            LibraryMiniBadge(
                                label = reorderBadge,
                                containerColor = FohPalette.SafeBg,
                                borderColor = FohPalette.SafeBorder,
                                contentColor = FohPalette.SafeAccent,
                            )
                        } else if (playlistBadge != null) {
                            LibraryMiniBadge(
                                label = "↳ $playlistBadge",
                                containerColor = FohPalette.SurfaceDeep,
                                borderColor = FohPalette.BorderSubtle,
                                contentColor = FohPalette.SafeAccent,
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
                        containerColor = FohPalette.SurfaceDeep,
                        borderColor = FohPalette.BorderSubtle,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = reorderControls.onMoveUp,
                    )
                    RundownReorderIconButton(
                        icon = UiIcons.ArrowDown,
                        description = "Turunkan urutan",
                        enabled = reorderControls.canMoveDown,
                        containerColor = FohPalette.SurfaceDeep,
                        borderColor = FohPalette.BorderSubtle,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = reorderControls.onMoveDown,
                    )
                    RundownReorderIconButton(
                        icon = UiIcons.Close,
                        description = "Keluarkan dari playlist",
                        enabled = renameEnabled,
                        containerColor = FohPalette.AlertBg,
                        borderColor = FohPalette.AlertBorder,
                        tint = FohPalette.AlertAccent,
                        onClick = reorderControls.onRemove,
                    )
                }
            } else if (!inSelection) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Surface(
                        onClick = onCue,
                        enabled = connected,
                        shape = RoundedCornerShape(8.dp),
                        color = if (connected) FohPalette.WarnBg else FohPalette.SurfaceDeep,
                        border = BorderStroke(
                            1.dp,
                            if (connected) FohPalette.WarnBorder else FohPalette.BorderSubtle,
                        ),
                    ) {
                        Text(
                            text = "Cue",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (connected) FohPalette.WarnAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Box {
                        GlyphButton(UiIcons.More, "Menu audio ${UiPresentation.title(track.displayName)}", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                                text = { Text("Rename") },
                                leadingIcon = { Icon(UiIcons.Rename, null) },
                                enabled = renameEnabled,
                                onClick = { menu = false; onRename() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
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
        color = if (enabled) containerColor else FohPalette.SurfaceDeep.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (enabled) borderColor else FohPalette.BorderSubtle.copy(alpha = 0.4f),
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
