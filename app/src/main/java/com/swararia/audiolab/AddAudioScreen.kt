package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

data class SearchActions(
    val preview: PreviewState,
    val search: (String, SearchProvider, SearchKind, SearchSort) -> Unit,
    val switchSearch: (String, SearchProvider, SearchKind, SearchSort) -> Unit = search,
    val sort: (SearchSort) -> Unit,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
    val openPlaylist: (SearchHit) -> Unit,
    val leavePlaylist: () -> Unit,
    val previewSong: (SearchHit) -> Unit,
    val stopPreview: () -> Unit,
    val previewError: () -> Unit,
)

private enum class HitLibraryStatus { NONE, QUEUED, SAVED }

/** Catalog search, public link download, and local file import screen aligned with Library UX. */
@Composable
fun AddAudioScreen(
    ingest: IngestState,
    search: YoutubeSearchState,
    controller: MediaController?,
    actions: SearchActions,
    modifier: Modifier,
    onDownload: (String, Boolean) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onCancelSearch: () -> Unit,
    onSettings: () -> Unit,
    upgrade: UpgradeUiActions,
    onDownloadBatch: (List<SearchHit>, Boolean) -> Unit,
    downloadedTrackIds: Set<Long> = emptySet(),
    onOpenDownloads: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    var providerName by rememberSaveable { mutableStateOf(search.provider.name) }
    var kindName by rememberSaveable { mutableStateOf(search.kind.name) }
    var recentQueries by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var linkDialog by remember { mutableStateOf(false) }
    var initialLinkUrl by remember { mutableStateOf("") }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pending by remember { mutableStateOf<List<SearchHit>?>(null) }
    var playlistChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistThumbnail by rememberSaveable { mutableStateOf<String?>(null) }

    val provider = SearchProvider.entries.firstOrNull { it.name == providerName } ?: SearchProvider.YOUTUBE_MUSIC
    val kind = SearchKind.entries.firstOrNull { it.name == kindName } ?: SearchKind.SONGS
    val controlsEnabled = !ingest.busy && !upgrade.operationBusy
    val idle = controlsEnabled && !search.busy && !actions.preview.loading
    val validQuery = remember(query) { runCatching { YoutubeSearchRules.validatedQuery(query) }.isSuccess }
    val detectedUrl = remember(query) { runCatching { DownloadQueueRules.validatedPublicSourceUrl(query) }.getOrNull() }
    val keyboard = LocalSoftwareKeyboardController.current
    val resultScroll = rememberLazyListState()
    val playlistScroll = rememberLazyListState()
    val inPlaylist = search.playlistId != null
    val sorted = remember(search.results, search.sort) { YoutubeSearchRules.sorted(search.results, search.sort) }
    val loadedSongs = remember(sorted) { sorted.filter { it.kind == SearchKind.SONGS } }
    val selectedSongs = remember(loadedSongs, selectedIds) { loadedSongs.filter { it.videoId in selectedIds } }

    val queuedUrls = remember(upgrade.jobs) {
        upgrade.jobs.asSequence()
            .filter { it.status == "QUEUED" || it.status == "RUNNING" }
            .mapTo(HashSet()) { it.sourceUrl }
    }
    val savedUrls = remember(upgrade.jobs, downloadedTrackIds) {
        upgrade.jobs.asSequence()
            .filter { it.status == "COMPLETED" && it.trackId != null && it.trackId in downloadedTrackIds }
            .mapTo(HashSet()) { it.sourceUrl }
    }

    val leave = { actions.leavePlaylist() }
    val executeSearch: (String, SearchProvider, SearchKind, SearchSort, Boolean) -> Unit = { q, targetProvider, targetKind, targetSort, preferSwitch ->
        val valid = runCatching { YoutubeSearchRules.validatedQuery(q) }.isSuccess
        if (controlsEnabled && valid && (preferSwitch || idle)) {
            keyboard?.hide()
            selectionMode = false
            selectedIds = arrayListOf()
            recentQueries = ArrayList(UiPresentation.recordRecentQuery(recentQueries, q))
            if (preferSwitch) {
                actions.switchSearch(q, targetProvider, targetKind, targetSort)
            } else {
                actions.stopPreview()
                actions.search(q, targetProvider, targetKind, targetSort)
            }
        }
    }
    val switchTabSearch: (SearchProvider, SearchKind) -> Unit = { nextProvider, nextKind ->
        providerName = nextProvider.name
        kindName = nextKind.name
        val nextSort = if (nextKind == SearchKind.PLAYLISTS &&
            (search.sort == SearchSort.DURATION_ASC || search.sort == SearchSort.DURATION_DESC)
        ) SearchSort.RELEVANCE else search.sort
        val activeQuery = query.takeIf { validQuery && detectedUrl == null }
            ?: search.query.takeIf { search.hasSearched && detectedUrl == null }
        val shouldSwitch = activeQuery != null && (
            nextProvider != search.provider ||
                nextKind != search.kind ||
                !search.hasSearched ||
                activeQuery.trim() != search.query.trim() ||
                search.error != null ||
                search.busy
            )
        if (shouldSwitch && activeQuery != null) {
            if (query != activeQuery) query = activeQuery
            executeSearch(activeQuery, nextProvider, nextKind, nextSort, true)
        } else if (nextKind == SearchKind.PLAYLISTS && nextSort != search.sort) {
            actions.sort(nextSort)
        }
    }
    val submit = { executeSearch(query, provider, kind, search.sort, !idle) }
    val toggle: (SearchHit) -> Unit = { hit ->
        if (hit.kind == SearchKind.SONGS) selectedIds = ArrayList(selectedIds.toMutableList().apply {
            if (!remove(hit.videoId)) add(hit.videoId)
        })
    }
    val openLinkModal: (String) -> Unit = { preset ->
        actions.stopPreview()
        initialLinkUrl = preset
        linkDialog = true
    }

    LaunchedEffect(search.query, search.provider, search.playlistId) {
        selectionMode = false
        selectedIds = arrayListOf()
        pending = null
    }
    LaunchedEffect(search.playlistId, search.hasSearched, search.provider, search.kind) {
        if (search.playlistId == null && search.hasSearched) {
            providerName = search.provider.name
            kindName = search.kind.name
        }
    }
    BackHandler(enabled = selectionMode || inPlaylist) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = arrayListOf()
        } else {
            leave()
        }
    }

    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = if (inPlaylist) playlistScroll else resultScroll,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", contentType = "header") {
                SearchTopHeader(
                    inPlaylist = inPlaylist,
                    selectionMode = selectionMode,
                    selectedCount = selectedSongs.size,
                    hasSongs = loadedSongs.isNotEmpty(),
                    idle = idle,
                    queueIdle = !upgrade.operationBusy && !search.busy && !actions.preview.loading,
                    onLeavePlaylist = leave,
                    onOpenLink = { openLinkModal(detectedUrl.orEmpty()) },
                    onOpenImport = onImport,
                    onEnterSelection = { actions.stopPreview(); selectionMode = true },
                    onExitSelection = { selectionMode = false; selectedIds = arrayListOf() },
                    onSelectAll = { selectedIds = ArrayList(loadedSongs.map { it.videoId }) },
                    onClearSelection = { selectedIds = arrayListOf() },
                    onOpenSettings = onSettings,
                )
            }
            if (ingest.busy || queuedUrls.isNotEmpty()) item(key = "ingest", contentType = "banner") {
                SearchIngestBanner(
                    stage = if (ingest.busy && ingest.stage.isNotBlank()) ingest.stage else "Menyiapkan antrean unduhan (${queuedUrls.size})…",
                    cancelable = ingest.cancelable,
                    onOpenDownloads = onOpenDownloads,
                    onCancel = onCancel,
                )
            }
            if (inPlaylist) item(key = "playlist-context", contentType = "playlist-context") {
                SearchPlaylistContextHeader(
                    title = search.playlistTitle.orEmpty(),
                    channel = playlistChannel,
                    thumbnailUrl = playlistThumbnail,
                    songCount = loadedSongs.size,
                )
            }
            if (!inPlaylist && !selectionMode) {
                item(key = "source-and-query", contentType = "controls") {
                    SearchControlsPanel(
                        query = query,
                        provider = provider,
                        kind = kind,
                        sort = search.sort,
                        controlsEnabled = controlsEnabled,
                        idle = idle,
                        validQuery = validQuery,
                        detectedUrl = detectedUrl,
                        recentQueries = recentQueries,
                        showRecent = !search.hasSearched && recentQueries.isNotEmpty() && detectedUrl == null,
                        resultCount = sorted.size,
                        showResultsHeader = search.hasSearched && sorted.isNotEmpty(),
                        inPlaylist = inPlaylist,
                        searchBusy = search.busy,
                        onQueryChange = { query = it },
                        onClearQuery = { query = "" },
                        onSubmit = submit,
                        onSelectRecent = { term ->
                            query = term
                            executeSearch(term, provider, kind, search.sort, !idle)
                        },
                        onClearRecent = { recentQueries = arrayListOf() },
                        onSelectProvider = { nextProvider -> switchTabSearch(nextProvider, kind) },
                        onSelectKind = { nextKind -> switchTabSearch(provider, nextKind) },
                        onSort = actions.sort,
                        onOpenDetectedUrl = { url -> openLinkModal(url) },
                    )
                }
            } else if (inPlaylist && !selectionMode && sorted.isNotEmpty()) {
                item(key = "playlist-sort", contentType = "controls") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sorted.size} lagu",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        SearchChoice(search.sort, SearchSort.entries, { it.label }, !search.busy, Modifier, actions.sort)
                    }
                }
            }
            if (search.busy) item(key = "loading", contentType = "banner") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = FohTheme.colors.surfaceCard,
                    border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicatorCompact()
                        Text(
                            text = when {
                                search.loadingMore -> "Memuat hasil berikutnya…"
                                inPlaylist -> "Memuat isi playlist…"
                                else -> "Mencari ${search.kind.label.lowercase()} di ${search.provider.label}…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelSearch) { Text("Batalkan") }
                    }
                }
            }
            search.error?.let { error ->
                item(key = "error", contentType = "banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = FohTheme.colors.alertBg,
                        border = BorderStroke(1.dp, FohTheme.colors.alertBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = UiPresentation.friendlySearchError(error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FohTheme.colors.alertAccent,
                            )
                            TextButton(onClick = actions.retry, enabled = idle) { Text("Coba lagi") }
                        }
                    }
                }
            }
            if (!search.busy && sorted.isEmpty() && detectedUrl == null) {
                item(key = "empty", contentType = "empty") {
                    SearchEmptyState(
                        hasSearched = search.hasSearched,
                        provider = provider,
                        idle = idle,
                        onOpenLink = { openLinkModal("") },
                        onOpenImport = onImport,
                        onSwitchProviderSearch = { alt ->
                            if (validQuery) {
                                switchTabSearch(alt, kind)
                            } else {
                                providerName = alt.name
                            }
                        },
                    )
                }
            }
            items(sorted, key = { "${it.kind}-${it.videoId}" }, contentType = { "search-hit" }) { hit ->
                val hitStatus = when {
                    hit.url in queuedUrls -> HitLibraryStatus.QUEUED
                    hit.url in savedUrls -> HitLibraryStatus.SAVED
                    else -> HitLibraryStatus.NONE
                }
                val previewThisHit = actions.preview.hit?.videoId == hit.videoId &&
                    actions.preview.error == null &&
                    (actions.preview.loading || actions.preview.media != null || actions.preview.phase.isNotEmpty())
                val previewLoading = previewThisHit && (
                    actions.preview.loading ||
                        actions.preview.canceling ||
                        actions.preview.phase in setOf("resolving", "connecting", "buffering")
                    )
                val queueIdle = !upgrade.operationBusy && !search.busy && !actions.preview.loading
                CatalogResult(
                    hit = hit,
                    enabled = idle,
                    queueEnabled = queueIdle,
                    selectionMode = selectionMode,
                    selected = hit.videoId in selectedIds,
                    previewActive = previewThisHit,
                    previewLoading = previewLoading,
                    status = hitStatus,
                    onTap = {
                        if (selectionMode) toggle(hit)
                        else if (hit.kind == SearchKind.PLAYLISTS) {
                            actions.stopPreview()
                            playlistChannel = hit.channel
                            playlistThumbnail = hit.thumbnailUrl
                            actions.openPlaylist(hit)
                        } else if (previewThisHit) {
                            actions.stopPreview()
                        } else {
                            actions.previewSong(hit)
                        }
                    },
                    onLongPress = {
                        if (hit.kind == SearchKind.SONGS) {
                            actions.stopPreview()
                            selectionMode = true
                            toggle(hit)
                        }
                    },
                    onDownload = { pending = listOf(hit) },
                    onToggle = { toggle(hit) },
                )
            }
            if (search.incompleteCount > 0) item(key = "incomplete", contentType = "notice") {
                Text(
                    "${search.incompleteCount} hasil tidak lengkap disembunyikan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (search.hasMore) item(key = "more", contentType = "more") {
                OutlinedButton(
                    onClick = actions.loadMore,
                    enabled = idle,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (search.loadingMore) {
                        CircularProgressIndicatorCompact()
                        Spacer(Modifier.width(8.dp))
                        Text("Memuat…")
                    } else {
                        Text("Tampilkan lebih banyak")
                    }
                }
            }
        }
        if (selectionMode) {
            val queueIdle = !upgrade.operationBusy && !search.busy && !actions.preview.loading
            Button(
                onClick = { pending = selectedSongs.toList() },
                enabled = queueIdle && selectedSongs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
            ) {
                Icon(UiIcons.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download (${selectedSongs.size})")
            }
        }
    }

    val queueIdle = !upgrade.operationBusy && !search.busy && !actions.preview.loading
    pending?.let { hits ->
        DownloadConsentDialog(
            hits = hits,
            busy = !queueIdle,
            onDismiss = { pending = null },
            onConfirm = {
                if (queueIdle) {
                    pending = null
                    selectionMode = false
                    selectedIds = arrayListOf()
                    if (hits.size == 1) {
                        val hit = hits.single()
                        onDownloadBatch(listOf(hit), true)
                    } else {
                        onDownloadBatch(hits, true)
                    }
                }
            },
        )
    }
    if (linkDialog) {
        DownloadLinkDialog(
            busy = !queueIdle,
            initialUrl = initialLinkUrl,
            onDismiss = { linkDialog = false },
            onDownload = { url ->
                if (queueIdle) {
                    linkDialog = false
                    onDownload(url, true)
                }
            },
        )
    }
}

