package com.swararia.audiolab.remote

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohDeckButton
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import kotlin.math.roundToInt

/** Bottom transport deck for controlling active remote playback in collapsed or expanded form. */
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
        append(playback?.title?.takeIf { it.isNotBlank() } ?: "Belum ada track dipilih")
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
                FohIndeterminateBar(
                    height = 3.dp,
                    color = FohTheme.colors.warnAccent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
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
                description = "Perluas kontrol pemutar",
                onClick = onToggleCollapse,
                size = 30.dp,
                iconSize = 16.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Perluas kontrol pemutar") { onToggleCollapse() },
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
                    description = if (showPause) "Pause pemutar target" else "Play pemutar target",
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
                    description = "Stop pemutar target",
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
                    .clickable(onClickLabel = "Perkecil kontrol pemutar") { onToggleCollapse() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (percentLabel != null) "PEMUTAR TARGET · $percentLabel" else "PEMUTAR TARGET",
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
                        description = "Perkecil kontrol pemutar",
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
                    text = "Mode Pantau Saja · Kontrol transport dinonaktifkan.",
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
                    FohIndeterminateBar(
                        color = FohTheme.colors.warnAccent,
                        trackColor = FohTheme.colors.surfaceDeep,
                        height = 4.dp,
                    )
                }
            } else if (state.fresh && state.role == "control" && state.playback?.online == true) {
                Text(
                    text = "Fade Out 3s belum tersedia pada server target ini.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
