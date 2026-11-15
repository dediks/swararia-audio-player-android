package com.swararia.audiolab

import java.util.Locale

/** Names are the persisted values and the app-only session protocol values. */
enum class PlaybackMode(val label: String) {
    LOOP_TRACK("Loop track"), CONTINUE("Continue"), ONCE("Once");

    val repeatCurrent: Boolean get() = this == LOOP_TRACK
    val pauseAtItemEnd: Boolean get() = this == ONCE

    companion object {
        fun fromStored(value: String?): PlaybackMode =
            entries.firstOrNull { it.name == value } ?: CONTINUE
    }
}

/** Global playback fade-in / fade-out presets in milliseconds. */
enum class PlaybackFadePreset(
    val durationMs: Long,
    val label: String,
    val shortLabel: String,
) {
    OFF(0L, "Off (0s)", "Off"),
    MS_300(300L, "0.3 detik", "0.3s"),
    MS_500(500L, "0.5 detik", "0.5s"),
    SEC_1(1000L, "1 detik", "1s"),
    SEC_2(2000L, "2 detik", "2s"),
    SEC_3(3000L, "3 detik", "3s"),
    SEC_5(5000L, "5 detik", "5s");

    companion object {
        fun fromDurationMs(durationMs: Long): PlaybackFadePreset? =
            entries.firstOrNull { it.durationMs == durationMs }
    }
}

/** Global persisted playback fade configuration shared across local transport triggers. */
data class PlaybackSettingsState(
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    val effectiveManualFadeOutMs: Long get() = FadeRules.manualDurationMs(fadeOutMs)
}

object FadeRules {
    const val DURATION_MS = 3000L
    const val MAX_DURATION_MS = 10_000L

    fun canStart(isPlaying: Boolean, busy: Boolean): Boolean = isPlaying && !busy

    fun clampDurationMs(durationMs: Long): Long = durationMs.coerceIn(0L, MAX_DURATION_MS)

    fun manualDurationMs(configuredFadeOutMs: Long): Long =
        if (configuredFadeOutMs > 0L) clampDurationMs(configuredFadeOutMs) else DURATION_MS

    fun rampVolumeAt(fromVolume: Float, toVolume: Float, elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return toVolume
        val fraction = elapsedMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
        return fromVolume + (toVolume - fromVolume) * fraction
    }

    fun volumeAt(original: Float, elapsedMs: Long): Float =
        rampVolumeAt(original, 0f, elapsedMs, DURATION_MS)

    fun volumeAt(original: Float, elapsedMs: Long, durationMs: Long): Float =
        rampVolumeAt(original, 0f, elapsedMs, durationMs)

    fun proportionalDurationMs(
        fullDurationMs: Long,
        fromVolume: Float,
        toVolume: Float,
        nominalVolume: Float = 1f,
    ): Long {
        val clampedFull = clampDurationMs(fullDurationMs)
        if (clampedFull <= 0L) return 0L
        if (nominalVolume <= 0f) return clampedFull
        val deltaRatio = (kotlin.math.abs(toVolume - fromVolume) / nominalVolume).coerceIn(0f, 1f)
        if (deltaRatio <= 0.001f) return 0L
        return (clampedFull * deltaRatio).toLong().coerceAtLeast(40L)
    }

    fun shortLabel(durationMs: Long): String {
        val clamped = clampDurationMs(durationMs)
        if (clamped <= 0L) return "Off"
        PlaybackFadePreset.fromDurationMs(clamped)?.let { return it.shortLabel }
        return if (clamped % 1000L == 0L) "${clamped / 1000L}s"
        else String.format(Locale.ROOT, "%.1fs", clamped / 1000.0)
    }

    fun shouldBypassFade(previewActive: Boolean, immediate: Boolean = false): Boolean =
        previewActive || immediate

    /** Prevents accidental auto-play when switching/cueing while an active fade-out is already stopping playback. */
    fun shouldAutoPlayOnSwitch(
        playWhenReady: Boolean,
        isPlaying: Boolean,
        fadingOutToStopOrPause: Boolean,
    ): Boolean = (playWhenReady || isPlaying) && !fadingOutToStopOrPause

    /** Enables tapping the Fade button both to start a fade-out and to hard-cut an in-progress fade-out. */
    fun canTapFadeControl(
        fadeSupported: Boolean,
        playing: Boolean,
        fadeBusy: Boolean,
        pending: Boolean,
    ): Boolean = fadeSupported && (playing || fadeBusy) && !pending
}
