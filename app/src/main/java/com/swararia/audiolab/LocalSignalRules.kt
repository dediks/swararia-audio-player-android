package com.swararia.audiolab

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw time/frequency snapshot extracted from a single decoded PCM audio chunk. */
data class RawPcmSignalFrame(
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val rmsL: Float = 0f,
    val rmsR: Float = 0f,
    val bands: List<Float> = LocalSignalRules.EmptyBands,
)

/** Ballistic-smoothed, post-fader UI signal snapshot for local playback meters and RTA bars. */
data class LocalAudioSignalSnapshot(
    val active: Boolean = false,
    val playing: Boolean = false,
    val postFaderGain: Float = 1f,
    val muted: Boolean = false,
    val levelL: Float = 0f,
    val levelR: Float = 0f,
    val peakHoldL: Float = 0f,
    val peakHoldR: Float = 0f,
    val peakLinearL: Float = 0f,
    val peakLinearR: Float = 0f,
    val peakDbLabel: String = "-∞ dB",
    val bands: List<Float> = LocalSignalRules.EmptyBands,
    val bandPeaks: List<Float> = LocalSignalRules.EmptyBands,
    val holdUntilMsL: Long = 0L,
    val holdUntilMsR: Long = 0L,
)

/**
 * Pure PCM signal analysis (stereo Peak/RMS + 16-band logarithmic FFT spectrum) and mixer-style
 * meter ballistics for local track playback.
 */
object LocalSignalRules {
    const val BAND_COUNT: Int = 16
    const val FFT_SIZE: Int = 256
    const val MIN_DB: Float = -54f
    const val MAX_DB: Float = 0f
    const val PEAK_HOLD_MS: Long = 550L

    val EmptyBands: List<Float> = List(BAND_COUNT) { 0f }

    // Disjoint monotonically increasing bin ranges over positive bins [1 .. FFT_SIZE/2 - 1]
    // covering ~170 Hz sub/bass through ~22 kHz air in 16 quasi-logarithmic bands.
    private val BAND_BIN_STARTS = intArrayOf(1, 2, 3, 4, 5, 7, 9, 11, 14, 18, 23, 30, 39, 51, 67, 90)
    private val BAND_BIN_ENDS = intArrayOf(2, 3, 4, 5, 7, 9, 11, 14, 18, 23, 30, 39, 51, 67, 90, 128)

    private const val LEVEL_DECAY = 0.78f
    private const val BAND_DECAY = 0.74f
    private const val PEAK_HOLD_STEP_DECAY = 0.035f
    private const val BAND_PEAK_STEP_DECAY = 0.025f
    private const val METER_FLOOR = 0.005f
    private const val LINEAR_SILENCE_THRESHOLD = 0.0005f

    /** Maps a linear amplitude `[0..1]` to a logarithmic meter fraction `[0..1]` over `-54..0 dB`. */
    fun linearToMeterFraction(linear: Float): Float {
        if (!linear.isFinite() || linear <= 0.0001f) return 0f
        val db = (20.0 * log10(linear.coerceAtMost(1f).toDouble())).toFloat()
        return ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }

    /** Formats a linear peak `[0..1]` into dBFS (`"0.0 dB"`, `"-6.2 dB"`, `"-∞ dB"`, or `"MUTED"`). */
    fun peakDecibelsLabel(peakLinear: Float, muted: Boolean = false): String {
        if (muted) return "MUTED"
        if (!peakLinear.isFinite() || peakLinear <= LINEAR_SILENCE_THRESHOLD) return "-∞ dB"
        val db = 20.0 * log10(peakLinear.coerceAtMost(1f).toDouble())
        return if (abs(db) < 0.05) "0.0 dB" else String.format(Locale.ROOT, "%.1f dB", db)
    }

    /** Analyzes interleaved signed 16-bit PCM samples into stereo peak/RMS and 16 RTA bands. */
    fun analyzePcm16(samples: ShortArray, sampleCount: Int, channelCount: Int): RawPcmSignalFrame {
        if (channelCount < 1 || sampleCount < channelCount || samples.isEmpty()) {
            return RawPcmSignalFrame()
        }
        val availableSamples = minOf(sampleCount, samples.size)
        val totalFrames = availableSamples / channelCount
        if (totalFrames <= 0) return RawPcmSignalFrame()

        val left = FloatArray(totalFrames)
        val right = FloatArray(totalFrames)
        val scale = 1f / 32768f
        for (frame in 0 until totalFrames) {
            val base = frame * channelCount
            val l = (samples[base].toFloat() * scale).coerceIn(-1f, 1f)
            val r = if (channelCount >= 2) (samples[base + 1].toFloat() * scale).coerceIn(-1f, 1f) else l
            left[frame] = l
            right[frame] = r
        }
        return analyzeFloatSamples(left, right, totalFrames)
    }

