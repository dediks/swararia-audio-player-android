package com.swararia.audiolab.foh

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
private fun phoneVolumePalette(tier: PhoneMediaVolumeTier): FaderTonePalette {
    val foh = FohTheme.colors
    return when (tier) {
        PhoneMediaVolumeTier.MUTED -> FaderTonePalette(foh.alertBg, foh.alertBorder, foh.alertAccent)
        PhoneMediaVolumeTier.LOW -> FaderTonePalette(foh.warnBg, foh.warnBorder, foh.warnAccent)
        PhoneMediaVolumeTier.MODERATE -> FaderTonePalette(foh.surfaceCard, foh.borderSubtle, MaterialTheme.colorScheme.onSurfaceVariant)
        PhoneMediaVolumeTier.OPTIMAL -> FaderTonePalette(foh.safeBg, foh.safeBorder, foh.safeAccent)
    }
}

private fun readPhoneMediaVolumeSnapshot(audioManager: AudioManager?): PhoneMediaVolumeState {
    val am = audioManager ?: return PhoneMediaVolumeState()
    val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
    val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
internal fun PhoneMediaVolumeCard(
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
