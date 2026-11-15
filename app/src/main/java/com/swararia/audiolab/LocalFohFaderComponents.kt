package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PillPalette(
    val bg: Color,
    val border: Color,
    val accent: Color,
)

@Composable
private fun faderPillPalette(state: FaderState): PillPalette {
    val foh = FohTheme.colors
    return when (state.pillTone) {
        FaderTone.ALERT -> PillPalette(foh.alertBg, foh.alertBorder, foh.alertAccent)
        FaderTone.WARN -> PillPalette(foh.warnBg, foh.warnBorder, foh.warnAccent)
        FaderTone.SILENT -> PillPalette(foh.surfaceDeep, foh.borderSubtle, MaterialTheme.colorScheme.onSurfaceVariant)
        FaderTone.SAFE -> PillPalette(foh.safeBg, foh.safeBorder, foh.safeAccent)
    }
}

@Composable
private fun channelStripAccent(state: FaderState): Color {
    val foh = FohTheme.colors
    return when {
        state.muted -> foh.alertAccent
        state.nominalGain <= 0f -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> foh.safeAccent
    }
}

/**
 * Compact stage-safe volume level badge used in [LocalFohBottomDeck], [RemoteLocalAudioPill],
 * and [MiniPlayer] so attenuated or muted output never hides off-screen.
 */
@Composable
internal fun LocalFohLevelPill(
    faderState: FaderState,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = faderPillPalette(faderState)
    val icon = if (faderState.muted || faderState.effectiveGain <= 0f) UiIcons.VolumeMuted else UiIcons.VolumeUp
    val hPad = if (compact) 7.dp else 9.dp
    val vPad = if (compact) 3.dp else 4.dp
    val semanticsModifier = modifier.semantics {
        contentDescription = "Master volume ${faderState.pillText}, ${faderState.dbLabel}"
    }
    val inner: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(if (compact) 11.dp else 13.dp),
            )
            Text(
                text = faderState.pillText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    color = palette.accent,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = palette.bg,
            border = BorderStroke(1.dp, palette.border),
            modifier = semanticsModifier.heightIn(min = if (compact) 24.dp else 28.dp),
            content = inner,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(50),
            color = palette.bg,
            border = BorderStroke(1.dp, palette.border),
            modifier = semanticsModifier,
            content = inner,
        )
    }
}

