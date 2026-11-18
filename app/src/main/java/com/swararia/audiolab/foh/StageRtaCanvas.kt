package com.swararia.audiolab.foh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.brandskin.BrandSkinConfig
import com.swararia.audiolab.brandskin.HaloChannelMode
import com.swararia.audiolab.brandskin.PrestigeBrandEmblem
import com.swararia.audiolab.brandskin.StageRtaStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val RtaBandFreqLabels = listOf(
    "170", "230", "310", "420", "560", "750", "1k", "1.4k",
    "1.9k", "2.6k", "3.5k", "4.8k", "6.5k", "9k", "13k", "18k",
)

private val GridDbLabels = listOf("0dB", "-12", "-24", "-36", "-48")

@Composable
internal fun StageRtaAnalyzerCard(
    signal: LocalAudioSignalSnapshot,
    liveActive: Boolean,
    brand: BrandSkinConfig,
    bassEnergy: Float,
    onSelectRtaStyle: (StageRtaStyle) -> Unit,
    onToggleHaloChannelMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val cardBg = if (brand.oledTrueBlack) Color(0xFF040505) else foh.surfaceCard
    val caption = when (brand.stageRtaStyle) {
        StageRtaStyle.LED -> "16-BAND LED · -54..0 dB"
        StageRtaStyle.CURVE -> "HARMONIC CURVE · -54..0 dB"
        StageRtaStyle.HALO -> "HALO RTA · 170Hz–18kHz"
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cardBg,
        border = BorderStroke(1.dp, foh.safeAccent.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (brand.stageRtaStyle == StageRtaStyle.HALO) {
                        Surface(
                            onClick = onToggleHaloChannelMode,
                            shape = RoundedCornerShape(8.dp),
                            color = foh.safeBg,
                            border = BorderStroke(1.dp, foh.safeBorder),
                        ) {
                            Text(
                                text = brand.haloChannelMode.label,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = foh.safeAccent,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(foh.surfaceDeep)
                        .border(1.dp, foh.borderSubtle, RoundedCornerShape(10.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    StageRtaStyle.entries.forEach { style ->
                        val selected = brand.stageRtaStyle == style
                        Surface(
                            onClick = { onSelectRtaStyle(style) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) foh.safeBg else Color.Transparent,
                            border = if (selected) BorderStroke(1.dp, foh.safeBorder) else null,
                        ) {
                            Text(
                                text = style.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (selected) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                StageRtaCanvas(
                    signal = signal,
                    liveActive = liveActive,
                    rtaStyle = brand.stageRtaStyle,
                    haloChannelMode = brand.haloChannelMode,
                    safeAccent = foh.safeAccent,
                    warnAccent = foh.warnAccent,
                    alertAccent = foh.alertAccent,
                    modifier = Modifier.fillMaxSize(),
                )

                if (brand.stageRtaStyle == StageRtaStyle.HALO) {
                    val haloCoreSize = (minOf(maxWidth, maxHeight) * 0.31f).coerceIn(70.dp, 108.dp)
                    PrestigeBrandEmblem(
                        logoKey = brand.customLogoKey,
                        size = haloCoreSize,
                        shape = CircleShape,
                        shimmerEnabled = brand.prestigeAnimations,
                        bassEnergy = bassEnergy,
                        playing = liveActive,
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRtaCanvas(
    signal: LocalAudioSignalSnapshot,
    liveActive: Boolean,
    rtaStyle: StageRtaStyle,
    haloChannelMode: HaloChannelMode,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val dbStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.44f),
        )
    }
    val freqStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
    val haloTagStyle = remember(safeAccent) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = safeAccent.copy(alpha = 0.90f),
        )
    }

    // Measure fixed chart labels once per theme palette so frame rendering allocates zero text objects.
    val dbLayouts = remember(dbStyle) {
        GridDbLabels.map { textMeasurer.measure(it, dbStyle) }
    }
    val freqLayouts = remember(freqStyle) {
        RtaBandFreqLabels.indices
            .filter { it % 3 == 0 }
            .map { idx -> idx to textMeasurer.measure(RtaBandFreqLabels[idx], freqStyle) }
    }
    val highLayout = remember(haloTagStyle) { textMeasurer.measure("HIGH · 18k", haloTagStyle) }
    val lowLayout = remember(haloTagStyle) { textMeasurer.measure("LOW · 170Hz", haloTagStyle) }
    val midLayout = remember(freqStyle) { textMeasurer.measure("1.4k", freqStyle) }
    val leftTagLayout = remember(haloTagStyle) { textMeasurer.measure("L", haloTagStyle) }
    val rightTagLayout = remember(haloTagStyle) { textMeasurer.measure("R", haloTagStyle) }
    val minus24Layout = remember(dbStyle) { textMeasurer.measure("-24", dbStyle) }
    val zeroDbLayout = remember(dbStyle) { textMeasurer.measure("0dB", dbStyle) }

    Canvas(modifier = modifier) {
        if (size.width <= 16f || size.height <= 16f) return@Canvas
        when (rtaStyle) {
            StageRtaStyle.LED, StageRtaStyle.CURVE -> {
                val padLeft = 32.dp.toPx()
                val padRight = 6.dp.toPx()
                val padTop = 8.dp.toPx()
                val padBottom = 18.dp.toPx()
                val plotW = (size.width - padLeft - padRight).coerceAtLeast(16f)
                val plotH = (size.height - padTop - padBottom).coerceAtLeast(16f)

                drawRtaGrid(
                    padLeft = padLeft,
                    padTop = padTop,
                    plotW = plotW,
                    plotH = plotH,
                    dbLayouts = dbLayouts,
                    freqLayouts = freqLayouts,
                )

                if (rtaStyle == StageRtaStyle.LED) {
                    drawStageLedColumns(
                        bands = signal.bands,
                        bandPeaks = signal.bandPeaks,
                        liveActive = liveActive,
                        padLeft = padLeft,
                        padTop = padTop,
                        plotW = plotW,
                        plotH = plotH,
                        safeAccent = safeAccent,
                        warnAccent = warnAccent,
                        alertAccent = alertAccent,
                    )
                } else {
                    drawStageHarmonicCurve(
                        bands = signal.bands,
                        bandPeaks = signal.bandPeaks,
                        liveActive = liveActive,
                        padLeft = padLeft,
                        padTop = padTop,
                        plotW = plotW,
                        plotH = plotH,
                        safeAccent = safeAccent,
                    )
                }
            }

            StageRtaStyle.HALO -> {
                drawStageHaloRta(
                    signal = signal,
                    liveActive = liveActive,
                    stereoSplit = haloChannelMode == HaloChannelMode.STEREO_SPLIT,
                    safeAccent = safeAccent,
                    warnAccent = warnAccent,
                    alertAccent = alertAccent,
                    highLayout = highLayout,
                    lowLayout = lowLayout,
                    midLayout = midLayout,
                    leftTagLayout = leftTagLayout,
                    rightTagLayout = rightTagLayout,
                    minus24Layout = minus24Layout,
                    zeroDbLayout = zeroDbLayout,
                )
            }
        }
    }
}

private fun DrawScope.drawRtaGrid(
    padLeft: Float,
    padTop: Float,
    plotW: Float,
    plotH: Float,
    dbLayouts: List<TextLayoutResult>,
    freqLayouts: List<Pair<Int, TextLayoutResult>>,
) {
    val gridColor = Color.White.copy(alpha = 0.07f)
    for (idx in dbLayouts.indices) {
        val y = padTop + (idx.toFloat() / (dbLayouts.size - 1)) * plotH
        drawLine(
            color = gridColor,
            start = Offset(padLeft, y),
            end = Offset(padLeft + plotW, y),
            strokeWidth = 1.dp.toPx(),
        )
        val lbl = dbLayouts[idx]
        drawText(
            textLayoutResult = lbl,
            topLeft = Offset(0f, (y - lbl.size.height / 2f).coerceIn(0f, size.height - lbl.size.height)),
        )
    }

    val colW = plotW / LocalSignalRules.BAND_COUNT
    val tickY = padTop + plotH + 4.dp.toPx()
    freqLayouts.forEach { (idx, layout) ->
        val cx = padLeft + idx * colW + colW * 0.5f
        val x = (cx - layout.size.width / 2f).coerceIn(padLeft, size.width - layout.size.width)
        if (tickY + layout.size.height <= size.height) {
            drawText(layout, topLeft = Offset(x, tickY))
        }
    }
}

private fun DrawScope.drawStageLedColumns(
    bands: List<Float>,
    bandPeaks: List<Float>,
    liveActive: Boolean,
    padLeft: Float,
    padTop: Float,
    plotW: Float,
    plotH: Float,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
) {
    val bandCount = LocalSignalRules.BAND_COUNT
    val colW = plotW / bandCount
    val barW = (colW * 0.68f).coerceAtLeast(3.dp.toPx())
    val gapY = 2.dp.toPx()
    val segments = 20
    val segH = ((plotH - (segments - 1) * gapY) / segments).coerceAtLeast(2f)
    val dimCell = Color.White.copy(alpha = 0.05f)
    val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

    for (i in 0 until bandCount) {
        val value = if (liveActive) bands.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
        val peak = if (liveActive) bandPeaks.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
        val litCount = if (value <= 0.02f) 0 else (value * segments).roundToInt().coerceIn(1, segments)
        val peakSeg = if (peak > 0.04f) (peak * (segments - 1)).roundToInt().coerceIn(0, segments - 1) else -1
        val x = padLeft + i * colW + (colW - barW) / 2f

        for (s in 0 until segments) {
            val y = padTop + plotH - (s + 1) * (segH + gapY) + gapY
            val color = when {
                s < litCount -> when {
                    s >= segments - 2 -> alertAccent
                    s >= segments - 6 -> warnAccent
                    else -> safeAccent
                }
                s == peakSeg -> Color.White.copy(alpha = 0.92f)
                else -> dimCell
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, segH),
                cornerRadius = corner,
            )
        }
    }
}

private fun DrawScope.drawStageHarmonicCurve(
    bands: List<Float>,
    bandPeaks: List<Float>,
    liveActive: Boolean,
    padLeft: Float,
    padTop: Float,
    plotW: Float,
    plotH: Float,
    safeAccent: Color,
) {
    val n = LocalSignalRules.BAND_COUNT
    val pts = List(n) { i ->
        val x = padLeft + (i.toFloat() / (n - 1)) * plotW
        val v = if (liveActive) bands.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
        val y = padTop + (1f - v) * plotH
        Offset(x, y)
    }

    val peakPath = Path().apply {
        for (i in 0 until n) {
            val x = padLeft + (i.toFloat() / (n - 1)) * plotW
            val p = if (liveActive) bandPeaks.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
            val y = padTop + (1f - p) * plotH
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(
        path = peakPath,
        color = Color.White.copy(alpha = 0.42f),
        style = Stroke(
            width = 1.3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        ),
    )

    val curvePath = Path().apply {
        moveTo(pts.first().x, pts.first().y)
        for (i in 0 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f
            val my = (pts[i].y + pts[i + 1].y) / 2f
            quadraticTo(pts[i].x, pts[i].y, mx, my)
        }
        lineTo(pts.last().x, pts.last().y)
    }

    val fillPath = Path().apply {
        addPath(curvePath)
        lineTo(pts.last().x, padTop + plotH)
        lineTo(pts.first().x, padTop + plotH)
        close()
    }
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(safeAccent.copy(alpha = 0.48f), safeAccent.copy(alpha = 0.14f), Color.Transparent),
            startY = padTop,
            endY = padTop + plotH,
        ),
    )
    drawPath(
        path = curvePath,
        color = safeAccent.copy(alpha = 0.25f),
        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
    )
    drawPath(
        path = curvePath,
        color = safeAccent,
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawStageHaloRta(
    signal: LocalAudioSignalSnapshot,
    liveActive: Boolean,
    stereoSplit: Boolean,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
    highLayout: TextLayoutResult,
    lowLayout: TextLayoutResult,
    midLayout: TextLayoutResult,
    leftTagLayout: TextLayoutResult,
    rightTagLayout: TextLayoutResult,
    minus24Layout: TextLayoutResult,
    zeroDbLayout: TextLayoutResult,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val minDim = minOf(size.width, size.height)
    val innerR = (minDim * 0.21f).coerceAtLeast(52.dp.toPx())
    val maxLen = (minDim * 0.185f).coerceAtLeast(36.dp.toPx())
    val outerR = innerR + maxLen

    // Concentric dB grid rings (-48 / -24 / 0 dB)
    listOf(0f, 0.5f, 1.0f).forEach { frac ->
        drawCircle(
            color = if (frac == 1.0f) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f),
            radius = innerR + frac * maxLen,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    val diagAngle = -PI.toFloat() / 4f
    val r24 = innerR + 0.5f * maxLen
    drawText(
        minus24Layout,
        topLeft = Offset(
            (cx + cos(diagAngle) * r24 - minus24Layout.size.width / 2f).coerceIn(0f, size.width - minus24Layout.size.width),
            (cy + sin(diagAngle) * r24 - minus24Layout.size.height / 2f).coerceIn(0f, size.height - minus24Layout.size.height),
        ),
    )
    drawText(
        zeroDbLayout,
        topLeft = Offset(
            (cx + cos(diagAngle) * outerR + 4.dp.toPx()).coerceIn(0f, size.width - zeroDbLayout.size.width),
            (cy + sin(diagAngle) * outerR - zeroDbLayout.size.height).coerceIn(0f, size.height - zeroDbLayout.size.height),
        ),
    )

    val bandCount = LocalSignalRules.BAND_COUNT
    val gapRad = 0.14f
    val sweepSpan = PI.toFloat() - gapRad * 2f
    val segments = 10
    val rBase = innerR + 4.dp.toPx()
    val segSpan = (maxLen - 4.dp.toPx()).coerceAtLeast(16f)
    val segStep = segSpan / segments
    val segGap = 2.dp.toPx().coerceAtMost(segStep * 0.35f)
    val dimCell = Color.White.copy(alpha = 0.06f)

    listOf(-1, 1).forEach { side ->
        val channelBands = when {
            !stereoSplit -> signal.bands
            side < 0 -> signal.bandsL
            else -> signal.bandsR
        }
        val channelPeaks = when {
            !stereoSplit -> signal.bandPeaks
            side < 0 -> signal.bandPeaksL
            else -> signal.bandPeaksR
        }

        val peakPolygon = Path()
        for (i in 0 until bandCount) {
            val t = i.toFloat() / (bandCount - 1)
            val value = if (liveActive) channelBands.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
            val peak = if (liveActive) channelPeaks.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f

            // Map band 0 (170Hz Low) at bottom (PI/2) sweeping upward along each side to band 15 (18kHz High) at top (-PI/2)
            val angle = if (side > 0) {
                (PI.toFloat() / 2f - gapRad) - t * sweepSpan
            } else {
                (PI.toFloat() / 2f + gapRad) + t * sweepSpan
            }
            val cosA = cos(angle)
            val sinA = sin(angle)
            val litSegs = if (value < 0.04f) 0 else (value * segments).roundToInt().coerceIn(1, segments)

            for (s in 0 until segments) {
                val r1 = rBase + s * segStep
                val r2 = r1 + (segStep - segGap).coerceAtLeast(1.5f)
                val segColor = when {
                    s < litSegs -> when {
                        s >= segments - 1 -> alertAccent
                        s >= segments - 3 -> warnAccent
                        else -> safeAccent
                    }
                    else -> dimCell
                }
                drawLine(
                    color = segColor,
                    start = Offset(cx + cosA * r1, cy + sinA * r1),
                    end = Offset(cx + cosA * r2, cy + sinA * r2),
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val rp = rBase + peak * segSpan + 2.dp.toPx()
            val px = cx + cosA * rp
            val py = cy + sinA * rp
            if (i == 0) peakPolygon.moveTo(px, py) else peakPolygon.lineTo(px, py)

            if (peak > 0.05f) {
                drawCircle(
                    color = if (peak > 0.85f) alertAccent else Color.White.copy(alpha = 0.9f),
                    radius = 1.6.dp.toPx(),
                    center = Offset(px, py),
                )
            }
        }

        if (liveActive) {
            drawPath(
                path = peakPolygon,
                color = safeAccent.copy(alpha = 0.40f),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }

    // Frequency & Channel axis markers cleanly positioned outside outer ring
    val topY = (cy - outerR - highLayout.size.height - 4.dp.toPx()).coerceAtLeast(0f)
    val botY = (cy + outerR + 4.dp.toPx()).coerceAtMost(size.height - lowLayout.size.height)
    drawText(highLayout, topLeft = Offset((cx - highLayout.size.width / 2f).coerceIn(0f, size.width - highLayout.size.width), topY))
    drawText(lowLayout, topLeft = Offset((cx - lowLayout.size.width / 2f).coerceIn(0f, size.width - lowLayout.size.width), botY))

    val leftSide = if (stereoSplit) leftTagLayout else midLayout
    val rightSide = if (stereoSplit) rightTagLayout else midLayout
    val sidePad = 6.dp.toPx()
    drawText(
        leftSide,
        topLeft = Offset((cx - outerR - leftSide.size.width - sidePad).coerceAtLeast(0f), cy - leftSide.size.height / 2f),
    )
    drawText(
        rightSide,
        topLeft = Offset((cx + outerR + sidePad).coerceAtMost(size.width - rightSide.size.width), cy - rightSide.size.height / 2f),
    )
}
