package com.swararia.audiolab

import android.media.AudioDeviceInfo
import java.util.Locale

internal enum class LocalPlayerViewMode(val label: String) {
    CUE_LIST("Cue"),
    PADS("Pads"),
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

/** Pure formatting and FOH output-routing classification rules kept free of Compose UI state. */
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
