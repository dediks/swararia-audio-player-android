package com.swararia.audiolab.phasechecker

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohFilterChip
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private fun phaseOnlyVectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

internal object PhaseOnlyIcons {
    val Mic = phaseOnlyVectorIcon(
        "PhaseMic",
        "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.49 6,-3.31 6,-6.72h-1.7z",
    )
    val Swap = phaseOnlyVectorIcon(
        "PhaseSwap",
        "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z",
    )
    val Share = phaseOnlyVectorIcon(
        "PhaseShare",
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z",
    )
}

private data class ComparisonBannerStyle(
    val bg: Color,
    val border: Color,
    val accent: Color,
    val label: String,
)

@Composable
internal fun PolarityReadoutCard(state: PhaseCheckerUiState) {
    val foh = FohTheme.colors
    val neutralAccent = MaterialTheme.colorScheme.onSurfaceVariant
    val (bg, border, accent) = when {
        !state.micListening && state.pulseCount == 0 -> Triple(
            foh.surfaceDeep,
            foh.borderSubtle,
            neutralAccent,
        )
        state.heldEffectiveVerdict == PolarityVerdict.POSITIVE -> Triple(
            foh.safeBg,
            foh.safeBorder,
            foh.safeAccent,
        )
        state.heldEffectiveVerdict == PolarityVerdict.NEGATIVE -> Triple(
            foh.alertBg,
            foh.alertBorder,
            foh.alertAccent,
        )
        state.heldEffectiveVerdict == PolarityVerdict.AMBIGUOUS -> Triple(
            foh.warnBg,
            foh.warnBorder,
            foh.warnAccent,
        )
        else -> Triple(
            foh.surfaceDeep,
            foh.borderSubtle,
            neutralAccent,
        )
    }
    val refComparison = PhaseCheckerRules.compareToReference(
        current = state.heldEffectiveVerdict,
        reference = state.referencePolarity,
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.2.dp, border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            !state.micListening && state.pulseCount == 0 -> "SIAP MENGUJI FASA"
                            state.heldEffectiveVerdict == PolarityVerdict.POSITIVE -> "+ IN-PHASE (POSITIF)"
                            state.heldEffectiveVerdict == PolarityVerdict.NEGATIVE -> "- OUT-OF-PHASE (-180°)"
                            state.heldEffectiveVerdict == PolarityVerdict.AMBIGUOUS -> "SINYAL SIMETRIS / BERGEMA"
                            else -> "MENUNGGU PULSA..."
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Text(
                        text = when {
                            !state.micListening && state.pulseCount == 0 ->
                                "Arahkan mic HP ~20–50 cm di depan speaker saat pulsa berbunyi."
                            state.heldEffectiveVerdict == PolarityVerdict.POSITIVE ->
                                "Gelombang awal mendorong positif (+)."
                            state.heldEffectiveVerdict == PolarityVerdict.NEGATIVE ->
                                "Polaritas terbalik (-) terhadap sinyal positif."
                            state.heldEffectiveVerdict == PolarityVerdict.AMBIGUOUS ->
                                "Terbaca pantulan/bising simetris; dekatkan mic ke driver."
                            else ->
                                "Menunggu pulsa uji dari mixer/speaker..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.micListening && state.micSourceLabel.isNotBlank()) {
                        val isFallbackDsp = state.micSourceLabel.contains("Non-raw", ignoreCase = true)
                        val stackSuffix = if (state.stackedPulses >= 2) " · Avg ${state.stackedPulses}×" else ""
                        Text(
                            text = when {
                                state.clipped -> "Mic klip (terlalu keras) — mundurkan mic 10–20 cm"
                                isFallbackDsp -> "${state.micSourceLabel} · AGC/filter HP aktif$stackSuffix"
                                else -> "${state.micSourceLabel}$stackSuffix"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                state.clipped -> foh.alertAccent
                                isFallbackDsp -> foh.warnAccent
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = when (state.heldEffectiveVerdict) {
                            PolarityVerdict.POSITIVE -> "+"
                            PolarityVerdict.NEGATIVE -> "−"
                            PolarityVerdict.AMBIGUOUS -> "~"
                            PolarityVerdict.LOW_SIGNAL -> "•"
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = accent,
                    )
                }
            }

            if (refComparison != ReferenceComparison.NONE) {
                ReferenceComparisonBanner(refComparison)
            }

            ImpulseOscilloscope(
                samples = state.waveformPreview,
                listening = state.micListening,
                positiveColor = foh.safeAccent,
                negativeColor = foh.alertAccent,
                gridColor = foh.borderSubtle,
                scopeBg = foh.surfaceDeep,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = "Keyakinan",
                    value = "${(state.confidence * 100f).roundToInt()}%",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Asimetri",
                    value = "${if (state.asymmetry >= 0f) "+" else ""}${(state.asymmetry * 100f).roundToInt()}%",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Peak / Pulsa",
                    value = "${state.peakDbfs.roundToInt()}dB · #${state.pulseCount}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun SinglePhoneSecondaryCard(
    state: PhaseCheckerUiState,
    selectedSpeakerSlot: String,
    onSelectSpeakerSlot: (String) -> Unit,
    onGainChange: (Float) -> Unit,
    onSelectSensitivity: (PhaseMicSensitivity) -> Unit,
    onLockOrClearRef: () -> Unit,
    onToggleInvertMic: () -> Unit,
    onSaveResult: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GainSliderSection(gain = state.generatorGain, onGainChange = onGainChange)
            SensitivityRow(selected = state.sensitivity, onSelect = onSelectSensitivity)
            CalibrationAndSlotRow(
                state = state,
                selectedSpeakerSlot = selectedSpeakerSlot,
                onSelectSpeakerSlot = onSelectSpeakerSlot,
                onLockOrClearRef = onLockOrClearRef,
                onToggleInvertMic = onToggleInvertMic,
                onSaveResult = onSaveResult,
            )
        }
    }
}

@Composable
internal fun TwoPhoneReceiverSecondaryCard(
    state: PhaseCheckerUiState,
    selectedSpeakerSlot: String,
    onSelectSpeakerSlot: (String) -> Unit,
    onSelectPulseMode: (PhaseTestSignalMode) -> Unit,
    onSelectSensitivity: (PhaseMicSensitivity) -> Unit,
    onLockOrClearRef: () -> Unit,
    onToggleInvertMic: () -> Unit,
    onSaveResult: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseTargetRow(selected = state.mode, onSelect = onSelectPulseMode)
            SensitivityRow(selected = state.sensitivity, onSelect = onSelectSensitivity)
            CalibrationAndSlotRow(
                state = state,
                selectedSpeakerSlot = selectedSpeakerSlot,
                onSelectSpeakerSlot = onSelectSpeakerSlot,
                onLockOrClearRef = onLockOrClearRef,
                onToggleInvertMic = onToggleInvertMic,
                onSaveResult = onSaveResult,
            )
        }
    }
}

