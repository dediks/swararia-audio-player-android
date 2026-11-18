package com.swararia.audiolab.foh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swararia.audiolab.FohTheme
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

private fun eqRtaBandColor(
    value: Float,
    muted: Boolean,
    safeAccent: Color,
    warnAccent: Color,
    alertAccent: Color,
): Color = when {
    muted -> alertAccent
    value > 0.85f -> alertAccent
    value > 0.58f -> warnAccent
    else -> safeAccent
}

@Composable
internal fun EqResponseCurveCard(
    eqState: GraphicEqState,
    selectedBand: Int,
    signal: LocalAudioSignalSnapshot,
    rtaEnabled: Boolean,
    curveHeight: Dp,
    onSelectBand: (Int) -> Unit,
) {
    val foh = FohTheme.colors
    val curveDb = remember(eqState) {
        GraphicEqRules.sampleResponseCurveDb(
            state = eqState,
            sampleRateHz = 48_000.0,
            pointCount = 93,
            includePreamp = false,
        )
    }
    val selectedFreq = GraphicEqRules.CENTER_FREQUENCIES_HZ.getOrElse(selectedBand) { 1000.0 }
    val minLog = remember { ln(20.0) }
    val maxLog = remember { ln(20_000.0) }
    val lineColor = if (eqState.enabled) foh.safeAccent else foh.warnAccent.copy(alpha = 0.7f)
    val liveRta = signal.active || signal.muted || signal.bands31.any { it > 0.01f }
    val rtaStatusText = when {
        !rtaEnabled -> "RTA OFF"
        signal.muted -> "RTA MUTED"
        liveRta -> "RTA ${signal.peakDbLabel}"
        else -> "RTA READY"
    }
    val rtaStatusColor = when {
        !rtaEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        signal.muted -> foh.alertAccent
        liveRta -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF080E0B),
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .height(curveHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetFreq = kotlin.math.exp(minLog + frac * (maxLog - minLog))
                        var bestIdx = 0
                        var bestDiff = Double.MAX_VALUE
                        for (i in 0 until GraphicEqRules.BAND_COUNT) {
                            val diff = abs(ln(GraphicEqRules.CENTER_FREQUENCIES_HZ[i]) - ln(targetFreq))
                            if (diff < bestDiff) {
                                bestDiff = diff
                                bestIdx = i
                            }
                        }
                        onSelectBand(bestIdx)
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                val w = size.width
                val h = size.height
                val zeroY = h * 0.5f

                // Horizontal dB grid: +6, 0, -6
                val gridColor = Color.White.copy(alpha = 0.08f)
                drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                drawLine(Color.White.copy(alpha = 0.20f), Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1.2f)
                drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                // Decade / octave vertical lines
                val gridFreqs = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
                for (gf in gridFreqs) {
                    val x = (((ln(gf) - minLog) / (maxLog - minLog)) * w).toFloat()
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }

                // Real-time post-EQ 31-band RTA spectrum + peak hold behind the EQ curve
                if (rtaEnabled) {
                    val barW = ((w / GraphicEqRules.BAND_COUNT.toFloat()) * 0.62f)
                        .coerceIn(2.dp.toPx(), 10.dp.toPx())
                    val barCorner = CornerRadius(barW * 0.28f, barW * 0.28f)
                    val maxBarH = (h - 3.dp.toPx()).coerceAtLeast(4f)
                    val floorH = 1.5.dp.toPx()
                    val bands = signal.bands31
                    val peaks = signal.bandPeaks31
                    for (i in 0 until GraphicEqRules.BAND_COUNT) {
                        val freq = GraphicEqRules.CENTER_FREQUENCIES_HZ[i]
                        val centerX = (((ln(freq) - minLog) / (maxLog - minLog)) * w).toFloat()
                        val barLeft = (centerX - barW * 0.5f).coerceIn(0f, (w - barW).coerceAtLeast(0f))
                        val bandVal = if (liveRta) bands.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
                        val peakVal = if (liveRta) peaks.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f

                        if (bandVal > 0.01f) {
                            val barH = (bandVal * maxBarH).coerceAtLeast(2.dp.toPx())
                            val barColor = eqRtaBandColor(
                                value = bandVal,
                                muted = signal.muted,
                                safeAccent = foh.safeAccent,
                                warnAccent = foh.warnAccent,
                                alertAccent = foh.alertAccent,
                            ).copy(alpha = if (signal.muted) 0.20f else 0.40f)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(barLeft, h - barH),
                                size = Size(barW, barH),
                                cornerRadius = barCorner,
                            )
                        } else {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.14f),
                                topLeft = Offset(barLeft, h - floorH),
                                size = Size(barW, floorH),
                                cornerRadius = barCorner,
                            )
                        }

                        if (peakVal > 0.02f) {
                            val peakY = (h - peakVal * maxBarH).coerceIn(1.dp.toPx(), h - 2.dp.toPx())
                            val peakColor = eqRtaBandColor(
                                value = peakVal,
                                muted = signal.muted,
                                safeAccent = foh.safeAccent,
                                warnAccent = foh.warnAccent,
                                alertAccent = foh.alertAccent,
                            ).copy(alpha = if (signal.muted) 0.35f else 0.86f)
                            drawLine(
                                color = peakColor,
                                start = Offset(barLeft, peakY),
                                end = Offset(barLeft + barW, peakY),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                }

                // Selected band highlight cursor
                val selX = (((ln(selectedFreq) - minLog) / (maxLog - minLog)) * w).toFloat().coerceIn(0f, w)
                drawLine(
                    color = foh.warnAccent.copy(alpha = 0.6f),
                    start = Offset(selX, 0f),
                    end = Offset(selX, h),
                    strokeWidth = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                )

                if (curveDb.size >= 2) {
                    val strokePath = Path()
                    val fillPath = Path()
                    for (i in curveDb.indices) {
                        val x = (i.toFloat() / (curveDb.size - 1).toFloat()) * w
                        val normDb = (curveDb[i] / GraphicEqRules.MAX_GAIN_DB).coerceIn(-1.15f, 1.15f)
                        val y = (zeroY - normDb * (h * 0.44f)).coerceIn(2f, h - 2f)
                        if (i == 0) {
                            strokePath.moveTo(x, y)
                            fillPath.moveTo(x, zeroY)
                            fillPath.lineTo(x, y)
                        } else {
                            strokePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo(w, zeroY)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.26f), lineColor.copy(alpha = 0.04f)),
                        ),
                    )
                    drawPath(
                        path = strokePath,
                        color = lineColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // Selected band gain dot on curve
                val selDb = if (eqState.enabled) eqState.bandGainsDb.getOrElse(selectedBand) { 0f } else 0f
                val selY = (zeroY - (selDb / GraphicEqRules.MAX_GAIN_DB) * (h * 0.44f)).coerceIn(3f, h - 3f)
                drawCircle(
                    color = foh.warnAccent,
                    radius = 3.5.dp.toPx(),
                    center = Offset(selX, selY),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3080E0B))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = rtaStatusText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        color = rtaStatusColor,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun Eq31BandFaderBank(
    eqState: GraphicEqState,
    selectedBand: Int,
    enabled: Boolean,
    signal: LocalAudioSignalSnapshot,
    rtaEnabled: Boolean,
    listState: LazyListState,
    onSelectBand: (Int) -> Unit,
    onChangeBandGain: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val liveRta = rtaEnabled && (signal.active || signal.muted || signal.bands31.any { it > 0.01f })
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EqDbScaleColumn(modifier = Modifier.fillMaxHeight())

            LazyRow(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(
                    count = GraphicEqRules.BAND_COUNT,
                    key = { it },
                ) { bandIndex ->
                    Eq31BandFaderColumn(
                        bandIndex = bandIndex,
                        freqLabel = GraphicEqRules.BAND_LABELS[bandIndex],
                        gainDb = eqState.bandGainsDb.getOrElse(bandIndex) { 0f },
                        rtaLevel = if (liveRta) signal.bands31.getOrElse(bandIndex) { 0f } else 0f,
                        rtaPeak = if (liveRta) signal.bandPeaks31.getOrElse(bandIndex) { 0f } else 0f,
                        signalMuted = signal.muted,
                        eqEnabled = eqState.enabled,
                        selected = bandIndex == selectedBand,
                        interactive = enabled,
                        onSelect = { onSelectBand(bandIndex) },
                        onChangeGain = { nextDb -> onChangeBandGain(bandIndex, nextDb) },
                        modifier = Modifier
                            .width(42.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EqDbScaleColumn(modifier: Modifier = Modifier) {
    val foh = FohTheme.colors
    Column(
        modifier = modifier
            .width(28.dp)
            .padding(top = 18.dp, bottom = 18.dp, end = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        val style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        )
        Text("+12", style = style, color = foh.safeAccent)
        Text("0", style = style.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        Text("-12", style = style, color = foh.warnAccent)
    }
}

@Composable
private fun Eq31BandFaderColumn(
    bandIndex: Int,
    freqLabel: String,
    gainDb: Float,
    rtaLevel: Float,
    rtaPeak: Float,
    signalMuted: Boolean,
    eqEnabled: Boolean,
    selected: Boolean,
    interactive: Boolean,
    onSelect: () -> Unit,
    onChangeGain: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val density = LocalDensity.current
    val activeAccent = when {
        !eqEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        gainDb >= 0.05f -> foh.safeAccent
        gainDb <= -0.05f -> foh.warnAccent
        selected -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) foh.surfaceDeep.copy(alpha = 0.9f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) foh.safeBorder else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(vertical = 3.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = GraphicEqRules.formatCompactBandDb(gainDb),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = if (abs(gainDb) >= 0.05f || selected) FontWeight.Bold else FontWeight.Normal,
                fontFeatureSettings = "tnum",
                color = activeAccent,
            ),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF0E1512))
                .semantics {
                    contentDescription = "EQ band $freqLabel Hz"
                    stateDescription = GraphicEqRules.formatGainDb(gainDb)
                },
        ) {
            val totalHeightPx = constraints.maxHeight.toFloat()
            val capHeightDp = 18.dp
            val edgePadDp = 4.dp
            val capPx = with(density) { capHeightDp.toPx() }
            val edgePx = with(density) { edgePadDp.toPx() }
            val topCenterPx = edgePx + capPx / 2f
            val bottomCenterPx = (totalHeightPx - edgePx - capPx / 2f).coerceAtLeast(topCenterPx + 1f)
            val travelPx = (bottomCenterPx - topCenterPx).coerceAtLeast(16f)
            val centerY = topCenterPx + travelPx * 0.5f

            var isDragging by remember { mutableStateOf(false) }
            var localDragDb by remember { mutableFloatStateOf(GraphicEqRules.clampGainDb(gainDb)) }
            val clampedExternalDb = GraphicEqRules.clampGainDb(gainDb)
            val displayedDb = if (isDragging) localDragDb else clampedExternalDb
            val normalizedPos = ((displayedDb - GraphicEqRules.MIN_GAIN_DB) /
                (GraphicEqRules.MAX_GAIN_DB - GraphicEqRules.MIN_GAIN_DB)).coerceIn(0f, 1f)
            val capCenterPx = topCenterPx + (1f - normalizedPos) * travelPx
            val capTopPx = (capCenterPx - capPx / 2f).roundToInt()

            val latestOnSelect by rememberUpdatedState(onSelect)
            val latestOnChangeGain by rememberUpdatedState(onChangeGain)
            val latestCapCenterPx by rememberUpdatedState(capCenterPx)
            val latestDisplayedDb by rememberUpdatedState(displayedDb)

            fun dbForY(y: Float): Float {
                val norm = (1f - (y - topCenterPx) / travelPx).coerceIn(0f, 1f)
                val raw = GraphicEqRules.MIN_GAIN_DB +
                    norm * (GraphicEqRules.MAX_GAIN_DB - GraphicEqRules.MIN_GAIN_DB)
                val rounded = GraphicEqRules.clampGainDb(raw)
                return if (abs(rounded) < 0.25f) 0f else rounded
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bandIndex, interactive) {
                        detectTapGestures(
                            onTap = { latestOnSelect() },
                            onDoubleTap = {
                                latestOnSelect()
                                if (interactive) latestOnChangeGain(0f)
                            },
                        )
                    }
                    // Wait for vertical touch slop before consuming so horizontal scroll across 31 bands stays effortless.
                    .pointerInput(bandIndex, interactive, topCenterPx, travelPx) {
                        if (!interactive) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var currentGrabOffset = 0f
                            val startedVertical = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                                latestOnSelect()
                                isDragging = true
                                val startCapY = latestCapCenterPx
                                if (abs(down.position.y - startCapY) <= capPx * 1.15f) {
                                    localDragDb = latestDisplayedDb
                                    currentGrabOffset = down.position.y - startCapY
                                } else {
                                    val jumped = dbForY(change.position.y)
                                    localDragDb = jumped
                                    latestOnChangeGain(jumped)
                                    currentGrabOffset = 0f
                                }
                            }
                            if (startedVertical != null) {
                                try {
                                    verticalDrag(startedVertical.id) { change ->
                                        change.consume()
                                        val next = dbForY(change.position.y - currentGrabOffset)
                                        if (next != localDragDb) {
                                            localDragDb = next
                                            latestOnChangeGain(next)
                                        }
                                    }
                                } finally {
                                    isDragging = false
                                }
                            }
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val midX = size.width * 0.5f
                    val grooveW = 3.dp.toPx()

                    // Live per-band post-EQ RTA backdrop inside fader well
                    val meterBottom = totalHeightPx - edgePx
                    val meterRange = (meterBottom - topCenterPx).coerceAtLeast(8f)
                    val meterW = 14.dp.toPx()
                    if (rtaLevel > 0.01f) {
                        val barH = (rtaLevel.coerceIn(0f, 1f) * meterRange).coerceAtLeast(2.dp.toPx())
                        val rtaColor = eqRtaBandColor(
                            value = rtaLevel,
                            muted = signalMuted,
                            safeAccent = foh.safeAccent,
                            warnAccent = foh.warnAccent,
                            alertAccent = foh.alertAccent,
                        ).copy(alpha = if (signalMuted) 0.14f else 0.24f)
                        drawRoundRect(
                            color = rtaColor,
                            topLeft = Offset(midX - meterW / 2f, meterBottom - barH),
                            size = Size(meterW, barH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                    if (rtaPeak > 0.02f) {
                        val peakY = (meterBottom - rtaPeak.coerceIn(0f, 1f) * meterRange)
                            .coerceIn(topCenterPx, meterBottom - 1.dp.toPx())
                        val peakColor = eqRtaBandColor(
                            value = rtaPeak,
                            muted = signalMuted,
                            safeAccent = foh.safeAccent,
                            warnAccent = foh.warnAccent,
                            alertAccent = foh.alertAccent,
                        ).copy(alpha = if (signalMuted) 0.28f else 0.62f)
                        drawLine(
                            color = peakColor,
                            start = Offset(midX - meterW / 2f, peakY),
                            end = Offset(midX + meterW / 2f, peakY),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }

                    drawRoundRect(
                        color = Color(0xFF23322A),
                        topLeft = Offset(midX - grooveW / 2f, topCenterPx),
                        size = Size(grooveW, (bottomCenterPx - topCenterPx).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(grooveW / 2f, grooveW / 2f),
                    )
                    // 0 dB center detent line
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f),
                        start = Offset(4.dp.toPx(), centerY),
                        end = Offset(size.width - 4.dp.toPx(), centerY),
                        strokeWidth = 1.dp.toPx(),
                    )
                    // Active bar from 0 dB to current cap
                    if (abs(displayedDb) >= 0.05f) {
                        val barTop = minOf(centerY, capCenterPx)
                        val barBottom = maxOf(centerY, capCenterPx)
                        val fillW = 8.dp.toPx()
                        drawRoundRect(
                            color = activeAccent.copy(alpha = if (eqEnabled) 0.42f else 0.18f),
                            topLeft = Offset(midX - fillW / 2f, barTop),
                            size = Size(fillW, (barBottom - barTop).coerceAtLeast(2f)),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                    }
                }

                // Fader thumb cap
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (selected) Color(0xFF395246) else Color(0xFF2B3C33),
                    border = BorderStroke(
                        width = if (selected || abs(displayedDb) >= 0.05f) 1.2.dp else 1.dp,
                        color = activeAccent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .height(capHeightDp)
                        .offset { IntOffset(0, capTopPx) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(1.5.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color(0xFFECF3EE)),
                        )
                    }
                }
            }
        }

        Text(
            text = freqLabel,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontFeatureSettings = "tnum",
                color = if (selected) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun EqPreampControlCard(
    preampDb: Float,
    limiterEnabled: Boolean,
    enabled: Boolean,
    onPreampChange: (Float) -> Unit,
    onToggleLimiter: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "EQ PREAMP & HEADROOM",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = GraphicEqRules.formatGainDb(preampDb),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = if (abs(preampDb) < 0.05f) MaterialTheme.colorScheme.onSurface else foh.safeAccent,
                        ),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        onClick = { onPreampChange(0f) },
                        enabled = enabled && abs(preampDb) >= 0.05f,
                        shape = RoundedCornerShape(8.dp),
                        color = foh.surfaceDeep,
                        border = BorderStroke(1.dp, foh.borderSubtle),
                    ) {
                        Text(
                            text = "0 dB",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (abs(preampDb) >= 0.05f) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                    Surface(
                        onClick = onToggleLimiter,
                        enabled = enabled,
                        shape = RoundedCornerShape(8.dp),
                        color = if (limiterEnabled) foh.safeBg else foh.warnBg,
                        border = BorderStroke(1.dp, if (limiterEnabled) foh.safeBorder else foh.warnBorder),
                    ) {
                        Text(
                            text = if (limiterEnabled) "Limiter -0.5 dB" else "Limiter Off",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (limiterEnabled) foh.safeAccent else foh.warnAccent,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Slider(
                value = GraphicEqRules.clampPreampDb(preampDb),
                onValueChange = { raw ->
                    val stepped = (raw * 2f).roundToInt() / 2f
                    onPreampChange(if (abs(stepped) < 0.25f) 0f else stepped)
                },
                valueRange = GraphicEqRules.MIN_PREAMP_DB..GraphicEqRules.MAX_PREAMP_DB,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = foh.safeAccent,
                    activeTrackColor = foh.safeAccent,
                    inactiveTrackColor = foh.surfaceDeep,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            )
        }
    }
}
