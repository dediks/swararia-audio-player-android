package com.swararia.audiolab

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
private fun phoneVolumePalette(tier: PhoneMediaVolumeTier): PillPalette {
    val foh = FohTheme.colors
    return when (tier) {
        PhoneMediaVolumeTier.MUTED -> PillPalette(foh.alertBg, foh.alertBorder, foh.alertAccent)
        PhoneMediaVolumeTier.LOW -> PillPalette(foh.warnBg, foh.warnBorder, foh.warnAccent)
        PhoneMediaVolumeTier.MODERATE -> PillPalette(foh.surfaceCard, foh.borderSubtle, MaterialTheme.colorScheme.onSurfaceVariant)
        PhoneMediaVolumeTier.OPTIMAL -> PillPalette(foh.safeBg, foh.safeBorder, foh.safeAccent)
    }
}

private fun readPhoneMediaVolumeSnapshot(audioManager: AudioManager?): PhoneMediaVolumeState {
    val am = audioManager ?: return PhoneMediaVolumeState()
    val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
    val min = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        runCatching { am.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
    } else {
        0
    }
    val current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
    val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
    return PhoneMediaVolumeRules.classify(
        currentStep = current,
        maxStep = max,
        minStep = min,
        streamMuted = muted,
    )
}

/**
 * Observes Android's system media volume (`STREAM_MUSIC`) read-only via system settings observer,
 * audio route changes, resume events, and a foreground-only fallback tick.
 */
@Composable
internal fun rememberPhoneMediaVolumeState(): PhoneMediaVolumeState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    var volumeState by remember(audioManager) { mutableStateOf(readPhoneMediaVolumeSnapshot(audioManager)) }

    DisposableEffect(context, lifecycleOwner, audioManager) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val handler = Handler(Looper.getMainLooper())
        val refresh = { volumeState = readPhoneMediaVolumeSnapshot(audioManager) }
        refresh()

        val settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                settingsObserver,
            )
        }

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(settingsObserver) }
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(lifecycleOwner, audioManager) {
        if (audioManager == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                volumeState = readPhoneMediaVolumeSnapshot(audioManager)
                delay(600L)
            }
        }
    }

    return volumeState
}

/** Shows Android's native media volume UI without mutating the current stream volume. */
internal fun showSystemMediaVolumePanel(context: Context) {
    val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI,
        )
    }
}