@Composable
private fun PulseTargetRow(
    selected: PhaseTestSignalMode,
    onSelect: (PhaseTestSignalMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Target Speaker:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(PhaseTestSignalMode.PULSE_FULL_RANGE, PhaseTestSignalMode.PULSE_SUB).forEach { mode ->
            FohFilterChip(
                label = if (mode == PhaseTestSignalMode.PULSE_FULL_RANGE) "Full" else "Sub LF",
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
internal fun StereoEarCheckerCard(
    state: PhaseCheckerUiState,
    onSelectStereoMode: (PhaseTestSignalMode) -> Unit,
    onGainChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val foh = FohTheme.colors
    val inPhaseActive = state.mode == PhaseTestSignalMode.STEREO_IN_PHASE
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, foh.borderSubtle),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Uji Kuping Stereo L / R (Tanpa Mic)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Sambungkan 1 HP ke channel Stereo L & R mixer, lalu gonta-ganti tombol di bawah sambil mendengarkan fokus tengah & bass.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StereoModeChoiceTile(
                    title = "In-Phase (L = R)",
                    hint = "Fokus tengah padat & bass penuh",
                    selected = inPhaseActive,
                    accent = foh.safeAccent,
                    bg = foh.safeBg,
                    border = foh.safeBorder,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectStereoMode(PhaseTestSignalMode.STEREO_IN_PHASE) },
                )
                StereoModeChoiceTile(
                    title = "Out-Phase (L = -R)",
                    hint = "Melebar ke samping & tengah hampa",
                    selected = !inPhaseActive,
                    accent = foh.alertAccent,
                    bg = foh.alertBg,
                    border = foh.alertBorder,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectStereoMode(PhaseTestSignalMode.STEREO_OUT_OF_PHASE) },
                )
            }

            GainSliderSection(gain = state.generatorGain, onGainChange = onGainChange)

            PhasePrimaryActionButton(
                active = state.generatorRunning,
                activeLabel = "Hentikan Sinyal Stereo",
                idleLabel = "Putar Sinyal Stereo",
                idleIcon = UiIcons.Play,
                onClick = onTogglePlay,
            )
        }
    }
}

