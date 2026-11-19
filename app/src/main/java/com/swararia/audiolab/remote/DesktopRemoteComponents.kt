package com.swararia.audiolab.remote

import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohFilterChip
import com.swararia.audiolab.FohSpinningIndicator
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.LibraryRules
import com.swararia.audiolab.UiIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class RemoteBadge(val label: String, val highlighted: Boolean, val error: Boolean = false)

internal fun resolveConnectionBadge(state: DesktopRemoteUiState): RemoteBadge? = when (state.remotePhase) {
    RemotePhase.DISCONNECTED -> null
    RemotePhase.CONNECTING -> RemoteBadge("Menghubungkan…", highlighted = false)
    RemotePhase.PENDING -> RemoteBadge("Menunggu Persetujuan", highlighted = false)
    RemotePhase.ACTIVE -> when {
        !state.fresh && state.remoteRole == null -> RemoteBadge("Menyambung…", highlighted = false)
        !state.fresh -> RemoteBadge("Syncing", highlighted = false)
        state.playback?.online != true -> RemoteBadge("Target Offline", highlighted = false, error = true)
        state.remoteRole == RemoteRole.VIEW_ONLY -> RemoteBadge("Pantau Saja", highlighted = false)
        else -> RemoteBadge("FOH Ready", highlighted = true)
    }
}

internal fun resolveTransportBadge(fresh: Boolean, playback: DesktopRemotePlayback?): RemoteBadge = when {
    !fresh -> RemoteBadge("SYNC", highlighted = false)
    playback == null || !playback.online -> RemoteBadge("OFFLINE", highlighted = false, error = true)
    playback.transport == "FADING" -> RemoteBadge("FADING 3S", highlighted = true)
    playback.playing == true || playback.transport == "PLAYING" -> RemoteBadge("PLAYING", highlighted = true)
    playback.transport == "PAUSED" -> RemoteBadge("PAUSED", highlighted = false)
    playback.transport == "STOPPED" || playback.transport == "CUED" -> RemoteBadge("CUED", highlighted = false)
    else -> RemoteBadge(playback.transport ?: "STANDBY", highlighted = false)
}

internal fun formatSecondsLabel(seconds: Number?): String =
    seconds?.let { LibraryRules.durationLabel((it.toDouble() * 1000.0).toLong().coerceAtLeast(0L)) } ?: "--:--"

internal fun formatRemainingLabel(timeSec: Number?, durationSec: Number?): String? {
    val cur = timeSec?.toDouble() ?: return null
    val dur = durationSec?.toDouble() ?: return null
    if (dur <= 0.0) return null
    val remMs = ((dur - cur).coerceAtLeast(0.0) * 1000.0).toLong()
    return "-${LibraryRules.durationLabel(remMs)}"
}

internal fun formatOutcomeFeedback(outcome: String): Pair<String, Boolean> =
    when (RemoteCommandOutcome.fromWire(outcome) ?: RemoteCommandOutcome.REJECTED) {
        RemoteCommandOutcome.ACCEPTED -> "Mengirim perintah ke target…" to false
        RemoteCommandOutcome.PENDING -> "Menunggu konfirmasi pemutar target…" to false
        RemoteCommandOutcome.APPLIED -> "Perintah diterapkan" to false
        RemoteCommandOutcome.UNKNOWN -> "Hasil belum pasti — tidak dikirim ulang" to true
        RemoteCommandOutcome.EXPIRED -> "Batas waktu habis — cek perangkat target" to true
        RemoteCommandOutcome.REJECTED -> "Perintah ditolak oleh pemutar target" to true
    }

