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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

data class SearchActions(
    val preview: PreviewState,
    val search: (String, SearchProvider, SearchKind, SearchSort) -> Unit,
    val switchSearch: (String, SearchProvider, SearchKind, SearchSort) -> Unit = search,
    val sort: (SearchSort) -> Unit,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
    val openPlaylist: (SearchHit) -> Unit,
    val openPlaylistUrl: (String) -> Unit = {},
    val leavePlaylist: () -> Unit,
    val previewSong: (SearchHit) -> Unit,
    val stopPreview: () -> Unit,
    val previewError: () -> Unit,
)

private enum class HitLibraryStatus { NONE, QUEUED, SAVED }

private enum class AddAudioSourceTab(val label: String) {
    YOUTUBE_MUSIC("YT Music"),
    YOUTUBE("YouTube"),
    LOCAL_FILE("File HP"),
}

/** Catalog search, 1-tap public link/song download, and local file import aligned with Library UX. */
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
    downloadedVideoIds: Set<String> = emptySet(),
    tracks: List<AudioTrack> = emptyList(),
    onOpenDownloads: () -> Unit = {},
    activeDownloadCount: Int = 0,
    failedDownloadCount: Int = 0,
    onBack: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    var sourceTabName by rememberSaveable {
        mutableStateOf(
            if (search.provider == SearchProvider.YOUTUBE) AddAudioSourceTab.YOUTUBE.name
            else AddAudioSourceTab.YOUTUBE_MUSIC.name,
        )
    }
    var kindName by rememberSaveable { mutableStateOf(search.kind.name) }
    var recentQueries by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var linkDialog by remember { mutableStateOf(false) }
    var initialLinkUrl by remember { mutableStateOf("") }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var quickToast by remember { mutableStateOf<String?>(null) }
    var dismissedLocalFinishKey by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistThumbnail by rememberSaveable { mutableStateOf<String?>(null) }

    val sourceTab = AddAudioSourceTab.entries.firstOrNull { it.name == sourceTabName } ?: AddAudioSourceTab.YOUTUBE_MUSIC
    val provider = if (sourceTab == AddAudioSourceTab.YOUTUBE) SearchProvider.YOUTUBE else SearchProvider.YOUTUBE_MUSIC
    val kind = SearchKind.entries.firstOrNull { it.name == kindName } ?: SearchKind.SONGS
    val controlsEnabled = !ingest.engineBusy && !upgrade.operationBusy
    val idle = controlsEnabled && !search.busy && !actions.preview.loading
    val importIdle = idle && !ingest.busy
    val queueIdle = !upgrade.operationBusy && !search.busy && !actions.preview.loading
    val validQuery = remember(query) { runCatching { YoutubeSearchRules.validatedQuery(query) }.isSuccess }
    val detectedUrl = remember(query) { runCatching { DownloadQueueRules.validatedPublicSourceUrl(query) }.getOrNull() }
    val detectedPlaylistUrl = remember(query) { runCatching { DownloadQueueRules.validatedPublicPlaylistUrl(query) }.getOrNull() }
    val keyboard = LocalSoftwareKeyboardController.current
    val resultScroll = rememberLazyListState()
    val playlistScroll = rememberLazyListState()
    val inPlaylist = search.playlistId != null
    val sorted = remember(search.results, search.sort) { YoutubeSearchRules.sorted(search.results, search.sort) }
    val loadedSongs = remember(sorted) { sorted.filter { it.kind == SearchKind.SONGS } }
    val selectedSongs = remember(loadedSongs, selectedIds) { loadedSongs.filter { it.videoId in selectedIds } }
    val recentLocalTracks = remember(tracks) { tracks.take(8) }
    var pendingPlaylistAutoDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    val previewQualities = remember { mutableStateMapOf<String, AudioStreamQuality>() }
    LaunchedEffect(
        actions.preview.hit?.videoId,
        actions.preview.bitrateKbps,
        actions.preview.audioCodec,
        actions.preview.media?.bitrateKbps,
        actions.preview.media?.audioCodec,
    ) {
        val id = actions.preview.hit?.videoId
        val kbps = (actions.preview.bitrateKbps ?: actions.preview.media?.bitrateKbps)?.takeIf { it > 0 }
        val codec = actions.preview.audioCodec ?: actions.preview.media?.audioCodec
        if (id != null && kbps != null) {
            previewQualities[id] = AudioStreamQuality(kbps, codec)
        }
    }

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
    val downloadablePlaylistSongs = remember(loadedSongs, queuedUrls, savedUrls, downloadedVideoIds) {
        loadedSongs.filter { hit ->
            hit.url !in queuedUrls && hit.url !in savedUrls && hit.videoId !in downloadedVideoIds
        }.take(YoutubeSearchRules.MAX_RESULTS)
    }

    val leave = {
        pendingPlaylistAutoDownloadId = null
        actions.leavePlaylist()
    }
    val openPlaylistFromUrl: (String, Boolean) -> Unit = { rawPlaylistUrl, downloadAll ->
        val pid = runCatching { DownloadQueueRules.validatedPublicPlaylistId(rawPlaylistUrl) }.getOrNull()
        if (pid != null && idle) {
            keyboard?.hide()
            selectionMode = false
            selectedIds = arrayListOf()
            playlistChannel = null
            playlistThumbnail = null
            pendingPlaylistAutoDownloadId = if (downloadAll) pid else null
            actions.stopPreview()
            actions.openPlaylistUrl(pid)
        }
    }
    LaunchedEffect(inPlaylist, search.playlistId, search.busy, search.error, loadedSongs.size) {
        val pendingId = pendingPlaylistAutoDownloadId ?: return@LaunchedEffect
        if (search.playlistId == pendingId && !search.busy) {
            pendingPlaylistAutoDownloadId = null
            if (search.error == null) {
                val targets = downloadablePlaylistSongs
                if (targets.isNotEmpty() && !upgrade.operationBusy) {
                    onDownloadBatch(targets, true)
                    quickToast = "${targets.size} lagu playlist masuk antrean"
                } else if (loadedSongs.isNotEmpty()) {
                    quickToast = "Semua lagu playlist sudah tersimpan atau ada di antrean"
                }
            }
        }
    }
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
    val switchSourceTab: (AddAudioSourceTab, SearchKind) -> Unit = { nextTab, nextKind ->
        sourceTabName = nextTab.name
        kindName = nextKind.name
        if (nextTab == AddAudioSourceTab.LOCAL_FILE) {
            keyboard?.hide()
            selectionMode = false
            selectedIds = arrayListOf()
        } else {
            val nextProvider = if (nextTab == AddAudioSourceTab.YOUTUBE) SearchProvider.YOUTUBE else SearchProvider.YOUTUBE_MUSIC
            val nextSort = if (nextKind == SearchKind.PLAYLISTS &&
                (search.sort == SearchSort.DURATION_ASC || search.sort == SearchSort.DURATION_DESC)
            ) SearchSort.RELEVANCE else search.sort
            val activeQuery = query.takeIf { validQuery && detectedUrl == null && detectedPlaylistUrl == null }
                ?: search.query.takeIf { search.hasSearched && detectedUrl == null && detectedPlaylistUrl == null }
            val shouldSwitch = activeQuery != null && (
                nextProvider != search.provider ||
                    nextKind != search.kind ||
                    !search.hasSearched ||
                    activeQuery.trim() != search.query.trim() ||
                    search.error != null ||
                    search.busy
                )
            if (shouldSwitch) {
                if (query != activeQuery) query = activeQuery
                executeSearch(activeQuery, nextProvider, nextKind, nextSort, true)
            } else if (nextKind == SearchKind.PLAYLISTS && nextSort != search.sort) {
                actions.sort(nextSort)
            }
        }
    }
    val submit = { executeSearch(query, provider, kind, search.sort, !idle) }
    val toggle: (SearchHit) -> Unit = { hit ->
        if (hit.kind == SearchKind.SONGS) selectedIds = ArrayList(selectedIds.toMutableList().apply {
            if (!remove(hit.videoId)) add(hit.videoId)
        })
    }
    val openLinkModal: (String) -> Unit = { preset ->
        initialLinkUrl = preset
        linkDialog = true
    }
    val triggerLocalImport: () -> Unit = {
        sourceTabName = AddAudioSourceTab.LOCAL_FILE.name
        if (importIdle) onImport()
    }
    val enqueueSingleHit: (SearchHit) -> Unit = { hit ->
        if (queueIdle) {
            onDownloadBatch(listOf(hit), true)
            quickToast = "\"${hit.title}\" masuk antrean"
        }
    }

    LaunchedEffect(search.query, search.provider, search.playlistId) {
        selectionMode = false
        selectedIds = arrayListOf()
    }
    LaunchedEffect(search.playlistId, search.hasSearched, search.provider, search.kind) {
        if (search.playlistId == null && search.hasSearched && sourceTab != AddAudioSourceTab.LOCAL_FILE) {
            sourceTabName = if (search.provider == SearchProvider.YOUTUBE) {
                AddAudioSourceTab.YOUTUBE.name
            } else {
                AddAudioSourceTab.YOUTUBE_MUSIC.name
            }
            kindName = search.kind.name
        }
    }
    LaunchedEffect(quickToast) {
        if (quickToast != null) {
            delay(3_500L)
            quickToast = null
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
                    hasSongs = loadedSongs.isNotEmpty() && (inPlaylist || sourceTab != AddAudioSourceTab.LOCAL_FILE),
                    idle = idle,
                    importIdle = importIdle,
                    queueIdle = queueIdle,
                    activeDownloadCount = activeDownloadCount,
                    failedDownloadCount = failedDownloadCount,
                    onBack = onBack,
                    onOpenDownloads = onOpenDownloads,
                    onLeavePlaylist = leave,
                    onOpenLink = { openLinkModal((detectedUrl ?: detectedPlaylistUrl).orEmpty()) },
                    onOpenImport = triggerLocalImport,
                    onEnterSelection = { actions.stopPreview(); selectionMode = true },
                    onExitSelection = { selectionMode = false; selectedIds = arrayListOf() },
                    onSelectAll = { selectedIds = ArrayList(loadedSongs.map { it.videoId }) },
                    onClearSelection = { selectedIds = arrayListOf() },
                    onOpenSettings = onSettings,
                )
            }

            if ((ingest.busy && ingest.batchId != null) || (queuedUrls.isNotEmpty() && sourceTab != AddAudioSourceTab.LOCAL_FILE)) {
                item(key = "ingest", contentType = "banner") {
                    SearchIngestBanner(
                        stage = if (ingest.busy && ingest.stage.isNotBlank()) ingest.stage else "${queuedUrls.size} dalam antrean",
                        cancelable = ingest.cancelable,
                        onOpenDownloads = onOpenDownloads,
                        onCancel = onCancel,
                    )
                }
            }

            if (inPlaylist) {
                item(key = "playlist-context", contentType = "playlist-context") {
                    SearchPlaylistContextHeader(
                        title = search.playlistTitle.orEmpty(),
                        channel = playlistChannel ?: search.playlistChannel,
                        thumbnailUrl = playlistThumbnail ?: search.playlistThumbnailUrl,
                        songCount = loadedSongs.size,
                        totalCount = search.playlistTotalCount,
                        downloadableCount = downloadablePlaylistSongs.size,
                        loading = search.busy && !search.loadingMore,
                        queueIdle = queueIdle,
                        onDownloadAll = {
                            if (queueIdle && downloadablePlaylistSongs.isNotEmpty()) {
                                onDownloadBatch(downloadablePlaylistSongs, true)
                                quickToast = "${downloadablePlaylistSongs.size} lagu playlist masuk antrean"
                            }
                        },
                    )
                }
            }

            if (!inPlaylist && !selectionMode) {
                item(key = "source-and-query", contentType = "controls") {
                    SearchControlsPanel(
                        sourceTab = sourceTab,
                        query = query,
                        provider = provider,
                        kind = kind,
                        sort = search.sort,
                        controlsEnabled = controlsEnabled,
                        validQuery = validQuery,
                        detectedUrl = detectedUrl,
                        detectedPlaylistUrl = detectedPlaylistUrl,
                        recentQueries = recentQueries,
                        showRecent = !search.hasSearched && recentQueries.isNotEmpty() && detectedUrl == null && detectedPlaylistUrl == null,
                        hasResults = sorted.isNotEmpty(),
                        inPlaylist = inPlaylist,
                        searchBusy = search.busy,
                        queueIdle = queueIdle,
                        onSelectSourceTab = { nextTab -> switchSourceTab(nextTab, kind) },
                        onQueryChange = { query = it },
                        onClearQuery = { query = "" },
                        onSubmit = submit,
                        onSelectRecent = { term ->
                            query = term
                            executeSearch(term, provider, kind, search.sort, !idle)
                        },
                        onClearRecent = { recentQueries = arrayListOf() },
                        onSelectKind = { nextKind -> switchSourceTab(sourceTab, nextKind) },
                        onSort = actions.sort,
                        onDownloadDetectedUrl = { url ->
                            if (queueIdle) {
                                onDownload(url, true)
                                query = ""
                                quickToast = "Tautan masuk antrean"
                            }
                        },
                        onOpenDetectedPlaylist = { playlistUrl, downloadAll ->
                            query = ""
                            openPlaylistFromUrl(playlistUrl, downloadAll)
                        },
                    )
                }
            } else if (inPlaylist && !selectionMode && sorted.isNotEmpty()) {
                item(key = "playlist-sort", contentType = "controls") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sorted.size} lagu",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        SearchChoice(search.sort, SearchSort.entries, { it.label }, !search.busy, Modifier, actions.sort)
                    }
                }
            }

            if (!inPlaylist && sourceTab == AddAudioSourceTab.LOCAL_FILE) {
                item(key = "local-import-cta", contentType = "local-import") {
                    LocalImportActionBanner(
                        enabled = importIdle,
                        onPickFiles = triggerLocalImport,
                    )
                }
                val importFinishKey = if (!ingest.busy && !ingest.error && ingest.batchId == null && ingest.stage == "Impor selesai" && ingest.detail.isNotBlank()) {
                    "${ingest.stage}|${ingest.detail}"
                } else null
                if (ingest.busy && dismissedLocalFinishKey != null) {
                    dismissedLocalFinishKey = null
                }
                if (ingest.batchId == null && (ingest.busy || ingest.error || (importFinishKey != null && importFinishKey != dismissedLocalFinishKey))) {
                    item(key = "local-ingest-status", contentType = "banner") {
                        LocalIngestStatusBanner(
                            ingest = ingest,
                            onCancel = onCancel,
                            onDismiss = importFinishKey?.let { key -> { dismissedLocalFinishKey = key } },
                        )
                    }
                }
                item(key = "local-library-header", contentType = "section-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Tersimpan (${tracks.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onBack != null && tracks.isNotEmpty()) {
                            TextButton(
                                onClick = onBack,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 28.dp),
                            ) {
                                Text("Buka Pustaka ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (recentLocalTracks.isEmpty()) {
                    item(key = "local-empty", contentType = "empty") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = FohTheme.colors.surfaceCard,
                            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                        ) {
                            Text(
                                text = "Belum ada audio tersimpan di Pustaka.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                            )
                        }
                    }
                } else {
                    items(recentLocalTracks, key = { "local-${it.id}" }, contentType = { "local-track" }) { track ->
                        LocalSavedTrackRow(track = track)
                    }
                }
            } else {
                if (search.busy && !search.loadingMore) item(key = "loading", contentType = "banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = FohTheme.colors.surfaceCard,
                        border = BorderStroke(1.dp, FohTheme.colors.safeBorder.copy(alpha = 0.7f)),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FohSpinningIndicator(
                                    size = 20.dp,
                                    strokeWidth = 2.4.dp,
                                    color = FohTheme.colors.safeAccent,
                                )
                                Text(
                                    text = when {
                                        search.loadingMore -> "Memuat hasil berikutnya…"
                                        inPlaylist -> "Memuat playlist…"
                                        else -> "Mencari di ${search.provider.label}…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onCancelSearch) { Text("Batalkan") }
                            }
                            FohIndeterminateBar(
                                color = FohTheme.colors.safeAccent,
                                trackColor = FohTheme.colors.surfaceDeep,
                                height = 4.dp,
                            )
                        }
                    }
                }

                if (search.busy && sorted.isEmpty() && !search.loadingMore) {
                    items(4, key = { "search-skeleton-$it" }, contentType = { "skeleton" }) {
                        SearchSkeletonRow()
                    }
                }

                search.error?.let { error ->
                    item(key = "error", contentType = "banner") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = FohTheme.colors.alertBg,
                            border = BorderStroke(1.dp, FohTheme.colors.alertBorder),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = UiPresentation.friendlySearchError(error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FohTheme.colors.alertAccent,
                                    modifier = Modifier.weight(1f),
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
                            importIdle = importIdle,
                            queueIdle = queueIdle,
                            onOpenLink = { openLinkModal("") },
                            onOpenImport = triggerLocalImport,
                            onSwitchProviderSearch = { alt ->
                                val targetTab = if (alt == SearchProvider.YOUTUBE) {
                                    AddAudioSourceTab.YOUTUBE
                                } else {
                                    AddAudioSourceTab.YOUTUBE_MUSIC
                                }
                                if (validQuery) {
                                    switchSourceTab(targetTab, kind)
                                } else {
                                    sourceTabName = targetTab.name
                                }
                            },
                        )
                    }
                }

                items(sorted, key = { "${it.kind}-${it.videoId}" }, contentType = { "search-hit" }) { hit ->
                    val hitStatus = when {
                        hit.url in queuedUrls -> HitLibraryStatus.QUEUED
                        hit.url in savedUrls || hit.videoId in downloadedVideoIds -> HitLibraryStatus.SAVED
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
                    val hitQuality = if (hit.kind == SearchKind.SONGS) {
                        hit.bitrateKbps?.takeIf { it > 0 }?.let { AudioStreamQuality(it, hit.audioCodec) }
                            ?: previewQualities[hit.videoId]
                    } else null
                    CatalogResult(
                        hit = hit,
                        enabled = idle,
                        queueEnabled = queueIdle,
                        selectionMode = selectionMode,
                        selected = hit.videoId in selectedIds,
                        previewActive = previewThisHit,
                        previewLoading = previewLoading,
                        status = hitStatus,
                        quality = hitQuality,
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
                        onDownload = { enqueueSingleHit(hit) },
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (search.loadingMore) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = FohTheme.colors.surfaceCard,
                                border = BorderStroke(1.dp, FohTheme.colors.safeBorder.copy(alpha = 0.7f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FohSpinningIndicator(
                                        size = 18.dp,
                                        strokeWidth = 2.2.dp,
                                        color = FohTheme.colors.safeAccent,
                                    )
                                    Text(
                                        text = "Memuat hasil berikutnya…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = onCancelSearch) {
                                        Text("Batalkan")
                                    }
                                }
                            }
                            FohIndeterminateBar(
                                color = FohTheme.colors.safeAccent,
                                trackColor = FohTheme.colors.surfaceDeep,
                                height = 4.dp,
                            )
                        } else {
                            OutlinedButton(
                                onClick = actions.loadMore,
                                enabled = idle,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Tampilkan lebih banyak")
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Surface(
                color = FohTheme.colors.surfaceDeck,
                border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
            ) {
                Button(
                    onClick = {
                        if (queueIdle && selectedSongs.isNotEmpty()) {
                            val targets = selectedSongs.toList()
                            selectionMode = false
                            selectedIds = arrayListOf()
                            onDownloadBatch(targets, true)
                        }
                    },
                    enabled = queueIdle && selectedSongs.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp),
                ) {
                    Icon(UiIcons.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Unduh (${selectedSongs.size} Lagu)", fontWeight = FontWeight.Bold)
                }
            }
        } else if (quickToast != null) {
            Surface(
                onClick = {
                    quickToast = null
                    onOpenDownloads()
                },
                shape = RoundedCornerShape(14.dp),
                color = FohTheme.colors.safeBg,
                border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(UiIcons.Check, null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(16.dp))
                    Text(
                        text = quickToast.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Lihat ›",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = FohTheme.colors.safeAccent,
                    )
                }
            }
        }
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
                    quickToast = "Tautan masuk antrean"
                }
            },
            onOpenPlaylistUrl = { playlistUrl, downloadAll ->
                linkDialog = false
                query = ""
                openPlaylistFromUrl(playlistUrl, downloadAll)
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
    importIdle: Boolean = idle,
    queueIdle: Boolean = idle,
    activeDownloadCount: Int = 0,
    failedDownloadCount: Int = 0,
    onBack: (() -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
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
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactCircleButton(
                icon = UiIcons.Close,
                description = "Keluar mode pilih",
                onClick = onExitSelection,
                size = 36.dp,
                iconSize = 18.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Text(
                text = "$selectedCount dipilih",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FohTheme.colors.safeAccent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FohHeaderChipButton(
                label = "Semua",
                icon = UiIcons.Check,
                active = true,
                enabled = hasSongs,
                onClick = onSelectAll,
            )
            TextButton(
                onClick = onClearSelection,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Reset", style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
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
        } else if (onBack != null) {
            CompactCircleButton(
                icon = UiIcons.Back,
                description = "Kembali ke Pustaka",
                onClick = onBack,
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Text(
            text = if (inPlaylist) "Playlist" else "Tambah Lagu",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BadgedBox(
            badge = {
                if (activeDownloadCount > 0 || failedDownloadCount > 0) {
                    Badge(
                        containerColor = if (activeDownloadCount > 0) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.error,
                        contentColor = if (activeDownloadCount > 0) FohTheme.colors.surfaceDeep else MaterialTheme.colorScheme.onError,
                    ) {
                        Text("${if (activeDownloadCount > 0) activeDownloadCount else failedDownloadCount}")
                    }
                }
            },
        ) {
            CompactCircleButton(
                icon = UiIcons.Download,
                description = "Antrean unduhan",
                onClick = onOpenDownloads,
                size = 36.dp,
                iconSize = 19.dp,
                containerColor = if (activeDownloadCount > 0) FohTheme.colors.safeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (activeDownloadCount > 0) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box {
            CompactCircleButton(
                icon = UiIcons.More,
                description = "Menu Tambah Lagu",
                onClick = { menu = true },
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Pilih banyak lagu") },
                    enabled = hasSongs && queueIdle,
                    leadingIcon = { Icon(UiIcons.Check, null) },
                    onClick = { menu = false; onEnterSelection() },
                )
                DropdownMenuItem(
                    text = { Text("Unduh dari Link") },
                    enabled = queueIdle,
                    leadingIcon = { Icon(UiIcons.Link, null) },
                    onClick = { menu = false; onOpenLink() },
                )
                DropdownMenuItem(
                    text = { Text("Impor File HP") },
                    enabled = importIdle,
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
    sourceTab: AddAudioSourceTab,
    query: String,
    provider: SearchProvider,
    kind: SearchKind,
    sort: SearchSort,
    controlsEnabled: Boolean,
    validQuery: Boolean,
    detectedUrl: String?,
    detectedPlaylistUrl: String?,
    recentQueries: List<String>,
    showRecent: Boolean,
    hasResults: Boolean,
    inPlaylist: Boolean,
    searchBusy: Boolean,
    queueIdle: Boolean,
    onSelectSourceTab: (AddAudioSourceTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmit: () -> Unit,
    onSelectRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onSelectKind: (SearchKind) -> Unit,
    onSort: (SearchSort) -> Unit,
    onDownloadDetectedUrl: (String) -> Unit,
    onOpenDetectedPlaylist: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FohSegmentedBar(
            options = AddAudioSourceTab.entries,
            selected = sourceTab,
            label = { it.label },
            enabled = controlsEnabled,
            onSelect = onSelectSourceTab,
        )
        if (sourceTab == AddAudioSourceTab.LOCAL_FILE) return

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = controlsEnabled,
            placeholder = {
                Text(
                    "Cari di ${if (provider == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube"} atau tempel link…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
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
                        if (detectedUrl == null && detectedPlaylistUrl == null) {
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
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                when {
                    detectedUrl != null -> onDownloadDetectedUrl(detectedUrl)
                    detectedPlaylistUrl != null -> onOpenDetectedPlaylist(detectedPlaylistUrl, false)
                    else -> onSubmit()
                }
            }),
        )

        if (detectedUrl != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = FohTheme.colors.btBg,
                border = BorderStroke(1.dp, FohTheme.colors.btBorder),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(UiIcons.Link, contentDescription = null, tint = FohTheme.colors.btAccent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tautan siap diunduh", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FohTheme.colors.btAccent)
                        Text(detectedUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(
                        onClick = { onDownloadDetectedUrl(detectedUrl) },
                        enabled = queueIdle,
                        shape = RoundedCornerShape(999.dp),
                        color = FohTheme.colors.btAccent,
                    ) {
                        Text(
                            text = "Unduh",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = FohTheme.colors.surfaceDeep,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        } else if (detectedPlaylistUrl != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = FohTheme.colors.safeBg,
                border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(UiIcons.Link, contentDescription = null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Playlist YouTube terdeteksi", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FohTheme.colors.safeAccent)
                            Text(detectedPlaylistUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpenDetectedPlaylist(detectedPlaylistUrl, false) },
                            enabled = controlsEnabled && queueIdle && !searchBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("Lihat Lagu", maxLines = 1)
                        }
                        Button(
                            onClick = { onOpenDetectedPlaylist(detectedPlaylistUrl, true) },
                            enabled = controlsEnabled && queueIdle && !searchBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("Unduh Semua", maxLines = 1)
                        }
                    }
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
                if (hasResults) {
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
            }
        }
    }
}

/** Calm, accessible catalog row matching Library cards: tap row to preview, 1-tap button to download. */
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
    quality: AudioStreamQuality? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    onToggle: () -> Unit,
) {
    val highlighted = selected || previewActive
    Surface(
        color = if (highlighted) FohTheme.colors.safeBg else FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, if (highlighted) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .combinedClickable(
                    enabled = if (selectionMode) queueEnabled else (enabled || previewActive),
                    onClick = onTap,
                    onLongClick = if (queueEnabled) onLongPress else null,
                    onLongClickLabel = "Pilih lagu",
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(46.dp)) {
                YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)))
                if (hit.kind == SearchKind.SONGS && !selectionMode) {
                    if (previewLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.Black.copy(alpha = 0.52f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FohSpinningIndicator(
                                size = 20.dp,
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                val creatorLabel = if (hit.kind == SearchKind.SONGS) {
                    hit.artist?.takeIf { it.isNotBlank() } ?: channelLabel
                } else {
                    channelLabel
                }
                val albumLabel = if (hit.kind == SearchKind.SONGS) hit.album?.takeIf { it.isNotBlank() } else null
                Text(
                    text = listOfNotNull(creatorLabel, albumLabel, details).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val qualityLabel = if (hit.kind == SearchKind.SONGS) {
                    UiPresentation.searchQualityLabel(quality?.bitrateKbps, quality?.audioCodec)
                } else null
                if (qualityLabel != null || previewActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (qualityLabel != null && quality != null) {
                            val (qBg, qBorder, qAccent) = when {
                                quality.bitrateKbps >= 192 -> Triple(
                                    FohTheme.colors.safeBg,
                                    FohTheme.colors.safeBorder,
                                    FohTheme.colors.safeAccent,
                                )
                                quality.bitrateKbps < 96 -> Triple(
                                    FohTheme.colors.warnBg,
                                    FohTheme.colors.warnBorder,
                                    FohTheme.colors.warnAccent,
                                )
                                else -> Triple(
                                    FohTheme.colors.surfaceDeep,
                                    FohTheme.colors.borderSubtle,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LibraryMiniBadge(
                                label = qualityLabel,
                                containerColor = qBg,
                                borderColor = qBorder,
                                contentColor = qAccent,
                            )
                        }
                        if (previewActive) {
                            Text(
                                text = if (previewLoading) "MEMUAT" else "PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = FohTheme.colors.safeAccent,
                                maxLines = 1,
                            )
                        }
                    }
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
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FohTheme.colors.warnAccent,
                                )
                            }
                        }
                    }
                    HitLibraryStatus.SAVED -> {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = FohTheme.colors.safeBg,
                            border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = UiIcons.Check,
                                    contentDescription = "Tersimpan ${hit.title}",
                                    tint = FohTheme.colors.safeAccent,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    "Tersimpan",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FohTheme.colors.safeAccent,
                                )
                            }
                        }
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
                                    contentDescription = "Unduh ${hit.title}",
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
