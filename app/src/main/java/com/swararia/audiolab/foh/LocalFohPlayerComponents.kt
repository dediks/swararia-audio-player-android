package com.swararia.audiolab.foh

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swararia.audiolab.AudioTrack
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.LibraryRules
import com.swararia.audiolab.LocalPlaybackControlUi
import com.swararia.audiolab.PlaybackMode
import com.swararia.audiolab.SessionPlayback
import com.swararia.audiolab.TrackArtwork
import com.swararia.audiolab.UiIcons

@Composable
internal fun rememberAudioOutputRoute(): AudioOutputRoute {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    fun readCurrentRoute(): AudioOutputRoute {
        if (audioManager == null) {
            return LocalFohRules.classifyOutputDevices(emptyList())
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.isSink else true
            }
            .map { device -> device.type to device.productName?.toString() }
        return LocalFohRules.classifyOutputDevices(devices)
    }

    var route by remember(audioManager) { mutableStateOf(readCurrentRoute()) }

    DisposableEffect(audioManager) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                route = readCurrentRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                route = readCurrentRoute()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    return route
}

@Composable
internal fun LocalFohHeaderBar(
    route: AudioOutputRoute,
    phoneVolume: PhoneMediaVolumeState = PhoneMediaVolumeState(),
    keepScreenOn: Boolean,
    guardEnabled: Boolean,
    safeModeEnabled: Boolean = false,
    safeNeedsDndPermission: Boolean = false,
    hasCurrentTrack: Boolean,
    onMinimize: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleGuard: () -> Unit,
    onOpenSafeMode: () -> Unit = {},
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onRouteClick: () -> Unit,
    onPhoneVolumeClick: () -> Unit = {},
    onOpenStageRta: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val foh = FohTheme.colors
    val setupPill = LocalFohRules.setupStatusPill(
        route = route,
        safeModeEnabled = safeModeEnabled,
        needsDndPermission = safeNeedsDndPermission,
        guardEnabled = guardEnabled,
    )
    val pillAttention = setupPill.level == FohReadinessLevel.ATTENTION
    val pillBg = if (pillAttention) foh.warnBg else foh.safeBg
    val pillBorder = if (pillAttention) foh.warnBorder else foh.safeBorder
    val pillAccent = if (pillAttention) foh.warnAccent else foh.safeAccent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Live Deck",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${route.label} · ${if (guardEnabled) "Guard Aktif" else "Guard Nonaktif"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (route.kind == AudioOutputKind.SPEAKER) foh.warnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                onClick = onOpenSafeMode,
                shape = RoundedCornerShape(50),
                color = pillBg,
                border = BorderStroke(1.dp, pillBorder),
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .semantics { contentDescription = "Setup FOH: ${setupPill.label}" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (pillAttention) UiIcons.Warning else UiIcons.Shield,
                        contentDescription = null,
                        tint = pillAccent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = setupPill.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = pillAccent,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Box {
                CompactCircleButton(
                    icon = UiIcons.More,
                    description = "Menu pemutar",
                    onClick = { menu = true },
                    size = 34.dp,
                    iconSize = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Ubah nama audio") },
                        leadingIcon = { Icon(UiIcons.Rename, null) },
                        enabled = hasCurrentTrack,
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Pengaturan & tentang") },
                        leadingIcon = { Icon(UiIcons.Settings, null) },
                        onClick = {
                            menu = false
                            onSettings()
                        },
                    )
                }
            }
        }

        AnimatedVisibility(visible = phoneVolume.shouldWarnPlayer) {
            PhoneMediaVolumeWarningStrip(
                phoneVolume = phoneVolume,
                onClick = onPhoneVolumeClick,
            )
        }
    }
}

