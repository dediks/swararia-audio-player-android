package com.swararia.audiolab

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

internal data class SessionQueueItem(val index: Int, val mediaId: String, val title: String?)

internal data class SessionPlayback(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val wantsPlay: Boolean = false,
    val idle: Boolean = true,
    val ended: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val canPlay: Boolean = false,
    val canSeek: Boolean = false,
    val canStop: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val canChooseQueue: Boolean = false,
    val queue: List<SessionQueueItem> = emptyList(),
    val error: String? = null,
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun readSwarariaPlayback(p: MediaController): SessionPlayback {
    if (!p.isConnected) return SessionPlayback()
    val queue = if (p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) List(p.mediaItemCount) { index ->
        val item = p.getMediaItemAt(index)
        SessionQueueItem(index, item.mediaId, item.mediaMetadata.title?.toString())
    } else emptyList()
    return SessionPlayback(
        connected = true, mediaId = p.currentMediaItem?.mediaId,
        title = p.mediaMetadata.title?.toString(), currentIndex = p.currentMediaItemIndex,
        playing = p.isPlaying, wantsPlay = playbackRequestsPause(p), idle = p.playbackState == Player.STATE_IDLE,
        ended = p.playbackState == Player.STATE_ENDED,
        buffering = p.playbackState == Player.STATE_BUFFERING,
        positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET },
        canPlay = p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
        canSeek = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && p.isCurrentMediaItemSeekable,
        canStop = p.isCommandAvailable(Player.COMMAND_STOP),
        hasPrevious = p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && p.hasPreviousMediaItem(),
        hasNext = p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && p.hasNextMediaItem(),
        canChooseQueue = p.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM), queue = queue,
        error = p.playerError?.let { "${it.errorCodeName}: ${it.message.orEmpty()}" },
    )
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun rememberSwarariaPlayback(controller: MediaController?): SessionPlayback {
    var state by remember(controller) { mutableStateOf(SessionPlayback()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        val p = controller ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!p.isConnected) { state = SessionPlayback(); return@repeatOnLifecycle }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { state = readSwarariaPlayback(p) }
            }
            p.addListener(listener)
            try {
                state = readSwarariaPlayback(p)
                // Events handle paused/idle changes. The position ticker exists only while playing and visible.
                snapshotFlow { state.playing }.collectLatest { playing ->
                    if (playing) while (isActive) {
                        delay(500)
                        if (!p.isConnected) { state = SessionPlayback(); break }
                        state = state.copy(positionMs = p.currentPosition.coerceAtLeast(0))
                    }
                }
            } finally { p.removeListener(listener) }
        }
    }
    return state
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private inline fun connectedCommand(controller: MediaController?, command: Int, action: (MediaController) -> Unit) {
    val p = controller ?: return
    if (p.isConnected && p.isCommandAvailable(command)) action(p)
}

private fun playbackRequestsPause(p: Player): Boolean =
    p.playWhenReady && p.playbackState != Player.STATE_IDLE &&
        p.playbackState != Player.STATE_ENDED && p.playerError == null

internal fun toggleSwarariaPlayback(controller: MediaController?) {
    connectedCommand(controller, Player.COMMAND_PLAY_PAUSE) { p ->
        if (p.currentMediaItem != null) {
            if (playbackRequestsPause(p)) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED && p.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) p.seekToDefaultPosition()
                if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                p.play()
            }
        }
    }
}

private fun playbackTitle(current: AudioTrack?, playback: SessionPlayback): String =
    current?.let { UiPresentation.title(it.displayName) }
        ?: playback.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
        ?: if (playback.mediaId == null) "Pilih audio dari Pustaka" else "Audio tidak tersedia"

