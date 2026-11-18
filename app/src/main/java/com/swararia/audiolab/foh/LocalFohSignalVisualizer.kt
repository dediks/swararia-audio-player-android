package com.swararia.audiolab.foh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swararia.audiolab.FohTheme
import kotlin.math.roundToInt

/**
 * Full-width FOH live signal analyzer strip combining a dBFS peak readout, 16-band logarithmic
 * RTA spectrum bars, and stereo L/R peak-hold LED meters driven by decoded PCM audio.
 */
@Composable
internal fun LocalFohSignalStrip(
    playing: Boolean,
    muted: Boolean,
    effectiveGain: Float,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val signal by rememberLocalAudioSignal()
    val liveActive = !muted && (playing || signal.active)
    val maxLinear = maxOf(signal.peakLinearL, signal.peakLinearR)
    val peakText = when {
        muted -> "MUTED"
        liveActive -> signal.peakDbLabel
        else -> "STANDBY"
    }
    val peakColor = when {
        muted -> foh.alertAccent
        liveActive && maxLinear >= 0.89f -> foh.alertAccent
        liveActive && maxLinear >= 0.50f -> foh.warnAccent
        liveActive -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val gainTag = if (muted) "0%" else "${(effectiveGain.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF080E0B),
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .semantics {
                contentDescription = if (liveActive) "Spektrum sinyal lokal ${signal.peakDbLabel}" else "Spektrum sinyal lokal $peakText"
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.width(62.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = if (muted) "SIGNAL $gainTag" else "SIGNAL",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = if (liveActive) foh.safeAccent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = peakText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = peakColor,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                drawRtaSpectrum(
                    bands = signal.bands,
                    bandPeaks = signal.bandPeaks,
                    muted = muted,
                    active = liveActive,
                    safeAccent = foh.safeAccent,
                    warnAccent = foh.warnAccent,
                    alertAccent = foh.alertAccent,
                )
            }

            Canvas(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight(),
            ) {
                drawDualCompactLedMeters(
                    levelL = if (liveActive) signal.levelL else 0f,
                    peakHoldL = if (liveActive) signal.peakHoldL else 0f,
                    levelR = if (liveActive) signal.levelR else 0f,
                    peakHoldR = if (liveActive) signal.peakHoldR else 0f,
                    safeAccent = foh.safeAccent,
                    warnAccent = foh.warnAccent,
                    alertAccent = foh.alertAccent,
                )
            }
        }
    }
}

/**
 * Compact multi-band live signal equalizer bars for MiniPlayer, Remote banner, Cue rows, and Library rows.
 */
