package com.swararia.audiolab

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class RemoteBadge(val label: String, val highlighted: Boolean, val error: Boolean = false)

internal fun resolveConnectionBadge(state: DesktopRemoteUiState): RemoteBadge? = when (state.remotePhase) {
    RemotePhase.DISCONNECTED -> null
    RemotePhase.CONNECTING -> RemoteBadge("Menghubungkan…", highlighted = false)
    RemotePhase.PENDING -> RemoteBadge("Menunggu PC", highlighted = false)
    RemotePhase.ACTIVE -> when {
        !state.fresh && state.remoteRole == null -> RemoteBadge("Menyambung…", highlighted = false)
        !state.fresh -> RemoteBadge("Syncing", highlighted = false)
        state.playback?.online != true -> RemoteBadge("PC Offline", highlighted = false, error = true)
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
        RemoteCommandOutcome.ACCEPTED -> "Mengirim perintah ke PC…" to false
        RemoteCommandOutcome.PENDING -> "Menunggu konfirmasi pemutar PC…" to false
        RemoteCommandOutcome.APPLIED -> "Perintah diterapkan di PC" to false
        RemoteCommandOutcome.UNKNOWN -> "Hasil belum pasti — tidak dikirim ulang" to true
        RemoteCommandOutcome.EXPIRED -> "Batas waktu habis — cek PC" to true
        RemoteCommandOutcome.REJECTED -> "Perintah ditolak oleh pemutar PC" to true
    }

@Composable
internal fun ActiveRemoteHeaderBar(
    state: DesktopRemoteUiState,
    keepScreenOn: Boolean,
    canSyncGroupToPc: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onRefresh: () -> Unit,
    onSyncGroupToPc: () -> Unit,
    onShowSessionInfo: () -> Unit,
    onRequestDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val statusNote = when {
        !state.fresh && state.role == null ->
            (state.message ?: "Menyambung ulang ke Desktop… kontrol terkunci sementara.") to false
        !state.fresh -> "Menyegarkan status terbaru dari PC…" to false
        state.playback?.online != true -> "Pemutar web PC belum aktif di browser PC." to true
        state.message != null -> state.message to true
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrandLogo(28.dp)
            Text(
                text = state.endpoint ?: "Desktop PC",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            resolveConnectionBadge(state)?.let { badge ->
                StatusPill(badge = badge, compact = true)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onToggleKeepScreenOn,
                shape = CircleShape,
                color = if (keepScreenOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(
                    1.dp,
                    if (keepScreenOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                ),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = UiIcons.Sun,
                        contentDescription = if (keepScreenOn) "Layar selalu menyala aktif" else "Jaga layar tetap menyala",
                        tint = if (keepScreenOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.busy,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(UiIcons.Refresh, contentDescription = "Segarkan status PC", modifier = Modifier.size(18.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(UiIcons.More, contentDescription = "Menu sesi", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canSyncGroupToPc) {
                        DropdownMenuItem(
                            text = { Text("Terapkan folder ini di PC") },
                            leadingIcon = { Icon(UiIcons.Refresh, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onSyncGroupToPc() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Info & keamanan sesi") },
                        leadingIcon = { Icon(UiIcons.Info, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onShowSessionInfo() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Putuskan sesi PC", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(UiIcons.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onRequestDisconnect() },
                    )
                }
            }
        }
        statusNote?.let { (text, isError) ->
            Surface(
                color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        UiIcons.Info,
                        contentDescription = null,
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
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
                    val selected = group.id == selectedGroupId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectGroup(group) },
                        shape = RoundedCornerShape(999.dp),
                        label = {
                            Text(
                                text = if (group.count > 0) "${group.name} (${group.count})" else group.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
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
                            imageVector = if (groupBySections) UiIcons.Check else UiIcons.Library,
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
                            text = "Sinkron Grup PC",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
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
    val activeHighlight = MaterialTheme.colorScheme.primary
    val containerColor = if (isCurrent) {
        activeHighlight.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderStroke = if (isCurrent) {
        BorderStroke(1.5.dp, activeHighlight.copy(alpha = 0.8f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
    }
    val downloadSubtext = when (downloadState?.status) {
        DesktopRemoteTrackDownload.Status.CHECKING -> "Memeriksa lagu di PC…"
        DesktopRemoteTrackDownload.Status.DOWNLOADING -> {
            val total = downloadState.totalBytes
            if (total > 0L) {
                val pct = ((downloadState.downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                "Mengunduh ke HP… $pct%"
            } else "Mengunduh ke HP…"
        }
        DesktopRemoteTrackDownload.Status.IMPORTING -> "Memverifikasi & menyimpan ke Library…"
        DesktopRemoteTrackDownload.Status.SAVED -> "Tersimpan di Library HP"
        DesktopRemoteTrackDownload.Status.ALREADY_IN_LIBRARY -> "Sudah ada di Library HP"
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
                        text = if (isCurrentPlaying) "Sedang diputar di PC" else "Aktif (Standby) di PC",
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
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = activeColor,
                    modifier = Modifier.size(17.dp),
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
                CircularProgressIndicator(
                    progress = { pct },
                    strokeWidth = 2.5.dp,
                    color = activeColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
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
internal fun FohTransportDock(
    state: DesktopRemoteUiState,
    activeSlotNumber: Int?,
    isPlaying: Boolean,
    isFading: Boolean,
    supportsFadeOut: Boolean,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    onCommand: (String) -> Unit,
) {
    val playback = state.playback
    val badge = resolveTransportBadge(state.fresh, playback)
    val durationSec = playback?.duration?.toDouble()
    val timeSec = playback?.time?.toDouble()
    val progress = if (timeSec != null && durationSec != null && durationSec > 0.0) {
        (timeSec / durationSec).toFloat().coerceIn(0f, 1f)
    } else 0f
    val percentLabel = if (durationSec != null && durationSec > 0.0) {
        "${(progress * 100f).roundToInt().coerceIn(0, 100)}%"
    } else null
    val elapsedLabel = formatSecondsLabel(timeSec)
    val remainingLabel = formatRemainingLabel(timeSec, durationSec)
    val trackDisplayTitle = buildString {
        if (activeSlotNumber != null) {
            append(activeSlotNumber.toString().padStart(2, '0'))
            append(" • ")
        }
        append(playback?.title?.takeIf { it.isNotBlank() } ?: "Belum ada track dipilih di PC")
    }
    val supportsPrevNext = "prev" in state.commands || "next" in state.commands
    val waitingCommand = state.waitingCommand
    val showPause = isPlaying && !isFading
    val playEnabled = state.canControl && ((if (showPause) "pause" else "play") in state.commands)
    val stopEnabled = state.canControl && "stop" in state.commands
    val fadeEnabled = state.canFadeOut
    val onTogglePlayPause = { onCommand(if (showPause) "pause" else "play") }
    val onStop = { onCommand("stop") }
    val onFadeOut = { onCommand("fade_out") }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isFading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            if (collapsed) {
                CollapsedFohTransportBody(
                    state = state,
                    badge = badge,
                    trackDisplayTitle = trackDisplayTitle,
                    elapsedLabel = elapsedLabel,
                    remainingLabel = remainingLabel,
                    waitingCommand = waitingCommand,
                    isFading = isFading,
                    showPause = showPause,
                    hasLoadedTrack = playback?.title != null,
                    playEnabled = playEnabled,
                    stopEnabled = stopEnabled,
                    supportsFadeOut = supportsFadeOut,
                    fadeEnabled = fadeEnabled,
                    onToggleCollapse = onToggleCollapse,
                    onTogglePlayPause = onTogglePlayPause,
                    onStop = onStop,
                    onFadeOut = onFadeOut,
                )
            } else {
                ExpandedFohTransportBody(
                    state = state,
                    badge = badge,
                    trackDisplayTitle = trackDisplayTitle,
                    percentLabel = percentLabel,
                    elapsedLabel = elapsedLabel,
                    remainingLabel = remainingLabel,
                    waitingCommand = waitingCommand,
                    isFading = isFading,
                    showPause = showPause,
                    hasLoadedTrack = playback?.title != null,
                    playEnabled = playEnabled,
                    stopEnabled = stopEnabled,
                    supportsPrevNext = supportsPrevNext,
                    supportsFadeOut = supportsFadeOut,
                    fadeEnabled = fadeEnabled,
                    onToggleCollapse = onToggleCollapse,
                    onCommand = onCommand,
                    onTogglePlayPause = onTogglePlayPause,
                    onStop = onStop,
                    onFadeOut = onFadeOut,
                )
            }
        }
    }
}

@Composable
private fun CollapsedFohTransportBody(
    state: DesktopRemoteUiState,
    badge: RemoteBadge,
    trackDisplayTitle: String,
    elapsedLabel: String,
    remainingLabel: String?,
    waitingCommand: Boolean,
    isFading: Boolean,
    showPause: Boolean,
    hasLoadedTrack: Boolean,
    playEnabled: Boolean,
    stopEnabled: Boolean,
    supportsFadeOut: Boolean,
    fadeEnabled: Boolean,
    onToggleCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onFadeOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactCircleButton(
                icon = UiIcons.ChevronUp,
                description = "Perluas pemutar desktop",
                onClick = onToggleCollapse,
                size = 30.dp,
                iconSize = 16.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Perluas kontrol desktop") { onToggleCollapse() },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dotColor = when {
                        badge.error -> MaterialTheme.colorScheme.error
                        badge.highlighted -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = trackDisplayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!hasLoadedTrack) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val statusMeta = when {
                    isFading -> "Fading 3s…"
                    waitingCommand -> "Mengirim…"
                    state.role == "view_only" -> "Pantau Saja · $elapsedLabel"
                    remainingLabel != null -> "$elapsedLabel · $remainingLabel"
                    else -> elapsedLabel
                }
                Text(
                    text = statusMeta,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isFading || remainingLabel != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.role == "view_only") {
                StatusPill(badge = badge, compact = true)
            } else {
                val primaryPlay = playEnabled && (showPause || hasLoadedTrack)
                CompactCircleButton(
                    icon = if (showPause) UiIcons.Pause else UiIcons.Play,
                    description = if (showPause) "Pause desktop" else "Play desktop",
                    onClick = onTogglePlayPause,
                    enabled = playEnabled,
                    size = 34.dp,
                    iconSize = 16.dp,
                    containerColor = if (primaryPlay) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    contentColor = if (primaryPlay) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                CompactCircleButton(
                    icon = UiIcons.Stop,
                    description = "Stop desktop",
                    onClick = onStop,
                    enabled = stopEnabled,
                    size = 34.dp,
                    iconSize = 15.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (stopEnabled) 0.75f else 0.4f),
                )
                if (supportsFadeOut) {
                    Surface(
                        onClick = onFadeOut,
                        enabled = fadeEnabled,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else if (fadeEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (fadeEnabled || isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .semantics { stateDescription = if (isFading) "Fading" else "Idle" },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 9.dp),
                        ) {
                            Text(
                                text = if (isFading) "Fading" else "Fade",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (fadeEnabled || isFading) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        state.outcome?.let { outcome ->
            val (label, isError) = formatOutcomeFeedback(outcome)
            if (isError) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedFohTransportBody(
    state: DesktopRemoteUiState,
    badge: RemoteBadge,
    trackDisplayTitle: String,
    percentLabel: String?,
    elapsedLabel: String,
    remainingLabel: String?,
    waitingCommand: Boolean,
    isFading: Boolean,
    showPause: Boolean,
    hasLoadedTrack: Boolean,
    playEnabled: Boolean,
    stopEnabled: Boolean,
    supportsPrevNext: Boolean,
    supportsFadeOut: Boolean,
    fadeEnabled: Boolean,
    onToggleCollapse: () -> Unit,
    onCommand: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onFadeOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Perkecil pemutar desktop") { onToggleCollapse() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (percentLabel != null) "PEMUTAR PC · $percentLabel" else "PEMUTAR PC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (waitingCommand) {
                        Text(
                            text = "Mengirim…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = trackDisplayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (!hasLoadedTrack) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(badge = badge, compact = true)
                    CompactCircleButton(
                        icon = UiIcons.ChevronDown,
                        description = "Perkecil pemutar desktop",
                        onClick = onToggleCollapse,
                        size = 26.dp,
                        iconSize = 15.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = elapsedLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    remainingLabel?.let { rem ->
                        Text(
                            text = "($rem)",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        state.outcome?.let { outcome ->
            val (label, isError) = formatOutcomeFeedback(outcome)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isError) UiIcons.Info else UiIcons.Check,
                        contentDescription = null,
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (state.role == "view_only") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Mode Pantau Saja · Kontrol transport dinonaktifkan oleh PC.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FohDeckButton(
                    label = "Prev",
                    icon = UiIcons.Previous,
                    enabled = supportsPrevNext && state.canStepTrack && "prev" in state.commands,
                    filled = false,
                    outlined = false,
                    onClick = { onCommand("prev") },
                    modifier = Modifier.weight(1f),
                )
                FohDeckButton(
                    label = if (showPause) "Pause" else "Play",
                    icon = if (showPause) UiIcons.Pause else UiIcons.Play,
                    enabled = playEnabled,
                    filled = showPause || (hasLoadedTrack && "play" in state.commands),
                    outlined = false,
                    onClick = onTogglePlayPause,
                    modifier = Modifier.weight(1f),
                )
                FohDeckButton(
                    label = "Stop",
                    icon = UiIcons.Stop,
                    enabled = stopEnabled,
                    filled = false,
                    outlined = false,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                )
                FohDeckButton(
                    label = "Next",
                    icon = UiIcons.Next,
                    enabled = supportsPrevNext && state.canStepTrack && "next" in state.commands,
                    filled = false,
                    outlined = false,
                    onClick = { onCommand("next") },
                    modifier = Modifier.weight(1f),
                )
            }

            if (supportsFadeOut) {
                OutlinedButton(
                    onClick = onFadeOut,
                    enabled = fadeEnabled,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (fadeEnabled || isFading) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (fadeEnabled || isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .semantics { stateDescription = if (isFading) "Fading" else "Idle" },
                ) {
                    Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isFading) "Fading Out (3s)…" else "Fade Out 3s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isFading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
            } else {
                Text(
                    text = "Fade Out 3s belum aktif di server PC (restart Swararia PC).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun NoticeCard(text: String, isError: Boolean = false, icon: ImageVector = UiIcons.Info) {
    val container = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val content = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) content else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun RemoteHeaderBar(state: DesktopRemoteUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandLogo(38.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Desktop Remote", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = state.endpoint?.let { "Desktop • $it" } ?: "Kendalikan pemutar PC dari HP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        resolveConnectionBadge(state)?.let { badge -> StatusPill(badge) }
    }
}

@Composable
internal fun DisconnectedPairingSection(
    input: String,
    scanning: Boolean,
    busy: Boolean,
    scanFeedback: String?,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnectManual: () -> Unit,
) {
    val validPreviewEndpoint = remember(input) {
        if (input.isBlank()) null
        else runCatching { DesktopRemotePairing.parse(input).endpoint }.getOrNull()
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("Hubungkan ke Pemutar PC", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Di PC: buka menu Android Remote → Hubungkan Android → Buat kode pairing, lalu pindai kode QR dari layar PC.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onScanQr,
                enabled = !busy && !scanning,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(UiIcons.QrScan, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Menyiapkan Scanner…" else "Scan QR dari PC")
            }
            if (scanning || busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            scanFeedback?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    errorMessage?.let { NoticeCard(it, isError = true) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Atau Pairing Manual", style = MaterialTheme.typography.titleSmall)
            Text(
                "Gunakan hanya jika kamera tidak tersedia. Jangan kirim teks pairing lewat chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Teks pairing swararia1|…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            if (validPreviewEndpoint != null) {
                Text(
                    text = "Endpoint terdeteksi: $validPreviewEndpoint",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (input.isNotEmpty()) {
                Text(
                    text = "Format valid: swararia1|IP_LAN:Port|<pin>|<invite>",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onConnectManual,
                enabled = input.isNotEmpty() && !busy && !scanning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            ) { Text("Connect") }
        }
    }
}

@Composable
internal fun PendingApprovalCard(state: DesktopRemoteUiState, onRefresh: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.remotePhase == RemotePhase.CONNECTING) "Menghubungkan ke PC…" else "Menunggu Persetujuan di PC",
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            Text(
                if (state.remotePhase == RemotePhase.CONNECTING) {
                    "Memverifikasi identitas TLS dan mendaftarkan sesi pairing."
                } else {
                    "Di jendela Swararia pada PC Anda, klik Izinkan kontrol / Pantau saja untuk menyetujui HP ini."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Cek Status") }
                TextButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Batalkan") }
            }
        }
    }
}

@Composable
internal fun ReplacePlayingTrackDialog(
    currentTitle: String,
    targetTrack: DesktopRemoteTrack,
    preferredAutoPlay: Boolean,
    onCueOnly: () -> Unit,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti Lagu di PC?") },
        text = {
            Text("PC sedang aktif pada \"$currentTitle\". Memilih \"${targetTrack.title}\" akan menghentikan lagu tersebut.")
        },
        confirmButton = {
            Button(onClick = if (preferredAutoPlay) onPlayNow else onCueOnly) {
                Text(if (preferredAutoPlay) "Putar Sekarang" else "Siapkan (Cue)")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDismiss) { Text("Batal") }
                OutlinedButton(onClick = if (preferredAutoPlay) onCueOnly else onPlayNow) {
                    Text(if (preferredAutoPlay) "Cue Saja" else "Putar Langsung")
                }
            }
        },
    )
}

@Composable
internal fun DisconnectConfirmDialog(
    endpoint: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Putuskan Sesi Remote?") },
        text = {
            Text(
                "Sesi terenkripsi dengan ${endpoint ?: "PC"} akan dihapus dari HP ini. " +
                    "Musik yang sedang berjalan di PC tidak akan dihentikan.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Putuskan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
internal fun SessionInfoDialog(
    state: DesktopRemoteUiState,
    onDismiss: () -> Unit,
) {
    val roleLabel = when (state.role) {
        "control" -> "Kontrol penuh"
        "view_only" -> "Pantau saja (View-Only)"
        else -> "-"
    }
    val mediaLabel = if (state.mediaSupported) {
        "Aktif di PC (ketuk ikon unduh pada baris lagu untuk menyimpan ke Library HP)"
    } else {
        "Nonaktif (aktifkan di modal Android Remote pada browser PC bila perlu mengunduh)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info & Keamanan Sesi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Endpoint: ${state.endpoint ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Text("Peran HP ini: $roleLabel", style = MaterialTheme.typography.bodySmall)
                Text("Unduh lagu ke HP: $mediaLabel", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Menutup aplikasi tidak menghentikan audio PC. Token sesi dienkripsi dengan Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

