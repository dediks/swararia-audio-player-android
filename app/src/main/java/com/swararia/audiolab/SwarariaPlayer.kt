package com.swararia.audiolab

import android.os.Bundle
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

@Stable
internal class PlaybackClockState(initialPositionMs: Long = 0L) {
    var positionMs by mutableLongStateOf(initialPositionMs)
        internal set
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun readSwarariaPlayback(p: MediaController, includePosition: Boolean = true): SessionPlayback {
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
        positionMs = if (includePosition) p.currentPosition.coerceAtLeast(0) else 0L,
        durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET },
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
internal fun rememberSwarariaPlayback(
    controller: MediaController?,
    clock: PlaybackClockState? = null,
): SessionPlayback {
    var state by remember(controller) { mutableStateOf(SessionPlayback()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner, clock) {
        val p = controller ?: run {
            clock?.positionMs = 0L
            return@LaunchedEffect
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!p.isConnected) {
                clock?.positionMs = 0L
                state = SessionPlayback()
                return@repeatOnLifecycle
            }
            val syncState = {
                clock?.positionMs = p.currentPosition.coerceAtLeast(0)
                state = readSwarariaPlayback(p, includePosition = clock == null)
            }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { syncState() }
            }
            p.addListener(listener)
            try {
                syncState()
                // Events handle paused/idle changes. The position ticker updates only PlaybackClockState so parent UI stays idle.
                snapshotFlow { state.playing }.collectLatest { playing ->
                    if (playing) while (isActive) {
                        delay(500)
                        if (!p.isConnected) {
                            clock?.positionMs = 0L
                            state = SessionPlayback()
                            break
                        }
                        val pos = p.currentPosition.coerceAtLeast(0)
                        if (clock != null) clock.positionMs = pos
                        else state = state.copy(positionMs = pos)
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

/** Pauses active playback and cancels any ongoing fade immediately so cueing/switching never leaks a tail. */
internal fun pauseSwarariaPlaybackImmediately(controller: MediaController?) {
    val p = controller ?: return
    if (!p.isConnected) return
    if (p.isSessionCommandAvailable(PlaybackCommands.immediatePause)) {
        p.sendCustomCommand(PlaybackCommands.immediatePause, Bundle.EMPTY)
    } else if (p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
        p.pause()
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
    positionMsProvider: () -> Long = { playback.positionMs },
) {
    val foh = FohTheme.colors
    val pillBackground = foh.localBannerBg
    val pillBorder = foh.localBannerBorder
    val pillAccent = foh.localBannerAccent
    val title = playbackTitle(current, playback)
    val faderState = rememberControllerFaderState(controller)

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
            CompactSignalVisualizer(
                playing = playback.playing,
                barCount = 4,
            )
            if (faderState.isAttenuated) {
                LocalFohLevelPill(faderState = faderState, compact = true)
            }
            RemoteLocalAudioElapsed(
                active = playback.connected && playback.mediaId != null,
                accent = pillAccent,
                positionMsProvider = positionMsProvider,
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
                    pauseSwarariaPlaybackImmediately(controller)
                    connectedCommand(controller, Player.COMMAND_STOP) { p ->
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
private fun RemoteLocalAudioElapsed(
    active: Boolean,
    accent: Color,
    positionMsProvider: () -> Long,
) {
    val elapsed = LibraryRules.durationLabel(positionMsProvider().takeIf { active })
    Text(
        text = elapsed,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = accent.copy(alpha = 0.9f),
    )
}

@Composable
internal fun MiniPlayer(
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    onOpen: () -> Unit,
    positionMsProvider: () -> Long = { playback.positionMs },
) {
    val foh = FohTheme.colors
    val faderState = rememberControllerFaderState(controller)
    val togglePlay = { toggleSwarariaPlayback(controller) }
    val skipNext: () -> Unit = {
        connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
    }
    val playEnabled = playback.connected && playback.canPlay
    val nextEnabled = playback.connected && playback.hasNext

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, if (playback.wantsPlay) foh.safeBorder else foh.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Buka pemutar lengkap" },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (playback.buffering) {
                FohIndeterminateBar(
                    color = foh.safeAccent,
                    trackColor = foh.borderSubtle,
                    height = 2.5.dp,
                )
            } else {
                playback.durationMs?.takeIf { it > 0L }?.let { duration ->
                    MiniPlayerProgressLine(
                        durationMs = duration,
                        positionMsProvider = positionMsProvider,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackArtwork(current?.artworkKey, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = playbackTitle(current, playback),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiniPlayerTimeSubtitle(
                        playback = playback,
                        positionMsProvider = positionMsProvider,
                    )
                }
                CompactSignalVisualizer(
                    playing = playback.playing,
                    barCount = 5,
                )
                if (faderState.isAttenuated) {
                    LocalFohLevelPill(faderState = faderState, compact = true)
                }
                CompactCircleButton(
                    icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                    description = if (playback.wantsPlay) "Jeda audio" else "Lanjutkan audio",
                    onClick = togglePlay,
                    enabled = playEnabled,
                    size = 36.dp,
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
                    description = "Buka pemutar lengkap",
                    onClick = onOpen,
                    size = 32.dp,
                    iconSize = 18.dp,
                    containerColor = foh.safeBg,
                    contentColor = foh.safeAccent,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressLine(
    durationMs: Long,
    positionMsProvider: () -> Long,
    modifier: Modifier = Modifier.fillMaxWidth().height(2.5.dp),
) {
    val foh = FohTheme.colors
    LinearProgressIndicator(
        progress = { if (durationMs <= 0L) 0f else (positionMsProvider().toFloat() / durationMs).coerceIn(0f, 1f) },
        modifier = modifier,
        color = foh.safeAccent,
        trackColor = foh.borderSubtle,
    )
}

@Composable
private fun MiniPlayerTimeSubtitle(
    playback: SessionPlayback,
    positionMsProvider: () -> Long,
) {
    val durationMs = playback.durationMs
    if (playback.connected && durationMs != null && durationMs > 0L) {
        val foh = FohTheme.colors
        val positionMs = positionMsProvider()
        val elapsed = LibraryRules.durationLabel(positionMs)
        val total = LibraryRules.durationLabel(durationMs)
        val countdown = LocalFohRules.countdownState(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = playback.playing,
        )
        val countdownColor = if (countdown.warningActive) foh.alertAccent else foh.safeAccent
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$elapsed / $total",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "· ${countdown.countdownText}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                fontWeight = FontWeight.Bold,
                color = countdownColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    } else {
        Text(
            text = playbackStatus(playback),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    viewMode: LocalPlayerViewMode,
    onSelectViewMode: (LocalPlayerViewMode) -> Unit,
    deckCollapsed: Boolean,
    onToggleDeckCollapsed: () -> Unit,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    guardEnabled: Boolean,
    onToggleGuardEnabled: () -> Unit,
    positionMsProvider: () -> Long = { playback.positionMs },
) {
    var pendingGuardEntry by remember { mutableStateOf<SessionQueueItem?>(null) }
    var routeDialogOpen by remember { mutableStateOf(false) }
    var faderSheetOpen by rememberSaveable { mutableStateOf(false) }
    var safeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var assignPadTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editPadSlot by remember { mutableStateOf<SoundPadSlot?>(null) }
    var seekPosition by remember(playback.mediaId, playback.currentIndex) { mutableStateOf<Float?>(null) }
    var pendingSeekTargetMs by remember(playback.mediaId, playback.currentIndex) { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingSeekTargetMs, playback.mediaId, playback.currentIndex) {
        val target = pendingSeekTargetMs ?: return@LaunchedEffect
        val deadlineNanos = System.nanoTime() + 450_000_000L
        delay(60L)
        while (isActive && pendingSeekTargetMs == target && System.nanoTime() < deadlineNanos) {
            if (kotlin.math.abs(positionMsProvider() - target) <= 750L) break
            delay(40L)
        }
        if (pendingSeekTargetMs == target) {
            pendingSeekTargetMs = null
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContainer = remember(context) { (context.applicationContext as? SwarariaApp)?.container }
    val safeModeController = appContainer?.safeModeController
    val soundboardEngine = appContainer?.soundboardEngine
    val safeSettings by safeModeController?.settings?.collectAsState()
        ?: remember { mutableStateOf(FohSafeModeSettings()) }
    val soundboardState by soundboardEngine?.state?.collectAsState()
        ?: remember { mutableStateOf(SoundboardUiState()) }
    var dndGranted by remember(safeModeController) {
        mutableStateOf(safeModeController?.isNotificationPolicyGranted() == true)
    }

    DisposableEffect(lifecycleOwner, safeModeController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dndGranted = safeModeController?.isNotificationPolicyGranted() == true
                safeModeController?.refreshPolicySync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(soundboardEngine, tracks) {
        soundboardEngine?.syncTracks(tracks)
    }

    val outputRoute = rememberAudioOutputRoute()
    val phoneVolume = rememberPhoneMediaVolumeState()
    val byId = remember(tracks) { tracks.associateBy { it.id.toString() } }
    val tracksByLongId = remember(tracks) { tracks.associateBy { it.id } }
    val listState = rememberLazyListState()

    LaunchedEffect(playback.currentIndex, viewMode, playback.queue.size) {
        if (viewMode == LocalPlayerViewMode.CUE_LIST && playback.currentIndex in playback.queue.indices) {
            listState.animateScrollToItem((playback.currentIndex - 1).coerceAtLeast(0))
        }
    }

    val activeTitle = playbackTitle(current, playback)
    val needsDndPermission = FohSafeModeRules.needsDndPermissionPrompt(safeSettings, dndGranted)
    val assignedInBank = remember(soundboardState.slots, soundboardState.selectedBank, tracksByLongId) {
        soundboardState.slots.count {
            it.bankIndex == soundboardState.selectedBank && it.isAssigned && tracksByLongId.containsKey(it.trackId)
        }
    }

    PlaybackModeBar(controller, modifier.fillMaxSize()) { controlUi ->
        val latestControlUi by rememberUpdatedState(controlUi)
        DisposableEffect(soundboardEngine) {
            soundboardEngine?.setAutoDuckListener { ducked ->
                latestControlUi.onSetPadDuck(ducked)
            }
            onDispose {
                soundboardEngine?.setAutoDuckListener(null)
                latestControlUi.onSetPadDuck(false)
            }
        }
        val desiredFocusGuard = FohSafeModeRules.shouldIgnoreExternalFocusLoss(safeSettings)
        LaunchedEffect(desiredFocusGuard, controlUi.connected, controlUi.focusGuard) {
            if (controlUi.connected && controlUi.focusGuard != desiredFocusGuard) {
                controlUi.onSetFocusGuard(desiredFocusGuard)
            }
        }

        Column(Modifier.fillMaxSize()) {
            LocalFohHeaderBar(
                route = outputRoute,
                phoneVolume = phoneVolume,
                keepScreenOn = keepScreenOn,
                guardEnabled = guardEnabled,
                safeModeEnabled = safeSettings.enabled,
                safeNeedsDndPermission = needsDndPermission,
                hasCurrentTrack = current != null,
                onMinimize = onBack,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onToggleGuard = onToggleGuardEnabled,
                onOpenSafeMode = { safeSheetOpen = true },
                onRename = { current?.let(onRename) },
                onSettings = onSettings,
                onRouteClick = { routeDialogOpen = true },
                onPhoneVolumeClick = { showSystemMediaVolumePanel(context) },
            )

            LocalFohTrackSummaryCard(
                current = current,
                title = activeTitle,
                playback = playback,
                controlUi = controlUi,
                seekPosition = seekPosition ?: pendingSeekTargetMs?.toFloat(),
                onSeekChange = {
                    pendingSeekTargetMs = null
                    seekPosition = it
                },
                onSeekCommit = {
                    val requested = seekPosition
                    seekPosition = null
                    if (requested != null) {
                        val targetMs = requested.toLong().coerceAtLeast(0L)
                        var dispatched = false
                        connectedCommand(controller, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) { p ->
                            if (p.currentMediaItem?.mediaId == playback.mediaId && p.currentMediaItemIndex == playback.currentIndex) {
                                p.seekTo(targetMs)
                                dispatched = true
                            }
                        }
                        if (dispatched) pendingSeekTargetMs = targetMs
                    }
                },
                positionMsProvider = positionMsProvider,
            )

            LocalFohViewModeHeader(
                queueCount = playback.queue.size,
                currentIndex = playback.currentIndex,
                assignedPads = assignedInBank,
                viewMode = viewMode,
                onSelectViewMode = onSelectViewMode,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (viewMode) {
                    LocalPlayerViewMode.CUE_LIST -> {
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
                    }
                    LocalPlayerViewMode.PADS -> {
                        FohSoundboardPanel(
                            soundboardState = soundboardState,
                            tracksById = tracksByLongId,
                            onSelectBank = { soundboardEngine?.selectBank(it) },
                            onTriggerPad = { bank, slot -> soundboardEngine?.triggerPad(bank, slot) },
                            onOpenAssignSlot = { bank, slot -> assignPadTarget = bank to slot },
                            onOpenEditSlot = { editPadSlot = it },
                            onTogglePadDuck = { soundboardEngine?.setPadDuckEnabled(it) },
                            onStopAllPads = { soundboardEngine?.stopAll() },
                        )
                    }
                    LocalPlayerViewMode.ART -> {
                        LocalFohCoverView(
                            current = current,
                            title = activeTitle,
                            playback = playback,
                            muted = controlUi.faderState.muted,
                            effectiveGain = controlUi.faderState.effectiveGain,
                        )
                    }
                }
            }

            LocalFohBottomDeck(
                nextUpLabel = LocalFohRules.nextUpLabel(playback, byId, controlUi.mode),
                keepScreenOn = keepScreenOn,
                playback = playback,
                controlUi = controlUi,
                collapsed = deckCollapsed,
                onToggleCollapsed = onToggleDeckCollapsed,
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
                onFaderPillClick = { faderSheetOpen = true },
                positionMsProvider = positionMsProvider,
            )
        }

        if (faderSheetOpen) {
            LocalFohFaderSheet(
                faderState = controlUi.faderState,
                phoneVolume = phoneVolume,
                monoSum = controlUi.monoSum,
                enabled = controlUi.connected && controlUi.faderSupported,
                playing = controlUi.playing,
                fadeBusy = controlUi.fadeBusy,
                onPositionChange = controlUi.onSetFaderPosition,
                onToggleDuck = controlUi.onToggleDuck,
                onToggleMute = controlUi.onToggleMute,
                onResetUnity = controlUi.onResetFader,
                onSetMonoSum = controlUi.onSetMonoSum,
                onOpenPhoneVolumePanel = { showSystemMediaVolumePanel(context) },
                onDismiss = { faderSheetOpen = false },
            )
        }
    }

    if (safeSheetOpen) {
        FohSafeModeSheet(
            settings = safeSettings,
            dndGranted = dndGranted,
            route = outputRoute,
            onToggleEnabled = { enabled -> safeModeController?.setEnabled(enabled) },
            onToggleAutoDnd = { enabled -> safeModeController?.updateSettings { it.copy(autoDnd = enabled) } },
            onToggleFocusLock = { enabled -> safeModeController?.updateSettings { it.copy(focusLock = enabled) } },
            onRequestDndPermission = { FohSafeModeController.openNotificationPolicySettings(context) },
            onOpenSamsungSeparateAppSound = { FohSafeModeController.openSamsungSeparateAppSound(context) },
            onDismiss = { safeSheetOpen = false },
        )
    }

    assignPadTarget?.let { (targetBank, targetSlot) ->
        SoundPadTrackPickerSheet(
            slotNumber = targetSlot + 1,
            tracks = tracks,
            onPickTrack = { track ->
                soundboardEngine?.assignSlot(targetBank, targetSlot, track)
                assignPadTarget = null
            },
            onDismiss = { assignPadTarget = null },
        )
    }

    editPadSlot?.let { slot ->
        SoundPadEditSheet(
            slot = slot,
            onSave = { label, trimDb ->
                soundboardEngine?.updateSlot(slot.bankIndex, slot.slotIndex, label, trimDb)
                editPadSlot = null
            },
            onReplaceAudio = {
                editPadSlot = null
                assignPadTarget = slot.bankIndex to slot.slotIndex
            },
            onClearSlot = {
                soundboardEngine?.clearSlot(slot.bankIndex, slot.slotIndex)
                editPadSlot = null
            },
            onDismiss = { editPadSlot = null },
        )
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
