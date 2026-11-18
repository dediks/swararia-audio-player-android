package com.swararia.audiolab.foh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.swararia.audiolab.AudioTrack
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FadeRules
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.LocalPlaybackControlUi
import com.swararia.audiolab.PlaybackModeBar
import com.swararia.audiolab.SessionPlayback
import com.swararia.audiolab.UiIcons
import com.swararia.audiolab.brandskin.BrandSkinConfig
import com.swararia.audiolab.brandskin.BrandSkinRules
import com.swararia.audiolab.brandskin.BrandSkinUiActions
import com.swararia.audiolab.brandskin.FohSkinPreset
import com.swararia.audiolab.brandskin.HaloChannelMode
import com.swararia.audiolab.brandskin.PrestigeBrandEmblem
import com.swararia.audiolab.brandskin.StageRtaStyle
import com.swararia.audiolab.playbackTitle
import com.swararia.audiolab.toggleSwarariaPlayback
import kotlin.math.roundToInt

@Composable
internal fun StageRtaScreen(
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    onClose: () -> Unit,
    onOpenBrandStudio: () -> Unit,
    modifier: Modifier = Modifier,
    showCloseButton: Boolean = false,
    brandSkinActions: BrandSkinUiActions = BrandSkinUiActions(),
) {
    val foh = FohTheme.colors
    val brand = FohTheme.brand
    val signal by rememberLocalAudioSignal()
    var quickThemeOpen by remember { mutableStateOf(false) }

    val activeTitle = playbackTitle(current, playback)
    val bassEnergy = remember(signal.bands, signal.muted, playback.playing, signal.active) {
        if (signal.muted || (!playback.playing && !signal.active)) {
            0f
        } else {
            ((signal.bands.getOrElse(0) { 0f } +
                signal.bands.getOrElse(1) { 0f } +
                signal.bands.getOrElse(2) { 0f }) / 3f).coerceIn(0f, 1f)
        }
    }
    val liveActive = !signal.muted && (playback.playing || signal.active)
    val maxPeakLinear = maxOf(signal.peakLinearL, signal.peakLinearR)
    val dbfsText = when {
        signal.muted -> "MUTED"
        liveActive -> signal.peakDbLabel
        else -> "-∞ dB"
    }
    val dbfsColor = when {
        signal.muted -> foh.alertAccent
        liveActive && maxPeakLinear >= 0.89f -> foh.alertAccent
        liveActive && maxPeakLinear >= 0.50f -> foh.warnAccent
        liveActive -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    PlaybackModeBar(controller, modifier = modifier.fillMaxSize()) { controlUi ->
        val backdropBrush = remember(foh.safeAccent, foh.surfaceDeep, brand.oledTrueBlack, bassEnergy) {
            val baseBg = if (brand.oledTrueBlack) Color.Black else foh.surfaceDeep
            Brush.radialGradient(
                0.0f to foh.safeAccent.copy(alpha = 0.08f + bassEnergy * 0.14f),
                0.65f to if (brand.oledTrueBlack) Color.Black else foh.surfaceDeck,
                1.0f to baseBg,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropBrush)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StageRtaTopBar(
                liveActive = liveActive,
                cleanMode = brand.cleanStageMode,
                rtaStyle = brand.stageRtaStyle,
                haloChannelMode = brand.haloChannelMode,
                showCloseButton = showCloseButton,
                onClose = onClose,
                onOpenThemeQuickSheet = { quickThemeOpen = true },
                onToggleCleanMode = { brandSkinActions.onSetCleanStageMode(!brand.cleanStageMode) },
            )

            StageHeroBrandHeader(
                brand = brand,
                liveActive = liveActive,
                bassEnergy = bassEnergy,
                dbfsText = dbfsText,
                dbfsColor = dbfsColor,
                compactEmblem = brand.stageRtaStyle == StageRtaStyle.HALO,
            )

            StageRtaAnalyzerCard(
                signal = signal,
                liveActive = liveActive,
                brand = brand,
                bassEnergy = bassEnergy,
                onSelectRtaStyle = brandSkinActions.onSetStageRtaStyle,
                onToggleHaloChannelMode = {
                    val next = if (brand.haloChannelMode == HaloChannelMode.MIRRORED) {
                        HaloChannelMode.STEREO_SPLIT
                    } else {
                        HaloChannelMode.MIRRORED
                    }
                    brandSkinActions.onSetHaloChannelMode(next)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            StageStereoVuCard(
                levelL = if (liveActive) signal.levelL else 0f,
                peakHoldL = if (liveActive) signal.peakHoldL else 0f,
                levelR = if (liveActive) signal.levelR else 0f,
                peakHoldR = if (liveActive) signal.peakHoldR else 0f,
            )

            AnimatedVisibility(
                visible = !brand.cleanStageMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                StageCompactDeck(
                    title = activeTitle,
                    playback = playback,
                    controlUi = controlUi,
                    onPlayPause = { toggleSwarariaPlayback(controller) },
                )
            }
        }
    }

    if (quickThemeOpen) {
        StageQuickThemeSheet(
            brand = brand,
            onSelectPreset = brandSkinActions.onSelectPreset,
            onSelectLogoAccent = brandSkinActions.onSelectLogoAccent,
            onToggleOled = brandSkinActions.onSetOledTrueBlack,
            onToggleAnimations = brandSkinActions.onSetPrestigeAnimations,
            onToggleClean = brandSkinActions.onSetCleanStageMode,
            onOpenFullStudio = {
                quickThemeOpen = false
                onOpenBrandStudio()
            },
            onDismiss = { quickThemeOpen = false },
        )
    }
}

@Composable
private fun StageRtaTopBar(
    liveActive: Boolean,
    cleanMode: Boolean,
    rtaStyle: StageRtaStyle,
    haloChannelMode: HaloChannelMode,
    showCloseButton: Boolean = false,
    onClose: () -> Unit,
    onOpenThemeQuickSheet: () -> Unit,
    onToggleCleanMode: () -> Unit,
) {
    val foh = FohTheme.colors
    val statusText = when {
        !liveActive -> "STANDBY · PCM"
        rtaStyle == StageRtaStyle.HALO && haloChannelMode == HaloChannelMode.STEREO_SPLIT ->
            "REAL-TIME RTA · STEREO L/R"
        else -> "REAL-TIME RTA · 16-BAND"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (showCloseButton) {
            CompactCircleButton(
                icon = UiIcons.ChevronDown,
                description = "Kembali",
                onClick = onClose,
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = foh.surfaceCard,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        Surface(
            shape = CircleShape,
            color = if (liveActive) foh.safeBg else foh.surfaceCard,
            border = BorderStroke(1.dp, if (liveActive) foh.safeBorder else foh.borderSubtle),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (liveActive) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = if (liveActive) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactCircleButton(
                icon = UiIcons.Palette,
                description = "Ganti Skin Cepat",
                onClick = onOpenThemeQuickSheet,
                size = 36.dp,
                iconSize = 18.dp,
                containerColor = foh.surfaceCard,
                contentColor = foh.safeAccent,
            )
            CompactCircleButton(
                icon = if (cleanMode) UiIcons.FullscreenExit else UiIcons.Fullscreen,
                description = if (cleanMode) "Tampilkan Kontrol Bawah" else "Mode Bersih Panggung",
                onClick = onToggleCleanMode,
                size = 36.dp,
                iconSize = 17.dp,
                containerColor = if (cleanMode) foh.safeBg else foh.surfaceCard,
                contentColor = if (cleanMode) foh.safeAccent else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StageHeroBrandHeader(
    brand: BrandSkinConfig,
    liveActive: Boolean,
    bassEnergy: Float,
    dbfsText: String,
    dbfsColor: Color,
    compactEmblem: Boolean,
) {
    val foh = FohTheme.colors
    val rentalTitle = BrandSkinRules.effectiveRentalTitle(brand)
    val eventSub = BrandSkinRules.effectiveEventSubtitle(brand)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compactEmblem) {
            PrestigeBrandEmblem(
                logoKey = brand.customLogoKey,
                size = 56.dp,
                shimmerEnabled = brand.prestigeAnimations,
                bassEnergy = bassEnergy,
                playing = liveActive,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = rentalTitle.uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.8.sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = eventSub.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                fontWeight = FontWeight.Bold,
                color = foh.safeAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = foh.surfaceCard,
            border = BorderStroke(1.dp, foh.borderSubtle),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = dbfsText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                        color = dbfsColor,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "PEAK OUT",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StageStereoVuCard(
    levelL: Float,
    peakHoldL: Float,
    levelR: Float,
    peakHoldR: Float,
) {
    val foh = FohTheme.colors
    val brand = FohTheme.brand
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (brand.oledTrueBlack) Color(0xFF070908) else foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StereoVuTrackRow("L", levelL, peakHoldL)
            StereoVuTrackRow("R", levelR, peakHoldR)
        }
    }
}

@Composable
private fun StereoVuTrackRow(
    channelLabel: String,
    level: Float,
    peakHold: Float,
) {
    val foh = FohTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = channelLabel,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.width(12.dp),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            drawRect(color = Color.White.copy(alpha = 0.06f))
            val frac = level.coerceIn(0f, 1f)
            if (frac > 0.005f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0.0f to foh.safeAccent.copy(alpha = 0.72f),
                        0.72f to foh.safeAccent,
                        0.88f to foh.warnAccent,
                        1.0f to foh.alertAccent,
                    ),
                    size = Size(size.width * frac, size.height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
            val pinFrac = peakHold.coerceIn(0f, 1f)
            if (pinFrac > 0.02f) {
                val px = (size.width * pinFrac).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
                drawRect(
                    color = Color.White,
                    topLeft = Offset(px - 1.5.dp.toPx(), 0f),
                    size = Size(3.dp.toPx(), size.height),
                )
            }
        }
    }
}

@Composable
private fun StageCompactDeck(
    title: String,
    playback: SessionPlayback,
    controlUi: LocalPlaybackControlUi,
    onPlayPause: () -> Unit,
) {
    val foh = FohTheme.colors
    val brand = FohTheme.brand
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (brand.oledTrueBlack) Color(0xFF070908) else foh.surfaceDeck,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${if (controlUi.monoSum) "MONO L+R" else "STEREO"} · ${controlUi.faderState.dbLabel} · ${LocalFohRules.modeTag(controlUi.mode)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = foh.safeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                onClick = { controlUi.onSetMonoSum(!controlUi.monoSum) },
                enabled = controlUi.connected && controlUi.faderSupported,
                shape = RoundedCornerShape(10.dp),
                color = if (controlUi.monoSum) foh.safeBg else foh.surfaceCard,
                border = BorderStroke(1.dp, if (controlUi.monoSum) foh.safeBorder else foh.borderSubtle),
            ) {
                Text(
                    text = if (controlUi.monoSum) "MONO" else "ST",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (controlUi.monoSum) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Surface(
                onClick = controlUi.onFadeOut,
                enabled = FadeRules.canTapFadeControl(
                    fadeSupported = controlUi.fadeSupported,
                    playing = controlUi.playing,
                    fadeBusy = controlUi.fadeBusy,
                    pending = controlUi.pending,
                ),
                shape = RoundedCornerShape(10.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, if (controlUi.fadeBusy) foh.alertBorder else foh.borderSubtle),
            ) {
                Text(
                    text = if (controlUi.fadeBusy) "Cut" else "Fade",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            CompactCircleButton(
                icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                description = if (playback.wantsPlay) "Jeda" else "Putar",
                onClick = onPlayPause,
                enabled = playback.connected && playback.canPlay,
                size = 38.dp,
                iconSize = 19.dp,
                containerColor = foh.safeAccent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StageQuickThemeSheet(
    brand: BrandSkinConfig,
    onSelectPreset: (String) -> Unit,
    onSelectLogoAccent: () -> Unit,
    onToggleOled: (Boolean) -> Unit,
    onToggleAnimations: (Boolean) -> Unit,
    onToggleClean: (Boolean) -> Unit,
    onOpenFullStudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = foh.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Skin & Tampilan Stage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FohSkinPreset.entries.forEach { preset ->
                    val selected = !brand.useCustomLogoAccent && brand.presetId == preset.id
                    Surface(
                        onClick = { onSelectPreset(preset.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) foh.safeBg else foh.surfaceDeep,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) Color(preset.accentArgb) else foh.borderSubtle,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(preset.accentArgb)),
                            )
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            if (brand.customAccentArgb != null) {
                Surface(
                    onClick = onSelectLogoAccent,
                    shape = RoundedCornerShape(12.dp),
                    color = if (brand.useCustomLogoAccent) foh.safeBg else foh.surfaceDeep,
                    border = BorderStroke(1.dp, if (brand.useCustomLogoAccent) Color(brand.customAccentArgb) else foh.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(brand.customAccentArgb)),
                        )
                        Text(
                            text = "Ikuti Warna Otomatis dari Logo",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            StageQuickSwitchRow("True Black OLED", brand.oledTrueBlack, onToggleOled)
            StageQuickSwitchRow("Animasi Kilau & Aura Bass", brand.prestigeAnimations, onToggleAnimations)
            StageQuickSwitchRow("Clean Stage (Sembunyikan Kontrol)", brand.cleanStageMode, onToggleClean)

            OutlinedButton(
                onClick = onOpenFullStudio,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, foh.safeBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Upload Logo & Identitas Rental", fontWeight = FontWeight.SemiBold, color = foh.safeAccent)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StageQuickSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val foh = FohTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = foh.safeAccent,
            ),
        )
    }
}
