package com.swararia.audiolab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun LibraryPage(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist> = emptyList(),
    currentId: String?,
    connected: Boolean,
    ingest: IngestState,
    search: YoutubeSearchState,
    organizerBusy: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onPlayQueue: (AudioTrack, List<AudioTrack>) -> Unit = { track, _ -> onPlay(track) },
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    onRename: (AudioTrack) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (AudioTrack) -> Unit,
    onImport: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var groupModeName by rememberSaveable { mutableStateOf(LibraryGroupMode.ALL.name) }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<LibraryPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<LibraryPlaylist?>(null) }
    var pickingPlaylistTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var addingTracksPlaylistId by remember { mutableStateOf<Long?>(null) }

    val groupMode = remember(groupModeName) {
        LibraryGroupMode.entries.firstOrNull { it.name == groupModeName } ?: LibraryGroupMode.ALL
    }
    val activePlaylist = remember(playlists, selectedPlaylistId) {
        playlists.firstOrNull { it.id == selectedPlaylistId }
    }
    LaunchedEffect(playlists, selectedPlaylistId) {
        if (selectedPlaylistId != null && playlists.none { it.id == selectedPlaylistId }) {
            selectedPlaylistId = null
        }
    }

    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val sortedMatching = remember(tracks, query, alphabetical) {
        val matching = filterLibraryTracks(tracks, query)
        sortLibraryTracks(matching, alphabetical)
    }
    val activePlaylistTracks = remember(activePlaylist, trackById, query, alphabetical) {
        if (activePlaylist == null) emptyList()
        else {
            val ordered = activePlaylist.trackIds.mapNotNull { trackById[it] }
            val filtered = filterLibraryTracks(ordered, query)
            if (alphabetical) sortLibraryTracks(filtered, alphabetical = true) else filtered
        }
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
            else -> emptyList()
        }
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "library-header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    BrandLogo(32.dp)
                    Box {
                        GlyphButton(UiIcons.More, "Menu Pustaka", { appMenu = true })
                        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                            DropdownMenuItem(text = { Text("Riwayat nama & urungkan") },
                                leadingIcon = { Icon(UiIcons.History, null) },
                                onClick = { appMenu = false; onHistory() })
                            DropdownMenuItem(text = { Text("Pengaturan & tentang") },
                                leadingIcon = { Icon(UiIcons.Settings, null) },
                                onClick = { appMenu = false; onSettings() })
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cari lagu, artis, album, atau playlist") },
                    leadingIcon = { Icon(UiIcons.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ GlyphButton(UiIcons.Close, "Hapus pencarian pustaka", { query = "" }) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = searchFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(LibraryGroupMode.entries, key = { it.name }) { mode ->
                        val chipLabel = if (mode == LibraryGroupMode.PLAYLIST && playlists.isNotEmpty()) {
                            "${mode.label} (${playlists.size})"
                        } else {
                            mode.label
                        }
                        FilterChip(
                            selected = groupMode == mode,
                            onClick = {
                                groupModeName = mode.name
                                if (mode != LibraryGroupMode.PLAYLIST) selectedPlaylistId = null
                            },
                            label = { Text(chipLabel) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onImport, enabled = !ingest.busy && !search.busy && !organizerBusy) {
                        Icon(UiIcons.Import, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                    }
                    OutlinedButton(onClick = { creatingPlaylist = true }, enabled = !organizerBusy) {
                        Icon(UiIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Playlist")
                    }
                }
                if (groupMode != LibraryGroupMode.PLAYLIST || activePlaylist != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val summaryLabel = when {
                            groupMode == LibraryGroupMode.PLAYLIST && activePlaylist != null ->
                                "${activePlaylistTracks.size} lagu di playlist"
                            groupedTracks.isNotEmpty() ->
                                "${groupedTracks.size} grup • ${sortedMatching.size} audio"
                            query.isBlank() -> "${tracks.size} audio"
                            else -> "${sortedMatching.size} dari ${tracks.size} audio"
                        }
                        Text(
                            summaryLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            TextButton(
                                onClick = { sortMenu = true },
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Urutkan audio: ${if (alphabetical) "A–Z" else "Terbaru"}" },
                            ) {
                                Text(if (alphabetical) "A–Z" else "Terbaru", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(UiIcons.ChevronDown, null, Modifier.padding(start = 6.dp).size(20.dp))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                DropdownMenuItem(text = { Text("Terbaru") }, onClick = { alphabetical = false; sortMenu = false },
                                    trailingIcon = if (!alphabetical) ({ Icon(UiIcons.Check, null) }) else null)
                                DropdownMenuItem(text = { Text("A–Z") }, onClick = { alphabetical = true; sortMenu = false },
                                    trailingIcon = if (alphabetical) ({ Icon(UiIcons.Check, null) }) else null)
                            }
                        }
                    }
                }
            }
        }
        if (ingest.busy || search.busy) item(key = "active-operation") {
            Surface(onClick = onAdd, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (ingest.busy) ingest.stage else "Mencari di ${search.provider.label}…",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(UiIcons.ChevronRight, "Lihat proses", Modifier.size(20.dp))
                }
            }
        }
        if (!connected && tracks.isNotEmpty()) item(key = "player-disconnected") {
            Text("Pemutar belum terhubung. Audio tetap bisa dicari, dikelompokkan, dan diubah namanya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        if (organizerBusy) item(key = "rename-progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        when (groupMode) {
            LibraryGroupMode.ALL -> {
                if (sortedMatching.isEmpty()) item(key = "library-empty") {
                    LibraryEmptyPlaceholder(
                        hasTracks = tracks.isNotEmpty(),
                        onAdd = onAdd,
                        onClearQuery = { query = "" },
                    )
                } else items(sortedMatching, key = { it.id }) { track ->
                    LibraryAudioRow(
                        track = track,
                        selected = track.id.toString() == currentId,
                        connected = connected,
                        renameEnabled = !organizerBusy,
                        onPlay = { onPlayQueue(track, sortedMatching) },
                        onRename = { onRename(track) },
                        onAddToPlaylist = { pickingPlaylistTrack = track },
                        onDelete = { onDelete(track) },
                    )
                }
            }
            LibraryGroupMode.PLAYLIST -> {
                if (activePlaylist != null) {
                    item(key = "playlist-detail-header") {
                        PlaylistDetailHeader(
                            playlist = activePlaylist,
                            tracks = activePlaylistTracks,
                            connected = connected,
                            busy = organizerBusy,
                            onBack = { selectedPlaylistId = null },
                            onPlayAll = {
                                if (activePlaylistTracks.isNotEmpty()) {
                                    onPlayQueue(activePlaylistTracks.first(), activePlaylistTracks)
                                }
                            },
                            onAddTracks = { addingTracksPlaylistId = activePlaylist.id },
                            onRename = { renamingPlaylist = activePlaylist },
                            onDelete = { deletingPlaylist = activePlaylist },
                        )
                    }
                    if (activePlaylistTracks.isEmpty()) {
                        item(key = "playlist-empty") {
                            EmptyMessage(
                                if (query.isBlank()) "Playlist masih kosong" else "Tidak ada lagu yang cocok",
                                if (query.isBlank()) "Tambahkan lagu dari pustaka Anda ke playlist ini."
                                else "Coba kata pencarian lain atau hapus filter pencarian.",
                                UiIcons.Queue,
                            ) {
                                if (query.isBlank()) {
                                    Button(
                                        onClick = { addingTracksPlaylistId = activePlaylist.id },
                                        enabled = tracks.isNotEmpty() && !organizerBusy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Tambah lagu") }
                                } else {
                                    TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("Hapus pencarian")
                                    }
                                }
                            }
                        }
                    } else {
                        items(activePlaylistTracks, key = { "pl:${activePlaylist.id}:${it.id}" }) { track ->
                            LibraryAudioRow(
                                track = track,
                                selected = track.id.toString() == currentId,
                                connected = connected,
                                renameEnabled = !organizerBusy,
                                onPlay = { onPlayQueue(track, activePlaylistTracks) },
                                onRename = { onRename(track) },
                                onAddToPlaylist = { pickingPlaylistTrack = track },
                                onRemoveFromPlaylist = {
                                    playlistActions.onRemoveTrackFromPlaylist(activePlaylist.id, track.id)
                                },
                                onDelete = { onDelete(track) },
                            )
                        }
                    }
                } else {
                    if (visiblePlaylists.isEmpty()) {
                        item(key = "playlists-empty") {
                            if (playlists.isEmpty()) {
                                EmptyMessage(
                                    "Belum ada playlist",
                                    "Buat playlist untuk mengelompokkan lagu sesuai suasana atau kebutuhan.",
                                    UiIcons.Queue,
                                ) {
                                    Button(
                                        onClick = { creatingPlaylist = true },
                                        enabled = !organizerBusy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Buat playlist") }
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
                            PlaylistSummaryRow(
                                playlist = playlist,
                                trackCount = plTracks.size,
                                totalDurationMs = plDuration,
                                connected = connected,
                                busy = organizerBusy,
                                onOpen = { selectedPlaylistId = playlist.id },
                                onPlayAll = { if (plTracks.isNotEmpty()) onPlayQueue(plTracks.first(), plTracks) },
                                onAddTracks = { addingTracksPlaylistId = playlist.id },
                                onRename = { renamingPlaylist = playlist },
                                onDelete = { deletingPlaylist = playlist },
                            )
                        }
                    }
                }
            }
            LibraryGroupMode.ARTIST, LibraryGroupMode.ALBUM, LibraryGroupMode.FORMAT -> {
                if (groupedTracks.isEmpty()) item(key = "grouped-empty") {
                    LibraryEmptyPlaceholder(
                        hasTracks = tracks.isNotEmpty(),
                        onAdd = onAdd,
                        onClearQuery = { query = "" },
                    )
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
                                onPlayAll = { if (group.items.isNotEmpty()) onPlayQueue(group.items.first(), group.items) },
                            )
                        }
                        if (!collapsed) {
                            items(group.items, key = { "grp:${groupMode.name}:${group.key}:${it.id}" }) { track ->
                                LibraryAudioRow(
                                    track = track,
                                    selected = track.id.toString() == currentId,
                                    connected = connected,
                                    renameEnabled = !organizerBusy,
                                    onPlay = { onPlayQueue(track, group.items) },
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
    }

    if (creatingPlaylist) {
        CreateOrRenamePlaylistDialog(
            title = "Buat playlist baru",
            confirmLabel = "Buat",
            busy = organizerBusy,
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                playlistActions.onCreatePlaylist(name, null)
                groupModeName = LibraryGroupMode.PLAYLIST.name
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

private fun matchesTrackQuery(track: AudioTrack, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return track.displayName.contains(q, ignoreCase = true) ||
        UiPresentation.title(track.displayName).contains(q, ignoreCase = true) ||
        (track.artist?.contains(q, ignoreCase = true) == true) ||
        (track.album?.contains(q, ignoreCase = true) == true) ||
        (track.genre?.contains(q, ignoreCase = true) == true)
}

private fun filterLibraryTracks(tracks: List<AudioTrack>, query: String): List<AudioTrack> {
    val q = query.trim()
    return if (q.isEmpty()) tracks else tracks.filter { matchesTrackQuery(it, q) }
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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

@Composable
private fun PlaylistSummaryRow(
    playlist: LibraryPlaylist,
    trackCount: Int,
    totalDurationMs: Long,
    connected: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onPlayAll: () -> Unit,
    onAddTracks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(UiIcons.Queue, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    UiPresentation.groupSummary(trackCount, totalDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connected && trackCount > 0) {
                GlyphButton(UiIcons.Play, "Putar playlist ${playlist.name}", onPlayAll, enabled = !busy)
            }
            Box {
                GlyphButton(UiIcons.More, "Menu playlist ${playlist.name}", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Tambah lagu") }, leadingIcon = { Icon(UiIcons.Plus, null) },
                        enabled = !busy, onClick = { menu = false; onAddTracks() })
                    DropdownMenuItem(text = { Text("Ganti nama") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                        enabled = !busy, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(UiIcons.Delete, null) },
                        enabled = !busy, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailHeader(
    playlist: LibraryPlaylist,
    tracks: List<AudioTrack>,
    connected: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onAddTracks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val totalMs = remember(tracks) { tracks.totalDurationMs() }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(UiIcons.Back, "Kembali ke daftar playlist", onBack)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        UiPresentation.groupSummary(tracks.size, totalMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    GlyphButton(UiIcons.More, "Opsi playlist", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Ganti nama playlist") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = !busy, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(UiIcons.Delete, null) },
                            enabled = !busy, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlayAll,
                    enabled = connected && tracks.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(UiIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Putar")
                }
                OutlinedButton(
                    onClick = onAddTracks,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(UiIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tambah lagu")
                }
            }
        }
    }
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun LibraryAudioRow(
    track: AudioTrack,
    selected: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { this.selected = selected },
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(enabled = connected, onClickLabel = "Putar ${UiPresentation.title(track.displayName)}",
                    onClick = onPlay).padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackArtwork(track.artworkKey, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(UiPresentation.title(track.displayName), style = MaterialTheme.typography.titleSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(UiPresentation.trackSubtitle(track.displayName, track.artist, track.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    GlyphButton(UiIcons.More, "Menu audio ${UiPresentation.title(track.displayName)}", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Masukkan ke playlist…") }, leadingIcon = { Icon(UiIcons.Queue, null) },
                            enabled = renameEnabled, onClick = { menu = false; onAddToPlaylist() })
                        if (onRemoveFromPlaylist != null) {
                            DropdownMenuItem(text = { Text("Keluarkan dari playlist") }, leadingIcon = { Icon(UiIcons.Close, null) },
                                enabled = renameEnabled, onClick = { menu = false; onRemoveFromPlaylist() })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = renameEnabled, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(UiIcons.Delete, null) },
                            enabled = renameEnabled, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (!selected) HorizontalDivider(Modifier.padding(start = 78.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        }
    }
}