private fun playbackStatus(playback: SessionPlayback): String = when {
    !playback.connected -> "Pemutar belum terhubung"
    playback.error != null -> "Pemutaran bermasalah"
    playback.buffering -> "Menyiapkan audio…"
    playback.idle && playback.mediaId != null -> "Dihentikan"
    playback.playing -> "Audio lokal"
    playback.ended -> "Selesai · Audio lokal"
    playback.mediaId != null -> "Dijeda · Audio lokal"
    else -> "Pilih audio dari Pustaka"
}

/** Compact warning-tinted top strip shown in the Remote tab when local phone audio is active. */
@Composable
internal fun RemoteLocalAudioPill(
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    onOpen: () -> Unit,
) {
    val pillBackground = Color(0xFF282417)
    val pillBorder = Color(0xFF7A6935)
    val pillAccent = Color(0xFFF5D782)
    val title = playbackTitle(current, playback)
    val elapsed = LibraryRules.durationLabel(playback.positionMs.takeIf { playback.connected && playback.mediaId != null })

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = pillBackground,
        border = BorderStroke(1.dp, pillBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .semantics { contentDescription = "Pemutar HP lokal: $title" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = UiIcons.Phone,
                contentDescription = null,
                tint = pillAccent,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "HP LOKAL:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = pillAccent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = elapsed,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = pillAccent.copy(alpha = 0.9f),
            )
            CompactCircleButton(
                icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                description = if (playback.wantsPlay) "Jeda audio lokal" else "Putar audio lokal",
                onClick = { toggleSwarariaPlayback(controller) },
                enabled = playback.connected && playback.canPlay,
                size = 30.dp,
                iconSize = 15.dp,
                containerColor = pillAccent.copy(alpha = 0.16f),
                contentColor = pillAccent,
            )
            CompactCircleButton(
                icon = UiIcons.Close,
                description = "Hentikan dan tutup pemutar lokal",
                onClick = {
                    connectedCommand(controller, Player.COMMAND_STOP) { p ->
                        p.pause()
                        p.stop()
                        if (p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) p.clearMediaItems()
                    }
                },
                enabled = playback.connected && playback.canStop,
                size = 30.dp,
                iconSize = 14.dp,
                contentColor = pillAccent.copy(alpha = 0.85f),
            )
            CompactCircleButton(
                icon = UiIcons.ChevronUp,
                description = "Buka pemutar HP lengkap",
                onClick = onOpen,
                size = 28.dp,
                iconSize = 16.dp,
                contentColor = pillAccent,
            )
        }
    }
}

