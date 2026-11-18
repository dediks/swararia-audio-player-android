package com.swararia.audiolab.foh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
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
import com.swararia.audiolab.FadeRules
import com.swararia.audiolab.FohDeckButton
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.LocalPlaybackControlUi
import com.swararia.audiolab.SessionPlayback
import com.swararia.audiolab.SessionQueueItem
import com.swararia.audiolab.TrackArtwork
import com.swararia.audiolab.UiIcons
import com.swararia.audiolab.UiPresentation
import java.util.Locale

@Composable
internal fun LocalFohCueList(
    queue: List<SessionQueueItem>,
    tracksById: Map<String, AudioTrack>,
    currentIndex: Int,
    wantsPlay: Boolean,
    enabled: Boolean,
    listState: LazyListState,
    onSelectEntry: (SessionQueueItem, cueOnly: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    if (queue.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Cue list masih kosong. Pilih audio dari tab Pustaka untuk mengisi daftar putar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = queue,
            key = { "${it.index}:${it.mediaId}" },
            contentType = { "cue-entry" },
        ) { entry ->
            val track = tracksById[entry.mediaId]
            val title = track?.let { UiPresentation.title(it.displayName) }
                ?: entry.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
                ?: "Audio tidak tersedia"
            val subtitle = LocalFohRules.cueItemSubtitle(track, entry.title)
            val active = entry.index == currentIndex

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (active) foh.safeBg else foh.surfaceCard,
                border = BorderStroke(1.dp, if (active) foh.safeAccent else foh.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, onClickLabel = "Putar $title") {
                        onSelectEntry(entry, false)
                    }
                    .semantics { selected = active },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = String.format(Locale.ROOT, "%02d", entry.index + 1),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = if (active) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.widthIn(min = 24.dp),
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) foh.safeAccent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (active) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactSignalVisualizer(
                                playing = wantsPlay,
                                barCount = 4,
                            )
                            FohMicroChip(
                                label = if (wantsPlay) "PLAYING" else "STANDBY",
                                containerColor = foh.safeAccent.copy(alpha = 0.16f),
                                contentColor = foh.safeAccent,
                            )
                        }
                    } else {
                        Surface(
                            onClick = { onSelectEntry(entry, true) },
                            enabled = enabled,
                            shape = RoundedCornerShape(8.dp),
                            color = foh.warnBg,
                            border = BorderStroke(1.dp, foh.warnBorder),
                            modifier = Modifier.heightIn(min = 32.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "Cue",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = foh.warnAccent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalFohCoverView(
    current: AudioTrack?,
    title: String,
    playback: SessionPlayback,
    muted: Boolean = false,
    effectiveGain: Float = 1f,
    onOpenStageRta: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * 0.56f, 260.dp).coerceAtLeast(110.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrackArtwork(
                current?.artworkKey,
                Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(22.dp)),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = LocalFohRules.cueItemSubtitle(current, playback.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            current?.album?.takeIf { it.isNotBlank() }?.let { album ->
                Text(
                    text = "Album: $album",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LocalFohSignalStrip(
                playing = playback.playing,
                muted = muted,
                effectiveGain = effectiveGain,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenStageRta),
            )
        }
    }
}

@Composable
internal fun LocalFohBottomDeck(
    nextUpLabel: String,
    keepScreenOn: Boolean,
    playback: SessionPlayback,
    controlUi: LocalPlaybackControlUi,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onCueStart: () -> Unit,
    onFaderPillClick: () -> Unit = {},
    onEqPillClick: () -> Unit = {},
    positionMsProvider: () -> Long = { playback.positionMs },
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = foh.surfaceDeck,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LocalFohCollapsedCountdown(
                        playback = playback,
                        positionMsProvider = positionMsProvider,
                    )
                    Text(
                        text = nextUpLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactSignalVisualizer(
                        playing = playback.playing,
                        barCount = 4,
                    )
                    if (controlUi.eq31.isProcessingAudio || !controlUi.eq31.enabled) {
                        LocalFohEqPill(
                            eqState = controlUi.eq31,
                            onClick = onEqPillClick,
                            compact = true,
                        )
                    }
                    if (controlUi.monoSum) {
                        MonoOutputBadge(
                            monoSum = true,
                            onClick = onFaderPillClick,
                        )
                    }
                    LocalFohLevelPill(
                        faderState = controlUi.faderState,
                        onClick = onFaderPillClick,
                        compact = true,
                    )
                    CompactCircleButton(
                        icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        description = if (playback.wantsPlay) "Jeda audio" else "Putar audio",
                        onClick = onPlayPause,
                        enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                        size = 34.dp,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    CompactCircleButton(
                        icon = UiIcons.Stop,
                        description = "Stop audio",
                        onClick = onStop,
                        enabled = playback.connected && playback.mediaId != null && playback.canStop,
                        size = 34.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    OutlinedButton(
                        onClick = controlUi.onFadeOut,
                        enabled = FadeRules.canTapFadeControl(
                            fadeSupported = controlUi.fadeSupported,
                            playing = controlUi.playing,
                            fadeBusy = controlUi.fadeBusy,
                            pending = controlUi.pending,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(if (controlUi.fadeBusy) "Tap: Cut" else "Fade", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                    CompactCircleButton(
                        icon = UiIcons.ChevronUp,
                        description = "Perluas transport deck",
                        onClick = onToggleCollapsed,
                        size = 32.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (keepScreenOn) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(foh.safeAccent),
                        )
                    }
                    Text(
                        text = nextUpLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactSignalVisualizer(
                        playing = playback.playing,
                        barCount = 4,
                    )
                    LocalFohEqPill(
                        eqState = controlUi.eq31,
                        onClick = onEqPillClick,
                        compact = true,
                    )
                    MonoOutputBadge(
                        monoSum = controlUi.monoSum,
                        onClick = onFaderPillClick,
                    )
                    LocalFohLevelPill(
                        faderState = controlUi.faderState,
                        onClick = onFaderPillClick,
                        compact = true,
                    )
                    CompactCircleButton(
                        icon = UiIcons.ChevronDown,
                        description = "Perkecil transport deck",
                        onClick = onToggleCollapsed,
                        size = 30.dp,
                        iconSize = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FohDeckButton(
                        label = "Prev",
                        icon = UiIcons.Previous,
                        enabled = playback.connected && playback.hasPrevious,
                        filled = false,
                        outlined = false,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = if (playback.wantsPlay) "Pause" else "Play",
                        icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                        filled = true,
                        outlined = false,
                        onClick = onPlayPause,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = "Stop",
                        icon = UiIcons.Stop,
                        enabled = playback.connected && playback.mediaId != null && playback.canStop,
                        filled = false,
                        outlined = true,
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = "Next",
                        icon = UiIcons.Next,
                        enabled = playback.connected && playback.hasNext,
                        filled = false,
                        outlined = false,
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(color = foh.borderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onCueStart,
                        enabled = playback.connected && playback.mediaId != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = foh.warnBg,
                            contentColor = foh.warnAccent,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Icon(UiIcons.CueStart, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cue 00:00", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = controlUi.onFadeOut,
                        enabled = FadeRules.canTapFadeControl(
                            fadeSupported = controlUi.fadeSupported,
                            playing = controlUi.playing,
                            fadeBusy = controlUi.fadeBusy,
                            pending = controlUi.pending,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (controlUi.fadeBusy) foh.alertBorder else if (controlUi.fadeSupported && controlUi.playing) foh.safeBorder else foh.borderSubtle,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (controlUi.fadeBusy) "Potong Instan" else "Fade Out ${FadeRules.shortLabel(controlUi.manualFadeOutMs)}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                if (controlUi.fadeBusy) {
                    FohIndeterminateBar(
                        color = foh.safeAccent,
                        trackColor = foh.surfaceDeep,
                        height = 4.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFohCollapsedCountdown(
    playback: SessionPlayback,
    positionMsProvider: () -> Long,
) {
    val foh = FohTheme.colors
    val countdown = LocalFohRules.countdownState(
        positionMs = positionMsProvider(),
        durationMs = playback.durationMs,
        isPlaying = playback.playing,
    )
    Text(
        text = countdown.countdownText,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            color = if (countdown.warningActive) foh.alertAccent else foh.safeAccent,
        ),
    )
}

@Composable
internal fun LocalTrackSwitchGuardDialog(
    targetTitle: String,
    activeTitle: String,
    cueButtonLabel: String = "Cue Only (Standby 00:00)",
    detailText: String = "Track \"$activeTitle\" sedang live. Pilih tindakan untuk \"$targetTitle\":",
    onCueOnly: () -> Unit,
    onCutAndPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(UiIcons.Shield, contentDescription = null, tint = foh.warnAccent) },
        title = { Text("Track Sedang Playing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onCueOnly,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = foh.warnBg,
                        contentColor = foh.warnAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(cueButtonLabel, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onCutAndPlay,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = foh.alertBg,
                        contentColor = foh.alertAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cut & Play Now", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal (Keep Playing)")
            }
        },
    )
}

@Composable
private fun MonoOutputBadge(
    monoSum: Boolean,
    onClick: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (monoSum) foh.safeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (monoSum) foh.safeBorder else foh.borderSubtle),
        modifier = Modifier
            .height(28.dp)
            .semantics { contentDescription = if (monoSum) "Output Mono L+R" else "Output Stereo" },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (monoSum) "MONO" else "ST",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
                fontWeight = FontWeight.Bold,
                color = if (monoSum) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun FohMicroChip(
    label: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
