package com.swararia.audiolab.foh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Compact clickable EQ status badge displayed in track summary & bottom deck.
 */
@Composable
internal fun LocalFohEqPill(
    eqState: GraphicEqState,
    onClick: () -> Unit,
    compact: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val active = eqState.isProcessingAudio
    val bg = when {
        !eqState.enabled -> foh.warnBg
        active -> foh.safeBg
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = when {
        !eqState.enabled -> foh.warnBorder
        active -> foh.safeBorder
        else -> foh.borderSubtle
    }
    val tint = when {
        !eqState.enabled -> foh.warnAccent
        active -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        !eqState.enabled -> "EQ OFF"
        active -> "EQ ON"
        else -> "EQ"
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .height(if (compact) 28.dp else 32.dp)
            .semantics { contentDescription = "Equalizer 31 band: ${eqState.shortStatusLabel}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 7.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = UiIcons.Equalizer,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Inline 31-band ISO 1/3-octave Graphic Equalizer panel inside Live Deck (`LocalPlayerViewMode.EQ`).
 */
@Composable
internal fun FohGraphicEqPanel(
    eqState: GraphicEqState,
    enabled: Boolean,
    onUpdateEq: (GraphicEqState) -> Unit,
    onExpandSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val signal by rememberLocalAudioSignal()
    var selectedBand by remember { mutableIntStateOf(17) } // Default 1 kHz
    var rtaEnabled by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EqToolbarRow(
            eqState = eqState,
            enabled = enabled,
            rtaEnabled = rtaEnabled,
            onToggleRta = { rtaEnabled = !rtaEnabled },
            onUpdateEq = onUpdateEq,
            onExpandSheet = onExpandSheet,
        )

        EqResponseCurveCard(
            eqState = eqState,
            selectedBand = selectedBand,
            signal = signal,
            rtaEnabled = rtaEnabled,
            curveHeight = 62.dp,
            onSelectBand = { band ->
                selectedBand = band
                scope.launch {
                    listState.animateScrollToItem((band - 3).coerceIn(0, GraphicEqRules.BAND_COUNT - 1))
                }
            },
        )

        EqZoneAndTrimRow(
            eqState = eqState,
            selectedBand = selectedBand,
            enabled = enabled,
            onJumpBand = { band ->
                selectedBand = band
                scope.launch {
                    listState.animateScrollToItem(band.coerceIn(0, GraphicEqRules.BAND_COUNT - 1))
                }
            },
            onNudgeSelected = { deltaDb ->
                val cur = eqState.bandGainsDb.getOrElse(selectedBand) { 0f }
                onUpdateEq(GraphicEqRules.withBandGain(eqState, selectedBand, cur + deltaDb))
            },
            onResetSelected = {
                onUpdateEq(GraphicEqRules.withBandGain(eqState, selectedBand, 0f))
            },
        )

        Eq31BandFaderBank(
            eqState = eqState,
            selectedBand = selectedBand,
            enabled = enabled,
            signal = signal,
            rtaEnabled = rtaEnabled,
            listState = listState,
            onSelectBand = { selectedBand = it },
            onChangeBandGain = { band, gainDb ->
                selectedBand = band
                onUpdateEq(GraphicEqRules.withBandGain(eqState, band, gainDb))
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 120.dp),
        )
    }
}

/**
 * Full-height modal 31-band Graphic Equalizer sheet with tall faders, Preamp trim, and Limiter card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFohEqSheet(
    eqState: GraphicEqState,
    enabled: Boolean,
    onUpdateEq: (GraphicEqState) -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val signal by rememberLocalAudioSignal()
    var selectedBand by remember { mutableIntStateOf(17) }
    var rtaEnabled by rememberSaveable { mutableStateOf(true) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "31-BAND GRAPHIC EQ (ISO 1/3 OCT)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${eqState.shortStatusLabel} · Preamp ${GraphicEqRules.formatGainDb(eqState.preampDb)} · 20 Hz–20 kHz",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                            color = if (eqState.isProcessingAudio) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
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

            EqToolbarRow(
                eqState = eqState,
                enabled = enabled,
                rtaEnabled = rtaEnabled,
                onToggleRta = { rtaEnabled = !rtaEnabled },
                onUpdateEq = onUpdateEq,
                onExpandSheet = null,
            )

            EqResponseCurveCard(
                eqState = eqState,
                selectedBand = selectedBand,
                signal = signal,
                rtaEnabled = rtaEnabled,
                curveHeight = 104.dp,
                onSelectBand = { band ->
                    selectedBand = band
                    scope.launch {
                        listState.animateScrollToItem((band - 3).coerceIn(0, GraphicEqRules.BAND_COUNT - 1))
                    }
                },
            )

            EqZoneAndTrimRow(
                eqState = eqState,
                selectedBand = selectedBand,
                enabled = enabled,
                onJumpBand = { band ->
                    selectedBand = band
                    scope.launch {
                        listState.animateScrollToItem(band.coerceIn(0, GraphicEqRules.BAND_COUNT - 1))
                    }
                },
                onNudgeSelected = { deltaDb ->
                    val cur = eqState.bandGainsDb.getOrElse(selectedBand) { 0f }
                    onUpdateEq(GraphicEqRules.withBandGain(eqState, selectedBand, cur + deltaDb))
                },
                onResetSelected = {
                    onUpdateEq(GraphicEqRules.withBandGain(eqState, selectedBand, 0f))
                },
            )

            Eq31BandFaderBank(
                eqState = eqState,
                selectedBand = selectedBand,
                enabled = enabled,
                signal = signal,
                rtaEnabled = rtaEnabled,
                listState = listState,
                onSelectBand = { selectedBand = it },
                onChangeBandGain = { band, gainDb ->
                    selectedBand = band
                    onUpdateEq(GraphicEqRules.withBandGain(eqState, band, gainDb))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(226.dp),
            )

            EqPreampControlCard(
                preampDb = eqState.preampDb,
                limiterEnabled = eqState.limiterEnabled,
                enabled = enabled,
                onPreampChange = { db ->
                    onUpdateEq(GraphicEqRules.withPreampDb(eqState, db))
                },
                onToggleLimiter = {
                    onUpdateEq(eqState.copy(limiterEnabled = !eqState.limiterEnabled))
                },
            )
        }
    }
}

@Composable
private fun EqToolbarRow(
    eqState: GraphicEqState,
    enabled: Boolean,
    rtaEnabled: Boolean,
    onToggleRta: () -> Unit,
    onUpdateEq: (GraphicEqState) -> Unit,
    onExpandSheet: (() -> Unit)?,
) {
    val foh = FohTheme.colors
    var presetsOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Master Active / Bypass toggle
        Surface(
            onClick = { onUpdateEq(eqState.copy(enabled = !eqState.enabled)) },
            enabled = enabled,
            shape = RoundedCornerShape(9.dp),
            color = if (eqState.enabled) foh.safeBg else foh.warnBg,
            border = BorderStroke(1.dp, if (eqState.enabled) foh.safeBorder else foh.warnBorder),
            modifier = Modifier.height(32.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (eqState.enabled) foh.safeAccent else foh.warnAccent),
                )
                Text(
                    text = if (eqState.enabled) "EQ ON" else "BYPASS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (eqState.enabled) foh.safeAccent else foh.warnAccent,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Preset picker dropdown
        Box {
            Surface(
                onClick = { presetsOpen = true },
                enabled = enabled,
                shape = RoundedCornerShape(9.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
                modifier = Modifier.height(32.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = GraphicEqRules.presetDisplayLabel(eqState.presetId),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Icon(
                        imageVector = UiIcons.ChevronDown,
                        contentDescription = "Pilih preset EQ",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            DropdownMenu(
                expanded = presetsOpen,
                onDismissRequest = { presetsOpen = false },
            ) {
                GraphicEqRules.PRESETS.forEach { preset ->
                    val selected = eqState.presetId == preset.id
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = preset.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) foh.safeAccent else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = if (selected) {
                            { Icon(UiIcons.Check, contentDescription = null, tint = foh.safeAccent, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            presetsOpen = false
                            onUpdateEq(GraphicEqRules.applyPreset(eqState, preset))
                        },
                    )
                }
            }
        }

        // Reset Flat button
        Surface(
            onClick = { onUpdateEq(GraphicEqRules.resetFlat(eqState)) },
            enabled = enabled && eqState.isNonFlat,
            shape = RoundedCornerShape(9.dp),
            color = foh.surfaceDeep,
            border = BorderStroke(1.dp, foh.borderSubtle),
            modifier = Modifier.height(32.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 9.dp),
            ) {
                Text(
                    text = "Flat",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (eqState.isNonFlat) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Safety Limiter toggle
        Surface(
            onClick = { onUpdateEq(eqState.copy(limiterEnabled = !eqState.limiterEnabled)) },
            enabled = enabled,
            shape = RoundedCornerShape(9.dp),
            color = if (eqState.limiterEnabled) foh.safeBg.copy(alpha = 0.7f) else foh.surfaceDeep,
            border = BorderStroke(1.dp, if (eqState.limiterEnabled) foh.safeBorder else foh.borderSubtle),
            modifier = Modifier.height(32.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = if (eqState.limiterEnabled) "Limit ON" else "Limit Off",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (eqState.limiterEnabled) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Real-time 31-band post-EQ RTA spectrum overlay toggle
        Surface(
            onClick = onToggleRta,
            shape = RoundedCornerShape(9.dp),
            color = if (rtaEnabled) foh.safeBg else foh.surfaceDeep,
            border = BorderStroke(1.dp, if (rtaEnabled) foh.safeBorder else foh.borderSubtle),
            modifier = Modifier.height(32.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (rtaEnabled) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ),
                )
                Text(
                    text = "RTA",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (rtaEnabled) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (onExpandSheet != null) {
            Surface(
                onClick = onExpandSheet,
                shape = RoundedCornerShape(9.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
                modifier = Modifier.height(32.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = UiIcons.Fullscreen,
                        contentDescription = "Perbesar EQ",
                        tint = foh.safeAccent,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Detail",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = foh.safeAccent,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun EqZoneAndTrimRow(
    eqState: GraphicEqState,
    selectedBand: Int,
    enabled: Boolean,
    onJumpBand: (Int) -> Unit,
    onNudgeSelected: (Float) -> Unit,
    onResetSelected: () -> Unit,
) {
    val foh = FohTheme.colors
    val bandLabel = GraphicEqRules.BAND_LABELS.getOrElse(selectedBand) { "1k" }
    val bandDb = eqState.bandGainsDb.getOrElse(selectedBand) { 0f }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GraphicEqRules.ZONE_RANGES.forEachIndexed { idx, (label, startBand) ->
                    val nextStart = GraphicEqRules.ZONE_RANGES.getOrNull(idx + 1)?.second ?: GraphicEqRules.BAND_COUNT
                    val activeZone = selectedBand in startBand until nextStart
                    Surface(
                        onClick = { onJumpBand(startBand) },
                        shape = RoundedCornerShape(7.dp),
                        color = if (activeZone) foh.safeBg else foh.surfaceDeep,
                        border = BorderStroke(1.dp, if (activeZone) foh.safeBorder else foh.borderSubtle),
                    ) {
                        Text(
                            text = label,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = if (activeZone) FontWeight.Bold else FontWeight.Medium,
                                color = if (activeZone) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // Precision 0.1 dB / 0.5 dB fine trim strip for the currently selected band
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = foh.surfaceCard,
            border = BorderStroke(1.dp, foh.borderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$bandLabel Hz · ${GraphicEqRules.formatGainDb(bandDb)}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = when {
                            abs(bandDb) < 0.05f -> MaterialTheme.colorScheme.onSurface
                            bandDb > 0f -> foh.safeAccent
                            else -> foh.warnAccent
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EqNudgeChip("-0.5", enabled = enabled && bandDb > GraphicEqRules.MIN_GAIN_DB) { onNudgeSelected(-0.5f) }
                    EqNudgeChip("-0.1", enabled = enabled && bandDb > GraphicEqRules.MIN_GAIN_DB) { onNudgeSelected(-0.1f) }
                    EqNudgeChip("0", enabled = enabled && abs(bandDb) >= 0.05f, onClick = onResetSelected)
                    EqNudgeChip("+0.1", enabled = enabled && bandDb < GraphicEqRules.MAX_GAIN_DB) { onNudgeSelected(0.1f) }
                    EqNudgeChip("+0.5", enabled = enabled && bandDb < GraphicEqRules.MAX_GAIN_DB) { onNudgeSelected(0.5f) }
                }
            }
        }
    }
}

@Composable
private fun EqNudgeChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = foh.surfaceDeep,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.height(24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
