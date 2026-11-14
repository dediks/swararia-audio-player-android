package com.swararia.audiolab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Locale

@Immutable
internal sealed interface LibraryDialog {
    data object CreatePlaylist : LibraryDialog
    data class RenamePlaylist(val playlist: LibraryPlaylist) : LibraryDialog
    data class DeletePlaylist(val playlist: LibraryPlaylist) : LibraryDialog
    data class PickPlaylistForTrack(val track: AudioTrack) : LibraryDialog
    data object BatchAddToPlaylist : LibraryDialog
    data class AddTracksToPlaylist(val playlistId: Long) : LibraryDialog
}

/**
 * Holds UI navigation, selection, filter/sort, and modal state for [LibraryPage].
 * Expensive lookups and playlist membership badges are derived once per state change
 * outside LazyColumn row items.
 */
@Stable
internal class LibraryBrowserState(
    initialTracks: List<AudioTrack>,
    initialPlaylists: List<LibraryPlaylist>,
    private val sectionNameState: MutableState<String>,
    private val queryState: MutableState<String>,
    private val alphabeticalState: MutableState<Boolean>,
    private val groupModeNameState: MutableState<String>,
    private val selectedPlaylistIdState: MutableState<Long?>,
    private val reorderModeState: MutableState<Boolean>,
    private val selectionModeState: MutableState<Boolean>,
) {
    var tracks by mutableStateOf(initialTracks)
    var playlists by mutableStateOf(initialPlaylists)

    var query: String
        get() = queryState.value
        set(value) { queryState.value = value }

    var alphabetical: Boolean
        get() = alphabeticalState.value
        set(value) { alphabeticalState.value = value }

    var selectedPlaylistId: Long?
        get() = selectedPlaylistIdState.value
        private set(value) { selectedPlaylistIdState.value = value }

    var reorderMode: Boolean
        get() = reorderModeState.value
        private set(value) { reorderModeState.value = value }

    var selectionMode: Boolean
        get() = selectionModeState.value
        private set(value) { selectionModeState.value = value }

    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    var collapsedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    var lastMovedTrackId by mutableStateOf<Long?>(null)
        private set

    var dialog by mutableStateOf<LibraryDialog?>(null)

    val section: LibrarySectionTab by derivedStateOf {
        val currentName = sectionNameState.value
        LibrarySectionTab.entries.firstOrNull { it.name == currentName } ?: LibrarySectionTab.ALL_TRACKS
    }

    val groupMode: LibraryGroupMode by derivedStateOf {
        val currentName = groupModeNameState.value
        LibraryGroupMode.entries.firstOrNull { it.name == currentName } ?: LibraryGroupMode.ALL
    }

    val activePlaylist: LibraryPlaylist? by derivedStateOf {
        val targetId = selectedPlaylistIdState.value ?: return@derivedStateOf null
        playlists.firstOrNull { it.id == targetId }
    }

    val trackById: Map<Long, AudioTrack> by derivedStateOf {
        tracks.associateBy { it.id }
    }

    val membershipByTrackId: Map<Long, List<String>> by derivedStateOf {
        val map = HashMap<Long, MutableList<String>>()
        for (pl in playlists) {
            for (tid in pl.trackIds) {
                map.getOrPut(tid) { mutableListOf() }.add(pl.name)
            }
        }
        map
    }

    val badgeByTrackId: Map<Long, String> by derivedStateOf {
        buildMap(membershipByTrackId.size) {
            for ((trackId, names) in membershipByTrackId) {
                UiPresentation.playlistMembershipBadge(names)?.let { put(trackId, it) }
            }
        }
    }

    private val matchingTracks: List<AudioTrack> by derivedStateOf {
        filterLibraryTracks(tracks, queryState.value, membershipByTrackId)
    }

    val sortedMatching: List<AudioTrack> by derivedStateOf {
        sortLibraryTracks(matchingTracks, alphabeticalState.value)
    }

    val activePlaylistTracks: List<AudioTrack> by derivedStateOf {
        val ids = activePlaylist?.trackIds.orEmpty()
        if (ids.isEmpty()) emptyList() else ids.mapNotNull(trackById::get)
    }

    val visiblePlaylists: List<LibraryPlaylist> by derivedStateOf {
        val q = queryState.value.trim()
        if (q.isEmpty()) playlists else playlists.filter { playlist ->
            playlist.name.contains(q, ignoreCase = true) ||
                playlist.trackIds.any { id -> trackById[id]?.let { matchesTrackQuery(it, q) } == true }
        }
    }

    val groupedTracks: List<LibraryTrackGroup<AudioTrack>> by derivedStateOf {
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

    fun selectSection(target: LibrarySectionTab) {
        sectionNameState.value = target.name
        if (target == LibrarySectionTab.PLAYLISTS) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            reorderMode = false
        }
    }

    fun selectGroupMode(mode: LibraryGroupMode) {
        groupModeNameState.value = mode.name
    }

    fun toggleSelectionMode() {
        if (selectionMode) exitSelectionMode() else selectionMode = true
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleTrackSelection(trackId: Long) {
        selectedIds = if (trackId in selectedIds) selectedIds - trackId else selectedIds + trackId
    }

    fun startSelectionWith(trackId: Long) {
        if (section == LibrarySectionTab.ALL_TRACKS) {
            selectionMode = true
            selectedIds = selectedIds + trackId
        }
    }

    fun toggleSelectAllVisible(visibleIds: List<Long>) {
        val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
        selectedIds = if (allSelected) emptySet() else visibleIds.toSet()
    }

    fun toggleGroupCollapsed(groupKey: String) {
        collapsedGroups = if (groupKey in collapsedGroups) collapsedGroups - groupKey else collapsedGroups + groupKey
    }

    fun openPlaylist(playlistId: Long) {
        reorderMode = false
        lastMovedTrackId = null
        selectedPlaylistId = playlistId
    }

    fun closePlaylist() {
        reorderMode = false
        lastMovedTrackId = null
        selectedPlaylistId = null
    }

    fun toggleReorderMode() {
        reorderMode = !reorderMode
    }

    fun markTrackMoved(trackId: Long) {
        lastMovedTrackId = trackId
    }

    internal fun clearLastMovedTrack() {
        lastMovedTrackId = null
    }

    internal fun pruneDeletedPlaylist() {
        if (selectedPlaylistId != null && playlists.none { it.id == selectedPlaylistId }) {
            selectedPlaylistId = null
            reorderMode = false
            lastMovedTrackId = null
        }
    }

    internal fun pruneDeletedTracks() {
        if (selectedIds.isEmpty()) return
        val validIds = tracks.mapTo(HashSet()) { it.id }
        val filtered = selectedIds.filterTo(HashSet()) { it in validIds }
        if (filtered != selectedIds) {
            selectedIds = filtered
            if (filtered.isEmpty()) selectionMode = false
        }
    }
}

@Composable
internal fun rememberLibraryBrowserState(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
): LibraryBrowserState {
    val sectionNameState = rememberSaveable { mutableStateOf(LibrarySectionTab.ALL_TRACKS.name) }
    val queryState = rememberSaveable { mutableStateOf("") }
    val alphabeticalState = rememberSaveable { mutableStateOf(false) }
    val groupModeNameState = rememberSaveable { mutableStateOf(LibraryGroupMode.ALL.name) }
    val selectedPlaylistIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val reorderModeState = rememberSaveable { mutableStateOf(false) }
    val selectionModeState = rememberSaveable { mutableStateOf(false) }

    val state = remember {
        LibraryBrowserState(
            initialTracks = tracks,
            initialPlaylists = playlists,
            sectionNameState = sectionNameState,
            queryState = queryState,
            alphabeticalState = alphabeticalState,
            groupModeNameState = groupModeNameState,
            selectedPlaylistIdState = selectedPlaylistIdState,
            reorderModeState = reorderModeState,
            selectionModeState = selectionModeState,
        )
    }
    if (state.tracks !== tracks) state.tracks = tracks
    if (state.playlists !== playlists) state.playlists = playlists

    LaunchedEffect(playlists, state.selectedPlaylistId) {
        state.pruneDeletedPlaylist()
    }
    LaunchedEffect(tracks) {
        state.pruneDeletedTracks()
    }
    LaunchedEffect(state.selectedPlaylistId) {
        state.clearLastMovedTrack()
    }
    LaunchedEffect(state.lastMovedTrackId, state.reorderMode) {
        if (!state.reorderMode) {
            state.clearLastMovedTrack()
        } else if (state.lastMovedTrackId != null) {
            delay(2200L)
            state.clearLastMovedTrack()
        }
    }
    return state
}

internal fun matchesTrackQuery(track: AudioTrack, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return track.displayName.contains(q, ignoreCase = true) ||
        UiPresentation.title(track.displayName).contains(q, ignoreCase = true) ||
        (track.artist?.contains(q, ignoreCase = true) == true) ||
        (track.album?.contains(q, ignoreCase = true) == true) ||
        (track.genre?.contains(q, ignoreCase = true) == true) ||
        LibraryRules.audioFormatLabel(track.displayName).contains(q, ignoreCase = true)
}

internal fun filterLibraryTracks(
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

internal fun sortLibraryTracks(tracks: List<AudioTrack>, alphabetical: Boolean): List<AudioTrack> =
    if (alphabetical) tracks.sortedWith(compareBy<AudioTrack> {
        UiPresentation.title(it.displayName).lowercase(Locale.ROOT)
    }.thenBy { it.id })
    else tracks.sortedWith(compareByDescending<AudioTrack> { it.addedAt }.thenByDescending { it.id })

internal fun List<AudioTrack>.totalDurationMs(): Long = sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) }