/** Compact warning strip displayed in the player header only when phone media volume is muted or low. */
@Composable
internal fun PhoneMediaVolumeWarningStrip(
    phoneVolume: PhoneMediaVolumeState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = phoneVolumePalette(phoneVolume.tier)
    val icon = if (phoneVolume.isMuted) UiIcons.VolumeMuted else UiIcons.Warning
    val badgeText = if (phoneVolume.isMuted) "Bisu" else "${phoneVolume.percent}%"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = palette.bg,
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .semantics { contentDescription = PhoneMediaVolumeRules.shortBannerText(phoneVolume) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
            Text(
                text = PhoneMediaVolumeRules.shortBannerText(phoneVolume),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = palette.accent.copy(alpha = 0.16f),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PhoneMediaVolumeCard(
    phoneVolume: PhoneMediaVolumeState,
    onOpenVolumePanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = phoneVolumePalette(phoneVolume.tier)
    val foh = FohTheme.colors
    val icon = when {
        phoneVolume.isMuted -> UiIcons.VolumeMuted
        phoneVolume.tier == PhoneMediaVolumeTier.LOW -> UiIcons.Warning
        else -> UiIcons.VolumeUp
    }
    val titleColor = if (phoneVolume.tier == PhoneMediaVolumeTier.MODERATE) {
        MaterialTheme.colorScheme.onSurface
    } else {
        palette.accent
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.bg,
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "VOLUME MEDIA HP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = titleColor,
                    )
                    Text(
                        text = "${phoneVolume.percentLabel} · ${phoneVolume.stepLabel}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = palette.accent,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = PhoneMediaVolumeRules.faderSheetDetailText(phoneVolume),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onOpenVolumePanel,
                shape = RoundedCornerShape(8.dp),
                color = foh.surfaceDeep,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Panel HP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
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
    phoneVolume: PhoneMediaVolumeState,
    monoSum: Boolean = false,
    enabled: Boolean,
    playing: Boolean,
    fadeBusy: Boolean,
    onPositionChange: (Float) -> Unit,
    onToggleDuck: () -> Unit,
    onToggleMute: () -> Unit,
    onResetUnity: () -> Unit,
    onSetMonoSum: (Boolean) -> Unit = {},
    onOpenPhoneVolumePanel: () -> Unit,
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
                    .height(260.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
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
                                .width(68.dp)
                                .fillMaxHeight(),
                        )
                        PostFaderLevelBar(
                            effectiveGain = faderState.effectiveGain,
                            playing = playing,
                            muted = faderState.muted,
                            modifier = Modifier
                                .width(28.dp)
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
                            playing = playing,
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

            OutputModeSwitchCard(
                monoSum = monoSum,
                enabled = enabled,
                onSetMonoSum = onSetMonoSum,
            )

            PhoneMediaVolumeCard(
                phoneVolume = phoneVolume,
                onOpenVolumePanel = onOpenPhoneVolumePanel,
            )
        }
    }
}

@Composable
private fun OutputModeSwitchCard(
    monoSum: Boolean,
    enabled: Boolean,
    onSetMonoSum: (Boolean) -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, if (monoSum) foh.safeBorder else foh.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "OUTPUT MODE",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (monoSum) "Mono L+R Sum" else "Stereo L/R",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (monoSum) foh.safeAccent else MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(foh.surfaceDeep)
                    .border(1.dp, foh.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    onClick = { onSetMonoSum(false) },
                    enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    color = if (!monoSum) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ) {
                    Text(
                        text = "STEREO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (!monoSum) FontWeight.Bold else FontWeight.Medium,
                        color = if (!monoSum) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    onClick = { onSetMonoSum(true) },
                    enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    color = if (monoSum) foh.safeBg else Color.Transparent,
                    border = if (monoSum) BorderStroke(1.dp, foh.safeBorder) else null,
                ) {
                    Text(
                        text = "MONO L+R",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (monoSum) FontWeight.Bold else FontWeight.Medium,
                        color = if (monoSum) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
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
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val signal by rememberLocalAudioSignal()
    val segments = 14
    val gainFraction = effectiveGain.coerceIn(0f, 1f)
    val ceilingSeg = if (gainFraction <= 0f || muted) -1 else ((gainFraction * segments).roundToInt() - 1).coerceIn(0, segments - 1)
    val showLive = !muted && (playing || signal.active)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0C1310),
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = modifier.semantics {
            contentDescription = if (muted) "Stereo meter muted" else "Stereo post-fader level meter ${signal.peakDbLabel}"
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                StereoMeterColumn(
                    level = if (showLive) signal.levelL else 0f,
                    peakHold = if (showLive) signal.peakHoldL else 0f,
                    ceilingSegment = ceilingSeg,
                    signalActive = signal.active,
                    segments = segments,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StereoMeterColumn(
                    level = if (showLive) signal.levelR else 0f,
                    peakHold = if (showLive) signal.peakHoldR else 0f,
                    ceilingSegment = ceilingSeg,
                    signalActive = signal.active,
                    segments = segments,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                Text(
                    text = "L",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Text(
                    text = "R",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StereoMeterColumn(
    level: Float,
    peakHold: Float,
    ceilingSegment: Int,
    signalActive: Boolean,
    segments: Int,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    val clampedLevel = level.coerceIn(0f, 1f)
    val clampedHold = peakHold.coerceIn(0f, 1f)
    val litSegments = if (clampedLevel <= 0.01f) 0 else (clampedLevel * segments).roundToInt().coerceIn(1, segments)
    val peakSeg = if (clampedHold <= 0.01f) -1 else ((clampedHold * segments).roundToInt() - 1).coerceIn(0, segments - 1)

    Canvas(modifier = modifier) {
        val gapPx = 2.dp.toPx()
        val totalGaps = gapPx * (segments - 1)
        val segHeight = ((size.height - totalGaps) / segments).coerceAtLeast(1f)
        val radius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        for (idx in 0 until segments) {
            val fromBottom = segments - 1 - idx
            val topY = idx * (segHeight + gapPx)
            val isLit = fromBottom < litSegments
            val isPeakHold = fromBottom == peakSeg
            val isCeilingMarker = !signalActive && ceilingSegment >= 0 && fromBottom == ceilingSegment
            val baseColor = when {
                fromBottom == segments - 1 -> foh.alertAccent
                fromBottom >= segments - 4 -> foh.warnAccent
                else -> foh.safeAccent
            }
            val color = when {
                isPeakHold -> baseColor
                isLit -> baseColor.copy(alpha = 0.94f)
                isCeilingMarker -> baseColor.copy(alpha = 0.28f)
                else -> Color(0xFF1D2B24).copy(alpha = 0.72f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, topY),
                size = Size(size.width, segHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun FaderNumericReadoutCard(
    faderState: FaderState,
    playing: Boolean,
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
            FaderLivePeakReadout(muted = faderState.muted, playing = playing)
        }
    }
}

@Composable
private fun FaderLivePeakReadout(
    muted: Boolean,
    playing: Boolean,
) {
    val foh = FohTheme.colors
    val signal by rememberLocalAudioSignal()
    val maxLinear = maxOf(signal.peakLinearL, signal.peakLinearR)
    val text = when {
        muted -> "Peak MUTED"
        playing || signal.active -> "Peak ${signal.peakDbLabel}"
        else -> "Peak -∞ dB"
    }
    val color = when {
        muted -> foh.alertAccent
        (playing || signal.active) && maxLinear >= 0.89f -> foh.alertAccent
        (playing || signal.active) && maxLinear >= 0.50f -> foh.warnAccent
        playing || signal.active -> foh.safeAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    }
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
            color = color,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

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
