package com.swararia.audiolab

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Stage-safety severity for compact master fader level indicators. */
enum class FaderTone { SAFE, WARN, ALERT, SILENT }

/**
 * Snapshot of the local FOH master output fader.
 *
 * Cold service startup initializes this state to Unity (100%, unmuted) so leftover stage
 * attenuation never silences a fresh cue session unexpectedly.
 */
data class FaderState(
    val position: Float = FaderRules.DEFAULT_POSITION,
    val muted: Boolean = false,
    val ducked: Boolean = false,
    val preDuckPosition: Float = FaderRules.DEFAULT_POSITION,
) {
    val normalizedPosition: Float get() = FaderRules.clampPosition(position)
    val nominalGain: Float get() = FaderRules.taperGain(position)
    val effectiveGain: Float get() = FaderRules.effectiveGain(position, muted)
    val percent: Int get() = FaderRules.percentInt(position)
    val dbLabel: String get() = FaderRules.decibelsLabel(position, muted)
    val gainDbLabel: String get() = FaderRules.gainDecibelsLabel(nominalGain)
    val isUnity: Boolean get() = !muted && !ducked && normalizedPosition >= FaderRules.UNITY_THRESHOLD
    val isAttenuated: Boolean get() = muted || ducked || normalizedPosition < FaderRules.UNITY_THRESHOLD
    val pillText: String get() = FaderRules.deckPillText(this)
    val pillTone: FaderTone get() = FaderRules.deckPillTone(this)
}

/**
 * Pure audio-taper math, dB formatting, and console state transitions for the local FOH fader.
 *
 * Uses a perceptual quadratic curve (`gain = pos^2`) so upper trim near 0 dB feels smooth while
 * the midpoint (`0.50`) lands cleanly at `-12.0 dB` (0.25 linear gain) for voiceover Duck cues.
 */
object FaderRules {
    const val DEFAULT_POSITION: Float = 1f
    const val DEFAULT_DUCK_POSITION: Float = 0.50f
    const val ZERO_CUTOFF: Float = 0.005f
    const val UNITY_THRESHOLD: Float = 0.995f
    private const val DUCK_TOLERANCE: Float = 0.04f

    /** Clamps [position] to `0f..1f`, falling back to unity if non-finite. */
    fun clampPosition(position: Float): Float =
        if (!position.isFinite()) DEFAULT_POSITION else position.coerceIn(0f, 1f)

    /** Converts normalized slider travel `[0..1]` into exponential linear gain `[0..1]`. */
    fun taperGain(position: Float): Float {
        val clamped = clampPosition(position)
        return if (clamped <= ZERO_CUTOFF) 0f else clamped * clamped
    }

