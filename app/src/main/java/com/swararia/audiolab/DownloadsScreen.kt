package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DownloadsScreen(
    upgrade: UpgradeUiActions,
    ingest: IngestState,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist> = emptyList(),
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    ytMusic: YoutubeMusicAccountState = YoutubeMusicAccountState(),
    connected: Boolean,
    currentPlayingTrackId: String? = null,
    isPlayingActive: Boolean = false,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onCancelIngest: () -> Unit,
    onAddAudio: () -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
) {
    val jobs = upgrade.jobs
    var filterName by rememberSaveable { mutableStateOf(DownloadFilterTab.ALL.name) }
    val selectedFilter = DownloadFilterTab.entries.firstOrNull { it.name == filterName } ?: DownloadFilterTab.ALL
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var playlistPickerTracks by remember { mutableStateOf<List<AudioTrack>?>(null) }

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    val summary = remember(jobs) { UiPresentation.summarizeDownloadStatuses(jobs.map { it.status }) }
    val visibleJobs = remember(jobs, selectedFilter) {
        UiPresentation.filterDownloads(jobs, selectedFilter) { it.status }
    }
    val visibleIds = remember(visibleJobs) { visibleJobs.map { it.id }.toSet() }
    LaunchedEffect(visibleIds, selectionMode) {
        if (selectionMode) {
            selectedIds = selectedIds.filter { it in visibleIds }
        }
    }

    val activeJobs = remember(visibleJobs) {
        visibleJobs.filter { it.status == "RUNNING" || it.status == "QUEUED" }
    }
    val queuedPositionById = remember(activeJobs) {
        buildMap {
            var pos = 0
            for (job in activeJobs) {
                if (job.status == "QUEUED") put(job.id, pos++)
            }
        }
    }
    val historyJobs = remember(visibleJobs) {
        visibleJobs
            .filterNot { it.status == "RUNNING" || it.status == "QUEUED" }
            .sortedByDescending { it.createdAt }
    }

    val runningJob = jobs.firstOrNull { it.status == "RUNNING" }
    val running = runningJob != null
    val queued = summary.queued > 0
    val failed = summary.failed
    val finishedCount = summary.completed + summary.canceled
    val active = running || queued || (ingest.busy && ingest.batchId != null)
    val canDispatch = !upgrade.operationBusy
    val queuedBatchId = jobs.firstOrNull { it.status == "QUEUED" }?.batchId
    val failedBatchIds = jobs.filter { it.status == "FAILED" }.map { it.batchId }.distinct()
    val directIngestBanner = ingest.batchId == null && (ingest.busy || ingest.error)

    val selectedIdSet = remember(selectedIds) { selectedIds.toSet() }
    val selectedJobs = remember(visibleJobs, selectedIdSet) {
        visibleJobs.filter { it.id in selectedIdSet }
    }
    val toggleSelection: (Long) -> Unit = { jobId ->
        selectedIds = if (jobId in selectedIdSet) selectedIds.filterNot { it == jobId } else selectedIds + jobId
    }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedIds = emptyList()
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", contentType = "header") {
                DownloadsHeader(
                    selectionMode = selectionMode,
                    selectedCount = selectedJobs.size,
                    allSelected = visibleJobs.isNotEmpty() && selectedJobs.size == visibleJobs.size,
                    finishedCount = finishedCount,
                    hasJobs = jobs.isNotEmpty(),
                    running = running || (ingest.busy && ingest.batchId != null),
                    hasQueued = queued,
                    operationBusy = upgrade.operationBusy,
                    onEnterSelection = { selectionMode = true; selectedIds = emptyList() },
                    onExitSelection = exitSelection,
                    onToggleSelectAll = {
                        selectedIds = if (selectedJobs.size == visibleJobs.size) emptyList() else visibleJobs.map { it.id }
                    },
                    onHaltQueue = upgrade.onHaltQueue,
                    onContinueQueue = upgrade.onContinueQueue,
                    onClearFinished = upgrade.onClearFinished,
                    onAddAudio = onAddAudio,
                    onSettings = onSettings,
                )
            }

            if (directIngestBanner) {
                item(key = "direct-ingest", contentType = "banner") {
                    DirectOperationCard(ingest = ingest, onCancelIngest = onCancelIngest)
                }
            }

            if (jobs.isEmpty()) {
                if (!directIngestBanner) {
                    item(key = "empty", contentType = "empty") {
                        EmptyMessage(
                            title = "Belum ada antrean unduhan",
                            description = "Cari lagu atau tempel tautan YouTube untuk mulai mengunduh ke Library.",
                            icon = UiIcons.Download,
                        ) {
                            Button(onClick = onAddAudio, shape = RoundedCornerShape(14.dp)) {
                                Icon(UiIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Buka Search")
                            }
                        }
                    }
                }
            } else {
                item(key = "summary", contentType = "summary") {
                    DownloadsSummaryCard(
                        summary = summary,
                        ingest = ingest,
                        ytMusic = ytMusic,
                        settings = upgrade.downloadSettings,
                        runningJobId = runningJob?.id,
                        onSettings = onSettings,
                        onHaltQueue = upgrade.onHaltQueue,
                        onContinueQueue = upgrade.onContinueQueue,
                        onSkipCurrent = upgrade.onSkipCurrentJob,
                        onSetWifiOnly = upgrade.onSetWifiOnly,
                        onSetRateLimit = upgrade.onSetRateLimit,
                    )
                }

                item(key = "filters", contentType = "filters") {
                    DownloadFilterRow(
                        selected = selectedFilter,
                        summary = summary,
                        onSelect = { filterName = it.name },
                    )
                }

                if (visibleJobs.isEmpty()) {
                    item(key = "filtered-empty", contentType = "empty") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = FohTheme.colors.surfaceCard,
                            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.7f)),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    "Tidak ada item di filter ${selectedFilter.label}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { filterName = DownloadFilterTab.ALL.name }) {
                                    Text("Semua")
                                }
                            }
                        }
                    }
                } else {
                    if (activeJobs.isNotEmpty()) {
                        item(key = "section-active", contentType = "section-header") {
                            DownloadSectionHeader(
                                title = "Antrean Aktif (${activeJobs.size})",
                                accent = FohTheme.colors.safeAccent,
                                actionLabel = if (!running && !ingest.busy) "Lanjutkan" else null,
                                onAction = upgrade.onContinueQueue,
                            )
                        }
                        items(activeJobs, key = { it.id }, contentType = { "job" }) { job ->
                            val matchedTrack = job.trackId?.let(tracksById::get)
                            DownloadJobRow(
                                job = job,
                                matchedTrack = matchedTrack,
                                queuedPosition = queuedPositionById[job.id],
                                isCurrentlyPlayingTrack = matchedTrack != null && matchedTrack.id.toString() == currentPlayingTrackId,
                                isPlaybackActive = isPlayingActive,
                                selectionMode = selectionMode,
                                selected = job.id in selectedIdSet,
                                connected = connected,
                                canDispatch = canDispatch,
                                actionsBusy = upgrade.operationBusy,
                                onToggleSelect = { toggleSelection(job.id) },
                                onLongPress = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds = listOf(job.id)
                                    } else {
                                        toggleSelection(job.id)
                                    }
                                },
                                onSkipItem = { upgrade.onSkipCurrentJob(job.id) },
                                onMoveUpItem = { upgrade.onMoveQueuedUp(job.id) },
                                onMoveToFrontItem = { upgrade.onMoveQueuedToFront(job.id) },
                                onCancelItem = { upgrade.onCancelJob(job.id) },
                                onRetryItem = { upgrade.onRetryJob(job.id) },
                                onRequeueItem = { upgrade.onRequeueJob(job.id) },
                                onRemoveItem = { upgrade.onRemoveJob(job.id) },
                                onAddToPlaylist = { playlistPickerTracks = listOf(it) },
                                onPlayTrack = onPlayTrack,
                            )
                        }
                    }

                    items(historyJobs, key = { it.id }, contentType = { "job" }) { job ->
                        val matchedTrack = job.trackId?.let(tracksById::get)
                        DownloadJobRow(
                            job = job,
                            matchedTrack = matchedTrack,
                            queuedPosition = null,
                            isCurrentlyPlayingTrack = matchedTrack != null && matchedTrack.id.toString() == currentPlayingTrackId,
                            isPlaybackActive = isPlayingActive,
                            selectionMode = selectionMode,
                            selected = job.id in selectedIdSet,
                            connected = connected,
                            canDispatch = canDispatch,
                            actionsBusy = upgrade.operationBusy,
                            onToggleSelect = { toggleSelection(job.id) },
                            onLongPress = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = listOf(job.id)
                                } else {
                                    toggleSelection(job.id)
                                }
                            },
                            onSkipItem = { upgrade.onSkipCurrentJob(job.id) },
                            onMoveUpItem = { upgrade.onMoveQueuedUp(job.id) },
                            onMoveToFrontItem = { upgrade.onMoveQueuedToFront(job.id) },
                            onCancelItem = { upgrade.onCancelJob(job.id) },
                            onRetryItem = { upgrade.onRetryJob(job.id) },
                            onRequeueItem = { upgrade.onRequeueJob(job.id) },
                            onRemoveItem = { upgrade.onRemoveJob(job.id) },
                            onAddToPlaylist = { playlistPickerTracks = listOf(it) },
                            onPlayTrack = onPlayTrack,
                        )
                    }
                }
            }
        }

        if (selectionMode) {
            DownloadsBatchActionBar(
                selectedJobs = selectedJobs,
                tracksById = tracksById,
                busy = upgrade.operationBusy,
                onRetrySelected = { ids ->
                    exitSelection()
                    upgrade.onRequeueJobs(ids)
                },
                onCancelSelected = { ids ->
                    exitSelection()
                    upgrade.onCancelJobs(ids)
                },
                onAddToPlaylist = { selectedTracks ->
                    playlistPickerTracks = selectedTracks
                },
                onRemoveSelected = { ids ->
                    exitSelection()
                    upgrade.onRemoveJobs(ids)
                },
            )
        } else if (active || failed > 0 || (queued && !running && !ingest.busy)) {
            Surface(
                color = FohTheme.colors.surfaceDeck,
                border = BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        OutlinedButton(
                            onClick = upgrade.onCancelBatch,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Text("Cancel batch", maxLines = 1, softWrap = false)
                        }
                    }
                    if (failed > 0 || (queued && !running && !ingest.busy)) {
                        Button(
                            onClick = {
                                if (failed > 0) {
                                    if (failedBatchIds.size == 1) upgrade.onRetryFailed(failedBatchIds.single())
                                    else upgrade.onRetryAllFailed()
                                } else if (queuedBatchId != null) {
                                    upgrade.onContinueQueue()
                                }
                            },
                            enabled = canDispatch,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Icon(
                                imageVector = if (failed > 0) UiIcons.Refresh else UiIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (failed > 0) "Retry failed ($failed)" else "Start download",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    playlistPickerTracks?.let { targets ->
        BatchPlaylistPickerDialog(
            selectedTracks = targets,
            playlists = playlists,
            busy = upgrade.operationBusy,
            onDismiss = { playlistPickerTracks = null },
            onAddTracksToPlaylist = { playlistId, ids ->
                playlistActions.onAddTracksToPlaylist(playlistId, ids)
                playlistPickerTracks = null
                if (selectionMode) exitSelection()
            },
            onCreatePlaylistWithTracks = { name, ids ->
                playlistActions.onCreatePlaylistWithTracks(name, ids)
                playlistPickerTracks = null
                if (selectionMode) exitSelection()
            },
        )
    }
}

@Composable
private fun DownloadsHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    finishedCount: Int,
    hasJobs: Boolean,
    running: Boolean,
    hasQueued: Boolean,
    operationBusy: Boolean,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onHaltQueue: () -> Unit,
    onContinueQueue: () -> Unit,
    onClearFinished: () -> Unit,
    onAddAudio: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectionMode) {
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
                label = if (allSelected) "Reset" else "All",
                icon = UiIcons.Check,
                active = allSelected,
                enabled = !operationBusy,
                onClick = onToggleSelectAll,
            )
            CompactCircleButton(
                icon = UiIcons.Close,
                description = "Keluar mode pilih",
                onClick = onExitSelection,
                size = 36.dp,
                iconSize = 18.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasJobs) {
                FohHeaderChipButton(
                    label = "Select",
                    icon = UiIcons.Check,
                    enabled = !operationBusy,
                    onClick = onEnterSelection,
                )
            }
            CompactCircleButton(
                icon = UiIcons.Plus,
                description = "Tambah audio",
                onClick = onAddAudio,
                size = 36.dp,
                iconSize = 19.dp,
                containerColor = if (!hasJobs) FohTheme.colors.safeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (!hasJobs) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
            )
            Box {
                CompactCircleButton(
                    icon = UiIcons.More,
                    description = "Menu Downloads",
                    onClick = { menu = true },
                    size = 36.dp,
                    iconSize = 20.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (running) {
                        DropdownMenuItem(
                            text = { Text("Jeda antrean") },
                            leadingIcon = { Icon(UiIcons.Stop, null) },
                            onClick = { menu = false; onHaltQueue() },
                        )
                    } else if (hasQueued) {
                        DropdownMenuItem(
                            text = { Text("Lanjutkan antrean") },
                            leadingIcon = { Icon(UiIcons.Play, null) },
                            onClick = { menu = false; onContinueQueue() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Tambah dari Search / Link") },
                        leadingIcon = { Icon(UiIcons.Search, null) },
                        onClick = { menu = false; onAddAudio() },
                    )
                    DropdownMenuItem(
                        text = { Text("Bersihkan selesai ($finishedCount)") },
                        enabled = finishedCount > 0 && !operationBusy,
                        leadingIcon = { Icon(UiIcons.Delete, null) },
                        onClick = { menu = false; onClearFinished() },
                    )
                    DropdownMenuItem(
                        text = { Text("Pengaturan & YT Music") },
                        leadingIcon = { Icon(UiIcons.Settings, null) },
                        onClick = { menu = false; onSettings() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectOperationCard(
    ingest: IngestState,
    onCancelIngest: () -> Unit,
) {
    val progress = ingest.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val borderColor = if (ingest.error) FohTheme.colors.alertBorder else FohTheme.colors.safeBorder
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ingest.busy) {
                    FohSpinningIndicator(size = 18.dp, strokeWidth = 2.2.dp, color = FohTheme.colors.safeAccent)
                } else if (ingest.error) {
                    Icon(UiIcons.Warning, null, tint = FohTheme.colors.alertAccent, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = ingest.stage,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (ingest.error) FohTheme.colors.alertAccent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ingest.busy && ingest.cancelable) {
                    TextButton(onClick = onCancelIngest, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Cancel")
                    }
                }
            }
            if (ingest.busy) {
                if (progress != null && progress > 0.01f) {
                    FohDeterminateShimmerBar(
                        progress = progress,
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                } else {
                    FohIndeterminateBar(
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                }
            }
            if (ingest.error && ingest.detail.isNotBlank()) {
                Text(ingest.detail, color = FohTheme.colors.alertAccent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DownloadsSummaryCard(
    summary: DownloadQueueSummary,
    ingest: IngestState,
    ytMusic: YoutubeMusicAccountState,
    settings: DownloadSettingsState,
    runningJobId: Long?,
    onSettings: () -> Unit,
    onHaltQueue: () -> Unit,
    onContinueQueue: () -> Unit,
    onSkipCurrent: (Long) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetRateLimit: (DownloadRateLimit) -> Unit,
) {
    var rateMenu by remember { mutableStateOf(false) }
    val activeQueue = summary.running > 0 || summary.queued > 0
    val isDownloading = summary.running > 0 || (ingest.busy && ingest.batchId != null)
    val waitingWifi = !isDownloading && summary.queued > 0 && ingest.stage.contains("Wi-Fi", ignoreCase = true)

    val (pillLabel, pillBg, pillBorder, pillFg) = when {
        isDownloading -> DownloadPillStyle("MENGUNDUH", FohTheme.colors.safeBg, FohTheme.colors.safeBorder, FohTheme.colors.safeAccent)
        waitingWifi -> DownloadPillStyle("MENUNGGU WI-FI", FohTheme.colors.warnBg, FohTheme.colors.warnBorder, FohTheme.colors.warnAccent)
        summary.queued > 0 -> DownloadPillStyle("ANTRIAN SIAP", FohTheme.colors.warnBg, FohTheme.colors.warnBorder, FohTheme.colors.warnAccent)
        summary.failed > 0 -> DownloadPillStyle("PERLU CEK", FohTheme.colors.alertBg, FohTheme.colors.alertBorder, FohTheme.colors.alertAccent)
        else -> DownloadPillStyle("SELESAI", FohTheme.colors.safeBg, FohTheme.colors.safeBorder, FohTheme.colors.safeAccent)
    }

    val summaryLine = buildString {
        append("${summary.completed}/${summary.total} selesai")
        if (summary.active > 0) append(" · ${summary.active} aktif")
        if (summary.failed > 0) append(" · ${summary.failed} gagal")
        if (summary.canceled > 0) append(" · ${summary.canceled} dibatalkan")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, if (activeQueue) pillBorder.copy(alpha = 0.85f) else FohTheme.colors.borderSubtle.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = pillBg,
                    border = BorderStroke(1.dp, pillBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (isDownloading) {
                            FohWaveBarsIndicator(
                                color = pillFg,
                                width = 12.dp,
                                height = 10.dp,
                            )
                        }
                        Text(
                            text = pillLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = pillFg,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Text(
                    text = summaryLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AccountQualityStatusPill(ytMusic = ytMusic, onClick = onSettings)
            }

            if (activeQueue) {
                if (summary.completed == 0) {
                    FohIndeterminateBar(
                        color = pillFg,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                } else {
                    val ratio = if (summary.total > 0) summary.completed.toFloat() / summary.total else 0f
                    FohDeterminateShimmerBar(
                        progress = ratio.coerceIn(0f, 1f),
                        color = pillFg,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                        shimmerEnabled = isDownloading,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isDownloading) {
                    CompactPolicyChip(
                        label = "Jeda antrean",
                        icon = UiIcons.Stop,
                        selected = false,
                        accentBg = FohTheme.colors.warnBg,
                        accentBorder = FohTheme.colors.warnBorder,
                        accentFg = FohTheme.colors.warnAccent,
                        onClick = onHaltQueue,
                    )
                    if (runningJobId != null) {
                        CompactPolicyChip(
                            label = "Lewati lagu",
                            icon = UiIcons.Next,
                            selected = false,
                            onClick = { onSkipCurrent(runningJobId) },
                        )
                    }
                } else if (summary.queued > 0) {
                    CompactPolicyChip(
                        label = "Lanjutkan (${summary.queued})",
                        icon = UiIcons.Play,
                        selected = true,
                        onClick = onContinueQueue,
                    )
                }

                CompactPolicyChip(
                    label = if (settings.wifiOnly) "Hanya Wi-Fi: ON" else "Hanya Wi-Fi",
                    icon = UiIcons.Shield,
                    selected = settings.wifiOnly,
                    onClick = { onSetWifiOnly(!settings.wifiOnly) },
                )

                Box {
                    CompactPolicyChip(
                        label = "Limit: ${settings.rateLimit.shortLabel}",
                        icon = UiIcons.Download,
                        selected = settings.rateLimit != DownloadRateLimit.UNLIMITED,
                        onClick = { rateMenu = true },
                    )
                    DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                        DownloadRateLimit.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                trailingIcon = if (settings.rateLimit == option) {
                                    { Icon(UiIcons.Check, null, tint = FohTheme.colors.safeAccent) }
                                } else null,
                                onClick = {
                                    rateMenu = false
                                    onSetRateLimit(option)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPolicyChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accentBg: Color = FohTheme.colors.safeBg,
    accentBorder: Color = FohTheme.colors.safeBorder,
    accentFg: Color = FohTheme.colors.safeAccent,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) accentBg else FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, if (selected) accentBorder else FohTheme.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accentFg else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) accentFg else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun AccountQualityStatusPill(
    ytMusic: YoutubeMusicAccountState,
    onClick: () -> Unit,
) {
    val activePremium = ytMusic.connected && ytMusic.enabledForDownloads && !ytMusic.needsReauth &&
        ytMusic.tier == YoutubeMusicPremiumTier.PREMIUM
    val needsReauth = ytMusic.connected && ytMusic.needsReauth
    val (label, bg, border, fg) = when {
        activePremium -> DownloadPillStyle("YT Music Premium", FohTheme.colors.safeBg, FohTheme.colors.safeBorder, FohTheme.colors.safeAccent)
        needsReauth -> DownloadPillStyle("Sesi perlu cek", FohTheme.colors.warnBg, FohTheme.colors.warnBorder, FohTheme.colors.warnAccent)
        ytMusic.connected && ytMusic.enabledForDownloads -> DownloadPillStyle("YT terhubung", FohTheme.colors.surfaceDeep, FohTheme.colors.borderSubtle, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> DownloadPillStyle("Mode publik", FohTheme.colors.surfaceDeep, FohTheme.colors.borderSubtle, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DownloadFilterRow(
    selected: DownloadFilterTab,
    summary: DownloadQueueSummary,
    onSelect: (DownloadFilterTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DownloadFilterTab.entries.forEach { tab ->
            val count = when (tab) {
                DownloadFilterTab.ALL -> summary.total
                DownloadFilterTab.ACTIVE -> summary.active
                DownloadFilterTab.COMPLETED -> summary.completed
                DownloadFilterTab.FAILED -> summary.failed
            }
            FohFilterChip(
                label = "${tab.label} ($count)",
                selected = tab == selected,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun DownloadSectionHeader(
    title: String,
    accent: Color,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 28.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadJobRow(
    job: DownloadJob,
    matchedTrack: AudioTrack?,
    queuedPosition: Int?,
    isCurrentlyPlayingTrack: Boolean,
    isPlaybackActive: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    connected: Boolean,
    canDispatch: Boolean,
    actionsBusy: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onSkipItem: () -> Unit,
    onMoveUpItem: () -> Unit,
    onMoveToFrontItem: () -> Unit,
    onCancelItem: () -> Unit,
    onRetryItem: () -> Unit,
    onRequeueItem: () -> Unit,
    onRemoveItem: () -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
) {
    val running = job.status == "RUNNING"
    val completed = job.status == "COMPLETED"
    val failed = job.status == "FAILED"
    val queued = job.status == "QUEUED"
    val canceled = job.status == "CANCELED"
    val progress = job.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    var rowMenu by remember { mutableStateOf(false) }

    val highlighted = selected || running || isCurrentlyPlayingTrack
    val containerColor = when {
        selected -> FohTheme.colors.safeBg
        running || isCurrentlyPlayingTrack -> FohTheme.colors.safeBg
        failed -> FohTheme.colors.alertBg.copy(alpha = 0.5f)
        else -> FohTheme.colors.surfaceCard
    }
    val borderStroke = when {
        highlighted -> BorderStroke(1.dp, FohTheme.colors.safeBorder)
        failed -> BorderStroke(1.dp, FohTheme.colors.alertBorder)
        else -> BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.7f))
    }
    val qualityBadge = remember(matchedTrack) {
        matchedTrack?.let { UiPresentation.highBitrateBadge(it.displayName, it.sizeBytes, it.durationMs) }
    }
    val statusText = when (job.status) {
        "QUEUED" -> buildString {
            queuedPosition?.let { append("#${it + 1} · ") }
            append(job.stage.ifBlank { "Menunggu antrean" })
        }
        "RUNNING" -> buildString {
            append(job.stage.ifBlank { "Memproses…" })
            progress?.let { append(" · ${(it * 100).toInt()}%") }
        }
        "COMPLETED" -> matchedTrack?.let {
            UiPresentation.qualitySummary(it.displayName, it.sizeBytes, it.durationMs)
        } ?: "Selesai · tidak lagi di Library"
        "FAILED" -> "Gagal"
        "CANCELED" -> if (job.stage.equals("Dilewati", ignoreCase = true)) "Dilewati" else "Dibatalkan"
        else -> job.stage.ifBlank { "Status belum tersedia" }
    }

    val clickableRow = selectionMode || (completed && matchedTrack != null && connected)

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        border = borderStroke,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = true,
                    onClick = {
                        when {
                            selectionMode -> onToggleSelect()
                            completed && matchedTrack != null && connected -> onPlayTrack(matchedTrack)
                        }
                    },
                    onLongClick = onLongPress,
                )
                .semantics { this.selected = selected || isCurrentlyPlayingTrack }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val artworkKey = matchedTrack?.artworkKey
                if (artworkKey != null) {
                    TrackArtwork(
                        artworkKey = artworkKey,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
                    )
                } else {
                    YoutubeThumbnail(
                        url = job.thumbnailUrl,
                        title = job.title,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
                    )
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (running || isCurrentlyPlayingTrack) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (qualityBadge == "LOSSLESS") {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = FohTheme.colors.safeBg,
                                border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                            ) {
                                Text(
                                    text = qualityBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FohTheme.colors.safeAccent,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(
                            text = if (isCurrentlyPlayingTrack && isPlaybackActive) "Diputar · $statusText" else statusText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (completed) FontFamily.Monospace else FontFamily.Default),
                            color = when {
                                failed -> FohTheme.colors.alertAccent
                                isCurrentlyPlayingTrack -> FohTheme.colors.safeAccent
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        enabled = !actionsBusy,
                    )
                } else {
                    when (job.status) {
                        "RUNNING" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FohSpinningIndicator(
                                    size = 20.dp,
                                    strokeWidth = 2.4.dp,
                                    color = FohTheme.colors.safeAccent,
                                    trackColor = FohTheme.colors.safeAccent.copy(alpha = 0.22f),
                                )
                                CompactCircleButton(
                                    icon = UiIcons.Next,
                                    description = "Lewati ${job.title}",
                                    onClick = onSkipItem,
                                    size = 32.dp,
                                    iconSize = 16.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                            }
                        }
                        "QUEUED" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if ((queuedPosition ?: 0) > 0) {
                                    CompactCircleButton(
                                        icon = UiIcons.ArrowUp,
                                        description = "Naikkan urutan ${job.title}",
                                        onClick = onMoveUpItem,
                                        enabled = !actionsBusy,
                                        size = 30.dp,
                                        iconSize = 15.dp,
                                        containerColor = FohTheme.colors.safeBg,
                                        contentColor = FohTheme.colors.safeAccent,
                                    )
                                }
                                Box {
                                    CompactCircleButton(
                                        icon = UiIcons.More,
                                        description = "Menu antrean ${job.title}",
                                        onClick = { rowMenu = true },
                                        enabled = !actionsBusy,
                                        size = 30.dp,
                                        iconSize = 16.dp,
                                        containerColor = Color.Transparent,
                                    )
                                    DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                        if ((queuedPosition ?: 0) > 0) {
                                            DropdownMenuItem(
                                                text = { Text("Naikkan 1 urutan") },
                                                leadingIcon = { Icon(UiIcons.ArrowUp, null) },
                                                onClick = { rowMenu = false; onMoveUpItem() },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Prioritaskan berikutnya") },
                                                leadingIcon = { Icon(UiIcons.CueStart, null) },
                                                onClick = { rowMenu = false; onMoveToFrontItem() },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Batalkan item") },
                                            leadingIcon = { Icon(UiIcons.Close, null) },
                                            onClick = { rowMenu = false; onCancelItem() },
                                        )
                                    }
                                }
                            }
                        }
                        "COMPLETED" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (matchedTrack != null) {
                                    FohPlayToggleCircleButton(
                                        playing = isCurrentlyPlayingTrack && isPlaybackActive,
                                        description = "Putar preview ${job.title}",
                                        onClick = { onPlayTrack(matchedTrack) },
                                        enabled = connected && clickableRow,
                                        size = 34.dp,
                                        iconSize = 16.dp,
                                    )
                                } else {
                                    Icon(UiIcons.Check, "Selesai", tint = FohTheme.colors.safeAccent)
                                }
                                Box {
                                    CompactCircleButton(
                                        icon = UiIcons.More,
                                        description = "Menu item ${job.title}",
                                        onClick = { rowMenu = true },
                                        enabled = !actionsBusy,
                                        size = 30.dp,
                                        iconSize = 16.dp,
                                        containerColor = Color.Transparent,
                                    )
                                    DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                        if (matchedTrack != null) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (isCurrentlyPlayingTrack && isPlaybackActive) "Hentikan preview"
                                                        else "Putar preview",
                                                    )
                                                },
                                                enabled = connected,
                                                leadingIcon = { Icon(UiIcons.Play, null) },
                                                onClick = { rowMenu = false; onPlayTrack(matchedTrack) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("+ Ke Playlist") },
                                                leadingIcon = { Icon(UiIcons.Queue, null) },
                                                onClick = { rowMenu = false; onAddToPlaylist(matchedTrack) },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Unduh ulang") },
                                            enabled = canDispatch,
                                            leadingIcon = { Icon(UiIcons.Refresh, null) },
                                            onClick = { rowMenu = false; onRequeueItem() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Hapus dari daftar") },
                                            leadingIcon = { Icon(UiIcons.Close, null) },
                                            onClick = { rowMenu = false; onRemoveItem() },
                                        )
                                    }
                                }
                            }
                        }
                        "FAILED" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FohHeaderChipButton(
                                    label = "Retry",
                                    icon = UiIcons.Refresh,
                                    enabled = canDispatch,
                                    onClick = onRetryItem,
                                )
                                CompactCircleButton(
                                    icon = UiIcons.Close,
                                    description = "Hapus ${job.title}",
                                    onClick = onRemoveItem,
                                    enabled = !actionsBusy,
                                    size = 30.dp,
                                    iconSize = 15.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                            }
                        }
                        "CANCELED" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FohHeaderChipButton(
                                    label = "Ulang",
                                    icon = UiIcons.Refresh,
                                    enabled = canDispatch,
                                    onClick = onRequeueItem,
                                )
                                CompactCircleButton(
                                    icon = UiIcons.Close,
                                    description = "Hapus ${job.title}",
                                    onClick = onRemoveItem,
                                    enabled = !actionsBusy,
                                    size = 30.dp,
                                    iconSize = 15.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                            }
                        }
                    }
                }
            }
            if (running) {
                if (progress != null && progress > 0.01f) {
                    FohDeterminateShimmerBar(
                        progress = progress,
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                } else {
                    FohIndeterminateBar(
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                }
            }
            job.error?.takeIf { it.isNotBlank() && !canceled && !completed && !queued }?.let { err ->
                Text(err, color = FohTheme.colors.alertAccent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DownloadsBatchActionBar(
    selectedJobs: List<DownloadJob>,
    tracksById: Map<Long, AudioTrack>,
    busy: Boolean,
    onRetrySelected: (List<Long>) -> Unit,
    onCancelSelected: (List<Long>) -> Unit,
    onAddToPlaylist: (List<AudioTrack>) -> Unit,
    onRemoveSelected: (List<Long>) -> Unit,
) {
    val retryableIds = remember(selectedJobs) {
        selectedJobs.filter { it.status == "FAILED" || it.status == "CANCELED" }.map { it.id }
    }
    val cancelableIds = remember(selectedJobs) {
        selectedJobs.filter { it.status == "QUEUED" }.map { it.id }
    }
    val playlistTracks = remember(selectedJobs, tracksById) {
        selectedJobs.mapNotNull { it.trackId?.let(tracksById::get) }.distinctBy { it.id }
    }
    val removableIds = remember(selectedJobs) {
        selectedJobs.filter { it.status != "RUNNING" && it.status != "QUEUED" }.map { it.id }
    }

    Surface(
        color = FohTheme.colors.surfaceDeck,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (retryableIds.isNotEmpty()) {
                Button(
                    onClick = { onRetrySelected(retryableIds) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Retry (${retryableIds.size})", maxLines = 1, softWrap = false)
                }
            }
            if (playlistTracks.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onAddToPlaylist(playlistTracks) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.safeBg,
                        contentColor = FohTheme.colors.safeAccent,
                    ),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.Queue, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("+ Playlist (${playlistTracks.size})", maxLines = 1, softWrap = false)
                }
            }
            if (cancelableIds.isNotEmpty()) {
                OutlinedButton(
                    onClick = { onCancelSelected(cancelableIds) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Batal (${cancelableIds.size})", maxLines = 1, softWrap = false)
                }
            }
            if (removableIds.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onRemoveSelected(removableIds) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.alertBg,
                        contentColor = FohTheme.colors.alertAccent,
                    ),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Hapus daftar (${removableIds.size})", maxLines = 1, softWrap = false)
                }
            }
            if (selectedJobs.isEmpty()) {
                Text(
                    "Ketuk item untuk memilih",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

private data class DownloadPillStyle(val label: String, val bg: Color, val border: Color, val fg: Color)
