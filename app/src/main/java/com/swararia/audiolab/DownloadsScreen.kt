package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountState
import java.util.Locale

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
    var selectedTabName by rememberSaveable { mutableStateOf(DownloadQueueTab.ACTIVE.name) }
    val selectedTab = DownloadQueueTab.entries.firstOrNull { it.name == selectedTabName } ?: DownloadQueueTab.ACTIVE
    var showSettingsPanel by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var playlistPickerTracks by remember { mutableStateOf<List<AudioTrack>?>(null) }

    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    val summary = remember(jobs) { UiPresentation.summarizeDownloadStatuses(jobs.map { it.status }) }
    val visibleJobs = remember(jobs, selectedTab) {
        UiPresentation.filterQueueTab(jobs, selectedTab) { it.status }
    }
    val visibleIds = remember(visibleJobs) { visibleJobs.map { it.id }.toSet() }
    LaunchedEffect(visibleIds, selectionMode) {
        if (selectionMode) {
            selectedIds = selectedIds.filter { it in visibleIds }
        }
    }

    val runningJob = remember(jobs) { jobs.firstOrNull { it.status == "RUNNING" } }
    val queuedJobs = remember(jobs) { jobs.filter { it.status == "QUEUED" } }
    val failedJobs = remember(jobs) { jobs.filter { it.status == "FAILED" } }
    val finishedJobs = remember(jobs) {
        jobs.filter { it.status == "COMPLETED" || it.status == "CANCELED" }
            .sortedByDescending { it.createdAt }
    }

    val isDownloading = runningJob != null || (ingest.busy && ingest.batchId != null)
    val waitingWifi = !isDownloading && summary.queued > 0 && ingest.stage.contains("Wi-Fi", ignoreCase = true)
    val canDispatch = !upgrade.operationBusy
    val failedBatchIds = remember(failedJobs) { failedJobs.map { it.batchId }.distinct() }
    val directIngestBanner = ingest.batchId == null && (ingest.busy || ingest.error)

    val selectedIdSet = remember(selectedIds) { selectedIds.toSet() }
    val selectedJobs = remember(visibleJobs, selectedIdSet) {
        visibleJobs.filter { it.id in selectedIdSet }
    }
    val toggleSelection: (Long) -> Unit = { jobId ->
        selectedIds = if (jobId in selectedIdSet) selectedIds.filterNot { it == jobId } else selectedIds + jobId
    }
    val enterSelectionWith: (Long?) -> Unit = { initialId ->
        selectionMode = true
        showSettingsPanel = false
        selectedIds = if (initialId != null) listOf(initialId) else emptyList()
    }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedIds = emptyList()
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header", contentType = "header") {
                DownloadsHeader(
                    selectionMode = selectionMode,
                    selectedCount = selectedJobs.size,
                    allSelected = visibleJobs.isNotEmpty() && selectedJobs.size == visibleJobs.size,
                    finishedCount = summary.finished,
                    hasJobs = jobs.isNotEmpty(),
                    hasActive = summary.active > 0 || (ingest.busy && ingest.batchId != null),
                    operationBusy = upgrade.operationBusy,
                    onEnterSelection = { enterSelectionWith(null) },
                    onExitSelection = exitSelection,
                    onToggleSelectAll = {
                        selectedIds = if (selectedJobs.size == visibleJobs.size) emptyList() else visibleJobs.map { it.id }
                    },
                    onToggleSettings = { showSettingsPanel = !showSettingsPanel },
                    onCancelBatch = upgrade.onCancelBatch,
                    onClearFinished = upgrade.onClearFinished,
                    onAddAudio = onAddAudio,
                )
            }

            if (directIngestBanner) {
                item(key = "direct-ingest", contentType = "banner") {
                    DirectOperationBanner(ingest = ingest, onCancelIngest = onCancelIngest)
                }
            }

            if (jobs.isEmpty()) {
                if (!directIngestBanner) {
                    item(key = "empty", contentType = "empty") {
                        DownloadsEmptyState(onAddAudio = onAddAudio)
                    }
                }
            } else {
                if (!selectionMode) {
                    item(key = "tabs", contentType = "tabs") {
                        DownloadQueueSegmentedBar(
                            selected = selectedTab,
                            summary = summary,
                            onSelect = { selectedTabName = it.name },
                        )
                    }
                }

                if (showSettingsPanel && !selectionMode) {
                    item(key = "settings-panel", contentType = "settings") {
                        DownloadInlineSettingsPanel(
                            settings = upgrade.downloadSettings,
                            ytMusic = ytMusic,
                            onSetWifiOnly = upgrade.onSetWifiOnly,
                            onSetRateLimit = upgrade.onSetRateLimit,
                            onOpenFullSettings = onSettings,
                            onClose = { showSettingsPanel = false },
                        )
                    }
                }

                if (waitingWifi && selectedTab == DownloadQueueTab.ACTIVE && !selectionMode) {
                    item(key = "wifi-wait", contentType = "banner") {
                        WaitingWifiNotice(
                            canDispatch = canDispatch,
                            onDownloadAnyway = {
                                upgrade.onSetWifiOnly(false)
                                upgrade.onContinueQueue()
                            },
                        )
                    }
                }

                when (selectedTab) {
                    DownloadQueueTab.ACTIVE -> {
                        if (runningJob == null && queuedJobs.isEmpty()) {
                            item(key = "active-empty", contentType = "empty") {
                                ActiveTabEmptyNotice(
                                    failedCount = summary.failed,
                                    finishedCount = summary.finished,
                                    onCheckFailed = { selectedTabName = DownloadQueueTab.FAILED.name },
                                    onViewFinished = { selectedTabName = DownloadQueueTab.COMPLETED.name },
                                    onAddAudio = onAddAudio,
                                )
                            }
                        } else {
                            if (runningJob != null) {
                                item(key = "hero-${runningJob.id}", contentType = "hero-job") {
                                    RunningJobHeroPanel(
                                        job = runningJob,
                                        selectionMode = selectionMode,
                                        selected = runningJob.id in selectedIdSet,
                                        actionsBusy = upgrade.operationBusy,
                                        onToggleSelect = { toggleSelection(runningJob.id) },
                                        onLongPress = {
                                            if (!selectionMode) enterSelectionWith(runningJob.id)
                                            else toggleSelection(runningJob.id)
                                        },
                                        onHaltQueue = upgrade.onHaltQueue,
                                        onSkipItem = { upgrade.onSkipCurrentJob(runningJob.id) },
                                    )
                                }
                            } else if (queuedJobs.isNotEmpty() && !selectionMode) {
                                item(key = "paused-banner", contentType = "paused") {
                                    QueueHaltedPanel(
                                        queuedCount = queuedJobs.size,
                                        canDispatch = canDispatch,
                                        onContinueQueue = upgrade.onContinueQueue,
                                    )
                                }
                            }

                            if (queuedJobs.isNotEmpty()) {
                                item(key = "queued-header", contentType = "section-header") {
                                    Text(
                                        text = "Berikutnya (${queuedJobs.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                itemsIndexed(queuedJobs, key = { _, job -> job.id }, contentType = { _, _ -> "queued-job" }) { index, job ->
                                    QueuedJobRow(
                                        job = job,
                                        position = index + 1,
                                        canMoveUp = index > 0 && !upgrade.operationBusy,
                                        selectionMode = selectionMode,
                                        selected = job.id in selectedIdSet,
                                        actionsBusy = upgrade.operationBusy,
                                        onToggleSelect = { toggleSelection(job.id) },
                                        onLongPress = {
                                            if (!selectionMode) enterSelectionWith(job.id)
                                            else toggleSelection(job.id)
                                        },
                                        onMoveUp = { upgrade.onMoveQueuedUp(job.id) },
                                        onMoveToFront = { upgrade.onMoveQueuedToFront(job.id) },
                                        onCancel = { upgrade.onCancelJob(job.id) },
                                    )
                                }
                            }
                        }
                    }

                    DownloadQueueTab.FAILED -> {
                        if (failedJobs.isEmpty()) {
                            item(key = "failed-empty", contentType = "empty") {
                                TabEmptyNotice("Tidak ada unduhan gagal")
                            }
                        } else {
                            if (!selectionMode) {
                                item(key = "failed-actions", contentType = "section-header") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "${failedJobs.size} perlu diulang",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = FohTheme.colors.alertAccent,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = { upgrade.onRemoveJobs(failedJobs.map { it.id }) },
                                                enabled = !upgrade.operationBusy,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 30.dp),
                                            ) {
                                                Text("Hapus", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (failedBatchIds.size == 1) upgrade.onRetryFailed(failedBatchIds.single())
                                                    else upgrade.onRetryAllFailed()
                                                    selectedTabName = DownloadQueueTab.ACTIVE.name
                                                },
                                                enabled = canDispatch,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 30.dp),
                                            ) {
                                                Text("Ulangi semua", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                            items(failedJobs, key = { it.id }, contentType = { "failed-job" }) { job ->
                                FailedJobRow(
                                    job = job,
                                    selectionMode = selectionMode,
                                    selected = job.id in selectedIdSet,
                                    canDispatch = canDispatch,
                                    actionsBusy = upgrade.operationBusy,
                                    onToggleSelect = { toggleSelection(job.id) },
                                    onLongPress = {
                                        if (!selectionMode) enterSelectionWith(job.id)
                                        else toggleSelection(job.id)
                                    },
                                    onRetry = { upgrade.onRetryJob(job.id) },
                                    onRemove = { upgrade.onRemoveJob(job.id) },
                                )
                            }
                        }
                    }

                    DownloadQueueTab.COMPLETED -> {
                        if (finishedJobs.isEmpty()) {
                            item(key = "finished-empty", contentType = "empty") {
                                TabEmptyNotice("Belum ada unduhan selesai")
                            }
                        } else {
                            if (!selectionMode) {
                                item(key = "finished-header", contentType = "section-header") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "${finishedJobs.size} riwayat",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        TextButton(
                                            onClick = upgrade.onClearFinished,
                                            enabled = !upgrade.operationBusy,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.heightIn(min = 30.dp),
                                        ) {
                                            Text("Bersihkan", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            items(finishedJobs, key = { it.id }, contentType = { "finished-job" }) { job ->
                                val matchedTrack = job.trackId?.let(tracksById::get)
                                FinishedJobRow(
                                    job = job,
                                    matchedTrack = matchedTrack,
                                    isCurrentlyPlayingTrack = matchedTrack != null && matchedTrack.id.toString() == currentPlayingTrackId,
                                    isPlaybackActive = isPlayingActive,
                                    selectionMode = selectionMode,
                                    selected = job.id in selectedIdSet,
                                    connected = connected,
                                    canDispatch = canDispatch,
                                    actionsBusy = upgrade.operationBusy,
                                    onToggleSelect = { toggleSelection(job.id) },
                                    onLongPress = {
                                        if (!selectionMode) enterSelectionWith(job.id)
                                        else toggleSelection(job.id)
                                    },
                                    onPlayTrack = onPlayTrack,
                                    onAddToPlaylist = { playlistPickerTracks = listOf(it) },
                                    onRequeue = { upgrade.onRequeueJob(job.id) },
                                    onRemove = { upgrade.onRemoveJob(job.id) },
                                )
                            }
                        }
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
    hasActive: Boolean,
    operationBusy: Boolean,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleSettings: () -> Unit,
    onCancelBatch: () -> Unit,
    onClearFinished: () -> Unit,
    onAddAudio: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
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
                label = if (allSelected) "Reset" else "Semua",
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
                text = "Antrean Unduhan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CompactCircleButton(
                icon = UiIcons.Plus,
                description = "Tambah lagu",
                onClick = onAddAudio,
                size = 36.dp,
                iconSize = 19.dp,
                containerColor = FohTheme.colors.safeBg,
                contentColor = FohTheme.colors.safeAccent,
            )
            Box {
                CompactCircleButton(
                    icon = UiIcons.More,
                    description = "Menu Antrean",
                    onClick = { menu = true },
                    size = 36.dp,
                    iconSize = 20.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Pilih banyak") },
                        enabled = hasJobs && !operationBusy,
                        leadingIcon = { Icon(UiIcons.Check, null) },
                        onClick = { menu = false; onEnterSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("Setelan Unduhan") },
                        leadingIcon = { Icon(UiIcons.Settings, null) },
                        onClick = { menu = false; onToggleSettings() },
                    )
                    if (finishedCount > 0) {
                        DropdownMenuItem(
                            text = { Text("Bersihkan selesai ($finishedCount)") },
                            enabled = !operationBusy,
                            leadingIcon = { Icon(UiIcons.Delete, null) },
                            onClick = { menu = false; onClearFinished() },
                        )
                    }
                    if (hasActive) {
                        DropdownMenuItem(
                            text = { Text("Batalkan semua", color = FohTheme.colors.alertAccent) },
                            enabled = !operationBusy,
                            leadingIcon = { Icon(UiIcons.Close, null, tint = FohTheme.colors.alertAccent) },
                            onClick = { menu = false; onCancelBatch() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueSegmentedBar(
    selected: DownloadQueueTab,
    summary: DownloadQueueSummary,
    onSelect: (DownloadQueueTab) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DownloadQueueTab.entries.forEach { tab ->
                val count = when (tab) {
                    DownloadQueueTab.ACTIVE -> summary.active
                    DownloadQueueTab.FAILED -> summary.failed
                    DownloadQueueTab.COMPLETED -> summary.finished
                }
                val active = tab == selected
                val isFailedTab = tab == DownloadQueueTab.FAILED
                val highlightAlert = isFailedTab && (active || count > 0)
                val bgColor = when {
                    active && isFailedTab -> FohTheme.colors.alertBg
                    active -> FohTheme.colors.safeBg
                    else -> Color.Transparent
                }
                val borderStroke = when {
                    active && isFailedTab -> BorderStroke(1.dp, FohTheme.colors.alertBorder)
                    active -> BorderStroke(1.dp, FohTheme.colors.safeBorder)
                    else -> null
                }
                val fgColor = when {
                    highlightAlert -> FohTheme.colors.alertAccent
                    active -> FohTheme.colors.safeAccent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    onClick = { onSelect(tab) },
                    shape = RoundedCornerShape(10.dp),
                    color = bgColor,
                    border = borderStroke,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "${tab.label} ($count)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = fgColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunningJobHeroPanel(
    job: DownloadJob,
    selectionMode: Boolean,
    selected: Boolean,
    actionsBusy: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onHaltQueue: () -> Unit,
    onSkipItem: () -> Unit,
) {
    val progress = job.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val stageText = buildString {
        append(job.stage.ifBlank { "Mengunduh audio" })
        progress?.let { append(" · ${(it * 100).toInt()}%") }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.safeBg,
        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = true,
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onLongPress,
                )
                .semantics { this.selected = selected || !selectionMode }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.size(50.dp)) {
                    YoutubeThumbnail(
                        url = job.thumbnailUrl,
                        title = job.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(FohTheme.colors.surfaceDeep),
                        contentAlignment = Alignment.Center,
                    ) {
                        FohSpinningIndicator(
                            size = 13.dp,
                            strokeWidth = 1.8.dp,
                            color = FohTheme.colors.safeAccent,
                            trackColor = FohTheme.colors.safeAccent.copy(alpha = 0.25f),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = FohTheme.colors.safeAccent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FohWaveBarsIndicator(
                            color = FohTheme.colors.safeAccent,
                            width = 11.dp,
                            height = 10.dp,
                        )
                        Text(
                            text = stageText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = !actionsBusy)
                }
            }

            FohOperationProgressBar(
                progress = progress,
                color = FohTheme.colors.safeAccent,
                trackColor = FohTheme.colors.surfaceDeep,
                height = 5.dp,
            )

            if (!selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onHaltQueue,
                        enabled = !actionsBusy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = FohTheme.colors.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    ) {
                        Icon(UiIcons.Stop, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Jeda", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onSkipItem,
                        enabled = !actionsBusy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = FohTheme.colors.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    ) {
                        Icon(UiIcons.Next, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lewati", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuedJobRow(
    job: DownloadJob,
    position: Int,
    canMoveUp: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    actionsBusy: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveToFront: () -> Unit,
    onCancel: () -> Unit,
) {
    var rowMenu by remember { mutableStateOf(false) }
    Surface(
        color = if (selected) FohTheme.colors.safeBg else FohTheme.colors.surfaceDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = true,
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onLongPress,
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = String.format(Locale.ROOT, "%02d", position),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = if (selected) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            YoutubeThumbnail(
                url = job.thumbnailUrl,
                title = job.title,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = job.stage.ifBlank { "Menunggu" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = !actionsBusy)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (canMoveUp) {
                        CompactCircleButton(
                            icon = UiIcons.ArrowUp,
                            description = "Naikkan urutan ${job.title}",
                            onClick = onMoveUp,
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
                            if (canMoveUp) {
                                DropdownMenuItem(
                                    text = { Text("Naikkan 1 urutan") },
                                    leadingIcon = { Icon(UiIcons.ArrowUp, null) },
                                    onClick = { rowMenu = false; onMoveUp() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Prioritaskan berikutnya") },
                                    leadingIcon = { Icon(UiIcons.CueStart, null) },
                                    onClick = { rowMenu = false; onMoveToFront() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Batalkan item") },
                                leadingIcon = { Icon(UiIcons.Close, null) },
                                onClick = { rowMenu = false; onCancel() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedJobRow(
    job: DownloadJob,
    selectionMode: Boolean,
    selected: Boolean,
    canDispatch: Boolean,
    actionsBusy: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = if (selected) FohTheme.colors.safeBg else FohTheme.colors.alertBg.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) FohTheme.colors.safeBorder else FohTheme.colors.alertBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = true,
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onLongPress,
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            YoutubeThumbnail(
                url = job.thumbnailUrl,
                title = job.title,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = job.error?.takeIf { it.isNotBlank() } ?: "Gagal mengunduh",
                    style = MaterialTheme.typography.bodySmall,
                    color = FohTheme.colors.alertAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = !actionsBusy)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FohHeaderChipButton(
                        label = "Ulang",
                        icon = UiIcons.Refresh,
                        enabled = canDispatch,
                        onClick = onRetry,
                    )
                    CompactCircleButton(
                        icon = UiIcons.Close,
                        description = "Hapus ${job.title}",
                        onClick = onRemove,
                        enabled = !actionsBusy,
                        size = 30.dp,
                        iconSize = 15.dp,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
