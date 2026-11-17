package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountState
import com.swararia.audiolab.ytmusic.YoutubeMusicPremiumTier
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

@Composable
private fun DownloadInlineSettingsPanel(
    settings: DownloadSettingsState,
    ytMusic: YoutubeMusicAccountState,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetRateLimit: (DownloadRateLimit) -> Unit,
    onOpenFullSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var rateMenu by remember { mutableStateOf(false) }
    val accountLabel = when {
        ytMusic.connected && ytMusic.enabledForDownloads && !ytMusic.needsReauth &&
            ytMusic.tier == YoutubeMusicPremiumTier.PREMIUM -> "YT Premium"
        ytMusic.connected && ytMusic.needsReauth -> "Perlu cek"
        ytMusic.connected && ytMusic.enabledForDownloads -> "YT Terhubung"
        else -> "Mode Publik"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Setelan Unduhan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 28.dp),
                ) {
                    Text("Tutup", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Hanya Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                FohFilterChip(
                    label = if (settings.wifiOnly) "Aktif" else "Mati",
                    selected = settings.wifiOnly,
                    onClick = { onSetWifiOnly(!settings.wifiOnly) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Batas Kecepatan", style = MaterialTheme.typography.bodyMedium)
                Box {
                    FohFilterChip(
                        label = settings.rateLimit.shortLabel,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Akun Kualitas", style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onOpenFullSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 30.dp),
                ) {
                    Text("$accountLabel ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun WaitingWifiNotice(
    canDispatch: Boolean,
    onDownloadAnyway: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.warnBg,
        border = BorderStroke(1.dp, FohTheme.colors.warnBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(UiIcons.Shield, null, tint = FohTheme.colors.warnAccent, modifier = Modifier.size(18.dp))
            Text(
                text = "Menunggu Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = FohTheme.colors.warnAccent,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDownloadAnyway,
                enabled = canDispatch,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 30.dp),
            ) {
                Text("Unduh sekarang", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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

            if (progress != null && progress > 0.01f) {
                FohDeterminateShimmerBar(
                    progress = progress,
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceDeep,
                    height = 5.dp,
                )
            } else {
                FohIndeterminateBar(
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceDeep,
                    height = 5.dp,
                )
            }

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

@Composable
private fun QueueHaltedPanel(
    queuedCount: Int,
    canDispatch: Boolean,
    onContinueQueue: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.warnBg,
        border = BorderStroke(1.dp, FohTheme.colors.warnBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(FohTheme.colors.surfaceDeep),
                contentAlignment = Alignment.Center,
            ) {
                Icon(UiIcons.Stop, null, tint = FohTheme.colors.warnAccent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Antrean dijeda",
                    style = MaterialTheme.typography.titleSmall,
                    color = FohTheme.colors.warnAccent,
                )
                Text(
                    text = "$queuedCount lagu menunggu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onContinueQueue,
                enabled = canDispatch,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(UiIcons.Play, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Lanjut", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FinishedJobRow(
    job: DownloadJob,
    matchedTrack: AudioTrack?,
    isCurrentlyPlayingTrack: Boolean,
    isPlaybackActive: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    connected: Boolean,
    canDispatch: Boolean,
    actionsBusy: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    onRequeue: () -> Unit,
    onRemove: () -> Unit,
) {
    val completed = job.status == "COMPLETED"
    val canceled = job.status == "CANCELED"
    var rowMenu by remember { mutableStateOf(false) }
    val highlighted = selected || isCurrentlyPlayingTrack
    val clickableRow = selectionMode || (completed && matchedTrack != null && connected)

    val qualityBadge = remember(matchedTrack) {
        matchedTrack?.let { UiPresentation.highBitrateBadge(it.displayName, it.sizeBytes, it.durationMs) }
    }
    val statusText = when {
        completed && matchedTrack != null -> UiPresentation.trackSubtitleWithQuality(
            matchedTrack.displayName,
            matchedTrack.artist,
            matchedTrack.sizeBytes,
            matchedTrack.durationMs,
        )
        completed -> "Selesai · dihapus dari Pustaka"
        canceled && job.stage.equals("Dilewati", ignoreCase = true) -> "Dilewati"
        canceled -> "Dibatalkan"
        else -> job.stage
    }

    Surface(
        color = if (highlighted) FohTheme.colors.safeBg else FohTheme.colors.surfaceDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (highlighted) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle),
    ) {
        Row(
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
                .semantics { this.selected = highlighted }
                .padding(horizontal = 12.dp, vertical = 9.dp),
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
                val displayTitle = matchedTrack?.let { UiPresentation.title(it.displayName) }
                    ?.takeIf { job.title == "Audio" || job.title == "Audio dari link" }
                    ?: job.title
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrentlyPlayingTrack) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
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
                        color = if (isCurrentlyPlayingTrack) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = !actionsBusy)
            } else if (canceled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FohHeaderChipButton(
                        label = "Ulang",
                        icon = UiIcons.Refresh,
                        enabled = canDispatch,
                        onClick = onRequeue,
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
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (matchedTrack != null) {
                        FohPlayToggleCircleButton(
                            playing = isCurrentlyPlayingTrack && isPlaybackActive,
                            description = "Putar ${job.title}",
                            onClick = { onPlayTrack(matchedTrack) },
                            enabled = connected && clickableRow,
                            size = 34.dp,
                            iconSize = 16.dp,
                        )
                    } else {
                        Icon(UiIcons.Check, "Selesai", tint = FohTheme.colors.safeAccent, modifier = Modifier.size(18.dp))
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
                                            if (isCurrentlyPlayingTrack && isPlaybackActive) "Hentikan"
                                            else "Putar",
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
                                onClick = { rowMenu = false; onRequeue() },
                            )
                            DropdownMenuItem(
                                text = { Text("Hapus dari daftar") },
                                leadingIcon = { Icon(UiIcons.Close, null) },
                                onClick = { rowMenu = false; onRemove() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectOperationBanner(
    ingest: IngestState,
    onCancelIngest: () -> Unit,
) {
    val progress = ingest.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val borderColor = if (ingest.error) FohTheme.colors.alertBorder else FohTheme.colors.safeBorder
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceDeep,
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
                        Text("Batal", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (ingest.busy) {
                if (progress != null && progress > 0.01f) {
                    FohDeterminateShimmerBar(
                        progress = progress,
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceCard,
                        height = 4.dp,
                    )
                } else {
                    FohIndeterminateBar(
                        color = FohTheme.colors.safeAccent,
                        trackColor = FohTheme.colors.surfaceCard,
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
private fun DownloadsEmptyState(onAddAudio: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(FohTheme.colors.safeBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(UiIcons.Download, contentDescription = null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(26.dp))
        }
        Text("Belum ada unduhan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onAddAudio, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(UiIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Tambah Lagu", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActiveTabEmptyNotice(
    failedCount: Int,
    finishedCount: Int,
    onCheckFailed: () -> Unit,
    onViewFinished: () -> Unit,
    onAddAudio: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (failedCount > 0) "Antrean aktif kosong" else "Semua unduhan selesai",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failedCount > 0) {
                    FilledTonalButton(
                        onClick = onCheckFailed,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FohTheme.colors.alertBg,
                            contentColor = FohTheme.colors.alertAccent,
                        ),
                    ) {
                        Text("Cek Gagal ($failedCount)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                } else if (finishedCount > 0) {
                    FilledTonalButton(
                        onClick = onViewFinished,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FohTheme.colors.safeBg,
                            contentColor = FohTheme.colors.safeAccent,
                        ),
                    ) {
                        Text("Lihat Selesai ($finishedCount)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(onClick = onAddAudio, shape = RoundedCornerShape(12.dp)) {
                    Text("+ Tambah", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TabEmptyNotice(label: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Ulang (${retryableIds.size})", maxLines = 1, softWrap = false)
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
                    Text("Hapus (${removableIds.size})", maxLines = 1, softWrap = false)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsQueueSheet(
    upgrade: UpgradeUiActions,
    ingest: IngestState,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist> = emptyList(),
    playlistActions: PlaylistUiActions = PlaylistUiActions(),
    ytMusic: YoutubeMusicAccountState = YoutubeMusicAccountState(),
    connected: Boolean,
    currentPlayingTrackId: String? = null,
    isPlayingActive: Boolean = false,
    onSettings: () -> Unit,
    onCancelIngest: () -> Unit,
    onAddAudio: () -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = FohTheme.colors.surfaceCard,
    ) {
        DownloadsScreen(
            upgrade = upgrade,
            ingest = ingest,
            tracks = tracks,
            playlists = playlists,
            playlistActions = playlistActions,
            ytMusic = ytMusic,
            connected = connected,
            currentPlayingTrackId = currentPlayingTrackId,
            isPlayingActive = isPlayingActive,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            onSettings = onSettings,
            onCancelIngest = onCancelIngest,
            onAddAudio = {
                onDismiss()
                onAddAudio()
            },
            onPlayTrack = onPlayTrack,
        )
    }
}