@Composable
internal fun MiniPlayer(
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    onOpen: () -> Unit,
) {
    val togglePlay = { toggleSwarariaPlayback(controller) }
    val skipNext: () -> Unit = {
        connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
    }
    val playEnabled = playback.connected && playback.canPlay
    val nextEnabled = playback.connected && playback.hasNext

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(if (collapsed) 16.dp else 20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .animateContentSize()
            .semantics { contentDescription = "Buka pemutar lengkap" },
    ) {
        if (collapsed) {
            Column(Modifier.fillMaxWidth()) {
                playback.durationMs?.let { duration ->
                    LinearProgressIndicator(
                        progress = { (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackArtwork(current?.artworkKey, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = playbackTitle(current, playback),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val elapsed = LibraryRules.durationLabel(playback.positionMs.takeIf { playback.connected })
                        val total = playback.durationMs?.let { LibraryRules.durationLabel(it) }
                        Text(
                            text = if (total != null) "$elapsed / $total" else playbackStatus(playback),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CompactCircleButton(
                        icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        description = if (playback.wantsPlay) "Jeda audio" else "Lanjutkan audio",
                        onClick = togglePlay,
                        enabled = playEnabled,
                        size = 34.dp,
                        iconSize = 18.dp,
                        containerColor = if (playEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    CompactCircleButton(
                        icon = UiIcons.Next,
                        description = "Audio berikutnya",
                        onClick = skipNext,
                        enabled = nextEnabled,
                        size = 32.dp,
                        iconSize = 18.dp,
                    )
                    CompactCircleButton(
                        icon = UiIcons.ChevronUp,
                        description = "Perluas pemutar lokal",
                        onClick = onToggleCollapse,
                        size = 32.dp,
                        iconSize = 18.dp,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackArtwork(current?.artworkKey, Modifier.size(46.dp).clip(RoundedCornerShape(7.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            playbackTitle(current, playback),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playbackStatus(playback),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlyphButton(
                        if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        if (playback.wantsPlay) "Jeda audio" else "Lanjutkan audio",
                        togglePlay,
                        enabled = playEnabled,
                        filled = true,
                    )
                    GlyphButton(UiIcons.Next, "Audio berikutnya", skipNext, enabled = nextEnabled)
                    CompactCircleButton(
                        icon = UiIcons.ChevronDown,
                        description = "Perkecil pemutar lokal",
                        onClick = onToggleCollapse,
                        size = 32.dp,
                        iconSize = 18.dp,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playback.durationMs?.let { duration ->
                    LinearProgressIndicator(
                        progress = { (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FocusedPlayer(
    tracks: List<AudioTrack>,
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    modifier: Modifier,
    onBack: () -> Unit,
    onRename: (AudioTrack) -> Unit,
    onSettings: () -> Unit,
) {
    var viewMode by rememberSaveable { mutableStateOf(LocalPlayerViewMode.CUE_LIST) }
    var deckCollapsed by rememberSaveable { mutableStateOf(false) }
    var keepScreenOn by rememberSaveable { mutableStateOf(true) }
    var guardEnabled by rememberSaveable { mutableStateOf(true) }
    var pendingGuardEntry by remember { mutableStateOf<SessionQueueItem?>(null) }
    var routeDialogOpen by remember { mutableStateOf(false) }
    var seekPosition by remember(playback.mediaId, playback.currentIndex) { mutableStateOf<Float?>(null) }

    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    val outputRoute = rememberAudioOutputRoute()
    val byId = remember(tracks) { tracks.associateBy { it.id.toString() } }
    val listState = rememberLazyListState()

    LaunchedEffect(playback.currentIndex, viewMode, playback.queue.size) {
        if (viewMode == LocalPlayerViewMode.CUE_LIST && playback.currentIndex in playback.queue.indices) {
            listState.animateScrollToItem((playback.currentIndex - 1).coerceAtLeast(0))
        }
    }

    val activeTitle = playbackTitle(current, playback)

    PlaybackModeBar(controller, modifier.fillMaxSize()) { controlUi ->
        val activePosMs = seekPosition?.toLong() ?: playback.positionMs
        val countdown = LocalFohRules.countdownState(
            positionMs = activePosMs,
            durationMs = playback.durationMs,
            isPlaying = playback.playing,
        )

        Column(Modifier.fillMaxSize()) {
            LocalFohHeaderBar(
                route = outputRoute,
                keepScreenOn = keepScreenOn,
                guardEnabled = guardEnabled,
                hasCurrentTrack = current != null,
                onMinimize = onBack,
                onToggleKeepScreenOn = { keepScreenOn = !keepScreenOn },
                onToggleGuard = { guardEnabled = !guardEnabled },
                onRename = { current?.let(onRename) },
                onSettings = onSettings,
                onRouteClick = { routeDialogOpen = true },
            )

            LocalFohTrackSummaryCard(
                current = current,
                title = activeTitle,
                playback = playback,
                controlUi = controlUi,
                seekPosition = seekPosition,
                onSeekChange = { seekPosition = it },
                onSeekCommit = {
                    val requested = seekPosition
                    seekPosition = null
                    if (requested != null) connectedCommand(controller, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) { p ->
                        if (p.currentMediaItem?.mediaId == playback.mediaId && p.currentMediaItemIndex == playback.currentIndex) p.seekTo(requested.toLong())
                    }
                },
            )

            LocalFohViewModeHeader(
                queueCount = playback.queue.size,
                currentIndex = playback.currentIndex,
                viewMode = viewMode,
                onSelectViewMode = { viewMode = it },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (viewMode == LocalPlayerViewMode.CUE_LIST) {
                    LocalFohCueList(
                        queue = playback.queue,
                        tracksById = byId,
                        currentIndex = playback.currentIndex,
                        wantsPlay = playback.wantsPlay,
                        enabled = playback.connected && playback.canChooseQueue,
                        listState = listState,
                        onSelectEntry = { entry, cueOnly ->
                            if (entry.index == playback.currentIndex) {
                                if (cueOnly) cueCurrentTrackToStart(controller, playback)
                                else applyQueueEntry(controller, entry, cueOnly = false)
                            } else if (guardEnabled && playback.wantsPlay) {
                                pendingGuardEntry = entry
                            } else {
                                applyQueueEntry(controller, entry, cueOnly = cueOnly)
                            }
                        },
                    )
                } else {
                    LocalFohCoverView(
                        current = current,
                        title = activeTitle,
                        playback = playback,
                    )
                }
            }

            LocalFohBottomDeck(
                nextUpLabel = LocalFohRules.nextUpLabel(playback, byId, controlUi.mode),
                keepScreenOn = keepScreenOn,
                countdown = countdown,
                playback = playback,
                controlUi = controlUi,
                collapsed = deckCollapsed,
                onToggleCollapsed = { deckCollapsed = !deckCollapsed },
                onPrevious = {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) { p ->
                        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    }
                },
                onPlayPause = { toggleSwarariaPlayback(controller) },
                onStop = { connectedCommand(controller, Player.COMMAND_STOP) { it.stop() } },
                onNext = {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p ->
                        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    }
                },
                onCueStart = { cueCurrentTrackToStart(controller, playback) },
            )
        }
    }

    pendingGuardEntry?.let { entry ->
        val targetTrack = byId[entry.mediaId]
        val targetTitle = targetTrack?.let { UiPresentation.title(it.displayName) }
            ?: entry.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
            ?: "Audio terpilih"
        LocalTrackSwitchGuardDialog(
            targetTitle = targetTitle,
            activeTitle = activeTitle,
            onCueOnly = {
                pendingGuardEntry = null
                applyQueueEntry(controller, entry, cueOnly = true)
            },
            onCutAndPlay = {
                pendingGuardEntry = null
                applyQueueEntry(controller, entry, cueOnly = false)
            },
            onDismiss = { pendingGuardEntry = null },
        )
    }

    if (routeDialogOpen) {
        AlertDialog(
            onDismissRequest = { routeDialogOpen = false },
            title = { Text(outputRoute.label) },
            text = { Text(outputRoute.detail) },
            confirmButton = {
                TextButton(onClick = { routeDialogOpen = false }) { Text("OK") }
            },
        )
    }
}

private fun applyQueueEntry(controller: MediaController?, entry: SessionQueueItem, cueOnly: Boolean) {
    connectedCommand(controller, Player.COMMAND_SEEK_TO_MEDIA_ITEM) { p ->
        if (entry.index in 0 until p.mediaItemCount && p.getMediaItemAt(entry.index).mediaId == entry.mediaId) {
            if (cueOnly) {
                if (playbackRequestsPause(p) && p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) p.pause()
                p.seekTo(entry.index, 0L)
                if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
            } else if (p.currentMediaItemIndex == entry.index) {
                toggleSwarariaPlayback(p)
            } else {
                p.seekTo(entry.index, C.TIME_UNSET)
                if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                if (p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) p.play()
            }
        }
    }
}

private fun cueCurrentTrackToStart(controller: MediaController?, playback: SessionPlayback) {
    val p = controller ?: return
    if (!p.isConnected || playback.mediaId == null) return
    if (p.currentMediaItem?.mediaId != playback.mediaId || p.currentMediaItemIndex != playback.currentIndex) return
    if (playbackRequestsPause(p) && p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) p.pause()
    if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
    when {
        p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) -> p.seekTo(0L)
        p.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION) -> p.seekToDefaultPosition()
    }
}
