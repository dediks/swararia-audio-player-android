package com.swararia.audiolab.foh

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
    val bandsL: List<Float> = bands,
    val bandsR: List<Float> = bands,
    val bands31: List<Float> = LocalSignalRules.EmptyBands31,
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
    val bandsL: List<Float> = bands,
    val bandsR: List<Float> = bands,
    val bandPeaksL: List<Float> = bandPeaks,
    val bandPeaksR: List<Float> = bandPeaks,
    val bands31: List<Float> = LocalSignalRules.EmptyBands31,
    val bandPeaks31: List<Float> = LocalSignalRules.EmptyBands31,
    val holdUntilMsL: Long = 0L,
    val holdUntilMsR: Long = 0L,
)

/**
 * Pure PCM signal analysis (stereo Peak/RMS + 16-band & 31-band logarithmic FFT spectrum) and
 * mixer-style meter ballistics for local track playback.
 */
object LocalSignalRules {
    const val BAND_COUNT: Int = 16
    const val RTA31_BAND_COUNT: Int = 31
    const val FFT_SIZE: Int = 256
    const val RTA31_FFT_SIZE: Int = 1024
    const val MIN_DB: Float = -54f
    const val MAX_DB: Float = 0f
    const val PEAK_HOLD_MS: Long = 550L

    val EmptyBands: List<Float> = List(BAND_COUNT) { 0f }
    val EmptyBands31: List<Float> = List(RTA31_BAND_COUNT) { 0f }

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
        val denom = (fftFrames - 1).coerceAtLeast(1)
        val startOffset = count - fftFrames
        var channelsIdentical = true
        for (i in 0 until fftFrames) {
            val idx = startOffset + i
            if (left[idx] != right[idx]) {
                channelsIdentical = false
                break
            }
        }

        val bandsMono = computeFftBands(
            fftFrames = fftFrames,
            denom = denom,
            sampleAt = { i ->
                val idx = startOffset + i
                val l = if (left[idx].isFinite()) left[idx].coerceIn(-1f, 1f) else 0f
                val r = if (right[idx].isFinite()) right[idx].coerceIn(-1f, 1f) else 0f
                0.5f * (l + r)
            },
        )
        val bandsL: List<Float>
        val bandsR: List<Float>
        if (channelsIdentical) {
            bandsL = bandsMono
            bandsR = bandsMono
        } else {
            bandsL = computeFftBands(
                fftFrames = fftFrames,
                denom = denom,
                sampleAt = { i ->
                    val v = left[startOffset + i]
                    if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
                },
            )
            bandsR = computeFftBands(
                fftFrames = fftFrames,
                denom = denom,
                sampleAt = { i ->
                    val v = right[startOffset + i]
                    if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
                },
            )
        }