@Composable
private fun StereoModeChoiceTile(
    title: String,
    hint: String,
    selected: Boolean,
    accent: Color,
    bg: Color,
    border: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) bg else FohTheme.colors.surfaceDeep,
        border = BorderStroke(
            width = if (selected) 1.4.dp else 1.dp,
            color = if (selected) border else FohTheme.colors.borderSubtle,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TwoPhoneTransmitterCard(
    state: PhaseCheckerUiState,
    onSelectMode: (PhaseTestSignalMode) -> Unit,
    onGainChange: (Float) -> Unit,
    onToggleTransmit: () -> Unit,
    onShareWav: suspend () -> Result<Unit>,
) {
    val foh = FohTheme.colors
    val exportScope = rememberCoroutineScope()
    var exportingWav by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val pulseFlash = state.generatorRunning && (state.pulseTick % 2L == 1L)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (state.generatorRunning) foh.safeBg else foh.surfaceDeep,
            border = BorderStroke(
                1.2.dp,
                if (state.generatorRunning) foh.safeBorder else foh.borderSubtle,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (state.generatorRunning) "MEMANCAR KE MIXER" else "PEMANCAR SIAP (TX)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (state.generatorRunning) foh.safeAccent else MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                UiIcons.KeepAwake,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                if (state.generatorRunning) {
                                    "${state.mode.shortLabel} aktif walau layar terkunci"
                                } else {
                                    "Colok ke mixer FOH lalu mulai pancaran"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = when {
                            pulseFlash -> foh.safeAccent
                            state.generatorRunning -> foh.safeAccent.copy(alpha = 0.45f)
                            else -> foh.borderSubtle
                        },
                        modifier = Modifier.size(16.dp),
                    ) {}
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PhaseTestSignalMode.entries.forEach { mode ->
                        FohFilterChip(
                            label = mode.shortLabel,
                            selected = state.mode == mode,
                            onClick = { onSelectMode(mode) },
                        )
                    }
                }
                Text(
                    state.mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                GainSliderSection(gain = state.generatorGain, onGainChange = onGainChange)

                PhasePrimaryActionButton(
                    active = state.generatorRunning,
                    activeLabel = "Hentikan Pancaran TX",
                    idleLabel = "Mulai Pancar Sinyal (TX)",
                    idleIcon = UiIcons.Play,
                    onClick = onToggleTransmit,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, foh.borderSubtle),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Di Meja FOH Pakai Laptop / HP Tanpa Aplikasi?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Ekspor & bagikan file pulsa 48 kHz (.wav, 5 detik loop) ke perangkat lain di meja mixer lalu gunakan HP ini sebagai RX.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        if (!exportingWav) {
                            exportScope.launch {
                                exportingWav = true
                                shareError = null
                                try {
                                    shareError = onShareWav().exceptionOrNull()?.message
                                } finally {
                                    exportingWav = false
                                }
                            }
                        }
                    },
                    enabled = !exportingWav,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(PhaseOnlyIcons.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (exportingWav) "Menyiapkan file WAV..." else "Ekspor & Bagikan File Pulsa (.wav)")
                }
                if (shareError != null) {
                    Text(
                        shareError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = foh.alertAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun GainSliderSection(
    gain: Float,
    onGainChange: (Float) -> Unit,
) {
    var localGain by remember { mutableStateOf(gain) }
    var dragging by remember { mutableStateOf(false) }
    var lastDispatchedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(gain, dragging) {
        if (!dragging) localGain = gain
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Level Output", style = MaterialTheme.typography.labelMedium)
            Text(
                "${(localGain * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Slider(
            value = localGain,
            onValueChange = { next ->
                dragging = true
                localGain = next
                val now = SystemClock.elapsedRealtime()
                if (now - lastDispatchedAt >= 80L) {
                    lastDispatchedAt = now
                    onGainChange(next)
                }
            },
            onValueChangeFinished = {
                dragging = false
                lastDispatchedAt = SystemClock.elapsedRealtime()
                onGainChange(localGain)
            },
            valueRange = 0.15f..0.90f,
        )
        Text(
            "Mulai dengan fader mixer/amp rendah sebelum mengirim sinyal uji.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SensitivityRow(
    selected: PhaseMicSensitivity,
    onSelect: (PhaseMicSensitivity) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Sensitivitas Mic:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhaseMicSensitivity.entries.forEach { preset ->
            FohFilterChip(
                label = preset.label,
                selected = selected == preset,
                onClick = { onSelect(preset) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalibrationAndSlotRow(
    state: PhaseCheckerUiState,
    selectedSpeakerSlot: String,
    onSelectSpeakerSlot: (String) -> Unit,
    onLockOrClearRef: () -> Unit,
    onToggleInvertMic: () -> Unit,
    onSaveResult: () -> Unit,
) {
    val canSave = state.heldEffectiveVerdict == PolarityVerdict.POSITIVE ||
        state.heldEffectiveVerdict == PolarityVerdict.NEGATIVE

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onLockOrClearRef,
            enabled = state.referencePolarity != null || canSave,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(UiIcons.Shield, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.referencePolarity != null) "Reset Ref A" else "Kunci Ref A",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onToggleInvertMic,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(PhaseOnlyIcons.Swap, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.micInverted) "Mic: Terbalik (±)" else "Balik Mic (±)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    HorizontalDivider(color = FohTheme.colors.borderSubtle.copy(alpha = 0.55f))

    Text(
        "Catat Hasil Per Speaker",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhaseCheckerRules.DEFAULT_SPEAKER_SLOTS.forEach { slot ->
            FohFilterChip(
                label = slot,
                selected = selectedSpeakerSlot == slot,
                onClick = { onSelectSpeakerSlot(slot) },
            )
        }
    }
    OutlinedButton(
        onClick = onSaveResult,
        enabled = canSave,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(UiIcons.Check, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Simpan Hasil untuk \"$selectedSpeakerSlot\"")
    }
}

@Composable
internal fun SpeakerChecklistCard(
    entries: List<SpeakerPhaseCheckEntry>,
    onRemoveEntry: (Long) -> Unit,
    onClearAll: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, foh.borderSubtle),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Daftar Speaker Teruji (${entries.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Bersihkan")
                }
            }
            entries.forEachIndexed { idx, entry ->
                if (idx > 0) {
                    HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.5f))
                }
                val isInverted = entry.comparison == ReferenceComparison.INVERTED ||
                    (entry.comparison == ReferenceComparison.NONE && entry.verdict == PolarityVerdict.NEGATIVE)
                val toneColor = if (isInverted) foh.alertAccent else foh.safeAccent
                val badgeText = when (entry.comparison) {
                    ReferenceComparison.MATCHING -> "SEFASA A (+)"
                    ReferenceComparison.INVERTED -> "TERBALIK (-180°)"
                    ReferenceComparison.PENDING, ReferenceComparison.NONE ->
                        if (entry.verdict == PolarityVerdict.POSITIVE) "+ POSITIF" else "- TERBALIK"
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.speakerName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "$badgeText · Keyakinan ${(entry.confidence * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = toneColor,
                        )
                    }
                    CompactCircleButton(
                        icon = UiIcons.Close,
                        description = "Hapus catatan ${entry.speakerName}",
                        onClick = { onRemoveEntry(entry.id) },
                        size = 30.dp,
                        iconSize = 15.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceComparisonBanner(comparison: ReferenceComparison) {
    val foh = FohTheme.colors
    val style = when (comparison) {
        ReferenceComparison.MATCHING -> ComparisonBannerStyle(
            bg = foh.safeBg,
            border = foh.safeBorder,
            accent = foh.safeAccent,
            label = "SAMA DENGAN REFERENSI A (SEFASA)",
        )
        ReferenceComparison.INVERTED -> ComparisonBannerStyle(
            bg = foh.alertBg,
            border = foh.alertBorder,
            accent = foh.alertAccent,
            label = "TERBALIK DARI REFERENSI A (-180°)",
        )
        ReferenceComparison.PENDING -> ComparisonBannerStyle(
            bg = foh.warnBg,
            border = foh.warnBorder,
            accent = foh.warnAccent,
            label = "Referensi A terkunci • Menunggu pembacaan speaker target",
        )
        ReferenceComparison.NONE -> return
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = style.bg,
        border = BorderStroke(1.dp, style.border),
    ) {
        Text(
            text = style.label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = style.accent,
        )
    }
}

@Composable
private fun ImpulseOscilloscope(
    samples: List<Float>,
    listening: Boolean,
    positiveColor: Color,
    negativeColor: Color,
    gridColor: Color,
    scopeBg: Color,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scopeBg,
        border = BorderStroke(1.dp, gridColor),
    ) {
        Box(
            Modifier.fillMaxWidth().height(98.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                val midY = size.height / 2f
                drawLine(gridColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1.4f)
                drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, size.height * 0.25f), Offset(size.width, size.height * 0.25f), strokeWidth = 0.8f)
                drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.75f), strokeWidth = 0.8f)

                if (samples.size < 2) return@Canvas
                val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                val tracePath = Path()
                var maxAbs = 0f
                var maxIdx = 0
                samples.forEachIndexed { index, sample ->
                    val clamped = sample.coerceIn(-1f, 1f)
                    val x = index * stepX
                    val y = midY - clamped * (size.height * 0.46f)
                    if (index == 0) tracePath.moveTo(x, y) else tracePath.lineTo(x, y)
                    if (abs(clamped) > maxAbs) {
                        maxAbs = abs(clamped)
                        maxIdx = index
                    }
                    if (abs(clamped) > 0.03f) {
                        drawLine(
                            color = (if (clamped >= 0f) positiveColor else negativeColor).copy(alpha = 0.42f),
                            start = Offset(x, midY),
                            end = Offset(x, y),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                val dominantColor = if (samples.getOrElse(maxIdx) { 0f } >= 0f) positiveColor else negativeColor
                drawPath(
                    path = tracePath,
                    color = if (maxAbs > 0.03f) dominantColor else gridColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            if (!listening && samples.all { abs(it) < 0.02f }) {
                Text(
                    "Trace impuls akan tampil di sini",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