@Composable
private fun SearchTopHeader(
    inPlaylist: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    hasSongs: Boolean,
    idle: Boolean,
    queueIdle: Boolean = idle,
    onLeavePlaylist: () -> Unit,
    onOpenLink: () -> Unit,
    onOpenImport: () -> Unit,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    if (selectionMode) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GlyphButton(UiIcons.Close, "Keluar selection", onExitSelection)
            Text(
                "$selectedCount dipilih",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll, enabled = hasSongs) { Text("Select all") }
            TextButton(onClick = onClearSelection) { Text("Clear") }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (inPlaylist) {
            CompactCircleButton(
                icon = UiIcons.Back,
                description = "Kembali ke hasil Playlist",
                onClick = onLeavePlaylist,
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Text(
            text = if (inPlaylist) "Playlist" else "Search",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!inPlaylist) {
            FohHeaderChipButton(label = "Link", icon = UiIcons.Link, enabled = queueIdle, onClick = onOpenLink)
        }
        if (hasSongs) {
            FohHeaderChipButton(label = "Select", icon = UiIcons.Check, enabled = queueIdle, onClick = onEnterSelection)
        } else if (!inPlaylist) {
            FohHeaderChipButton(label = "Import", icon = UiIcons.Import, enabled = idle, onClick = onOpenImport)
        }
        Box {
            CompactCircleButton(
                icon = UiIcons.More,
                description = "Menu Search",
                onClick = { menu = true },
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Unduh dari Link") },
                    enabled = idle,
                    leadingIcon = { Icon(UiIcons.Link, null) },
                    onClick = { menu = false; onOpenLink() },
                )
                DropdownMenuItem(
                    text = { Text("Pilih File dari HP") },
                    enabled = idle,
                    leadingIcon = { Icon(UiIcons.Import, null) },
                    onClick = { menu = false; onOpenImport() },
                )
                DropdownMenuItem(
                    text = { Text("Pengaturan") },
                    leadingIcon = { Icon(UiIcons.Settings, null) },
                    onClick = { menu = false; onOpenSettings() },
                )
            }
        }
    }
}