@Composable
internal fun ActiveRemoteHeaderBar(
    state: DesktopRemoteUiState,
    keepScreenOn: Boolean,
    canSyncGroupToPc: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onRefresh: () -> Unit,
    onSyncGroupToPc: () -> Unit,
    onOpenFolderSync: (() -> Unit)? = null,
    onOpenUpload: (() -> Unit)? = null,
    onShowSessionInfo: () -> Unit,
    onRequestDisconnect: () -> Unit,
) {
    val foh = FohTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val statusNote = when {
        !state.fresh && state.role == null ->
            (state.message ?: "Menyambung ulang ke perangkat target… kontrol terkunci sementara.") to false
        !state.fresh -> "Menyegarkan status terbaru dari perangkat target…" to false
        state.playback?.online != true -> "Pemutar di perangkat target belum aktif." to true
        state.message != null -> state.message to true
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = foh.surfaceCard,
            border = BorderStroke(1.dp, foh.borderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.endpoint ?: "Perangkat Target",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val roleLabel = if (state.role == "control") "Kendali Penuh" else if (state.role == "view_only") "Pantau Saja" else "Menyambung"
                    val metaTag = when {
                        !state.fresh && state.role == null -> "Menunggu perangkat target…"
                        state.fresh && state.playback?.online == true && state.tracks.isNotEmpty() ->
                            "Perangkat target · ${state.tracks.size} lagu · $roleLabel"
                        state.fresh && state.playback?.online != true -> "Pemutar target nonaktif · $roleLabel"
                        else -> "Perangkat target · $roleLabel"
                    }
                    Text(
                        text = metaTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CompactCircleButton(
                    icon = UiIcons.Sun,
                    description = if (keepScreenOn) "Layar selalu menyala aktif" else "Jaga layar tetap menyala",
                    onClick = onToggleKeepScreenOn,
                    size = 34.dp,
                    iconSize = 17.dp,
                    containerColor = if (keepScreenOn) foh.safeBg else foh.surfaceDeep,
                    contentColor = if (keepScreenOn) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    CompactCircleButton(
                        icon = UiIcons.More,
                        description = "Menu sesi remote",
                        onClick = { menuOpen = true },
                        size = 34.dp,
                        iconSize = 18.dp,
                        containerColor = foh.surfaceDeep,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Segarkan status") },
                            leadingIcon = { Icon(UiIcons.Refresh, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                        if (canSyncGroupToPc) {
                            DropdownMenuItem(
                                text = { Text("Terapkan folder ini ke target") },
                                leadingIcon = { Icon(UiIcons.Refresh, null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onSyncGroupToPc() },
                            )
                        }
                        if (state.canSyncFolders && onOpenFolderSync != null) {
                            DropdownMenuItem(
                                text = { Text("Sinkron folder / playlist") },
                                leadingIcon = { Icon(UiIcons.Folder, null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onOpenFolderSync() },
                            )
                        }
                        if (state.canUploadToDesktop && onOpenUpload != null) {
                            DropdownMenuItem(
                                text = { Text("Unggah audio ke PC") },
                                leadingIcon = { Icon(UiIcons.ArrowUp, null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onOpenUpload() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Info & keamanan sesi") },
                            leadingIcon = { Icon(UiIcons.Info, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onShowSessionInfo() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Putuskan sesi", color = foh.alertAccent) },
                            leadingIcon = { Icon(UiIcons.Close, null, tint = foh.alertAccent, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onRequestDisconnect() },
                        )
                    }
                }
            }
        }
        statusNote?.let { (text, isError) ->
            Surface(
                color = if (isError) foh.alertBg else foh.surfaceDeep,
                border = BorderStroke(1.dp, if (isError) foh.alertBorder else foh.borderSubtle),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        UiIcons.Info,
                        contentDescription = null,
                        tint = if (isError) foh.alertAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) foh.alertAccent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteSearchFilterStrip(
    query: String,
    hasActiveTrackInCatalog: Boolean,
    replaceGuardEnabled: Boolean,
    groups: List<DesktopRemoteTrackGroup>,
    selectedGroupId: String?,
    activePcGroupId: String?,
    canSwitchGroup: Boolean,
    groupBySections: Boolean,
    hasGroupedSections: Boolean,
    allCollapsed: Boolean,
    loadedTrackCount: Int,
    totalTracksCount: Int,
    onQueryChange: (String) -> Unit,
    onJumpToActive: () -> Unit,
    onToggleReplaceGuard: () -> Unit,
    onSelectGroup: (DesktopRemoteTrackGroup) -> Unit,
    onToggleGroupBySections: () -> Unit,
    onToggleCollapseAll: () -> Unit,
    onSyncGroupToPc: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = FohTheme.colors.surfaceDeep,
            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    UiIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Cari judul lagu…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { if (it.length <= 80) onQueryChange(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(UiIcons.Close, contentDescription = "Hapus pencarian", modifier = Modifier.size(15.dp))
                    }
                }
                Surface(
                    onClick = onJumpToActive,
                    enabled = hasActiveTrackInCatalog,
                    shape = RoundedCornerShape(999.dp),
                    color = if (hasActiveTrackInCatalog) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(
                        1.dp,
                        if (hasActiveTrackInCatalog) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    ),
                ) {
                    Text(
                        text = "Ke Aktif",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasActiveTrackInCatalog) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
                Surface(
                    onClick = onToggleReplaceGuard,
                    shape = RoundedCornerShape(999.dp),
                    color = if (replaceGuardEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (replaceGuardEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Text(
                        text = if (replaceGuardEnabled) "Guard ON" else "Guard OFF",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (replaceGuardEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (groups.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(groups, key = { it.id }, contentType = { "remote-group-chip" }) { group ->
                    FohFilterChip(
                        label = if (group.count > 0) "${group.name} (${group.count})" else group.name,
                        selected = group.id == selectedGroupId,
                        onClick = { onSelectGroup(group) },
                    )
                }
            }
        }

        val showSectionToggle = (selectedGroupId == "g_all" || selectedGroupId == null) && groups.size > 2
        val showSyncGroup = canSwitchGroup && selectedGroupId != null && selectedGroupId != activePcGroupId
        val showTruncatedNote = totalTracksCount > loadedTrackCount && loadedTrackCount > 0
        if (showSectionToggle || hasGroupedSections || showSyncGroup || showTruncatedNote) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSectionToggle) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleGroupBySections)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (groupBySections) UiIcons.Check else UiIcons.Folder,
                            contentDescription = null,
                            tint = if (groupBySections) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Per folder",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (groupBySections) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hasGroupedSections) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleCollapseAll)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (allCollapsed) UiIcons.ChevronRight else UiIcons.ChevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (allCollapsed) "Buka semua" else "Tutup semua",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showSyncGroup) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSyncGroupToPc)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            UiIcons.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Sinkron Grup",
                            style = MaterialTheme.typography.labelMedium,
                            color = FohTheme.colors.safeAccent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (showTruncatedNote) {
                    Text(
                        text = "$loadedTrackCount/$totalTracksCount lagu (pilih folder untuk sisanya)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteTrackGroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) UiIcons.ChevronRight else UiIcons.ChevronDown,
            contentDescription = if (collapsed) "Buka folder $name" else "Tutup folder $name",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count lagu",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TrackPickerRow(
    slotNumber: Int,
    track: DesktopRemoteTrack,
    isCurrent: Boolean,
    isCurrentPlaying: Boolean,
    canSelect: Boolean,
    canControl: Boolean,
    canDownloadMedia: Boolean,
    downloadState: DesktopRemoteTrackDownload?,
    onCueOrSelect: () -> Unit,
    onPlayOrPause: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val foh = FohTheme.colors
    val activeHighlight = foh.safeAccent
    val containerColor = if (isCurrent) {
        foh.safeBg
    } else {
        foh.surfaceCard
    }
    val borderStroke = if (isCurrent) {
        BorderStroke(1.5.dp, foh.safeBorder)
    } else {
        BorderStroke(1.dp, foh.borderSubtle)
    }
    val downloadSubtext = when (downloadState?.status) {
        DesktopRemoteTrackDownload.Status.CHECKING -> "Memeriksa file…"
        DesktopRemoteTrackDownload.Status.DOWNLOADING -> {
            val total = downloadState.totalBytes
            if (total > 0L) {
                val pct = ((downloadState.downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                "Mengunduh… $pct%"
            } else "Mengunduh…"
        }
        DesktopRemoteTrackDownload.Status.IMPORTING -> "Memverifikasi & menyimpan ke Library…"
        DesktopRemoteTrackDownload.Status.SAVED -> "Tersimpan di Library"
        DesktopRemoteTrackDownload.Status.ALREADY_IN_LIBRARY -> "Sudah ada di Library"
        DesktopRemoteTrackDownload.Status.FAILED -> downloadState.detail ?: "Unduhan gagal"
        null -> null
    }

    Surface(
        onClick = onCueOrSelect,
        enabled = canSelect,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = borderStroke,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "[${slotNumber.toString().padStart(2, '0')}]",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) activeHighlight else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) activeHighlight else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Text(
                        text = if (isCurrentPlaying) "Sedang diputar" else "Aktif · Standby",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeHighlight,
                    )
                }
                downloadSubtext?.let { text ->
                    val failed = downloadState?.status == DesktopRemoteTrackDownload.Status.FAILED
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canDownloadMedia || downloadState != null) {
                TrackDownloadActionButton(
                    trackTitle = track.title,
                    downloadState = downloadState,
                    canStart = canDownloadMedia,
                    onDownload = onDownload,
                    onCancel = onCancelDownload,
                )
            }
            if (isCurrentPlaying) {
                FilledIconButton(
                    onClick = onPlayOrPause,
                    enabled = canControl,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = activeHighlight,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(UiIcons.Pause, contentDescription = "Pause", modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(
                    onClick = onPlayOrPause,
                    enabled = if (isCurrent) canControl else canSelect,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        UiIcons.Play,
                        contentDescription = "Putar ${track.title}",
                        tint = if (isCurrent) activeHighlight else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackDownloadActionButton(
    trackTitle: String,
    downloadState: DesktopRemoteTrackDownload?,
    canStart: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    when (downloadState?.status) {
        DesktopRemoteTrackDownload.Status.CHECKING,
        DesktopRemoteTrackDownload.Status.IMPORTING -> {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                FohSpinningIndicator(
                    size = 18.dp,
                    strokeWidth = 2.2.dp,
                    color = activeColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        DesktopRemoteTrackDownload.Status.DOWNLOADING -> {
            val total = downloadState.totalBytes
            val pct = if (total > 0L) (downloadState.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                if (pct > 0.01f) {
                    CircularProgressIndicator(
                        progress = { pct },
                        strokeWidth = 2.5.dp,
                        color = activeColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    FohSpinningIndicator(
                        size = 20.dp,
                        strokeWidth = 2.4.dp,
                        color = activeColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
        DesktopRemoteTrackDownload.Status.SAVED,
        DesktopRemoteTrackDownload.Status.ALREADY_IN_LIBRARY -> {
            IconButton(
                onClick = onDownload,
                enabled = canStart,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    UiIcons.Check,
                    contentDescription = "Tersimpan di Library HP: $trackTitle",
                    tint = activeColor,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        DesktopRemoteTrackDownload.Status.FAILED -> {
            IconButton(
                onClick = onDownload,
                enabled = canStart,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    UiIcons.Download,
                    contentDescription = "Ulangi unduh $trackTitle",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        null -> {
            IconButton(
                onClick = onDownload,
                enabled = canStart,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    UiIcons.Download,
                    contentDescription = "Unduh $trackTitle ke HP",
                    tint = if (canStart) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(badge: RemoteBadge, modifier: Modifier = Modifier, compact: Boolean = false) {
    val containerColor = when {
        badge.error -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        badge.highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        badge.error -> MaterialTheme.colorScheme.error
        badge.highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
    ) {
        Text(
            text = badge.label,
            style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp) else MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp),
        )
    }
}

@Composable
internal fun NoticeCard(
    text: String,
    isError: Boolean = false,
    icon: ImageVector = UiIcons.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val foh = FohTheme.colors
    val container = if (isError) foh.alertBg else foh.surfaceCard
    val border = if (isError) foh.alertBorder else foh.borderSubtle
    val content = if (isError) foh.alertAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) content else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun RemoteScopePill(label: String, highlighted: Boolean = false) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) foh.safeBg else foh.surfaceDeep,
        border = BorderStroke(1.dp, if (highlighted) foh.safeBorder else foh.borderSubtle),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