    /** Analyzes normalized float stereo channels `[left, right]` across `frameCount` frames. */
    fun analyzeFloatSamples(left: FloatArray, right: FloatArray, frameCount: Int): RawPcmSignalFrame {
        val count = minOf(frameCount, left.size, right.size)
        if (count <= 0) return RawPcmSignalFrame()

        var peakL = 0f
        var peakR = 0f
        var sumSqL = 0.0
        var sumSqR = 0.0
        for (i in 0 until count) {
            val rawL = left[i]
            val rawR = right[i]
            val l = if (rawL.isFinite()) rawL.coerceIn(-1f, 1f) else 0f
            val r = if (rawR.isFinite()) rawR.coerceIn(-1f, 1f) else 0f
            val absL = abs(l)
            val absR = abs(r)
            if (absL > peakL) peakL = absL
            if (absR > peakR) peakR = absR
            sumSqL += (l * l).toDouble()
            sumSqR += (r * r).toDouble()
        }
        val rmsL = sqrt(sumSqL / count).toFloat().coerceIn(0f, 1f)
        val rmsR = sqrt(sumSqR / count).toFloat().coerceIn(0f, 1f)

        val fftFrames = minOf(count, FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val denom = (fftFrames - 1).coerceAtLeast(1)
        val startOffset = count - fftFrames
        for (i in 0 until fftFrames) {
            val idx = startOffset + i
            val l = if (left[idx].isFinite()) left[idx].coerceIn(-1f, 1f) else 0f
            val r = if (right[idx].isFinite()) right[idx].coerceIn(-1f, 1f) else 0f
            val mono = 0.5f * (l + r)
            val hann = if (fftFrames == 1) 1f else (0.5 - 0.5 * cos(2.0 * PI * i / denom)).toFloat()
            real[i] = mono * hann
        }

        fftInPlace(real, imag)

        // Coherent gain of Hann window is 0.5, and symmetric DFT splits sine energy in half -> N/4 normalization.
        val normFactor = (fftFrames.coerceAtLeast(1) * 0.25f)
        val bands = List(BAND_COUNT) { bandIdx ->
            val binStart = BAND_BIN_STARTS[bandIdx]
            val binEnd = BAND_BIN_ENDS[bandIdx]
            var maxMag = 0f
            for (k in binStart until binEnd) {
                val mag = hypot(real[k], imag[k]) / normFactor
                if (mag > maxMag) maxMag = mag
            }
            linearToMeterFraction(maxMag.coerceIn(0f, 1f))
        }

        return RawPcmSignalFrame(
            peakL = peakL.coerceIn(0f, 1f),
            peakR = peakR.coerceIn(0f, 1f),
            rmsL = rmsL,
            rmsR = rmsR,
            bands = bands,
        )
    }

    /**
     * Advances mixer-style attack/release ballistics and peak-hold indicators using [rawFrame]
     * scaled by post-fader [postFaderGain] and [muted] override.
     */
    fun stepBallistics(
        previous: LocalAudioSignalSnapshot,
        rawFrame: RawPcmSignalFrame?,
        playing: Boolean,
        postFaderGain: Float,
        muted: Boolean,
        nowMs: Long,
    ): LocalAudioSignalSnapshot {
        val clampedGain = if (postFaderGain.isFinite()) postFaderGain.coerceIn(0f, 1f) else 0f
        val effectiveGain = if (!playing || muted) 0f else clampedGain

        val scaledPeakLinearL = if (rawFrame != null && effectiveGain > 0f) {
            (rawFrame.peakL * effectiveGain).coerceIn(0f, 1f)
        } else 0f
        val scaledPeakLinearR = if (rawFrame != null && effectiveGain > 0f) {
            (rawFrame.peakR * effectiveGain).coerceIn(0f, 1f)
        } else 0f

        val nextPeakLinearL = maxOf(scaledPeakLinearL, previous.peakLinearL * LEVEL_DECAY)
            .let { if (it < LINEAR_SILENCE_THRESHOLD) 0f else it }
        val nextPeakLinearR = maxOf(scaledPeakLinearR, previous.peakLinearR * LEVEL_DECAY)
            .let { if (it < LINEAR_SILENCE_THRESHOLD) 0f else it }

        val targetLevelL = linearToMeterFraction(scaledPeakLinearL)
        val targetLevelR = linearToMeterFraction(scaledPeakLinearR)

        val nextLevelL = maxOf(targetLevelL, previous.levelL * LEVEL_DECAY)
            .let { if (it < METER_FLOOR) 0f else it }
        val nextLevelR = maxOf(targetLevelR, previous.levelR * LEVEL_DECAY)
            .let { if (it < METER_FLOOR) 0f else it }

        val (nextPeakHoldL, nextHoldUntilMsL) = stepSinglePeakHold(
            targetLevel = targetLevelL,
            previousHold = previous.peakHoldL,
            previousHoldUntilMs = previous.holdUntilMsL,
            nowMs = nowMs,
        )
        val (nextPeakHoldR, nextHoldUntilMsR) = stepSinglePeakHold(
            targetLevel = targetLevelR,
            previousHold = previous.peakHoldR,
            previousHoldUntilMs = previous.holdUntilMsR,
            nowMs = nowMs,
        )

        val gainDbMeterOffset = if (effectiveGain > 0.0001f) {
            ((20.0 * log10(effectiveGain.toDouble())).toFloat() / (MAX_DB - MIN_DB))
        } else {
            -1f
        }

        val prevBands = previous.bands
        val prevBandPeaks = previous.bandPeaks
        val nextBands = List(BAND_COUNT) { idx ->
            val rawBand = rawFrame?.bands?.getOrNull(idx) ?: 0f
            val targetBand = if (rawBand <= 0f || effectiveGain <= 0.0001f) {
                0f
            } else {
                (rawBand + gainDbMeterOffset).coerceIn(0f, 1f)
            }
            val prevBand = prevBands.getOrNull(idx) ?: 0f
            maxOf(targetBand, prevBand * BAND_DECAY).let { if (it < METER_FLOOR) 0f else it }
        }
        val nextBandPeaks = List(BAND_COUNT) { idx ->
            val currentBand = nextBands[idx]
            val prevPeak = prevBandPeaks.getOrNull(idx) ?: 0f
            if (currentBand >= prevPeak) {
                currentBand
            } else {
                maxOf(currentBand, (prevPeak - BAND_PEAK_STEP_DECAY).coerceAtLeast(0f))
                    .let { if (it < METER_FLOOR) 0f else it }
            }
        }

        val active = playing ||
            nextLevelL > 0.01f ||
            nextLevelR > 0.01f ||
            nextPeakHoldL > 0.01f ||
            nextPeakHoldR > 0.01f ||
            nextBands.any { it > 0.015f }

        val maxLinear = maxOf(nextPeakLinearL, nextPeakLinearR)
        return LocalAudioSignalSnapshot(
            active = active,
            playing = playing,
            postFaderGain = clampedGain,
            muted = muted,
            levelL = nextLevelL,
            levelR = nextLevelR,
            peakHoldL = nextPeakHoldL,
            peakHoldR = nextPeakHoldR,
            peakLinearL = nextPeakLinearL,
            peakLinearR = nextPeakLinearR,
            peakDbLabel = peakDecibelsLabel(maxLinear, muted = muted),
            bands = nextBands,
            bandPeaks = nextBandPeaks,
            holdUntilMsL = nextHoldUntilMsL,
            holdUntilMsR = nextHoldUntilMsR,
        )
    }

    private fun stepSinglePeakHold(
        targetLevel: Float,
        previousHold: Float,
        previousHoldUntilMs: Long,
        nowMs: Long,
    ): Pair<Float, Long> = when {
        targetLevel >= previousHold -> targetLevel to (nowMs + PEAK_HOLD_MS)
        nowMs < previousHoldUntilMs -> previousHold to previousHoldUntilMs
        else -> {
            val decayed = maxOf(targetLevel, (previousHold - PEAK_HOLD_STEP_DECAY).coerceAtLeast(0f))
                .let { if (it < METER_FLOOR) 0f else it }
            decayed to previousHoldUntilMs
        }
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                val ti = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tr
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angleStep = -2.0 * PI / len
            val wStepRe = cos(angleStep).toFloat()
            val wStepIm = sin(angleStep).toFloat()
            var start = 0
            while (start < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val evenIdx = start + k
                    val oddIdx = evenIdx + half
                    val oddRe = real[oddIdx] * wRe - imag[oddIdx] * wIm
                    val oddIm = real[oddIdx] * wIm + imag[oddIdx] * wRe
                    real[oddIdx] = real[evenIdx] - oddRe
                    imag[oddIdx] = imag[evenIdx] - oddIm
                    real[evenIdx] += oddRe
                    imag[evenIdx] += oddIm
                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}
