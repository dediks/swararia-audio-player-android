package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private data class PendingTrackChoice(val track: DesktopRemoteTrack, val preferredAutoPlay: Boolean)

/** No saved state, clipboard reads, camera permission, discovery, or playback ownership. */
@Composable
fun DesktopRemoteScreen(session: DesktopRemoteSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf<String?>(null) }
    var pendingScanned by remember { mutableStateOf<String?>(null) }
    var trackQuery by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var groupBySections by remember { mutableStateOf(true) }
    var collapsedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingTrackSwitch by remember { mutableStateOf<PendingTrackChoice?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(session, lifecycle) {
        val lease = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    session.visible(lease)
                    pendingScanned?.let { scanned ->
                        pendingScanned = null
                        session.connect(scanned)
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.hidden(lease)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.visible(lease)
        onDispose {
            pendingScanned = null
            session.hidden(lease)
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == "pending" || state.phase == "active") {
            input = ""
            scanFeedback = null
            pendingScanned = null
        } else if (state.phase == "disconnected") {
            trackQuery = ""
            selectedGroupId = null
            collapsedGroupIds = emptySet()
            pendingTrackSwitch = null
        }
    }

    if (state.phase != "active") {
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RemoteHeaderBar(state)
            if (state.phase == "disconnected") {
                DisconnectedPairingSection(
                    input = input,
                    scanning = scanning,
                    busy = state.busy,
                    scanFeedback = scanFeedback,
                    errorMessage = state.message,
                    onInputChange = {
                        if (it.length <= 256) {
                            input = it
                            scanFeedback = null
                            pendingScanned = null
                        }
                    },
                    onScanQr = {
                        if (scanning || state.busy) return@DisconnectedPairingSection
                        scanning = true
                        scanFeedback = null
                        pendingScanned = null
                        coroutineScope.launch {
                            try {
                                when (val res = DesktopRemoteQrScanner.scan(context) { scanFeedback = it }) {
                                    is DesktopRemoteScanResult.Scanned -> {
                                        scanFeedback = null
                                        input = ""
                                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            pendingScanned = null
                                            session.connect(res.rawText)
                                        } else {
                                            // Scanner overlay may briefly pause the Activity; deliver right after ON_RESUME restores visibility.
                                            pendingScanned = res.rawText
                                        }
                                    }
                                    DesktopRemoteScanResult.Canceled -> scanFeedback = null
                                    is DesktopRemoteScanResult.Failed -> scanFeedback = res.message
                                }
                            } finally {
                                scanning = false
                            }
                        }
                    },
                    onConnectManual = {
                        scanFeedback = null
                        pendingScanned = null
                        session.connect(input)
                    },
                )
            } else {
                PendingApprovalCard(
                    state = state,
                    onRefresh = { session.refresh() },
                    onCancel = {
                        session.disconnect()
                        input = ""
                    },
                )
            }
        }
        return
    }

    val playback = state.playback
    val isPlaying = playback?.playing == true || playback?.transport == "PLAYING"
    val isFading = playback?.transport == "FADING"
    val supportsFadeOut = "fade_out" in state.commands
    val supportsSelectTrack = "select_track" in state.commands
    val effectiveGroupId = remember(selectedGroupId, state.activeGroupId, state.groups) {
        when {
            selectedGroupId != null && state.groups.any { it.id == selectedGroupId } -> selectedGroupId
            else -> state.activeGroupId?.takeIf { id -> state.groups.any { it.id == id } } ?: state.groups.firstOrNull()?.id
        }
    }
    val showAllGroups = effectiveGroupId == "g_all" || effectiveGroupId == null || state.groups.isEmpty()
    val indexedTracks = remember(state.tracks) {
        state.tracks.mapIndexed { index, track -> (index + 1) to track }
    }
    val groupFilteredTracks = remember(indexedTracks, effectiveGroupId, showAllGroups) {
        if (showAllGroups) indexedTracks
        else indexedTracks.filter { (_, track) -> track.groupId == effectiveGroupId }
    }
    val trimmedQuery = trackQuery.trim()
    val filteredTracks = remember(groupFilteredTracks, trimmedQuery) {
        if (trimmedQuery.isEmpty()) groupFilteredTracks
        else groupFilteredTracks.filter { (_, track) -> track.title.contains(trimmedQuery, ignoreCase = true) }
    }
    val groupNameById = remember(state.groups) { state.groups.associate { it.id to it.name } }
    val groupedSections = remember(filteredTracks, state.groups, groupNameById, showAllGroups, groupBySections, trimmedQuery) {
        if (!groupBySections || !showAllGroups || trimmedQuery.isNotEmpty() || state.groups.size <= 2) null
        else {
            val byGroup = linkedMapOf<String, MutableList<Pair<Int, DesktopRemoteTrack>>>()
            for (pair in filteredTracks) {
                val gid = pair.second.groupId ?: "_other"
                byGroup.getOrPut(gid) { mutableListOf() }.add(pair)
            }
            if (byGroup.size <= 1) null
            else byGroup.entries.map { (gid, list) ->
                Triple(gid, groupNameById[gid] ?: "Lainnya", list)
            }
        }
    }
    val listState = rememberLazyListState()
    val showCompactBar by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }

    fun handleTrackChoice(track: DesktopRemoteTrack, autoPlay: Boolean) {
        if (!state.canSelectTrack) return
        if (track.id == state.currentTrackId) {
            if (autoPlay) session.command(if (isPlaying && !isFading) "pause" else "play")
            return
        }
        if (isPlaying || isFading) {
            pendingTrackSwitch = PendingTrackChoice(track, autoPlay)
        } else {
            session.selectTrack(track.id, autoPlay)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showCompactBar) {
            CompactStickyControlBar(
                state = state,
                isPlaying = isPlaying,
                isFading = isFading,
                onCommand = { session.command(it) },
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteHeaderBar(state)
                    if (!state.fresh) {
                        NoticeCard("Menyegarkan status terbaru dari PC… kontrol terkunci sementara.")
                    } else if (playback?.online != true) {
                        NoticeCard("Pemutar web PC belum aktif. Buka halaman Swararia di browser PC.", isError = true)
                    } else if (state.role == "view_only") {
                        NoticeCard("Mode View-Only: Anda hanya dapat memantau status dan daftar lagu PC.")
                    }
                }
            }
            item(key = "now_playing") {
                NowPlayingDeckCard(state.fresh, playback)
            }
            item(key = "transport") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TransportDeckCard(
                        state = state,
                        isPlaying = isPlaying,
                        isFading = isFading,
                        supportsFadeOut = supportsFadeOut,
                        onCommand = { session.command(it) },
                    )
                    state.outcome?.let { outcome ->
                        val (label, isError) = formatOutcomeFeedback(outcome)
                        NoticeCard(label, isError = isError, icon = if (isError) UiIcons.Info else UiIcons.Check)
                    }
                    state.message?.let { message -> NoticeCard(message, isError = state.outcome == null) }
                }
            }
            if (supportsSelectTrack) {
                item(key = "library_header") {
                    TrackLibraryHeaderCard(
                        playlist = state.playlist,
                        totalCount = state.tracks.size,
                        filteredCount = filteredTracks.size,
                        query = trackQuery,
                        groups = state.groups,
                        selectedGroupId = effectiveGroupId,
                        activePcGroupId = state.activeGroupId,
                        canSwitchGroup = state.canSwitchGroup,
                        groupBySections = groupBySections,
                        onToggleGroupBySections = { groupBySections = !groupBySections },
                        onSelectGroup = { group ->
                            selectedGroupId = group.id
                            val hasLocalTracks = group.id == "g_all" || indexedTracks.any { (_, t) -> t.groupId == group.id }
                            val needsPcSwitch = state.canSwitchGroup && (
                                !hasLocalTracks ||
                                    (group.id == "g_all" && state.activeGroupId != "g_all") ||
                                    (state.totalTracks > state.tracks.size && group.id != state.activeGroupId)
                            )
                            if (needsPcSwitch) session.selectGroup(group.id)
                        },
                        onSyncGroupToPc = {
                            effectiveGroupId?.let { session.selectGroup(it) }
                        },
                        onQueryChange = { trackQuery = it },
                    )
                }
                if (state.tracks.isEmpty()) {
                    item(key = "library_empty") {
                        NoticeCard(
                            if (playback?.online == true) "Daftar lagu PC masih kosong atau belum tersinkron." else "Menunggu pemutar PC online untuk memuat daftar lagu.",
                        )
                    }
                } else if (filteredTracks.isEmpty()) {
                    item(key = "library_no_match") {
                        NoticeCard(
                            if (trimmedQuery.isNotEmpty()) "Tidak ada lagu yang cocok dengan \"$trimmedQuery\"."
                            else "Belum ada lagu terisi pada grup ini.",
                        )
                    }
                } else if (groupedSections != null) {
                    groupedSections.forEach { (groupId, groupName, sectionTracks) ->
                        val collapsed = groupId in collapsedGroupIds
                        item(key = "grp_hdr_$groupId") {
                            RemoteTrackGroupHeader(
                                name = groupName,
                                count = sectionTracks.size,
                                collapsed = collapsed,
                                onToggle = {
                                    collapsedGroupIds = if (collapsed) collapsedGroupIds - groupId else collapsedGroupIds + groupId
                                },
                            )
                        }
                        if (!collapsed) {
                            items(sectionTracks, key = { (_, track) -> track.id }) { (slotNumber, track) ->
                                val isCurrent = track.id == state.currentTrackId
                                TrackPickerRow(
                                    slotNumber = slotNumber,
                                    track = track,
                                    isCurrent = isCurrent,
                                    isCurrentPlaying = isCurrent && isPlaying && !isFading,
                                    canSelect = state.canSelectTrack,
                                    canControl = state.canControl,
                                    onCueOrSelect = { handleTrackChoice(track, autoPlay = false) },
                                    onPlayOrPause = { handleTrackChoice(track, autoPlay = true) },
                                )
                            }
                        }
                    }
                } else {
                    items(filteredTracks, key = { (_, track) -> track.id }) { (slotNumber, track) ->
                        val isCurrent = track.id == state.currentTrackId
                        TrackPickerRow(
                            slotNumber = slotNumber,
                            track = track,
                            isCurrent = isCurrent,
                            isCurrentPlaying = isCurrent && isPlaying && !isFading,
                            canSelect = state.canSelectTrack,
                            canControl = state.canControl,
                            onCueOrSelect = { handleTrackChoice(track, autoPlay = false) },
                            onPlayOrPause = { handleTrackChoice(track, autoPlay = true) },
                        )
                    }
                }
            } else if (state.role == "control") {
                item(key = "library_upgrade_hint") {
                    NoticeCard("Fitur pilih lagu belum aktif di server PC saat ini (restart server Swararia di PC).")
                }
            }
            item(key = "footer") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { session.refresh() },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        ) {
                            Icon(UiIcons.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh")
                        }
                        OutlinedButton(
                            onClick = {
                                session.disconnect()
                                input = ""
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        ) {
                            Icon(UiIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Putuskan")
                        }
                    }
                    Text(
                        "Menutup layar atau pindah aplikasi tidak menghentikan audio PC. Sesi disimpan di RAM saja.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    pendingTrackSwitch?.let { choice ->
        ReplacePlayingTrackDialog(
            currentTitle = playback?.title ?: "lagu aktif",
            targetTrack = choice.track,
            preferredAutoPlay = choice.preferredAutoPlay,
            onCueOnly = {
                pendingTrackSwitch = null
                session.selectTrack(choice.track.id, autoPlay = false)
            },
            onPlayNow = {
                pendingTrackSwitch = null
                session.selectTrack(choice.track.id, autoPlay = true)
            },
            onDismiss = { pendingTrackSwitch = null },
        )
    }
}

private data class RemoteBadge(val label: String, val highlighted: Boolean, val error: Boolean = false)

private fun resolveConnectionBadge(state: DesktopRemoteUiState): RemoteBadge? = when (state.phase) {
    "disconnected" -> null
    "connecting" -> RemoteBadge("Menghubungkan", highlighted = false)
    "pending" -> RemoteBadge("Menunggu PC", highlighted = false)
    else -> when {
        !state.fresh -> RemoteBadge("Sinkronisasi", highlighted = false)
        state.playback?.online != true -> RemoteBadge("PC Offline", highlighted = false, error = true)
        state.role == "view_only" -> RemoteBadge("View-Only", highlighted = false)
        else -> RemoteBadge("FOH Ready", highlighted = true)
    }
}

private fun resolveTransportBadge(fresh: Boolean, playback: DesktopRemotePlayback?): RemoteBadge = when {
    !fresh -> RemoteBadge("SINKRONISASI", highlighted = false)
    playback == null || !playback.online -> RemoteBadge("OFFLINE", highlighted = false, error = true)
    playback.transport == "FADING" -> RemoteBadge("FADING OUT 3S", highlighted = true)
    playback.playing == true || playback.transport == "PLAYING" -> RemoteBadge("PLAYING", highlighted = true)
    playback.transport == "PAUSED" -> RemoteBadge("PAUSED", highlighted = false)
    playback.transport == "STOPPED" || playback.transport == "CUED" -> RemoteBadge("CUED", highlighted = false)
    else -> RemoteBadge(playback.transport ?: "STANDBY", highlighted = false)
}

private fun formatSecondsLabel(seconds: Number?): String =
    seconds?.let { LibraryRules.durationLabel((it.toDouble() * 1000.0).toLong().coerceAtLeast(0L)) } ?: "--:--"

private fun formatOutcomeFeedback(outcome: String): Pair<String, Boolean> = when (outcome) {
    "accepted" -> "Perintah diterima, meneruskan ke pemutar PC…" to false
    "pending" -> "Menunggu konfirmasi pemutar PC…" to false
    "applied" -> "Dikonfirmasi oleh pemutar PC" to false
    "unknown" -> "Hasil belum pasti — tidak dikirim ulang otomatis" to true
    "expired" -> "Batas waktu perintah habis — cek pemutar PC" to true
    else -> "Perintah ditolak oleh pemutar PC" to true
}

@Composable
private fun CompactStickyControlBar(
    state: DesktopRemoteUiState,
    isPlaying: Boolean,
    isFading: Boolean,
    onCommand: (String) -> Unit,
) {
    val activeTitle = state.playback?.title?.takeIf { it.isNotBlank() } ?: "Pemutar PC"
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = resolveTransportBadge(state.fresh, state.playback).label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPlaying || isFading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onCommand(if (isPlaying && !isFading) "pause" else "play") },
                    enabled = state.canControl && ((if (isPlaying && !isFading) "pause" else "play") in state.commands),
                ) {
                    Icon(
                        if (isPlaying && !isFading) UiIcons.Pause else UiIcons.Play,
                        contentDescription = if (isPlaying && !isFading) "Pause PC" else "Play PC",
                    )
                }
                IconButton(
                    onClick = { onCommand("stop") },
                    enabled = state.canControl && "stop" in state.commands,
                ) {
                    Icon(UiIcons.Stop, contentDescription = "Stop PC")
                }
                FilledTonalButton(
                    onClick = { onCommand("fade_out") },
                    enabled = state.canFadeOut,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 38.dp),
                ) {
                    Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isFading) "Fading" else "Fade 3s", style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun RemoteHeaderBar(state: DesktopRemoteUiState) {
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
private fun StatusPill(badge: RemoteBadge, modifier: Modifier = Modifier) {
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
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean = false, icon: ImageVector = UiIcons.Info) {
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
private fun DisconnectedPairingSection(
    input: String,
    scanning: Boolean,
    busy: Boolean,
    scanFeedback: String?,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnectManual: () -> Unit,
) {
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
                "Di PC: buka menu HP Remote (Android App) → Aktifkan Listener → Buat Kode Pairing, lalu pindai kode QR dari layar PC.",
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
private fun PendingApprovalCard(state: DesktopRemoteUiState, onRefresh: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.phase == "connecting") "Menghubungkan ke PC…" else "Menunggu Persetujuan di PC",
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            Text(
                if (state.phase == "connecting") {
                    "Memverifikasi identitas TLS dan mendaftarkan sesi pairing."
                } else {
                    "Di jendela Swararia pada PC Anda, klik Setujui / Approve untuk mengizinkan HP ini terhubung."
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
private fun NowPlayingDeckCard(fresh: Boolean, playback: DesktopRemotePlayback?) {
    val badge = resolveTransportBadge(fresh, playback)
    val durationSec = playback?.duration?.toDouble()
    val timeSec = playback?.time?.toDouble()
    val progress = if (timeSec != null && durationSec != null && durationSec > 0.0) {
        (timeSec / durationSec).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PEMUTAR PC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(badge)
            }
            Text(
                text = playback?.title?.takeIf { it.isNotBlank() } ?: "Belum ada track dipilih di PC",
                style = MaterialTheme.typography.titleLarge,
                color = if (playback?.title.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSecondsLabel(playback?.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatSecondsLabel(playback?.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TransportDeckCard(
    state: DesktopRemoteUiState,
    isPlaying: Boolean,
    isFading: Boolean,
    supportsFadeOut: Boolean,
    onCommand: (String) -> Unit,
) {
    val waitingCommand = state.busy || state.outcome in setOf("accepted", "pending")
    val supportsPrevNext = "prev" in state.commands || "next" in state.commands
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "KONTROL TRANSPORT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (waitingCommand) {
                    Text("Mengirim perintah…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckActionButton(
                    label = "Play",
                    icon = UiIcons.Play,
                    enabled = state.canControl && "play" in state.commands,
                    filled = !isPlaying,
                    outlined = false,
                    onClick = { onCommand("play") },
                    modifier = Modifier.weight(1f),
                )
                DeckActionButton(
                    label = "Pause",
                    icon = UiIcons.Pause,
                    enabled = state.canControl && "pause" in state.commands,
                    filled = isPlaying && !isFading,
                    outlined = false,
                    onClick = { onCommand("pause") },
                    modifier = Modifier.weight(1f),
                )
                DeckActionButton(
                    label = "Stop",
                    icon = UiIcons.Stop,
                    enabled = state.canControl && "stop" in state.commands,
                    filled = false,
                    outlined = true,
                    onClick = { onCommand("stop") },
                    modifier = Modifier.weight(1f),
                )
            }
            FilledTonalButton(
                onClick = { onCommand("fade_out") },
                enabled = state.canFadeOut,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.canFadeOut || isFading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(
                    1.dp,
                    if (state.canFadeOut || isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .semantics { stateDescription = if (isFading) "Fading" else "Idle" },
            ) {
                Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isFading) "Fading Out (3s)…" else "Fade Out 3s",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (isFading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            if (supportsPrevNext) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { onCommand("prev") },
                        enabled = state.canStepTrack && "prev" in state.commands,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) {
                        Icon(UiIcons.Previous, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Prev", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onCommand("next") },
                        enabled = state.canStepTrack && "next" in state.commands,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) {
                        Text("Next", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Icon(UiIcons.Next, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (state.role == "control" && !supportsFadeOut) {
                Text(
                    "Fade Out 3s belum aktif di server PC saat ini (restart server Swararia di PC).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrackLibraryHeaderCard(
    playlist: String?,
    totalCount: Int,
    filteredCount: Int,
    query: String,
    groups: List<DesktopRemoteTrackGroup>,
    selectedGroupId: String?,
    activePcGroupId: String?,
    canSwitchGroup: Boolean,
    groupBySections: Boolean,
    onToggleGroupBySections: () -> Unit,
    onSelectGroup: (DesktopRemoteTrackGroup) -> Unit,
    onSyncGroupToPc: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DAFTAR LAGU PC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        playlist?.takeIf { it.isNotBlank() } ?: "Semua Lagu",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    RemoteBadge(
                        label = if (query.isBlank() && selectedGroupId == activePcGroupId) "$totalCount lagu" else "$filteredCount / $totalCount",
                        highlighted = false,
                    ),
                )
            }
            if (groups.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.id }) { group ->
                        FilterChip(
                            selected = group.id == selectedGroupId,
                            onClick = { onSelectGroup(group) },
                            label = {
                                Text(
                                    if (group.count > 0) "${group.name} (${group.count})" else group.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if ((selectedGroupId == "g_all" || selectedGroupId == null) && groups.size > 2) {
                        FilterChip(
                            selected = groupBySections,
                            onClick = onToggleGroupBySections,
                            leadingIcon = if (groupBySections) {
                                { Icon(UiIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text("Kelompokkan per folder") },
                        )
                    }
                    if (canSwitchGroup && selectedGroupId != null && selectedGroupId != activePcGroupId) {
                        OutlinedButton(
                            onClick = onSyncGroupToPc,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Terapkan grup di PC", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 80) onQueryChange(it) },
                placeholder = { Text("Cari judul lagu…") },
                leadingIcon = { Icon(UiIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(UiIcons.Close, contentDescription = "Hapus pencarian", modifier = Modifier.size(18.dp))
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tap judul lagu untuk Cue (standby), atau tombol ▶ untuk langsung memutar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteTrackGroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (collapsed) UiIcons.ChevronRight else UiIcons.ChevronDown,
                contentDescription = if (collapsed) "Buka grup $name" else "Tutup grup $name",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count lagu",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackPickerRow(
    slotNumber: Int,
    track: DesktopRemoteTrack,
    isCurrent: Boolean,
    isCurrentPlaying: Boolean,
    canSelect: Boolean,
    canControl: Boolean,
    onCueOrSelect: () -> Unit,
    onPlayOrPause: () -> Unit,
) {
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val container = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
    Surface(
        onClick = onCueOrSelect,
        enabled = canSelect,
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = slotNumber.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Text(
                        text = if (isCurrentPlaying) "Sedang diputar di PC" else "Aktif di pemutar PC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onPlayOrPause,
                enabled = if (isCurrent) canControl else canSelect,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isCurrentPlaying) UiIcons.Pause else UiIcons.Play,
                    contentDescription = if (isCurrentPlaying) "Pause" else "Putar ${track.title}",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplacePlayingTrackDialog(
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
private fun DeckActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    val sizedModifier = modifier.heightIn(min = 60.dp)
    when {
        filled -> Button(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
        outlined -> OutlinedButton(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
        else -> FilledTonalButton(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
    }
}
