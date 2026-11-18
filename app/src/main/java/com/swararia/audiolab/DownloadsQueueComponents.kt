package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountState
import com.swararia.audiolab.ytmusic.YoutubeMusicPremiumTier

@Composable
internal fun DownloadInlineSettingsPanel(
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
internal fun WaitingWifiNotice(
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

@Composable
internal fun QueueHaltedPanel(
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
internal fun FinishedJobRow(
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
internal fun DirectOperationBanner(
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
                FohOperationProgressBar(
                    progress = progress,
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceCard,
                    height = 4.dp,
                )
            }
            if (ingest.error && ingest.detail.isNotBlank()) {
                Text(ingest.detail, color = FohTheme.colors.alertAccent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun DownloadsEmptyState(onAddAudio: () -> Unit) {
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
internal fun ActiveTabEmptyNotice(
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
internal fun TabEmptyNotice(label: String) {
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
internal fun DownloadsBatchActionBar(
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