    /** Inverse of [taperGain]: recovers slider travel `[0..1]` from a linear gain `[0..1]`. */
    fun positionFromGain(gain: Float): Float {
        if (!gain.isFinite()) return DEFAULT_POSITION
        val clamped = gain.coerceIn(0f, 1f)
        return if (clamped <= 0f) 0f else sqrt(clamped.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Returns actual ExoPlayer target gain, accounting for mute override. */
    fun effectiveGain(position: Float, muted: Boolean): Float =
        if (muted) 0f else taperGain(position)

    /** Rounded `0..100` percent representation of slider position. */
    fun percentInt(position: Float): Int =
        (clampPosition(position) * 100f).roundToInt().coerceIn(0, 100)

    /** Formats a linear gain `[0..1]` into decibels (`"0.0 dB"`, `"-12.0 dB"`, `"-∞ dB"`). */
    fun gainDecibelsLabel(linearGain: Float): String {
        if (!linearGain.isFinite() || linearGain <= 0.0001f) return "-∞ dB"
        val db = 20.0 * log10(linearGain.coerceAtMost(1f).toDouble())
        return if (abs(db) < 0.05) "0.0 dB" else String.format(Locale.ROOT, "%.1f dB", db)
    }

    /** Formats active fader status (`"MUTED"` when muted, otherwise decibels for [position]). */
    fun decibelsLabel(position: Float, muted: Boolean): String =
        if (muted) "MUTED" else gainDecibelsLabel(taperGain(position))

    /** Compact stage badge text for `LocalFohBottomDeck` and remote local pill warnings. */
    fun deckPillText(state: FaderState): String {
        val gain = taperGain(state.position)
        return when {
            state.muted -> "MUTED"
            state.ducked -> "DUCK -12dB"
            gain <= 0f -> "-∞ dB"
            else -> gainDecibelsLabel(gain)
        }
    }

    /** Visual badge tier following mixer convention: Alert = Mute, Warn = Duck macro, Silent = -∞, Safe = active signal. */
    fun deckPillTone(state: FaderState): FaderTone = when {
        state.muted -> FaderTone.ALERT
        state.ducked -> FaderTone.WARN
        taperGain(state.position) <= 0f -> FaderTone.SILENT
        else -> FaderTone.SAFE
    }

    /** Applies manual slider drag; keeps mute state so operators can pre-position silently while muted. */
    fun withPosition(state: FaderState, rawPosition: Float): FaderState {
        val clamped = clampPosition(rawPosition)
        val keepDuck = state.ducked && abs(clamped - DEFAULT_DUCK_POSITION) <= DUCK_TOLERANCE
        return state.copy(
            position = clamped,
            ducked = keepDuck,
            preDuckPosition = if (keepDuck) clampPosition(state.preDuckPosition) else clamped,
        )
    }

    /** Toggles -12 dB voiceover Duck, remembering prior above-duck trim and clearing Mute. */
    fun toggleDuck(state: FaderState): FaderState {
        if (!state.ducked) {
            val currentPos = clampPosition(state.position)
            val restorePos = if (currentPos > DEFAULT_DUCK_POSITION + 0.02f) currentPos else DEFAULT_POSITION
            return FaderState(
                position = DEFAULT_DUCK_POSITION,
                muted = false,
                ducked = true,
                preDuckPosition = restorePos,
            )
        }
        val restored = clampPosition(state.preDuckPosition)
        return FaderState(
            position = restored,
            muted = false,
            ducked = false,
            preDuckPosition = restored,
        )
    }

    /** Toggles master Mute while preserving fader trim and Duck state. */
    fun toggleMute(state: FaderState): FaderState =
        state.copy(
            position = clampPosition(state.position),
            muted = !state.muted,
            preDuckPosition = clampPosition(state.preDuckPosition),
        )

    /** Resets fader to clean Unity (100%, unmuted, unducked). */
    fun resetUnity(): FaderState = FaderState()
}

/** Operational headroom tier for Android system media volume (`STREAM_MUSIC`). */
enum class PhoneMediaVolumeTier { MUTED, LOW, MODERATE, OPTIMAL }

/** Read-only snapshot of Android's system media volume (`STREAM_MUSIC`) relative to device ceiling. */
data class PhoneMediaVolumeState(
    val currentStep: Int = 1,
    val maxStep: Int = 1,
    val minStep: Int = 0,
    val percent: Int = 100,
    val isMuted: Boolean = false,
    val tier: PhoneMediaVolumeTier = PhoneMediaVolumeTier.OPTIMAL,
) {
    val shouldWarnPlayer: Boolean
        get() = tier == PhoneMediaVolumeTier.MUTED || tier == PhoneMediaVolumeTier.LOW

    val percentLabel: String
        get() = if (isMuted) "MUTED (0%)" else "$percent%"

    val stepLabel: String
        get() = "$currentStep/$maxStep"
}

/** Pure classification and formatting helpers for read-only phone media volume monitoring. */
object PhoneMediaVolumeRules {
    const val LOW_THRESHOLD_PERCENT: Int = 50
    const val OPTIMAL_THRESHOLD_PERCENT: Int = 80

    /** Classifies raw `AudioManager.STREAM_MUSIC` step bounds into percentage and headroom tier. */
    fun classify(
        currentStep: Int,
        maxStep: Int,
        minStep: Int = 0,
        streamMuted: Boolean = false,
    ): PhoneMediaVolumeState {
        val safeMin = minStep.coerceAtLeast(0)
        val safeMax = maxStep.coerceAtLeast(safeMin)
        val span = safeMax - safeMin
        val clampedStep = currentStep.coerceIn(safeMin, safeMax)
        val muted = streamMuted || (span > 0 && safeMin == 0 && clampedStep == 0)
        val percent = when {
            muted -> 0
            span <= 0 -> 100
            else -> (((clampedStep - safeMin).toFloat() / span.toFloat()) * 100f)
                .roundToInt()
                .let { raw -> if (safeMin > 0) raw.coerceIn(1, 100) else raw.coerceIn(0, 100) }
        }
        val tier = when {
            muted || percent <= 0 -> PhoneMediaVolumeTier.MUTED
            percent < LOW_THRESHOLD_PERCENT -> PhoneMediaVolumeTier.LOW
            percent < OPTIMAL_THRESHOLD_PERCENT -> PhoneMediaVolumeTier.MODERATE
            else -> PhoneMediaVolumeTier.OPTIMAL
        }
        return PhoneMediaVolumeState(
            currentStep = if (muted) safeMin else clampedStep,
            maxStep = safeMax,
            minStep = safeMin,
            percent = percent,
            isMuted = muted,
            tier = tier,
        )
    }

    /** Compact single-line warning label for player headers when phone volume restricts output. */
    fun shortBannerText(state: PhoneMediaVolumeState): String = when (state.tier) {
        PhoneMediaVolumeTier.MUTED -> "Volume Media HP 0% (Bisu) · Tap untuk atur"
        PhoneMediaVolumeTier.LOW -> "Volume Media HP rendah (${state.percent}%) · Tap untuk atur"
        PhoneMediaVolumeTier.MODERATE -> "Volume Media HP ${state.percent}% (${state.stepLabel})"
        PhoneMediaVolumeTier.OPTIMAL -> "Volume Media HP ${state.percent}% (${state.stepLabel})"
    }

    /** Descriptive FOH gain-staging hint displayed inside the Master Output Fader sheet. */
    fun faderSheetDetailText(state: PhoneMediaVolumeState): String = when (state.tier) {
        PhoneMediaVolumeTier.MUTED -> "Volume HP mati — naikkan via tombol volume HP agar suara keluar."
        PhoneMediaVolumeTier.LOW -> "Batas volume HP rendah (${state.percent}%) — headroom FOH terpotong."
        PhoneMediaVolumeTier.MODERATE -> "Volume HP ${state.percent}% — fader aplikasi memotong dari batas ini."
        PhoneMediaVolumeTier.OPTIMAL -> "Headroom HP aman — fader aplikasi mengatur level internal."
    }
}