        return RawPcmSignalFrame(
            peakL = peakL.coerceIn(0f, 1f),
            peakR = peakR.coerceIn(0f, 1f),
            rmsL = rmsL,
            rmsR = rmsR,
            bands = bandsMono,
            bandsL = bandsL,
            bandsR = bandsR,
        )
    }

    private inline fun computeFftBands(
        fftFrames: Int,
        denom: Int,
        sampleAt: (Int) -> Float,
    ): List<Float> {
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        for (i in 0 until fftFrames) {
            val hann = if (fftFrames == 1) 1f else (0.5 - 0.5 * cos(2.0 * PI * i / denom)).toFloat()
            real[i] = sampleAt(i) * hann
        }
        fftInPlace(real, imag)

        // Coherent gain of Hann window is 0.5, and symmetric DFT splits sine energy in half -> N/4 normalization.
        val normFactor = (fftFrames.coerceAtLeast(1) * 0.25f)
        return List(BAND_COUNT) { bandIdx ->
            val binStart = BAND_BIN_STARTS[bandIdx]
            val binEnd = BAND_BIN_ENDS[bandIdx]
            var maxMag = 0f
            for (k in binStart until binEnd) {
                val mag = hypot(real[k], imag[k]) / normFactor
                if (mag > maxMag) maxMag = mag
            }
            linearToMeterFraction(maxMag.coerceIn(0f, 1f))
        }
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

        val nextBands = stepBandSeries(rawFrame?.bands, previous.bands, BAND_COUNT, effectiveGain, gainDbMeterOffset)
        val nextBandPeaks = stepBandPeakSeries(nextBands, previous.bandPeaks, BAND_COUNT)
        val nextBandsL = stepBandSeries(rawFrame?.bandsL, previous.bandsL, BAND_COUNT, effectiveGain, gainDbMeterOffset)
        val nextBandPeaksL = stepBandPeakSeries(nextBandsL, previous.bandPeaksL, BAND_COUNT)
        val nextBandsR = stepBandSeries(rawFrame?.bandsR, previous.bandsR, BAND_COUNT, effectiveGain, gainDbMeterOffset)
        val nextBandPeaksR = stepBandPeakSeries(nextBandsR, previous.bandPeaksR, BAND_COUNT)
        val nextBands31 = stepBandSeries(rawFrame?.bands31, previous.bands31, RTA31_BAND_COUNT, effectiveGain, gainDbMeterOffset)
        val nextBandPeaks31 = stepBandPeakSeries(nextBands31, previous.bandPeaks31, RTA31_BAND_COUNT)

        val active = playing ||
            nextLevelL > 0.01f ||
            nextLevelR > 0.01f ||
            nextPeakHoldL > 0.01f ||
            nextPeakHoldR > 0.01f ||
            nextBands.any { it > 0.015f } ||
            nextBands31.any { it > 0.015f }

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
            bandsL = nextBandsL,
            bandsR = nextBandsR,
            bandPeaksL = nextBandPeaksL,
            bandPeaksR = nextBandPeaksR,
            bands31 = nextBands31,
            bandPeaks31 = nextBandPeaks31,
            holdUntilMsL = nextHoldUntilMsL,
            holdUntilMsR = nextHoldUntilMsR,
        )
    }

    /**
     * Analyzes a mono float window (typically 4096 or 8192 newest samples) into 31 ISO 1/3-octave
     * meter fractions `[0..1]` (`-54..0 dBFS`) covering `20 Hz .. 20 kHz`.
     */
    fun analyze31BandRta(
        monoWindow: FloatArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): List<Float> {
        val count = minOf(sampleCount, monoWindow.size)
        if (count < 256 || sampleRateHz <= 0) return EmptyBands31

        val fs = sampleRateHz.toDouble()
        val freqs = GraphicEqRules.CENTER_FREQUENCIES_HZ
        val result = FloatArray(RTA31_BAND_COUNT)

        // Low bands 0..8 (20..125 Hz): decimate to ~5.5-6 kHz for sharp sub-bass frequency resolution.
        val decim = (sampleRateHz / 6_000).coerceIn(4, 16)
        val decimN = (count / decim).coerceIn(1, 512)
        if (decimN >= 32) {
            val startOffset = count - decimN * decim
            val fsLow = fs / decim.toDouble()
            val lowWin = FloatArray(decimN)
            val lowDenom = (decimN - 1).coerceAtLeast(1)
            for (i in 0 until decimN) {
                var sum = 0f
                val base = startOffset + i * decim
                for (k in 0 until decim) {
                    val v = monoWindow[base + k]
                    if (v.isFinite()) sum += v
                }
                val hann = (0.5 - 0.5 * cos(2.0 * PI * i / lowDenom)).toFloat()
                lowWin[i] = (sum / decim.toFloat()) * hann
            }
            val normLow = decimN * 0.25
            for (b in 0..8) {
                val freq = freqs[b]
                if (freq >= fsLow * 0.48) continue
                val w = 2.0 * PI * freq / fsLow
                val dCos = cos(w)
                val dSin = sin(w)
                var c = 1.0
                var s = 0.0
                var re = 0.0
                var im = 0.0
                for (n in 0 until decimN) {
                    val x = lowWin[n].toDouble()
                    re += x * c
                    im += x * s
                    val nextC = c * dCos - s * dSin
                    s = c * dSin + s * dCos
                    c = nextC
                }
                var mag = hypot(re, im) / normLow
                val sincArg = PI * freq * decim.toDouble() / fs
                if (sincArg > 1e-4) {
                    val sinc = abs(sin(sincArg) / sincArg)
                    if (sinc > 0.05) mag /= sinc
                }
                result[b] = linearToMeterFraction(mag.toFloat().coerceIn(0f, 1f))
            }
        }

        // High bands 9..30 (160 Hz..20 kHz): 1024-point Hann FFT over newest samples.
        val fftN = RTA31_FFT_SIZE
        val activeN = minOf(count, fftN)
        val startHi = count - activeN
        val denomHi = (activeN - 1).coerceAtLeast(1)
        val real = FloatArray(fftN)
        val imag = FloatArray(fftN)
        for (i in 0 until activeN) {
            val raw = monoWindow[startHi + i]
            val safe = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
            val hann = if (activeN == 1) 1f else (0.5 - 0.5 * cos(2.0 * PI * i / denomHi)).toFloat()
            real[i] = safe * hann
        }
        fftInPlace(real, imag)

        val normHi = activeN * 0.25f
        val halfBins = fftN / 2
        for (b in 9 until RTA31_BAND_COUNT) {
            val fCenter = freqs[b]
            if (fCenter >= fs * 0.495) continue
            val fLo = sqrt(freqs[b - 1] * fCenter)
            val fHi = if (b < RTA31_BAND_COUNT - 1) sqrt(fCenter * freqs[b + 1]) else minOf(fs * 0.49, fCenter * 1.122)
            var kLo = kotlin.math.floor(fLo * fftN / fs).toInt().coerceIn(1, halfBins - 1)
            var kHi = kotlin.math.ceil(fHi * fftN / fs).toInt().coerceIn(1, halfBins - 1)
            val kCenter = kotlin.math.round(fCenter * fftN / fs).toInt().coerceIn(1, halfBins - 1)
            if (kLo > kCenter) kLo = kCenter
            if (kHi < kCenter) kHi = kCenter
            var maxMag = 0f
            for (k in kLo..kHi) {
                val mag = hypot(real[k], imag[k]) / normHi
                if (mag > maxMag) maxMag = mag
            }
            result[b] = linearToMeterFraction(maxMag.coerceIn(0f, 1f))
        }

        return result.toList()
    }

    private fun stepBandSeries(
        rawBands: List<Float>?,
        previousBands: List<Float>,
        bandCount: Int,
        effectiveGain: Float,
        gainDbMeterOffset: Float,
    ): List<Float> = List(bandCount) { idx ->
        val rawBand = rawBands?.getOrNull(idx) ?: 0f
        val targetBand = if (rawBand <= 0f || effectiveGain <= 0.0001f) {
            0f
        } else {
            (rawBand + gainDbMeterOffset).coerceIn(0f, 1f)
        }
        val prevBand = previousBands.getOrNull(idx) ?: 0f
        maxOf(targetBand, prevBand * BAND_DECAY).let { if (it < METER_FLOOR) 0f else it }
    }

    private fun stepBandPeakSeries(
        currentBands: List<Float>,
        previousPeaks: List<Float>,
        bandCount: Int,
    ): List<Float> = List(bandCount) { idx ->
        val currentBand = currentBands[idx]
        val prevPeak = previousPeaks.getOrNull(idx) ?: 0f
        if (currentBand >= prevPeak) {
            currentBand
        } else {
            maxOf(currentBand, (prevPeak - BAND_PEAK_STEP_DECAY).coerceAtLeast(0f))
                .let { if (it < METER_FLOOR) 0f else it }
        }
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