/**
 * Spacious slide-up Master FOH console sheet triggered from [LocalFohLevelPill] so the main
 * Local FOH view stays uncluttered while preserving tall vertical fader travel and precision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFohFaderSheet(
    faderState: FaderState,
    enabled: Boolean,
    playing: Boolean,
    fadeBusy: Boolean,
    onPositionChange: (Float) -> Unit,
    onToggleDuck: () -> Unit,
    onToggleMute: () -> Unit,
    onResetUnity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    val stripAccent = channelStripAccent(faderState)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subtitleText = when {
        faderState.muted -> "MUTED · Fader ${faderState.percent}% kept"
        else -> "${faderState.dbLabel} · ${faderState.percent}% · Ceiling"
    }
    val subtitleColor = when {
        faderState.muted -> foh.alertAccent
        faderState.ducked -> foh.warnAccent
        else -> stripAccent
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = foh.surfaceDeck,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = foh.borderSubtle) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "MASTER OUTPUT FADER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitleText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                            color = subtitleColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = foh.surfaceCard,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                    modifier = Modifier.heightIn(min = 34.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaderScaleColumn(modifier = Modifier.fillMaxHeight())
                        VerticalConsoleFaderTrack(
                            position = faderState.normalizedPosition,
                            accent = stripAccent,
                            muted = faderState.muted,
                            enabled = enabled,
                            dbLabel = faderState.dbLabel,
                            onPositionChange = onPositionChange,
                            modifier = Modifier
                                .width(74.dp)
                                .fillMaxHeight(),
                        )
                        PostFaderLevelBar(
                            effectiveGain = faderState.effectiveGain,
                            playing = playing,
                            modifier = Modifier
                                .width(14.dp)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .width(142.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        FaderNumericReadoutCard(
                            faderState = faderState,
                            fadeBusy = fadeBusy,
                            accent = stripAccent,
                        )
                        FaderQuickActionsStack(
                            faderState = faderState,
                            enabled = enabled,
                            onResetUnity = onResetUnity,
                            onToggleDuck = onToggleDuck,
                            onToggleMute = onToggleMute,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaderScaleColumn(modifier: Modifier = Modifier) {
    val foh = FohTheme.colors
    val ticks = listOf("0 dB", "-3", "-6", "-12", "-24", "-48", "-∞")
    Column(
        modifier = modifier
            .width(36.dp)
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        ticks.forEachIndexed { index, label ->
            val tickColor = when (index) {
                0 -> foh.safeAccent
                3 -> foh.warnAccent.copy(alpha = 0.90f)
                ticks.lastIndex -> foh.alertAccent
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = if (index == 0 || index == 3) FontWeight.Bold else FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                    color = tickColor,
                ),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun VerticalConsoleFaderTrack(
    position: Float,
    accent: Color,
    muted: Boolean,
    enabled: Boolean,
    dbLabel: String,
    onPositionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val density = LocalDensity.current
    val trackBg = Color(0xFF0E1512)
    val grooveColor = Color(0xFF24332B)
    val capBodyColor = when {
        muted -> Color(0xFF472527)
        else -> Color(0xFF33473D)
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(trackBg)
            .semantics {
                contentDescription = "Master output fader"
                stateDescription = dbLabel
            },
    ) {
        val totalHeightPx = constraints.maxHeight.toFloat()
        val capHeightDp = 38.dp
        val edgePadDp = 8.dp
        val capPx = with(density) { capHeightDp.toPx() }
        val edgePx = with(density) { edgePadDp.toPx() }
        val topCenterPx = edgePx + capPx / 2f
        val bottomCenterPx = (totalHeightPx - edgePx - capPx / 2f).coerceAtLeast(topCenterPx + 1f)
        val travelPx = (bottomCenterPx - topCenterPx).coerceAtLeast(24f)

        var isDragging by remember { mutableStateOf(false) }
        var localDragPos by remember { mutableFloatStateOf(FaderRules.clampPosition(position)) }
        val clampedExternalPos = FaderRules.clampPosition(position)
        val displayedPos = if (isDragging) localDragPos else clampedExternalPos
        val capCenterPx = topCenterPx + (1f - displayedPos) * travelPx
        val capTopPx = (capCenterPx - capPx / 2f).roundToInt()

        val latestOnPositionChange by rememberUpdatedState(onPositionChange)
        val latestCapCenterPx by rememberUpdatedState(capCenterPx)
        val latestDisplayedPos by rememberUpdatedState(displayedPos)

        fun posForY(y: Float): Float = (1f - (y - topCenterPx) / travelPx).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Key only on geometry/enabled so moving the cap never cancels the active drag gesture.
                .pointerInput(enabled, topCenterPx, travelPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isDragging = true
                        val startCapY = latestCapCenterPx
                        val grabOffset = if (abs(down.position.y - startCapY) <= capPx * 0.75f) {
                            localDragPos = latestDisplayedPos
                            down.position.y - startCapY
                        } else {
                            val jumped = posForY(down.position.y)
                            localDragPos = jumped
                            latestOnPositionChange(jumped)
                            0f
                        }
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                val next = posForY(change.position.y - grabOffset)
                                localDragPos = next
                                latestOnPositionChange(next)
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val borderStrokePx = 1.dp.toPx()
                drawRoundRect(
                    color = foh.borderSubtle,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = borderStrokePx),
                )

                val centerX = size.width / 2f
                val grooveWidth = 4.dp.toPx()
                val railTop = topCenterPx
                val railBottom = bottomCenterPx
                drawRoundRect(
                    color = grooveColor,
                    topLeft = Offset(centerX - grooveWidth / 2f, railTop),
                    size = Size(grooveWidth, (railBottom - railTop).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(grooveWidth / 2f, grooveWidth / 2f),
                )

                // Unity reference line (top = 0 dB)
                val hPad = 10.dp.toPx()
                drawLine(
                    color = foh.safeAccent.copy(alpha = 0.45f),
                    start = Offset(hPad, topCenterPx),
                    end = Offset(size.width - hPad, topCenterPx),
                    strokeWidth = 1.dp.toPx(),
                )

                // -12 dB Duck guide line (pos = 0.50)
                val duckY = topCenterPx + (1f - FaderRules.DEFAULT_DUCK_POSITION) * travelPx
                drawLine(
                    color = foh.warnAccent.copy(alpha = 0.55f),
                    start = Offset(hPad, duckY),
                    end = Offset(size.width - hPad, duckY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )

                // Active fader fill bar from bottom edge up to current cap center
                val fillLeft = 10.dp.toPx()
                val fillWidth = (size.width - fillLeft * 2f).coerceAtLeast(8f)
                val fillTop = capCenterPx
                val fillBottom = (size.height - edgePx).coerceAtLeast(fillTop)
                val fillColor = if (muted) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.34f)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(fillLeft, fillTop),
                    size = Size(fillWidth, (fillBottom - fillTop).coerceAtLeast(4f)),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }

            // Tactile grooved fader cap
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = capBodyColor,
                border = BorderStroke(1.5.dp, accent),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp)
                    .height(capHeightDp)
                    .offset { IntOffset(0, capTopPx) },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color(0xFFECF3EE)),
                    )
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(1.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color(0xFF9FB3A7)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PostFaderLevelBar(
    effectiveGain: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val segments = 12
    val fraction = effectiveGain.coerceIn(0f, 1f)
    val litSegments = if (fraction <= 0f) 0 else (fraction * segments).roundToInt().coerceIn(1, segments)

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color(0xFF0C1310),
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (idx in 0 until segments) {
                val fromBottom = segments - 1 - idx
                val lit = fromBottom < litSegments
                // Standard digital mixer meter ladder: bottom green (safe), upper amber (-6..-1 dB), top red (0 dB peak).
                val segColor = when {
                    !lit -> Color(0xFF1D2B24)
                    fromBottom == segments - 1 -> foh.alertAccent
                    fromBottom >= segments - 4 -> foh.warnAccent
                    else -> foh.safeAccent
                }.copy(alpha = if (lit && playing) 0.95f else if (lit) 0.65f else 0.70f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(segColor),
                )
            }
        }
    }
}

@Composable
private fun FaderNumericReadoutCard(
    faderState: FaderState,
    fadeBusy: Boolean,
    accent: Color,
) {
    val foh = FohTheme.colors
    val ceilingTag = when {
        fadeBusy -> "Fading · drag overrides"
        faderState.muted -> "Silent · ${faderState.percent}% kept"
        else -> "${faderState.percent}% · Lin ${String.format(Locale.ROOT, "%.2f", faderState.nominalGain)}"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = foh.surfaceDeep,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "MASTER OUTPUT",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = faderState.dbLabel,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    color = accent,
                ),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = ceilingTag,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontFeatureSettings = "tnum",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FaderActionButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.45f),
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun FaderQuickActionsStack(
    faderState: FaderState,
    enabled: Boolean,
    onResetUnity: () -> Unit,
    onToggleDuck: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val foh = FohTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        FaderActionButton(
            label = "Unity (100%)",
            containerColor = foh.safeBg,
            contentColor = foh.safeAccent,
            border = if (faderState.isUnity) BorderStroke(1.5.dp, foh.safeAccent) else BorderStroke(1.dp, foh.safeBorder),
            enabled = enabled,
            onClick = onResetUnity,
        )

        FaderActionButton(
            label = if (faderState.ducked) "Restore (${FaderRules.percentInt(faderState.preDuckPosition)}%)" else "Duck (-12 dB)",
            containerColor = if (faderState.ducked) foh.warnAccent else foh.warnBg,
            contentColor = if (faderState.ducked) foh.warnBg else foh.warnAccent,
            border = BorderStroke(1.dp, foh.warnBorder),
            enabled = enabled,
            onClick = onToggleDuck,
        )

        FaderActionButton(
            label = if (faderState.muted) "Unmute" else "Mute",
            containerColor = if (faderState.muted) foh.alertAccent else foh.alertBg,
            contentColor = if (faderState.muted) foh.alertBg else foh.alertAccent,
            border = BorderStroke(1.dp, foh.alertBorder),
            enabled = enabled,
            icon = if (faderState.muted) UiIcons.VolumeUp else UiIcons.VolumeMuted,
            onClick = onToggleMute,
        )
    }
}