@Composable
private fun SearchControlsPanel(
    query: String,
    provider: SearchProvider,
    kind: SearchKind,
    sort: SearchSort,
    controlsEnabled: Boolean,
    idle: Boolean,
    validQuery: Boolean,
    detectedUrl: String?,
    recentQueries: List<String>,
    showRecent: Boolean,
    resultCount: Int,
    showResultsHeader: Boolean,
    inPlaylist: Boolean,
    searchBusy: Boolean,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmit: () -> Unit,
    onSelectRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onSelectProvider: (SearchProvider) -> Unit,
    onSelectKind: (SearchKind) -> Unit,
    onSort: (SearchSort) -> Unit,
    onOpenDetectedUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FohSegmentedBar(
            options = SearchProvider.entries,
            selected = provider,
            label = { if (it == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube" },
            enabled = controlsEnabled,
            onSelect = onSelectProvider,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = controlsEnabled,
            placeholder = {
                Text(
                    "Cari lagu di ${if (provider == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube"}…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = FohTheme.colors.borderSubtle,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            leadingIcon = {
                if (searchBusy) {
                    CircularProgressIndicatorCompact()
                } else {
                    Icon(UiIcons.Search, null)
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                        IconButton(onClick = onClearQuery, enabled = controlsEnabled && !searchBusy) {
                            Icon(UiIcons.Close, contentDescription = "Hapus kata kunci", modifier = Modifier.size(18.dp))
                        }
                        Surface(
                            onClick = onSubmit,
                            enabled = controlsEnabled && validQuery,
                            shape = RoundedCornerShape(999.dp),
                            color = if (controlsEnabled && validQuery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            if (searchBusy) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                ) {
                                    FohSpinningIndicator(
                                        size = 15.dp,
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f),
                                    )
                                }
                            } else {
                                Text(
                                    text = "Cari",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (controlsEnabled && validQuery) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        if (detectedUrl != null) {
            Surface(
                onClick = { onOpenDetectedUrl(detectedUrl) },
                enabled = !searchBusy,
                shape = RoundedCornerShape(14.dp),
                color = FohTheme.colors.btBg,
                border = BorderStroke(1.dp, FohTheme.colors.btBorder),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(UiIcons.Link, contentDescription = null, tint = FohTheme.colors.btAccent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tautan YouTube terdeteksi", style = MaterialTheme.typography.labelLarge, color = FohTheme.colors.btAccent)
                        Text("Ketuk untuk unduh langsung jadi audio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(UiIcons.Download, contentDescription = "Download link", tint = FohTheme.colors.btAccent, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            if (showRecent) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Riwayat:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recentQueries.forEach { term ->
                        Surface(
                            onClick = { onSelectRecent(term) },
                            enabled = controlsEnabled,
                            shape = RoundedCornerShape(999.dp),
                            color = FohTheme.colors.surfaceCard,
                            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(UiIcons.History, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(term, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    TextButton(onClick = onClearRecent, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Hapus", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchKind.entries.forEach { option ->
                        FohFilterChip(
                            label = option.label,
                            selected = kind == option,
                            enabled = controlsEnabled,
                            onClick = { onSelectKind(option) },
                        )
                    }
                }
                val sorts = if (!inPlaylist && kind == SearchKind.PLAYLISTS) {
                    listOf(SearchSort.RELEVANCE, SearchSort.TITLE)
                } else {
                    SearchSort.entries
                }
                SearchChoice(
                    value = sort.takeIf { it in sorts } ?: SearchSort.RELEVANCE,
                    choices = sorts,
                    label = { it.label },
                    enabled = !searchBusy,
                    modifier = Modifier,
                    selected = onSort,
                )
            }
            if (showResultsHeader) {
                Text(
                    text = "$resultCount hasil ditemukan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchIngestBanner(
    stage: String,
    cancelable: Boolean,
    onOpenDownloads: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        onClick = onOpenDownloads,
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.safeBg,
        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicatorCompact()
            Column(Modifier.weight(1f)) {
                Text(stage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Pencarian baru menunggu proses selesai · Ketuk untuk lihat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cancelable) {
                TextButton(onClick = onCancel) { Text("Batalkan") }
            }
        }
    }
}

@Composable
private fun SearchPlaylistContextHeader(
    title: String,
    channel: String?,
    thumbnailUrl: String?,
    songCount: Int,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YoutubeThumbnail(thumbnailUrl, title, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Daftar putar", style = MaterialTheme.typography.labelSmall, color = FohTheme.colors.safeAccent)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(channel, "$songCount lagu dimuat").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchEmptyState(
    hasSearched: Boolean,
    provider: SearchProvider,
    idle: Boolean,
    onOpenLink: () -> Unit,
    onOpenImport: () -> Unit,
    onSwitchProviderSearch: (SearchProvider) -> Unit,
) {
    if (hasSearched) {
        val alternate = if (provider == SearchProvider.YOUTUBE_MUSIC) SearchProvider.YOUTUBE else SearchProvider.YOUTUBE_MUSIC
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = FohTheme.colors.surfaceCard,
            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Belum ada hasil yang cocok", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Coba ejaan lain atau cari langsung di tab ${if (alternate == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onSwitchProviderSearch(alternate) }, enabled = idle) {
                    Text("Cari di ${if (alternate == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube"}")
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "Cara cepat tambah audio",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        QuickEntrySurface(
            icon = UiIcons.Link,
            title = "Tempel Link YouTube / YT Music",
            subtitle = "Sudah menyalin tautan video? Unduh langsung jadi audio",
            accent = FohTheme.colors.safeAccent,
            iconBg = FohTheme.colors.safeBg,
            enabled = true,
            onClick = onOpenLink,
        )
        QuickEntrySurface(
            icon = UiIcons.Import,
            title = "Pilih File Audio dari HP",
            subtitle = "Ambil MP3, OPUS, WAV, atau FLAC dari penyimpanan HP",
            accent = MaterialTheme.colorScheme.secondary,
            iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
            enabled = idle,
            onClick = onOpenImport,
        )
        Text(
            "Tips: Setelah hasil muncul, ketuk satu lagu untuk dengar preview, atau tekan Select untuk mengunduh banyak sekaligus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun QuickEntrySurface(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    iconBg: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(UiIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Calm, accessible catalog row matching Library cards: tap row to preview, explicit button to download. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogResult(
    hit: SearchHit,
    enabled: Boolean,
    queueEnabled: Boolean = enabled,
    selectionMode: Boolean,
    selected: Boolean,
    previewActive: Boolean,
    previewLoading: Boolean,
    status: HitLibraryStatus,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    onToggle: () -> Unit,
) {
    val highlighted = selected || previewActive
    Surface(
        color = if (highlighted) FohTheme.colors.safeBg else FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, if (highlighted) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 74.dp)
                .combinedClickable(
                    enabled = if (selectionMode) queueEnabled else (enabled || previewActive),
                    onClick = onTap,
                    onLongClick = if (queueEnabled) onLongPress else null,
                    onLongClickLabel = "Pilih lagu",
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(48.dp)) {
                YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                if (hit.kind == SearchKind.SONGS && !selectionMode) {
                    if (previewLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.52f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FohSpinningIndicator(
                                size = 22.dp,
                                strokeWidth = 2.2.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.28f),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp)
                                .clip(CircleShape)
                                .background(if (previewActive) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.65f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (previewActive) UiIcons.Stop else UiIcons.Play,
                                contentDescription = if (previewActive) "Stop Preview" else "Preview ${hit.title}",
                                tint = if (previewActive) MaterialTheme.colorScheme.onPrimary else Color.White,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (highlighted) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = if (hit.kind == SearchKind.PLAYLISTS) {
                    hit.itemCount?.let { "$it lagu" } ?: "Playlist"
                } else {
                    hit.durationSeconds?.let { LibraryRules.durationLabel(it * 1000L) } ?: "—"
                }
                val channelLabel = hit.channel?.takeIf { it.isNotBlank() }?.let { ch ->
                    if (hit.channelIsVerified) "✓ $ch" else ch
                }
                Text(
                    text = listOfNotNull(channelLabel, details).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (previewActive || status != HitLibraryStatus.NONE) {
                    val badgeText = when {
                        previewLoading -> "Memuat preview… · Ketuk untuk batal"
                        previewActive -> "PREVIEW · Ketuk untuk berhenti"
                        status == HitLibraryStatus.SAVED -> "Sudah di Library"
                        else -> "Sedang diantrekan"
                    }
                    val badgeColor = when {
                        previewActive -> FohTheme.colors.safeAccent
                        status == HitLibraryStatus.SAVED -> FohTheme.colors.safeAccent
                        else -> FohTheme.colors.warnAccent
                    }
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor,
                        maxLines = 1,
                    )
                }
            }
            if (selectionMode && hit.kind == SearchKind.SONGS) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = queueEnabled)
            } else if (hit.kind == SearchKind.SONGS) {
                when (status) {
                    HitLibraryStatus.QUEUED -> {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = FohTheme.colors.warnBg,
                            border = BorderStroke(1.dp, FohTheme.colors.warnBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                FohSpinningIndicator(
                                    size = 12.dp,
                                    strokeWidth = 1.8.dp,
                                    color = FohTheme.colors.warnAccent,
                                    trackColor = FohTheme.colors.warnAccent.copy(alpha = 0.25f),
                                )
                                Text(
                                    "Antrean",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = FohTheme.colors.warnAccent,
                                )
                            }
                        }
                    }
                    HitLibraryStatus.SAVED -> {
                        GlyphButton(UiIcons.Download, "Unduh ulang ${hit.title}", onDownload, enabled = queueEnabled)
                    }
                    HitLibraryStatus.NONE -> {
                        Surface(
                            onClick = onDownload,
                            enabled = queueEnabled,
                            shape = CircleShape,
                            color = FohTheme.colors.safeBg,
                            border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = UiIcons.Download,
                                    contentDescription = "Download ${hit.title}",
                                    tint = if (queueEnabled) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                GlyphButton(UiIcons.ChevronRight, "Buka Playlist ${hit.title}", onTap, enabled = enabled)
            }
        }
    }
}

@Composable
private fun <T> SearchChoice(
    value: T,
    choices: List<T>,
    label: (T) -> String,
    enabled: Boolean,
    modifier: Modifier,
    selected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label(value),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
                Icon(
                    UiIcons.ChevronDown,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { expanded = false; selected(option) },
                    enabled = enabled,
                    trailingIcon = if (option == value) ({ Icon(UiIcons.Check, null) }) else null,
                )
            }
        }
    }
}