@Composable
internal fun CompactSignalVisualizer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
) {
    val foh = FohTheme.colors
    val signal by rememberLocalAudioSignal()
    val safeBars = barCount.coerceIn(3, 8)
    val widthDp = (safeBars * 3 + (safeBars - 1) * 2).dp
    val isLive = !signal.muted && (playing || signal.active)

    Canvas(
        modifier = modifier
            .width(widthDp)
            .height(16.dp),
    ) {
        val gapPx = 2.dp.toPx()
        val barWidth = ((size.width - gapPx * (safeBars - 1)) / safeBars).coerceAtLeast(1.5f)
        val radius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        val totalSourceBands = signal.bands.size.coerceAtLeast(1)

        for (i in 0 until safeBars) {
            val startIdx = (i * totalSourceBands) / safeBars
            val endIdx = (((i + 1) * totalSourceBands) / safeBars).coerceAtLeast(startIdx + 1).coerceAtMost(totalSourceBands)
            var peakBand = 0f
            for (b in startIdx until endIdx) {
                val value = signal.bands.getOrElse(b) { 0f }
                if (value > peakBand) peakBand = value
            }
            val level = if (isLive) peakBand.coerceIn(0f, 1f) else 0f
            val minBaseline = 2.dp.toPx().coerceAtMost(size.height)
            val barHeight = if (level <= 0.01f) minBaseline else (minBaseline + level * (size.height - minBaseline)).coerceAtMost(size.height)
            val x = i * (barWidth + gapPx)
            val y = size.height - barHeight
            val color = when {
                signal.muted -> foh.alertAccent.copy(alpha = 0.45f)
                !isLive || level <= 0.01f -> foh.safeAccent.copy(alpha = 0.30f)
                level > 0.86f -> foh.alertAccent
                level > 0.66f -> foh.warnAccent
                else -> foh.safeAccent
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

private fun DrawScope.drawRtaSpectrum(
    bands: List<Float>,
    bandPeaks: List<Float>,
    muted: Boolean,
    active: Boolean,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
) {
    val gridColor = Color.White.copy(alpha = 0.07f)
    for (row in 1..2) {
        val y = (size.height / 3f) * row
        drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
    }

    val count = LocalSignalRules.BAND_COUNT
    val gapPx = 2.dp.toPx()
    val barWidth = ((size.width - gapPx * (count - 1)) / count).coerceAtLeast(2f)
    val minHeight = 2.dp.toPx().coerceAtMost(size.height)
    val radius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())

    for (i in 0 until count) {
        val x = i * (barWidth + gapPx)
        val bandValue = if (!active || muted) 0f else bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        val peakValue = if (!active || muted) 0f else bandPeaks.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        val barHeight = if (bandValue <= 0.01f) minHeight else (minHeight + bandValue * (size.height - minHeight)).coerceAtMost(size.height)
        val barTop = size.height - barHeight
        val barColor = when {
            muted -> alertAccent.copy(alpha = 0.25f)
            bandValue <= 0.01f -> safeAccent.copy(alpha = 0.20f)
            bandValue > 0.86f -> alertAccent
            bandValue > 0.66f -> warnAccent
            else -> safeAccent
        }
        drawRoundRect(
            color = barColor,
            topLeft = Offset(x, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = radius,
        )

        if (peakValue > 0.03f && peakValue > bandValue + 0.02f) {
            val capHeight = 1.5.dp.toPx()
            val peakY = (size.height - (minHeight + peakValue * (size.height - minHeight))).coerceIn(0f, size.height - capHeight)
            val capColor = when {
                peakValue > 0.86f -> alertAccent
                peakValue > 0.66f -> warnAccent
                else -> safeAccent.copy(alpha = 0.90f)
            }
            drawRect(
                color = capColor,
                topLeft = Offset(x, peakY),
                size = Size(barWidth, capHeight),
            )
        }
    }
}

private fun DrawScope.drawDualCompactLedMeters(
    levelL: Float,
    peakHoldL: Float,
    levelR: Float,
    peakHoldR: Float,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
) {
    val channelGap = 2.dp.toPx()
    val colWidth = ((size.width - channelGap) / 2f).coerceAtLeast(3f)
    drawSingleCompactMeterColumn(0f, colWidth, levelL, peakHoldL, safeAccent, warnAccent, alertAccent)
    drawSingleCompactMeterColumn(colWidth + channelGap, colWidth, levelR, peakHoldR, safeAccent, warnAccent, alertAccent)
}

private fun DrawScope.drawSingleCompactMeterColumn(
    x: Float,
    width: Float,
    level: Float,
    peakHold: Float,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
) {
    val segments = 8
    val segGap = 1.5.dp.toPx()
    val segHeight = ((size.height - segGap * (segments - 1)) / segments).coerceAtLeast(1f)
    val radius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
    val clamped = level.coerceIn(0f, 1f)
    val clampedHold = peakHold.coerceIn(0f, 1f)
    val litCount = if (clamped <= 0.01f) 0 else (clamped * segments).roundToInt().coerceIn(1, segments)
    val holdIdx = if (clampedHold <= 0.01f) -1 else ((clampedHold * segments).roundToInt() - 1).coerceIn(0, segments - 1)

    for (idx in 0 until segments) {
        val fromBottom = segments - 1 - idx
        val topY = idx * (segHeight + segGap)
        val lit = fromBottom < litCount
        val hold = fromBottom == holdIdx
        val base = when {
            fromBottom == segments - 1 -> alertAccent
            fromBottom >= segments - 3 -> warnAccent
            else -> safeAccent
        }
        val color = when {
            hold -> base
            lit -> base.copy(alpha = 0.94f)
            else -> Color(0xFF1D2B24).copy(alpha = 0.72f)
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(x, topY),
            size = Size(width, segHeight),
            cornerRadius = radius,
        )
    }
}