@Composable
internal fun LocalFohTrackSummaryCard(
    current: AudioTrack?,
    title: String,
    playback: SessionPlayback,
    controlUi: LocalPlaybackControlUi,
    seekPosition: Float?,
    onSeekChange: (Float) -> Unit,
    onSeekCommit: () -> Unit,
    onOpenStageRta: () -> Unit = {},
    onOpenEqSheet: () -> Unit = {},
    positionMsProvider: () -> Long = { playback.positionMs },
) {
    val foh = FohTheme.colors
    val formatTag = LocalFohRules.formatTag(current)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrackArtwork(
                    current?.artworkKey,
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FohMicroChip(
                            label = formatTag,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (controlUi.padDucked) {
                            FohMicroChip(
                                label = "DUCK -12dB",
                                containerColor = FohTheme.colors.warnBg,
                                contentColor = FohTheme.colors.warnAccent,
                            )
                        }
                        if (controlUi.eq31.isProcessingAudio || !controlUi.eq31.enabled) {
                            LocalFohEqPill(
                                eqState = controlUi.eq31,
                                onClick = onOpenEqSheet,
                                compact = true,
                            )
                        }
                        LibraryRules.sanitizedTag(current?.artist)?.let { artist ->
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                LocalFohCountdownReadout(
                    playback = playback,
                    seekPosition = seekPosition,
                    positionMsProvider = positionMsProvider,
                )
            }

            LocalFohSignalStrip(
                playing = playback.playing,
                muted = controlUi.faderState.muted,
                effectiveGain = controlUi.faderState.effectiveGain,
            )

            LocalFohSeekAndEndingSection(
                playback = playback,
                mode = controlUi.mode,
                seekPosition = seekPosition,
                onSeekChange = onSeekChange,
                onSeekCommit = onSeekCommit,
                positionMsProvider = positionMsProvider,
            )

            if (playback.buffering) {
                FohIndeterminateBar(
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceDeep,
                    height = 4.dp,
                )
            }
            if (!playback.connected) {
                Text(
                    "Pemutar belum terhubung. Kembali ke Pustaka untuk memilih audio setelah tersambung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            playback.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // 1-Tap Play Mode Segmented Strip (Once / Continue / Loop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val orderedModes = listOf(PlaybackMode.ONCE, PlaybackMode.CONTINUE, PlaybackMode.LOOP_TRACK)
                orderedModes.forEach { mode ->
                    val selectedMode = controlUi.mode == mode
                    Surface(
                        onClick = { controlUi.onSelectMode(mode) },
                        enabled = controlUi.modeSupported && !controlUi.pending && !selectedMode,
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedMode) foh.safeBg else foh.surfaceDeep,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selectedMode) foh.safeAccent else foh.borderSubtle,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .semantics { selected = selectedMode },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp)) {
                            Icon(
                                imageVector = UiIcons.forPlaybackMode(mode),
                                contentDescription = LocalFohRules.modeButtonLabel(mode),
                                tint = if (selectedMode) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            val controlStatus = controlUi.statusText
            if (controlStatus != null && (controlUi.isError || !controlUi.connected || !controlUi.modeSupported || controlUi.fadeBusy)) {
                Text(
                    text = controlStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (controlUi.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun LocalFohCountdownReadout(
    playback: SessionPlayback,
    seekPosition: Float?,
    positionMsProvider: () -> Long,
) {
    val foh = FohTheme.colors
    val activePosMs = seekPosition?.toLong() ?: positionMsProvider()
    val countdown = LocalFohRules.countdownState(
        positionMs = activePosMs,
        durationMs = playback.durationMs,
        isPlaying = playback.playing,
    )
    val countdownColor = when {
        countdown.warningActive -> foh.alertAccent
        playback.playing -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = countdown.countdownText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                color = countdownColor,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = "${LibraryRules.durationLabel(activePosMs.takeIf { playback.connected && playback.mediaId != null })} / ${LibraryRules.durationLabel(playback.durationMs)}",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontFeatureSettings = "tnum",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LocalFohSeekAndEndingSection(
    playback: SessionPlayback,
    mode: PlaybackMode?,
    seekPosition: Float?,
    onSeekChange: (Float) -> Unit,
    onSeekCommit: () -> Unit,
    positionMsProvider: () -> Long,
) {
    val foh = FohTheme.colors
    val livePosMs = positionMsProvider()
    val activePosMs = seekPosition?.toLong() ?: livePosMs
    val countdown = LocalFohRules.countdownState(
        positionMs = activePosMs,
        durationMs = playback.durationMs,
        isPlaying = playback.playing,
    )
    val endingAlert = LocalFohRules.endingAlertText(countdown.remainingMs, mode, playback.playing)
    val duration = playback.durationMs ?: 1L
    val displayed = if (playback.durationMs == null) 0f else (seekPosition ?: livePosMs.toFloat()).coerceIn(0f, duration.toFloat())
    val accent = if (countdown.warningActive) foh.alertAccent else foh.safeAccent

    Slider(
        value = displayed,
        onValueChange = onSeekChange,
        valueRange = 0f..duration.toFloat(),
        enabled = playback.connected && playback.canSeek && playback.durationMs != null,
        onValueChangeFinished = onSeekCommit,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .semantics { contentDescription = "Posisi pemutaran audio" },
    )

    AnimatedVisibility(visible = endingAlert != null) {
        if (endingAlert != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = foh.alertBg,
                border = BorderStroke(1.dp, foh.alertBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        UiIcons.Warning,
                        contentDescription = null,
                        tint = foh.alertAccent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = endingAlert,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = foh.alertAccent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalFohViewModeHeader(
    queueCount: Int,
    currentIndex: Int,
    assignedPads: Int = 0,
    eqStatusLabel: String = "FLAT",
    viewMode: LocalPlayerViewMode,
    onSelectViewMode: (LocalPlayerViewMode) -> Unit,
) {
    val foh = FohTheme.colors
    val headerCaption = when (viewMode) {
        LocalPlayerViewMode.CUE_LIST -> "LIVE CUE LIST · $queueCount TRACKS"
        LocalPlayerViewMode.PADS -> "JINGLE PADS · $assignedPads/${SoundboardRules.SLOTS_PER_BANK}"
        LocalPlayerViewMode.EQ -> "31-BAND EQ · $eqStatusLabel"
        LocalPlayerViewMode.ART -> "COVER ART & INFO"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = headerCaption,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (viewMode == LocalPlayerViewMode.EQ && eqStatusLabel != "FLAT" && eqStatusLabel != "BYPASS") {
                    foh.safeAccent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (viewMode != LocalPlayerViewMode.PADS && viewMode != LocalPlayerViewMode.EQ && currentIndex >= 0 && queueCount > 0) {
                Text(
                    text = "Active Slot #${currentIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = foh.safeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = foh.surfaceDeep,
            border = BorderStroke(1.dp, foh.borderSubtle),
        ) {
            Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LocalPlayerViewMode.entries.forEach { mode ->
                    val isSelected = viewMode == mode
                    Surface(
                        onClick = { onSelectViewMode(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) foh.safeBg else Color.Transparent,
                        modifier = Modifier.semantics { selected = isSelected },
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
