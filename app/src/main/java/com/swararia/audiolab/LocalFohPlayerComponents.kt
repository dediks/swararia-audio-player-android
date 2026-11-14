package com.swararia.audiolab

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class LocalPlayerViewMode(val label: String) {
    CUE_LIST("Cue List"),
    ART("Art"),
}

internal enum class AudioOutputKind {
    LINE_OR_USB,
    BLUETOOTH,
    SPEAKER,
}

internal data class AudioOutputRoute(
    val kind: AudioOutputKind,
    val label: String,
    val detail: String,
)

internal data class LocalCountdownState(
    val countdownText: String,
    val remainingMs: Long?,
    val warningActive: Boolean,
)

internal object LocalFohRules {
    const val ENDING_SOON_THRESHOLD_MS = 15_000L

    private val WIRED_OR_USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI,
    )

    private val BLUETOOTH_BASE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        // API 31/33 BLE constants represented numerically so host JVM tests and minSdk 29 stay safe.
        26, // TYPE_BLE_HEADSET
        27, // TYPE_BLE_SPEAKER
        30, // TYPE_BLE_BROADCAST
    )

    fun formatCountdown(remainingMs: Long?): String {
        if (remainingMs == null || remainingMs < 0L) return "--:--"
        val totalSeconds = remainingMs / 1000L
        return if (totalSeconds >= 3600L) {
            String.format(
                Locale.ROOT,
                "-%d:%02d:%02d",
                totalSeconds / 3600L,
                (totalSeconds / 60L) % 60L,
                totalSeconds % 60L,
            )
        } else {
            String.format(Locale.ROOT, "-%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
        }
    }

    fun countdownState(
        positionMs: Long,
        durationMs: Long?,
        isPlaying: Boolean,
    ): LocalCountdownState {
        if (durationMs == null || durationMs <= 0L) {
            return LocalCountdownState(countdownText = "--:--", remainingMs = null, warningActive = false)
        }
        val remaining = (durationMs - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val warning = isPlaying && remaining in 1L..ENDING_SOON_THRESHOLD_MS
        return LocalCountdownState(
            countdownText = formatCountdown(remaining),
            remainingMs = remaining,
            warningActive = warning,
        )
    }

    fun endingAlertText(remainingMs: Long?, mode: PlaybackMode?, isPlaying: Boolean): String? {
        if (!isPlaying || remainingMs == null || remainingMs !in 1L..ENDING_SOON_THRESHOLD_MS) return null
        val seconds = (remainingMs / 1000L).coerceAtLeast(1L)
        return when (mode) {
            PlaybackMode.ONCE -> "Ending in ${seconds}s · Auto-Stop (Mode Once)"
            PlaybackMode.CONTINUE -> "Ending in ${seconds}s · Auto-Next (Continue)"
            PlaybackMode.LOOP_TRACK -> "Ending in ${seconds}s · Looping Track"
            null -> "Ending in ${seconds}s"
        }
    }

    fun formatTag(track: AudioTrack?): String {
        val ext = track?.displayName?.substringAfterLast('.', "")?.trim().orEmpty()
        return if (ext.isBlank() || ext == track?.displayName) "AUDIO" else ext.uppercase(Locale.ROOT)
    }

    fun cueItemSubtitle(track: AudioTrack?, fallbackTitle: String? = null): String =
        if (track != null) UiPresentation.trackSubtitle(track.displayName, track.artist, track.durationMs)
        else fallbackTitle?.takeIf { it.isNotBlank() }?.let(UiPresentation::title) ?: "Audio lokal"

    fun modeTag(mode: PlaybackMode?): String = when (mode) {
        PlaybackMode.ONCE -> "ONCE · AUTO STOP"
        PlaybackMode.CONTINUE -> "CONTINUE"
        PlaybackMode.LOOP_TRACK -> "LOOP TRACK"
        null -> "STANDBY"
    }

    fun modeButtonLabel(mode: PlaybackMode): String = when (mode) {
        PlaybackMode.ONCE -> "Once (Auto-Stop)"
        PlaybackMode.CONTINUE -> "Continue"
        PlaybackMode.LOOP_TRACK -> "Loop Track"
    }

    private fun entryTitle(entry: SessionQueueItem, byId: Map<String, AudioTrack>): String? =
        byId[entry.mediaId]?.let { UiPresentation.title(it.displayName) }
            ?: entry.title?.takeIf { it.isNotBlank() }?.let(UiPresentation::title)

    fun nextUpLabel(playback: SessionPlayback, byId: Map<String, AudioTrack>, mode: PlaybackMode?): String {
        if (!playback.connected || playback.mediaId == null) return "Next Up: Belum ada track"
        if (mode == PlaybackMode.LOOP_TRACK) return "Loop Active · Track Diulang"
        val nextTitle = playback.queue.getOrNull(playback.currentIndex + 1)?.let { entryTitle(it, byId) }
        return when (mode) {
            PlaybackMode.ONCE -> if (nextTitle != null) "Auto-Stop · Next Ready: $nextTitle" else "Mode Once · Auto-Stop"
            else -> if (nextTitle != null) "Next Up: $nextTitle" else "End of Cue List"
        }
    }

    fun classifyOutputDevices(devices: List<Pair<Int, String?>>): AudioOutputRoute {
        val wired = devices.firstOrNull { (type, _) -> type in WIRED_OR_USB_TYPES }
        if (wired != null) {
            val name = wired.second?.trim()?.takeIf { it.isNotEmpty() }
            return AudioOutputRoute(
                kind = AudioOutputKind.LINE_OR_USB,
                label = "USB / Line Out",
                detail = name?.let { "Output aktif melalui $it." } ?: "Kabel Line Out / USB Audio terhubung.",
            )
        }
        val bt = devices.firstOrNull { (type, _) -> type in BLUETOOTH_BASE_TYPES }
        if (bt != null) {
            val name = bt.second?.trim()?.takeIf { it.isNotEmpty() }
            return AudioOutputRoute(
                kind = AudioOutputKind.BLUETOOTH,
                label = "Bluetooth Audio",
                detail = name?.let { "Output aktif ke Bluetooth: $it." } ?: "Audio diarahkan ke perangkat Bluetooth.",
            )
        }
        return AudioOutputRoute(
            kind = AudioOutputKind.SPEAKER,
            label = "Output: Phone Speaker",
            detail = "Tidak ada kabel Line Out / USB terdeteksi. Audio keluar lewat speaker HP.",
        )
    }
}

internal object FohPalette {
    val SafeBg = Color(0xFF132B21)
    val SafeBorder = Color(0xFF285944)
    val SafeAccent = Color(0xFF8CE3C7)

    val BtBg = Color(0xFF142730)
    val BtBorder = Color(0xFF2B5669)
    val BtAccent = Color(0xFF7DD3FC)

    val WarnBg = Color(0xFF2E2311)
    val WarnBorder = Color(0xFF7A5B21)
    val WarnAccent = Color(0xFFF5C451)

    val AlertBg = Color(0xFF351B1B)
    val AlertBorder = Color(0xFF843B38)
    val AlertAccent = Color(0xFFFF8E7D)

    val SurfaceDeep = Color(0xFF131C18)
    val SurfaceCard = Color(0xFF19231F)
    val SurfaceDeck = Color(0xFF111916)
    val BorderSubtle = Color(0xFF2B3D33)
}

@Composable
internal fun rememberAudioOutputRoute(): AudioOutputRoute {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    fun readCurrentRoute(): AudioOutputRoute {
        if (audioManager == null) {
            return LocalFohRules.classifyOutputDevices(emptyList())
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.isSink else true
            }
            .map { device -> device.type to device.productName?.toString() }
        return LocalFohRules.classifyOutputDevices(devices)
    }

    var route by remember(audioManager) { mutableStateOf(readCurrentRoute()) }

    DisposableEffect(audioManager) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                route = readCurrentRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                route = readCurrentRoute()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    return route
}

@Composable
internal fun LocalFohHeaderBar(
    route: AudioOutputRoute,
    keepScreenOn: Boolean,
    guardEnabled: Boolean,
    hasCurrentTrack: Boolean,
    onMinimize: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleGuard: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onRouteClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    val (routeBg, routeBorder, routeAccent, routeIcon) = when (route.kind) {
        AudioOutputKind.LINE_OR_USB -> Quad(FohPalette.SafeBg, FohPalette.SafeBorder, FohPalette.SafeAccent, UiIcons.Plug)
        AudioOutputKind.BLUETOOTH -> Quad(FohPalette.BtBg, FohPalette.BtBorder, FohPalette.BtAccent, UiIcons.Bluetooth)
        AudioOutputKind.SPEAKER -> Quad(FohPalette.WarnBg, FohPalette.WarnBorder, FohPalette.WarnAccent, UiIcons.Warning)
    }

    val (routeText, routeBadge) = when (route.kind) {
        AudioOutputKind.LINE_OR_USB -> "Output: USB / Line Out" to "Stage Ready"
        AudioOutputKind.BLUETOOTH -> "Output: Bluetooth Audio" to "Wireless"
        AudioOutputKind.SPEAKER -> "Output: Phone Speaker · No Line Out" to "Cek"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactCircleButton(
                icon = UiIcons.ChevronDown,
                description = "Perkecil pemutar",
                onClick = onMinimize,
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            Text(
                text = "LOCAL FOH",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )

            Surface(
                onClick = onToggleKeepScreenOn,
                shape = RoundedCornerShape(50),
                color = if (keepScreenOn) FohPalette.SafeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (keepScreenOn) FohPalette.SafeBorder else FohPalette.BorderSubtle),
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .semantics { contentDescription = if (keepScreenOn) "Screen Awake aktif" else "Aktifkan Screen Awake" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        UiIcons.KeepAwake,
                        contentDescription = null,
                        tint = if (keepScreenOn) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (keepScreenOn) "Awake" else "Sleep OK",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (keepScreenOn) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Surface(
                onClick = onToggleGuard,
                shape = RoundedCornerShape(50),
                color = if (guardEnabled) FohPalette.SafeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (guardEnabled) FohPalette.SafeBorder else FohPalette.BorderSubtle),
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .semantics { contentDescription = if (guardEnabled) "Guard ON" else "Guard OFF" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        UiIcons.Shield,
                        contentDescription = null,
                        tint = if (guardEnabled) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (guardEnabled) "Guard ON" else "Guard OFF",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (guardEnabled) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Box {
                CompactCircleButton(
                    icon = UiIcons.More,
                    description = "Menu pemutar",
                    onClick = { menu = true },
                    size = 34.dp,
                    iconSize = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Ubah nama audio") },
                        leadingIcon = { Icon(UiIcons.Rename, null) },
                        enabled = hasCurrentTrack,
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Pengaturan & tentang") },
                        leadingIcon = { Icon(UiIcons.Settings, null) },
                        onClick = {
                            menu = false
                            onSettings()
                        },
                    )
                }
            }
        }

        Surface(
            onClick = onRouteClick,
            shape = RoundedCornerShape(12.dp),
            color = routeBg,
            border = BorderStroke(1.dp, routeBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .semantics { contentDescription = route.label },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(routeIcon, contentDescription = null, tint = routeAccent, modifier = Modifier.size(15.dp))
                Text(
                    text = routeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = routeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FohMicroChip(
                    label = routeBadge,
                    containerColor = routeAccent.copy(alpha = 0.16f),
                    contentColor = routeAccent,
                )
            }
        }
    }
}

@Composable
internal fun LocalFohTrackSummaryCard(
    current: AudioTrack?,
    title: String,
    playback: SessionPlayback,
    controlUi: LocalPlaybackControlUi,
    seekPosition: Float?,
    onSeekChange: (Float) -> Unit,
    onSeekCommit: () -> Unit,
) {
    val activePosMs = seekPosition?.toLong() ?: playback.positionMs
    val countdown = LocalFohRules.countdownState(
        positionMs = activePosMs,
        durationMs = playback.durationMs,
        isPlaying = playback.playing,
    )
    val endingAlert = LocalFohRules.endingAlertText(countdown.remainingMs, controlUi.mode, playback.playing)
    val formatTag = LocalFohRules.formatTag(current)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = FohPalette.SurfaceCard,
        border = BorderStroke(
            width = 1.dp,
            color = if (countdown.warningActive) FohPalette.AlertBorder else FohPalette.BorderSubtle,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrackArtwork(
                    current?.artworkKey,
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FohMicroChip(
                            label = formatTag,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FohMicroChip(
                            label = LocalFohRules.modeTag(controlUi.mode),
                            containerColor = FohPalette.SafeBg,
                            contentColor = FohPalette.SafeAccent,
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    val countdownColor = when {
                        countdown.warningActive -> FohPalette.AlertAccent
                        playback.playing -> FohPalette.SafeAccent
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = countdown.countdownText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = countdownColor,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "${LibraryRules.durationLabel(activePosMs.takeIf { playback.connected && playback.mediaId != null })} / ${LibraryRules.durationLabel(playback.durationMs)}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            val duration = playback.durationMs ?: 1L
            val displayed = if (playback.durationMs == null) 0f else (seekPosition ?: playback.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
            Slider(
                value = displayed,
                onValueChange = onSeekChange,
                valueRange = 0f..duration.toFloat(),
                enabled = playback.connected && playback.canSeek && playback.durationMs != null,
                onValueChangeFinished = onSeekCommit,
                colors = SliderDefaults.colors(
                    thumbColor = if (countdown.warningActive) FohPalette.AlertAccent else FohPalette.SafeAccent,
                    activeTrackColor = if (countdown.warningActive) FohPalette.AlertAccent else FohPalette.SafeAccent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .semantics { contentDescription = "Posisi pemutaran audio" },
            )

            AnimatedVisibility(visible = endingAlert != null) {
                if (endingAlert != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = FohPalette.AlertBg,
                        border = BorderStroke(1.dp, FohPalette.AlertBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                UiIcons.Warning,
                                contentDescription = null,
                                tint = FohPalette.AlertAccent,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = endingAlert,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = FohPalette.AlertAccent,
                            )
                        }
                    }
                }
            }

            if (playback.buffering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!playback.connected) {
                Text(
                    "Pemutar belum terhubung. Kembali ke Pustaka untuk memilih audio setelah tersambung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            playback.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // 1-Tap Play Mode Segmented Strip (Once / Continue / Loop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val orderedModes = listOf(PlaybackMode.ONCE, PlaybackMode.CONTINUE, PlaybackMode.LOOP_TRACK)
                orderedModes.forEach { mode ->
                    val selectedMode = controlUi.mode == mode
                    Surface(
                        onClick = { controlUi.onSelectMode(mode) },
                        enabled = controlUi.modeSupported && !controlUi.pending && !selectedMode,
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedMode) FohPalette.SafeBg else FohPalette.SurfaceDeep,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selectedMode) FohPalette.SafeAccent else FohPalette.BorderSubtle,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp)
                            .semantics { selected = selectedMode },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(
                                text = LocalFohRules.modeButtonLabel(mode),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedMode) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val controlStatus = controlUi.statusText
            if (controlStatus != null && (controlUi.isError || !controlUi.connected || !controlUi.modeSupported || controlUi.fadeBusy)) {
                Text(
                    text = controlStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (controlUi.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
internal fun LocalFohViewModeHeader(
    queueCount: Int,
    currentIndex: Int,
    viewMode: LocalPlayerViewMode,
    onSelectViewMode: (LocalPlayerViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = if (viewMode == LocalPlayerViewMode.CUE_LIST) "LIVE CUE LIST · $queueCount TRACKS" else "COVER ART & INFO",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentIndex >= 0 && queueCount > 0) {
                Text(
                    text = "Active Slot #${currentIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FohPalette.SafeAccent,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = FohPalette.SurfaceDeep,
            border = BorderStroke(1.dp, FohPalette.BorderSubtle),
        ) {
            Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LocalPlayerViewMode.entries.forEach { mode ->
                    val isSelected = viewMode == mode
                    Surface(
                        onClick = { onSelectViewMode(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) FohPalette.SafeBg else Color.Transparent,
                        modifier = Modifier.semantics { selected = isSelected },
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalFohCueList(
    queue: List<SessionQueueItem>,
    tracksById: Map<String, AudioTrack>,
    currentIndex: Int,
    wantsPlay: Boolean,
    enabled: Boolean,
    listState: LazyListState,
    onSelectEntry: (SessionQueueItem, cueOnly: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Cue list masih kosong. Pilih audio dari tab Pustaka untuk mengisi daftar putar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(queue, key = { "${it.index}:${it.mediaId}" }) { entry ->
            val track = tracksById[entry.mediaId]
            val title = track?.let { UiPresentation.title(it.displayName) }
                ?: entry.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
                ?: "Audio tidak tersedia"
            val subtitle = LocalFohRules.cueItemSubtitle(track, entry.title)
            val active = entry.index == currentIndex

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (active) FohPalette.SafeBg else FohPalette.SurfaceCard,
                border = BorderStroke(1.dp, if (active) FohPalette.SafeAccent else FohPalette.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, onClickLabel = "Putar $title") {
                        onSelectEntry(entry, false)
                    }
                    .semantics { selected = active },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = String.format(Locale.ROOT, "%02d", entry.index + 1),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = if (active) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.widthIn(min = 24.dp),
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) FohPalette.SafeAccent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (active) {
                        FohMicroChip(
                            label = if (wantsPlay) "PLAYING" else "STANDBY",
                            containerColor = FohPalette.SafeAccent.copy(alpha = 0.16f),
                            contentColor = FohPalette.SafeAccent,
                        )
                    } else {
                        Surface(
                            onClick = { onSelectEntry(entry, true) },
                            enabled = enabled,
                            shape = RoundedCornerShape(8.dp),
                            color = FohPalette.WarnBg,
                            border = BorderStroke(1.dp, FohPalette.WarnBorder),
                            modifier = Modifier.heightIn(min = 32.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "Cue",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FohPalette.WarnAccent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalFohCoverView(
    current: AudioTrack?,
    title: String,
    playback: SessionPlayback,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * 0.56f, 260.dp).coerceAtLeast(110.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrackArtwork(
                current?.artworkKey,
                Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(22.dp)),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = LocalFohRules.cueItemSubtitle(current, playback.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            current?.album?.takeIf { it.isNotBlank() }?.let { album ->
                Text(
                    text = "Album: $album",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LocalFohBottomDeck(
    nextUpLabel: String,
    keepScreenOn: Boolean,
    countdown: LocalCountdownState,
    playback: SessionPlayback,
    controlUi: LocalPlaybackControlUi,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onCueStart: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = FohPalette.SurfaceDeck,
        border = BorderStroke(1.dp, FohPalette.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = countdown.countdownText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = if (countdown.warningActive) FohPalette.AlertAccent else FohPalette.SafeAccent,
                        ),
                    )
                    Text(
                        text = nextUpLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactCircleButton(
                        icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        description = if (playback.wantsPlay) "Jeda audio" else "Putar audio",
                        onClick = onPlayPause,
                        enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                        size = 36.dp,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    CompactCircleButton(
                        icon = UiIcons.Stop,
                        description = "Stop audio",
                        onClick = onStop,
                        enabled = playback.connected && playback.mediaId != null && playback.canStop,
                        size = 36.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    OutlinedButton(
                        onClick = controlUi.onFadeOut,
                        enabled = controlUi.fadeSupported && controlUi.playing && !controlUi.fadeBusy && !controlUi.pending,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(if (controlUi.fadeBusy) "Fading…" else "Fade", style = MaterialTheme.typography.labelSmall)
                    }
                    CompactCircleButton(
                        icon = UiIcons.ChevronUp,
                        description = "Perluas transport deck",
                        onClick = onToggleCollapsed,
                        size = 34.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (keepScreenOn) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(FohPalette.SafeAccent),
                        )
                    }
                    Text(
                        text = nextUpLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactCircleButton(
                        icon = UiIcons.ChevronDown,
                        description = "Perkecil transport deck",
                        onClick = onToggleCollapsed,
                        size = 30.dp,
                        iconSize = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FohDeckButton(
                        label = "Prev",
                        icon = UiIcons.Previous,
                        enabled = playback.connected && playback.hasPrevious,
                        filled = false,
                        outlined = false,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = if (playback.wantsPlay) "Pause" else "Play",
                        icon = if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                        enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                        filled = true,
                        outlined = false,
                        onClick = onPlayPause,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = "Stop",
                        icon = UiIcons.Stop,
                        enabled = playback.connected && playback.mediaId != null && playback.canStop,
                        filled = false,
                        outlined = true,
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    )
                    FohDeckButton(
                        label = "Next",
                        icon = UiIcons.Next,
                        enabled = playback.connected && playback.hasNext,
                        filled = false,
                        outlined = false,
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(color = FohPalette.BorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onCueStart,
                        enabled = playback.connected && playback.mediaId != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FohPalette.WarnBg,
                            contentColor = FohPalette.WarnAccent,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Icon(UiIcons.CueStart, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cue 00:00", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = controlUi.onFadeOut,
                        enabled = controlUi.fadeSupported && controlUi.playing && !controlUi.fadeBusy && !controlUi.pending,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (controlUi.fadeSupported && controlUi.playing) FohPalette.SafeBorder else FohPalette.BorderSubtle,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (controlUi.fadeBusy) "Fading…" else "Fade Out 3s",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                if (controlUi.fadeBusy) {
                    LinearProgressIndicator(
                        color = FohPalette.SafeAccent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalTrackSwitchGuardDialog(
    targetTitle: String,
    activeTitle: String,
    cueButtonLabel: String = "Cue Only (Standby 00:00)",
    detailText: String = "Track \"$activeTitle\" sedang live. Pilih tindakan untuk \"$targetTitle\":",
    onCueOnly: () -> Unit,
    onCutAndPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(UiIcons.Shield, contentDescription = null, tint = FohPalette.WarnAccent) },
        title = { Text("Track Sedang Playing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onCueOnly,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohPalette.WarnBg,
                        contentColor = FohPalette.WarnAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(cueButtonLabel, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onCutAndPlay,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FohPalette.AlertBg,
                        contentColor = FohPalette.AlertAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cut & Play Now", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal (Keep Playing)")
            }
        },
    )
}

@Composable
private fun FohMicroChip(
    label: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
