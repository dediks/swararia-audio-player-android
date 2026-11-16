package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private data class PendingTrackChoice(val track: DesktopRemoteTrack, val preferredAutoPlay: Boolean)

/** No saved state, automatic clipboard reads, camera permission, discovery, or playback ownership. */
@Composable
fun DesktopRemoteScreen(
    session: DesktopRemoteSession,
    modifier: Modifier = Modifier,
    qrScanRequestTick: Int = 0,
    peerState: RemoteWifiPeerState = RemoteWifiPeerState(),
    wifiStatus: RemoteWifiTransportStatus = RemoteWifiTransportStatus(),
    mdnsServers: List<RemoteMdnsServer> = emptyList(),
    onHandleQrInput: (String, (String) -> Unit) -> Unit = { raw, direct -> direct(raw) },
    onConfirmLegacyWifiJoin: ((String) -> Unit) -> Unit = {},
    onCancelLegacyWifiJoin: () -> Unit = {},
    onReleasePeerNetwork: () -> Unit = {},
    onDiscoverP2p: () -> Unit = {},
    onConnectP2p: (String) -> Unit = {},
    onDisconnectP2p: () -> Unit = {},
    onRequireLegacyStoragePermission: (() -> Unit) -> Unit = { it() },
) {
    val state by session.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf<String?>(null) }
    var manualFeedback by remember { mutableStateOf<String?>(null) }
    var pendingScanned by remember { mutableStateOf<String?>(null) }
    var trackQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupBySections by rememberSaveable { mutableStateOf(true) }
    var collapsedGroupIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<String>>(emptySet()) }
    var replaceGuardEnabled by rememberSaveable { mutableStateOf(true) }
    var keepScreenOn by rememberSaveable { mutableStateOf(false) }
    var transportDockCollapsed by rememberSaveable { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var pendingTrackSwitch by remember { mutableStateOf<PendingTrackChoice?>(null) }
    var pendingScrollToTrackId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(view, keepScreenOn, state.phase) {
        val active = keepScreenOn && state.phase == "active"
        val prev = view.keepScreenOn
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = prev }
    }

    DisposableEffect(session, lifecycle) {
        val lease = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    session.visible(lease)
                    pendingScanned?.let { scanned ->
                        pendingScanned = null
                        onHandleQrInput(scanned) { direct -> session.connect(direct) }
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
            manualFeedback = null
            pendingScanned = null
        } else if (state.phase == "disconnected") {
            trackQuery = ""
            selectedGroupId = null
            collapsedGroupIds = emptySet()
            replaceGuardEnabled = true
            transportDockCollapsed = false
            showDisconnectConfirm = false
            showSessionInfo = false
            pendingTrackSwitch = null
            pendingScrollToTrackId = null
        }
    }

    fun startQrScan() {
        if (scanning || state.busy) return
        scanning = true
        scanFeedback = null
        manualFeedback = null
        pendingScanned = null
        coroutineScope.launch {
            try {
                when (val res = DesktopRemoteQrScanner.scan(context) { scanFeedback = it }) {
                    is DesktopRemoteScanResult.Scanned -> {
                        scanFeedback = null
                        manualFeedback = null
                        input = ""
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            pendingScanned = null
                            onHandleQrInput(res.rawText) { direct -> session.connect(direct) }
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
    }

    LaunchedEffect(qrScanRequestTick) {
        if (qrScanRequestTick > 0 && state.phase == "disconnected") {
            startQrScan()
        }
    }

    if (state.phase != "active") {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.phase == "disconnected") {
                DisconnectedPairingSection(
                    input = input,
                    scanning = scanning,
                    busy = state.busy,
                    scanFeedback = scanFeedback,
                    manualFeedback = manualFeedback,
                    errorMessage = state.message,
                    peerState = peerState,
                    wifiStatus = wifiStatus,
                    mdnsServers = mdnsServers,
                    onInputChange = {
                        if (it.length <= 512) {
                            input = it
                            scanFeedback = null
                            manualFeedback = null
                            pendingScanned = null
                        }
                    },
                    onScanQr = ::startQrScan,
                    onPasteManual = {
                        val pasted = readPlainTextFromClipboard(context)
                        if (pasted != null) {
                            input = pasted.take(512)
                            scanFeedback = null
                            manualFeedback = null
                            pendingScanned = null
                        } else {
                            manualFeedback = "Clipboard kosong atau tidak berisi teks."
                        }
                    },
                    onConnectManual = {
                        scanFeedback = null
                        manualFeedback = null
                        pendingScanned = null
                        onHandleQrInput(input) { direct -> session.connect(direct) }
                    },
                    onConfirmLegacyWifiJoin = {
                        onConfirmLegacyWifiJoin { direct -> session.connect(direct) }
                    },
                    onCancelLegacyWifiJoin = onCancelLegacyWifiJoin,
                    onReleasePeerNetwork = onReleasePeerNetwork,
                    onDiscoverP2p = onDiscoverP2p,
                    onConnectP2p = onConnectP2p,
                    onDisconnectP2p = onDisconnectP2p,
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
    val activeSlotNumber = remember(indexedTracks, state.currentTrackId) {
        state.currentTrackId?.let { tid -> indexedTracks.firstOrNull { (_, track) -> track.id == tid }?.first }
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
    val canSyncGroupToPc = state.canSwitchGroup && effectiveGroupId != null && effectiveGroupId != state.activeGroupId
    val sectionIds = groupedSections?.map { it.first }?.toSet() ?: emptySet()
    val allCollapsed = sectionIds.isNotEmpty() && sectionIds.all { it in collapsedGroupIds }

    LaunchedEffect(pendingScrollToTrackId, groupedSections, filteredTracks, collapsedGroupIds) {
        val targetId = pendingScrollToTrackId ?: return@LaunchedEffect
        val lazyIndex = if (groupedSections != null) {
            var idx = 0
            var found: Int? = null
            for ((gid, _, items) in groupedSections) {
                idx += 1 // section header item
                if (gid !in collapsedGroupIds) {
                    val pos = items.indexOfFirst { (_, t) -> t.id == targetId }
                    if (pos >= 0) {
                        found = idx + pos
                        break
                    }
                    idx += items.size
                }
            }
            found
        } else {
            val pos = filteredTracks.indexOfFirst { (_, t) -> t.id == targetId }
            if (pos >= 0) pos else null
        }
        pendingScrollToTrackId = null
        if (lazyIndex != null) {
            listState.animateScrollToItem(lazyIndex)
        }
    }

    fun handleTrackChoice(track: DesktopRemoteTrack, autoPlay: Boolean) {
        if (!state.canSelectTrack) return
        if (track.id == state.currentTrackId) {
            if (autoPlay) session.command(if (isPlaying && !isFading) "pause" else "play")
            return
        }
        if (replaceGuardEnabled && (isPlaying || isFading)) {
            pendingTrackSwitch = PendingTrackChoice(track, autoPlay)
        } else {
            session.selectTrack(track.id, autoPlay)
        }
    }

    val renderTrackRow: @Composable (Pair<Int, DesktopRemoteTrack>) -> Unit = { (slotNumber, track) ->
        val isCurrent = track.id == state.currentTrackId
        TrackPickerRow(
            slotNumber = slotNumber,
            track = track,
            isCurrent = isCurrent,
            isCurrentPlaying = isCurrent && isPlaying && !isFading,
            canSelect = state.canSelectTrack,
            canControl = state.canControl,
            canDownloadMedia = state.canDownloadMedia,
            downloadState = state.downloads[track.id],
            onCueOrSelect = { handleTrackChoice(track, autoPlay = false) },
            onPlayOrPause = { handleTrackChoice(track, autoPlay = true) },
            onDownload = {
                onRequireLegacyStoragePermission {
                    session.downloadTrack(track.id)
                }
            },
            onCancelDownload = { session.cancelDownload(track.id) },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        ActiveRemoteHeaderBar(
            state = state,
            keepScreenOn = keepScreenOn,
            canSyncGroupToPc = canSyncGroupToPc,
            onToggleKeepScreenOn = { keepScreenOn = !keepScreenOn },
            onRefresh = { session.refresh() },
            onSyncGroupToPc = { effectiveGroupId?.let { session.selectGroup(it) } },
            onShowSessionInfo = { showSessionInfo = true },
            onRequestDisconnect = { showDisconnectConfirm = true },
        )

        if (supportsSelectTrack) {
            RemoteSearchFilterStrip(
                query = trackQuery,
                hasActiveTrackInCatalog = activeSlotNumber != null,
                replaceGuardEnabled = replaceGuardEnabled,
                groups = state.groups,
                selectedGroupId = effectiveGroupId,
                activePcGroupId = state.activeGroupId,
                canSwitchGroup = state.canSwitchGroup,
                groupBySections = groupBySections,
                hasGroupedSections = groupedSections != null,
                allCollapsed = allCollapsed,
                loadedTrackCount = state.tracks.size,
                totalTracksCount = state.totalTracks,
                onQueryChange = { trackQuery = it },
                onJumpToActive = {
                    val target = state.currentTrackId?.let { tid ->
                        state.tracks.firstOrNull { it.id == tid }
                    } ?: return@RemoteSearchFilterStrip
                    trackQuery = ""
                    if (effectiveGroupId != "g_all" && target.groupId != effectiveGroupId) {
                        selectedGroupId = state.groups.firstOrNull { it.id == "g_all" }?.id
                    }
                    val targetSectionId = target.groupId ?: "_other"
                    collapsedGroupIds = collapsedGroupIds - targetSectionId
                    pendingScrollToTrackId = target.id
                },
                onToggleReplaceGuard = { replaceGuardEnabled = !replaceGuardEnabled },
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
                onToggleGroupBySections = { groupBySections = !groupBySections },
                onToggleCollapseAll = {
                    collapsedGroupIds = if (allCollapsed) collapsedGroupIds - sectionIds else collapsedGroupIds + sectionIds
                },
                onSyncGroupToPc = { effectiveGroupId?.let { session.selectGroup(it) } },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!supportsSelectTrack) {
                when {
                    !state.fresh && state.role == null -> {
                        item(key = "remote_offline_catalog_hint") {
                            NoticeCard(
                                text = "Daftar lagu muncul otomatis setelah koneksi ke perangkat target tersambung.",
                                actionLabel = if (state.busy) null else "Coba Lagi",
                                onAction = { session.refresh() },
                            )
                        }
                    }
                    state.fresh && state.playback?.online != true -> {
                        item(key = "remote_player_offline_catalog_hint") {
                            NoticeCard("Pemutar di perangkat target belum aktif. Buka pemutar pada perangkat target untuk melihat daftar lagu.")
                        }
                    }
                    state.role == "control" -> {
                        item(key = "library_upgrade_hint") {
                            NoticeCard("Fitur pilih lagu belum aktif pada server target ini.")
                        }
                    }
                    else -> {
                        item(key = "view_only_catalog_hint") {
                            NoticeCard("Daftar lagu akan tampil saat terhubung dengan izin katalog aktif.")
                        }
                    }
                }
            } else if (state.tracks.isEmpty()) {
                item(key = "library_empty") {
                    NoticeCard(
                        if (playback?.online == true) "Daftar lagu perangkat target masih kosong atau belum tersinkron."
                        else "Menunggu pemutar target online untuk memuat daftar lagu.",
                    )
                }
            } else if (filteredTracks.isEmpty()) {
                item(key = "library_no_match") {
                    NoticeCard(
                        if (trimmedQuery.isNotEmpty()) "Tidak ada lagu yang cocok dengan \"$trimmedQuery\"."
                        else "Belum ada lagu terisi pada folder ini.",
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
                        items(
                            sectionTracks,
                            key = { (_, track) -> track.id },
                            contentType = { "remote-track-row" },
                        ) { item ->
                            renderTrackRow(item)
                        }
                    }
                }
            } else {
                items(
                    filteredTracks,
                    key = { (_, track) -> track.id },
                    contentType = { "remote-track-row" },
                ) { item ->
                    renderTrackRow(item)
                }
            }
        }

        FohTransportDock(
            state = state,
            activeSlotNumber = activeSlotNumber,
            isPlaying = isPlaying,
            isFading = isFading,
            supportsFadeOut = supportsFadeOut,
            collapsed = transportDockCollapsed,
            onToggleCollapse = { transportDockCollapsed = !transportDockCollapsed },
            onCommand = { session.command(it) },
        )
    }

    pendingTrackSwitch?.let { choice ->
        ReplacePlayingTrackDialog(
            currentTitle = playback?.title ?: "lagu aktif",
            targetTrack = choice.track,
            preferredAutoPlay = choice.preferredAutoPlay,
            onCueOnly = {
                session.selectTrack(choice.track.id, autoPlay = false)
                pendingTrackSwitch = null
            },
            onPlayNow = {
                session.selectTrack(choice.track.id, autoPlay = true)
                pendingTrackSwitch = null
            },
            onDismiss = { pendingTrackSwitch = null },
        )
    }

    if (showDisconnectConfirm) {
        DisconnectConfirmDialog(
            endpoint = state.endpoint,
            onConfirm = {
                showDisconnectConfirm = false
                session.disconnect()
                onReleasePeerNetwork()
                input = ""
            },
            onDismiss = { showDisconnectConfirm = false },
        )
    }

    if (showSessionInfo) {
        SessionInfoDialog(
            state = state,
            onDismiss = { showSessionInfo = false },
        )
    }
}
