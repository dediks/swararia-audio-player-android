package com.swararia.audiolab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class PhaseTestSignalMode(
    val shortLabel: String,
    val title: String,
    val description: String,
    val usesMicPulse: Boolean,
) {
    PULSE_FULL_RANGE(
        shortLabel = "Pulsa Full",
        title = "Pulsa Asimetris Full-Range",
        description = "Pulsa kompresi positif (+) setiap 0.5 detik untuk PA top, wedge monitor, dan speaker full-range.",
        usesMicPulse = true,
    ),
    PULSE_SUB(
        shortLabel = "Pulsa Sub",
        title = "Pulsa LF / Subwoofer",
        description = "Pulsa berdurasi lebih rendah (~64 ms siklus aktif) agar lolos low-pass filter subwoofer.",
        usesMicPulse = true,
    ),
    STEREO_IN_PHASE(
        shortLabel = "Stereo L=R",
        title = "Uji Kuping Stereo (In-Phase)",
        description = "Mengirim sinyal identik L=R (+/+). Jika speaker L & R sefasa, fokus vokal & bass terasa di tengah.",
        usesMicPulse = false,
    ),
    STEREO_OUT_OF_PHASE(
        shortLabel = "Stereo L=-R",
        title = "Uji Kuping Stereo (Out-Phase)",
        description = "Mengirim sinyal berlawanan L=-R (+/-). Berguna untuk membandingkan efek fasa terbalik secara langsung.",
        usesMicPulse = false,
    ),
}

internal enum class PolarityVerdict {
    POSITIVE,
    NEGATIVE,
    AMBIGUOUS,
    LOW_SIGNAL,
}

internal enum class ReferenceComparison {
    NONE,
    PENDING,
    MATCHING,
    INVERTED,
}

internal data class MicAnalysisResult(
    val rawVerdict: PolarityVerdict,
    val effectiveVerdict: PolarityVerdict,
    val confidence: Float,
    val asymmetry: Float,
    val peakDbfs: Float,
    val snrRatio: Float,
    val peakSampleIndex: Int,
    val waveformPreview: List<Float>,
) {
    companion object {
        fun idle(): MicAnalysisResult = MicAnalysisResult(
            rawVerdict = PolarityVerdict.LOW_SIGNAL,
            effectiveVerdict = PolarityVerdict.LOW_SIGNAL,
            confidence = 0f,
            asymmetry = 0f,
            peakDbfs = -96f,
            snrRatio = 0f,
            peakSampleIndex = -1,
            waveformPreview = List(64) { 0f },
        )
    }
}

internal enum class PhasePhoneTopology(
    val label: String,
    val subtitle: String,
) {
    SINGLE_PHONE(
        label = "1 HP (Dekat)",
        subtitle = "Generator + Mic di HP ini, atau uji stereo",
    ),
    TWO_PHONES(
        label = "2 HP (Mixer ↔ Speaker)",
        subtitle = "HP 1 pengirim di mixer, HP 2 ke speaker",
    ),
}

internal enum class PhaseSingleSubMode(val label: String) {
    PULSE_AND_MIC("Pulsa + Mic"),
    STEREO_EAR("Stereo L/R"),
}

internal enum class PhaseTwoPhoneRole(
    val label: String,
    val subtitle: String,
) {
    TRANSMITTER(
        label = "HP 1 · TX (Mixer)",
        subtitle = "Colok ke mixer FOH",
    ),
    RECEIVER(
        label = "HP 2 · RX (Speaker)",
        subtitle = "Bawa ke depan speaker",
    ),
}

internal enum class PhaseMicSensitivity(
    val label: String,
    val minActivePeak: Float,
    val minSnrRatio: Float,
) {
    NORMAL("Normal", 0.012f, 2.8f),
    HIGH("Sensitif", 0.005f, 2.3f),
}

internal data class SpeakerPhaseCheckEntry(
    val id: Long,
    val speakerName: String,
    val verdict: PolarityVerdict,
    val comparison: ReferenceComparison,
    val confidence: Float,
)

/** Pure DSP pulse synthesis, impulse polarity detection, and reference comparison rules. */
internal object PhaseCheckerRules {
    const val SAMPLE_RATE = 48_000
    const val PREVIEW_POINT_COUNT = 64
    const val DEFAULT_GENERATOR_GAIN = 0.55f
    const val HOLD_DURATION_MS = 1_300L

    val DEFAULT_SPEAKER_SLOTS = listOf(
        "PA Main L",
        "PA Main R",
        "Sub L",
        "Sub R",
        "Monitor 1",
        "Monitor 2",
    )

    private const val MIN_ASYMMETRY = 0.14f

    fun effectivePolarity(raw: PolarityVerdict, micInverted: Boolean): PolarityVerdict =
        if (!micInverted) raw
        else when (raw) {
            PolarityVerdict.POSITIVE -> PolarityVerdict.NEGATIVE
            PolarityVerdict.NEGATIVE -> PolarityVerdict.POSITIVE
            PolarityVerdict.AMBIGUOUS, PolarityVerdict.LOW_SIGNAL -> raw
        }

    fun compareToReference(
        current: PolarityVerdict,
        reference: PolarityVerdict?,
    ): ReferenceComparison {
        if (reference == null || (reference != PolarityVerdict.POSITIVE && reference != PolarityVerdict.NEGATIVE)) {
            return ReferenceComparison.NONE
        }
        if (current != PolarityVerdict.POSITIVE && current != PolarityVerdict.NEGATIVE) {
            return ReferenceComparison.PENDING
        }
        return if (current == reference) ReferenceComparison.MATCHING else ReferenceComparison.INVERTED
    }

    /**
     * Builds one interleaved stereo 16-bit PCM buffer (`L, R, L, R...`) cleanly loopable
     * at [sampleRate]. Both pulse modes have exact zero DC balance so amplifier caps and voice coils
     * experience zero steady DC offset while retaining strong positive peak asymmetry.
     */
    fun buildStereoTestFrame(
        mode: PhaseTestSignalMode,
        gain: Float = DEFAULT_GENERATOR_GAIN,
        sampleRate: Int = SAMPLE_RATE,
    ): ShortArray {
        val clampedGain = gain.coerceIn(0.05f, 0.95f)
        val frameCount = max(sampleRate / 2, 240)
        val out = ShortArray(frameCount * 2)
        when (mode) {
            PhaseTestSignalMode.PULSE_FULL_RANGE -> writeBalancedPulse(
                out = out,
                frameCount = frameCount,
                leadSilence = max(16, (sampleRate * 0.005).roundToInt()),
                posSamples = max(8, (sampleRate * 0.0025).roundToInt()),
                negSamples = max(48, (sampleRate * 0.018).roundToInt()),
                gain = clampedGain,
            )

            PhaseTestSignalMode.PULSE_SUB -> writeBalancedPulse(
                out = out,
                frameCount = frameCount,
                leadSilence = max(16, (sampleRate * 0.005).roundToInt()),
                posSamples = max(24, (sampleRate * 0.008).roundToInt()),
                negSamples = max(168, (sampleRate * 0.056).roundToInt()),
                gain = clampedGain,
            )

            PhaseTestSignalMode.STEREO_IN_PHASE,
            PhaseTestSignalMode.STEREO_OUT_OF_PHASE -> {
                val invertRight = mode == PhaseTestSignalMode.STEREO_OUT_OF_PHASE
                val scale = clampedGain * 0.65f * 32760f
                val step = 1.0 / frameCount.toDouble()
                for (i in 0 until frameCount) {
                    // Integer cycle multiples over 0.5s guarantee click-free continuous looping.
                    val phase = i * step
                    val harmonic =
                        0.46 * sin(2.0 * PI * 60.0 * phase) +   // 120 Hz
                            0.30 * sin(2.0 * PI * 120.0 * phase) + // 240 Hz
                            0.16 * sin(2.0 * PI * 240.0 * phase) + // 480 Hz
                            0.08 * sin(2.0 * PI * 480.0 * phase)   // 960 Hz
                    val left = (harmonic * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    val right = if (invertRight) (-left.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() else left
                    out[i * 2] = left
                    out[i * 2 + 1] = right
                }
            }
        }
        return out
    }

    private fun writeBalancedPulse(
        out: ShortArray,
        frameCount: Int,
        leadSilence: Int,
        posSamples: Int,
        negSamples: Int,
        gain: Float,
    ) {
        val totalActive = leadSilence + posSamples + negSamples
        if (totalActive >= frameCount) return
        val peakAmplitude = (gain * 32760f).toDouble()
        val mono = IntArray(frameCount)
        var posSum = 0L
        for (i in 0 until posSamples) {
            val weight = sin(PI * (i + 0.5) / posSamples)
            val s = (weight * peakAmplitude).roundToInt().coerceIn(0, Short.MAX_VALUE.toInt())
            mono[leadSilence + i] = s
            posSum += s
        }
        val negWeights = DoubleArray(negSamples)
        var rawNegArea = 0.0
        for (j in 0 until negSamples) {
            val w = sin(PI * (j + 0.5) / negSamples)
            negWeights[j] = w
            rawNegArea += w
        }
        var negSum = 0L
        if (rawNegArea > 0.0) {
            val scale = posSum.toDouble() / rawNegArea
            for (j in 0 until negSamples) {
                val s = -(negWeights[j] * scale).roundToInt().coerceIn(0, Short.MAX_VALUE.toInt())
                mono[leadSilence + posSamples + j] = s
                negSum += s
            }
            // Absorb 1-LSB quantization rounding residual across center of negative tail for exact 0 DC.
            var residual = (posSum + negSum).toInt()
            var cursor = leadSilence + posSamples + negSamples / 2
            while (residual != 0 && cursor < leadSilence + posSamples + negSamples) {
                val adj = if (residual > 0) -1 else 1
                mono[cursor] = (mono[cursor] + adj).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                residual += adj
                cursor++
            }
        }
        for (i in 0 until frameCount) {
            val s = mono[i].toShort()
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
    }

    /**
     * Analyzes a mono PCM buffer (~400-500 ms recommended) for an asymmetric speaker impulse,
     * rejecting weak background noise and symmetrical steady sine waves.
     */
    fun analyzeMicWindow(
        samples: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        micInverted: Boolean = false,
        sensitivity: PhaseMicSensitivity = PhaseMicSensitivity.NORMAL,
    ): MicAnalysisResult {
        if (samples.size < 128) return MicAnalysisResult.idle()
        var sum = 0.0
        for (s in samples) sum += s
        val mean = ((sum / samples.size) / 32768.0).toFloat()
        val norm = FloatArray(samples.size) { i -> samples[i] / 32768.0f - mean }

        val margin = min(samples.size / 8, (sampleRate * 0.012).roundToInt())
        val searchStart = margin.coerceAtLeast(0)
        val searchEnd = (samples.size - margin).coerceAtLeast(searchStart + 1)
        var peakIdx = searchStart
        var absPeak = 0f
        for (i in searchStart until searchEnd) {
            val a = abs(norm[i])
            if (a > absPeak) {
                absPeak = a
                peakIdx = i
            }
        }

        val exclBefore = (sampleRate * 0.025).roundToInt()
        val exclAfter = (sampleRate * 0.055).roundToInt()
        var bgSumSq = 0.0
        var bgCount = 0
        for (i in norm.indices) {
            if (i < peakIdx - exclBefore || i > peakIdx + exclAfter) {
                val v = norm[i].toDouble()
                bgSumSq += v * v
                bgCount++
            }
        }
        val noiseFloorRms = if (bgCount >= 16) kotlin.math.sqrt(bgSumSq / bgCount).toFloat() else 1e-4f
        val snrRatio = absPeak / max(noiseFloorRms, 1e-4f)
        val peakDbfs = if (absPeak <= 1e-5f) -96f else (20f * log10(absPeak)).coerceIn(-96f, 0f)

        if (absPeak < sensitivity.minActivePeak || (snrRatio < sensitivity.minSnrRatio && absPeak < 0.025f)) {
            return MicAnalysisResult(
                rawVerdict = PolarityVerdict.LOW_SIGNAL,
                effectiveVerdict = PolarityVerdict.LOW_SIGNAL,
                confidence = 0f,
                asymmetry = 0f,
                peakDbfs = peakDbfs,
                snrRatio = snrRatio,
                peakSampleIndex = -1,
                waveformPreview = downsampleSigned(
                    data = norm,
                    start = 0,
                    end = norm.size,
                    points = PREVIEW_POINT_COUNT,
                    scale = max(absPeak, 0.05f),
                    invert = micInverted,
                ),
            )
        }

        if (snrRatio < sensitivity.minSnrRatio) {
            return MicAnalysisResult(
                rawVerdict = PolarityVerdict.AMBIGUOUS,
                effectiveVerdict = PolarityVerdict.AMBIGUOUS,
                confidence = 0f,
                asymmetry = 0f,
                peakDbfs = peakDbfs,
                snrRatio = snrRatio,
                peakSampleIndex = -1,
                waveformPreview = downsampleSigned(
                    data = norm,
                    start = 0,
                    end = norm.size,
                    points = PREVIEW_POINT_COUNT,
                    scale = max(absPeak, 0.05f),
                    invert = micInverted,
                ),
            )
        }

        val clusterStart = max(0, peakIdx - (sampleRate * 0.008).roundToInt())
        val clusterEnd = min(norm.size, peakIdx + (sampleRate * 0.014).roundToInt())
        var posPeak = 0f
        var negPeak = 0f
        var leadingSign = 0
        val leadingThreshold = absPeak * 0.45f
        for (i in clusterStart until clusterEnd) {
            val v = norm[i]
            if (v > posPeak) posPeak = v
            if (-v > negPeak) negPeak = -v
            if (leadingSign == 0 && i <= peakIdx && abs(v) >= leadingThreshold) {
                leadingSign = if (v >= 0f) 1 else -1
            }
        }

        val rawAsymmetry = (posPeak - negPeak) / max(posPeak + negPeak, 1e-6f)
        val rawVerdict: PolarityVerdict
        val baseConfidence: Float
        if (abs(rawAsymmetry) < MIN_ASYMMETRY) {
            val dampedLeading = detectDampedLeadingPolarity(
                norm = norm,
                peakIdx = peakIdx,
                absPeak = absPeak,
                sampleRate = sampleRate,
                clusterEnd = clusterEnd,
            )
            if (dampedLeading != null) {
                rawVerdict = dampedLeading
                baseConfidence = 0.62f
            } else {
                rawVerdict = PolarityVerdict.AMBIGUOUS
                baseConfidence = (abs(rawAsymmetry) / MIN_ASYMMETRY * 0.35f).coerceIn(0f, 0.35f)
            }
        } else {
            rawVerdict = if (rawAsymmetry > 0f) PolarityVerdict.POSITIVE else PolarityVerdict.NEGATIVE
            val rawConf = (0.48f + (abs(rawAsymmetry) - MIN_ASYMMETRY) * 0.9f).coerceIn(0f, 1f)
            val agreesWithLeading = (rawVerdict == PolarityVerdict.POSITIVE && leadingSign >= 0) ||
                (rawVerdict == PolarityVerdict.NEGATIVE && leadingSign <= 0)
            baseConfidence = if (agreesWithLeading) rawConf else (rawConf * 0.85f)
        }

        val effective = effectivePolarity(rawVerdict, micInverted)
        val effectiveAsymmetry = if (micInverted) -rawAsymmetry else rawAsymmetry
        val viewStart = max(0, peakIdx - (sampleRate * 0.010).roundToInt())
        val viewEnd = min(norm.size, peakIdx + (sampleRate * 0.022).roundToInt())
        return MicAnalysisResult(
            rawVerdict = rawVerdict,
            effectiveVerdict = effective,
            confidence = baseConfidence,
            asymmetry = effectiveAsymmetry,
            peakDbfs = peakDbfs,
            snrRatio = snrRatio,
            peakSampleIndex = peakIdx,
            waveformPreview = downsampleSigned(
                data = norm,
                start = viewStart,
                end = viewEnd,
                points = PREVIEW_POINT_COUNT,
                scale = max(absPeak, 1e-4f),
                invert = micInverted,
            ),
        )
    }

    private fun detectDampedLeadingPolarity(
        norm: FloatArray,
        peakIdx: Int,
        absPeak: Float,
        sampleRate: Int,
        clusterEnd: Int,
    ): PolarityVerdict? {
        val searchBackStart = max(0, peakIdx - (sampleRate * 0.012).roundToInt())
        val onsetThreshold = absPeak * 0.55f
        var firstArrivalIdx = -1
        for (i in searchBackStart..peakIdx) {
            if (abs(norm[i]) >= onsetThreshold) {
                firstArrivalIdx = i
                break
            }
        }
        if (firstArrivalIdx < 0) return null

        val preStart = max(0, firstArrivalIdx - (sampleRate * 0.018).roundToInt())
        val preEnd = max(preStart, firstArrivalIdx - (sampleRate * 0.0025).roundToInt())
        var preMax = 0f
        for (i in preStart until preEnd) {
            val a = abs(norm[i])
            if (a > preMax) preMax = a
        }
        if (preMax >= absPeak * 0.20f) return null

        val leadEnd = min(norm.size, firstArrivalIdx + (sampleRate * 0.0045).roundToInt())
        var leadMaxSigned = 0f
        for (i in firstArrivalIdx until leadEnd) {
            if (abs(norm[i]) > abs(leadMaxSigned)) leadMaxSigned = norm[i]
        }
        if (abs(leadMaxSigned) < absPeak * 0.85f) return null
        val leadPositive = leadMaxSigned > 0f

        val postStart = min(norm.size, firstArrivalIdx + (sampleRate * 0.0032).roundToInt())
        for (i in postStart until clusterEnd) {
            if (i <= leadEnd) continue
            val sameDirPeak = if (leadPositive) norm[i] else -norm[i]
            if (sameDirPeak >= absPeak * 0.82f) return null
        }
        return if (leadPositive) PolarityVerdict.POSITIVE else PolarityVerdict.NEGATIVE
    }

    private fun downsampleSigned(
        data: FloatArray,
        start: Int,
        end: Int,
        points: Int,
        scale: Float,
        invert: Boolean,
    ): List<Float> {
        val len = (end - start).coerceAtLeast(1)
        val sign = if (invert) -1f else 1f
        return List(points) { bucket ->
            val bStart = start + (bucket * len) / points
            val bEnd = (start + ((bucket + 1) * len) / points).coerceAtLeast(bStart + 1).coerceAtMost(data.size)
            var best = 0f
            for (i in bStart until bEnd) {
                val v = data[i]
                if (abs(v) > abs(best)) best = v
            }
            ((best / scale) * sign).coerceIn(-1f, 1f)
        }
    }
}
