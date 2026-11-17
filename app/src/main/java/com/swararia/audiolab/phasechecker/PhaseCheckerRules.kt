package com.swararia.audiolab.phasechecker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class PhaseTestSignalMode(
    val shortLabel: String,
    val title: String,
    val description: String,
    val usesMicPulse: Boolean,
) {
    PULSE_FULL_RANGE(
        shortLabel = "Pulsa Full",
        title = "Pulsa Asimetris Full-Range",
        description = "Pulsa Hann positif (+) 1.05 ms dengan ekor pemulihan DC-nol setiap 0.5 detik untuk PA top, wedge monitor, dan full-range.",
        usesMicPulse = true,
    ),
    PULSE_SUB(
        shortLabel = "Pulsa Sub",
        title = "Pulsa LF / Subwoofer",
        description = "Pulsa Hann berdurasi rendah (~86 ms siklus aktif) agar lolos bandpass subwoofer dengan muka gelombang jelas.",
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
    val anchorSampleIndex: Int = peakSampleIndex,
    val clipped: Boolean = false,
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
            anchorSampleIndex = -1,
            clipped = false,
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

    private const val CLIP_PEAK_THRESHOLD = 0.98f

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
                posSamples = max(12, (sampleRate * 0.00105).roundToInt()),
                negSamples = max(64, (sampleRate * 0.034).roundToInt()),
                gain = clampedGain,
            )

            PhaseTestSignalMode.PULSE_SUB -> writeBalancedPulse(
                out = out,
                frameCount = frameCount,
                leadSilence = max(16, (sampleRate * 0.005).roundToInt()),
                posSamples = max(32, (sampleRate * 0.0065).roundToInt()),
                negSamples = max(240, (sampleRate * 0.080).roundToInt()),
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

    private fun hannWeight(index: Int, length: Int): Double =
        0.5 * (1.0 - cos(2.0 * PI * (index + 0.5) / length))

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
            val weight = hannWeight(i, posSamples)
            val s = (weight * peakAmplitude).roundToInt().coerceIn(0, Short.MAX_VALUE.toInt())
            mono[leadSilence + i] = s
            posSum += s
        }
        val negWeights = DoubleArray(negSamples)
        var rawNegArea = 0.0
        for (j in 0 until negSamples) {
            val w = hannWeight(j, negSamples)
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
        mode: PhaseTestSignalMode = PhaseTestSignalMode.PULSE_FULL_RANGE,
    ): MicAnalysisResult {
        if (samples.size < 128) return MicAnalysisResult.idle()
        val rawFloat = FloatArray(samples.size) { i -> samples[i] / 32768.0f }
        return analyzeFloatWindow(
            samples = rawFloat,
            sampleRate = sampleRate,
            micInverted = micInverted,
            sensitivity = sensitivity,
            mode = mode,
            skipMeanSubtraction = false,
        )
    }

    /**
     * Analyzes a normalized mono float buffer (`[-1f, 1f]`) using smoothed wavefront localization,
     * causal leading-lobe polarity tracking (robust against speaker/mic HPF overshoot), and
     * damping checks to reject noise and symmetric multi-cycle bursts.
     */
    internal fun analyzeFloatWindow(
        samples: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        micInverted: Boolean = false,
        sensitivity: PhaseMicSensitivity = PhaseMicSensitivity.NORMAL,
        mode: PhaseTestSignalMode = PhaseTestSignalMode.PULSE_FULL_RANGE,
        skipMeanSubtraction: Boolean = false,
    ): MicAnalysisResult {
        if (samples.size < 128) return MicAnalysisResult.idle()
        val isSubMode = mode == PhaseTestSignalMode.PULSE_SUB
        val norm = if (skipMeanSubtraction) {
            samples
        } else {
            var sum = 0.0
            for (v in samples) sum += v
            val mean = (sum / samples.size).toFloat()
            FloatArray(samples.size) { i -> samples[i] - mean }
        }

        val smoothRadius = if (isSubMode) max(2, (sampleRate * 0.00017).roundToInt()) else max(1, (sampleRate * 0.000085).roundToInt())
        val smooth = smoothBoxcar(norm, smoothRadius)

        val leadMargin = min(norm.size / 8, (sampleRate * 0.012).roundToInt())
        val searchStart = leadMargin.coerceAtLeast(0)
        val searchEnd = (norm.size - leadMargin).coerceAtLeast(searchStart + 1)

        var smPeakIdx = searchStart
        var smPeak = 0f
        for (i in searchStart until searchEnd) {
            val a = abs(smooth[i])
            if (a > smPeak) {
                smPeak = a
                smPeakIdx = i
            }
        }

        val clusterStart = max(0, smPeakIdx - (sampleRate * (if (isSubMode) 0.024 else 0.010)).roundToInt())
        val clusterEnd = min(norm.size, smPeakIdx + (sampleRate * (if (isSubMode) 0.065 else 0.016)).roundToInt())
        var peakIdx = smPeakIdx
        var absPeak = 0f
        for (i in clusterStart until clusterEnd) {
            val a = abs(norm[i])
            if (a > absPeak) {
                absPeak = a
                peakIdx = i
            }
        }
        val clipped = absPeak >= CLIP_PEAK_THRESHOLD

        val exclBefore = (sampleRate * (if (isSubMode) 0.030 else 0.025)).roundToInt()
        val exclAfter = (sampleRate * (if (isSubMode) 0.095 else 0.055)).roundToInt()
        val bgStats = computeBackgroundStats(norm, smooth, smPeakIdx, exclBefore, exclAfter)
        val snrRatio = absPeak / max(bgStats.rawRms, 1e-4f)
        val peakDbfs = if (absPeak <= 1e-5f) -96f else (20f * log10(absPeak)).coerceIn(-96f, 0f)

        fun fallbackPreview(start: Int, end: Int): List<Float> = downsampleSigned(
            data = norm,
            start = start,
            end = end,
            points = PREVIEW_POINT_COUNT,
            scale = max(absPeak, 0.05f),
            invert = micInverted,
        )

        if (absPeak < sensitivity.minActivePeak || (snrRatio < sensitivity.minSnrRatio && absPeak < 0.025f)) {
            return MicAnalysisResult(
                rawVerdict = PolarityVerdict.LOW_SIGNAL,
                effectiveVerdict = PolarityVerdict.LOW_SIGNAL,
                confidence = 0f,
                asymmetry = 0f,
                peakDbfs = peakDbfs,
                snrRatio = snrRatio,
                peakSampleIndex = -1,
                waveformPreview = fallbackPreview(0, norm.size),
                anchorSampleIndex = -1,
                clipped = clipped,
            )
        }

        val minTailForComplete = (sampleRate * (if (isSubMode) 0.045 else 0.014)).roundToInt()
        val tailTruncated = norm.size >= sampleRate / 4 && (norm.size - smPeakIdx) < minTailForComplete
        val smSnr = smPeak / max(bgStats.smoothRms, 1e-4f)
        val minSmSnr = max(3.6f, sensitivity.minSnrRatio * 1.45f)
        val hasSeparateBgCrest = bgStats.count >= 16 && (smSnr < minSmSnr || bgStats.smoothMax >= smPeak * 0.65f)
        if (snrRatio < sensitivity.minSnrRatio || tailTruncated || hasSeparateBgCrest) {
            return MicAnalysisResult(
                rawVerdict = PolarityVerdict.AMBIGUOUS,
                effectiveVerdict = PolarityVerdict.AMBIGUOUS,
                confidence = 0f,
                asymmetry = 0f,
                peakDbfs = peakDbfs,
                snrRatio = snrRatio,
                peakSampleIndex = -1,
                waveformPreview = fallbackPreview(0, norm.size),
                anchorSampleIndex = -1,
                clipped = clipped,
            )
        }

        var posPeak = 0f
        var negPeak = 0f
        for (i in clusterStart until clusterEnd) {
            val v = norm[i]
            if (v > posPeak) posPeak = v
            if (-v > negPeak) negPeak = -v
        }
        val rawAsymmetry = (posPeak - negPeak) / max(posPeak + negPeak, 1e-6f)

        val causal = detectCausalImpulse(
            smooth = smooth,
            smPeakIdx = smPeakIdx,
            smPeak = smPeak,
            smNoiseRms = bgStats.smoothRms,
            sampleRate = sampleRate,
            clusterEnd = clusterEnd,
            isSubMode = isSubMode,
        )

        val rawVerdict: PolarityVerdict
        val baseConfidence: Float
        val anchorIdx: Int
        if (causal != null) {
            rawVerdict = causal.verdict
            anchorIdx = causal.firstArrivalIdx
            baseConfidence = computeCausalConfidence(
                snrRatio = snrRatio,
                minSnrRatio = sensitivity.minSnrRatio,
                causal = causal,
                rawAsymmetry = rawAsymmetry,
                clipped = clipped,
            )
        } else {
            rawVerdict = PolarityVerdict.AMBIGUOUS
            anchorIdx = -1
            baseConfidence = 0f
        }

        val effective = effectivePolarity(rawVerdict, micInverted)
        val effectiveAsymmetry = if (micInverted) -rawAsymmetry else rawAsymmetry
        val previewAnchor = if (anchorIdx >= 0) anchorIdx else peakIdx
        val viewStart = max(0, previewAnchor - (sampleRate * (if (isSubMode) 0.012 else 0.006)).roundToInt())
        val viewEnd = min(norm.size, previewAnchor + (sampleRate * (if (isSubMode) 0.072 else 0.024)).roundToInt())
        return MicAnalysisResult(
            rawVerdict = rawVerdict,
            effectiveVerdict = effective,
            confidence = baseConfidence,
            asymmetry = effectiveAsymmetry,
            peakDbfs = peakDbfs,
            snrRatio = snrRatio,
            peakSampleIndex = if (anchorIdx >= 0) peakIdx else -1,
            waveformPreview = downsampleSigned(
                data = norm,
                start = viewStart,
                end = viewEnd,
                points = PREVIEW_POINT_COUNT,
                scale = max(absPeak, 1e-4f),
                invert = micInverted,
            ),
            anchorSampleIndex = anchorIdx,
            clipped = clipped,
        )
    }

    private data class BgNoiseStats(
        val rawRms: Float,
        val smoothRms: Float,
        val smoothMax: Float,
        val count: Int,
    )

    private fun computeBackgroundStats(
        norm: FloatArray,
        smooth: FloatArray,
        centerIdx: Int,
        exclBefore: Int,
        exclAfter: Int,
    ): BgNoiseStats {
        var rawSumSq = 0.0
        var smSumSq = 0.0
        var smMax = 0f
        var count = 0
        val lo = centerIdx - exclBefore
        val hi = centerIdx + exclAfter
        for (i in norm.indices) {
            if (i < lo || i > hi) {
                val rv = norm[i].toDouble()
                val sv = smooth[i]
                rawSumSq += rv * rv
                smSumSq += sv.toDouble() * sv.toDouble()
                val absSv = abs(sv)
                if (absSv > smMax) smMax = absSv
                count++
            }
        }
        val rawRms = if (count >= 16) sqrt(rawSumSq / count).toFloat() else 1e-4f
        val smoothRms = if (count >= 16) sqrt(smSumSq / count).toFloat() else 1e-4f
        return BgNoiseStats(rawRms = rawRms, smoothRms = smoothRms, smoothMax = smMax, count = count)
    }

    internal fun smoothBoxcar(data: FloatArray, radius: Int): FloatArray {
        if (radius <= 0 || data.isEmpty()) return data
        val n = data.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + data[i]
        return FloatArray(n) { i ->
            val lo = max(0, i - radius)
            val hi = min(n, i + radius + 1)
            ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
    }

    private data class CausalImpulse(
        val verdict: PolarityVerdict,
        val firstArrivalIdx: Int,
        val l1Ratio: Float,
        val l3Ratio: Float,
        val preRmsRatio: Float,
    )

    private fun detectCausalImpulse(
        smooth: FloatArray,
        smPeakIdx: Int,
        smPeak: Float,
        smNoiseRms: Float,
        sampleRate: Int,
        clusterEnd: Int,
        isSubMode: Boolean,
    ): CausalImpulse? {
        if (smPeak <= 1e-5f) return null
        val searchBack = (sampleRate * (if (isSubMode) 0.038 else 0.014)).roundToInt()
        val sb = max(0, smPeakIdx - searchBack)
        val onsetThreshold = min(smPeak * 0.58f, max(smPeak * 0.30f, smNoiseRms * 3.2f))
        val l1MaxSamples = (sampleRate * (if (isSubMode) 0.022 else 0.006)).roundToInt()

        var idx = sb
        while (idx <= smPeakIdx) {
            if (abs(smooth[idx]) < onsetThreshold) {
                idx++
                continue
            }
            val sign = if (smooth[idx] > 0f) 1f else -1f
            val l1Limit = min(smooth.size, idx + l1MaxSamples)
            var l1Peak = 0f
            var z1 = l1Limit
            for (k in idx until l1Limit) {
                if (smooth[k] * sign <= 0f) {
                    z1 = k
                    break
                }
                val a = abs(smooth[k])
                if (a > l1Peak) l1Peak = a
            }
            // Skip tiny noise precursor blips that never form a substantial leading lobe.
            if (l1Peak < smPeak * 0.35f) {
                idx = max(idx + 1, z1)
                continue
            }

            val preStart = max(0, idx - (sampleRate * (if (isSubMode) 0.024 else 0.018)).roundToInt())
            val preEnd = max(preStart, idx - (sampleRate * 0.0025).roundToInt())
            var preSumSq = 0.0
            var preMax = 0f
            val preCount = preEnd - preStart
            for (k in preStart until preEnd) {
                val a = abs(smooth[k])
                preSumSq += a.toDouble() * a.toDouble()
                if (a > preMax) preMax = a
            }
            val preRms = if (preCount > 0) sqrt(preSumSq / preCount).toFloat() else 0f
            if (preRms >= smPeak * 0.19f || preMax >= max(smPeak * 0.28f, smNoiseRms * 4.5f)) {
                return null
            }

            var z2 = clusterEnd
            for (k in z1 until clusterEnd) {
                if (smooth[k] * sign > 0f) {
                    z2 = k
                    break
                }
            }
            val minPostOffset = min(smooth.size, idx + (sampleRate * (if (isSubMode) 0.012 else 0.0032)).roundToInt())
            val postStart = max(z2, minPostOffset)
            var l3Peak = 0f
            for (k in postStart until clusterEnd) {
                val sameDir = smooth[k] * sign
                if (sameDir > l3Peak) l3Peak = sameDir
            }
            val l1Ratio = l1Peak / smPeak
            val l3Ratio = l3Peak / smPeak
            if (l3Ratio >= 0.78f) return null

            return CausalImpulse(
                verdict = if (sign > 0f) PolarityVerdict.POSITIVE else PolarityVerdict.NEGATIVE,
                firstArrivalIdx = idx,
                l1Ratio = l1Ratio,
                l3Ratio = l3Ratio,
                preRmsRatio = preRms / smPeak,
            )
        }
        return null
    }

    private fun computeCausalConfidence(
        snrRatio: Float,
        minSnrRatio: Float,
        causal: CausalImpulse,
        rawAsymmetry: Float,
        clipped: Boolean,
    ): Float {
        val snrScore = ((snrRatio - minSnrRatio) / 10f).coerceIn(0f, 1f)
        val l1Score = ((causal.l1Ratio - 0.30f) / 0.65f).coerceIn(0f, 1f)
        val dampingScore = ((0.85f - causal.l3Ratio) / 0.70f).coerceIn(0f, 1f)
        val quietScore = (1f - causal.preRmsRatio / 0.22f).coerceIn(0f, 1f)
        val asymAgrees = (causal.verdict == PolarityVerdict.POSITIVE && rawAsymmetry > 0f) ||
            (causal.verdict == PolarityVerdict.NEGATIVE && rawAsymmetry < 0f)
        val asymBonus = if (asymAgrees) (abs(rawAsymmetry) / 0.45f).coerceIn(0f, 1f) else 0.15f
        val raw = 0.38f +
            0.28f * snrScore +
            0.16f * l1Score +
            0.10f * dampingScore +
            0.05f * quietScore +
            0.03f * asymBonus
        val adjusted = if (clipped) raw * 0.85f else raw
        val cap = if (clipped) 0.85f else 0.96f
        return adjusted.coerceIn(0.35f, cap)
    }

    internal data class InvertedHoldState(
        val micInverted: Boolean,
        val heldEffectiveVerdict: PolarityVerdict,
        val asymmetry: Float,
        val waveformPreview: List<Float>,
    )

    internal fun toggleHoldInversion(
        heldRawVerdict: PolarityVerdict,
        currentAsymmetry: Float,
        currentWaveform: List<Float>,
        nextMicInverted: Boolean,
    ): InvertedHoldState = InvertedHoldState(
        micInverted = nextMicInverted,
        heldEffectiveVerdict = effectivePolarity(heldRawVerdict, nextMicInverted),
        asymmetry = -currentAsymmetry,
        waveformPreview = currentWaveform.map { -it },
    )

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

internal data class StackedPulseResult(
    val analysis: MicAnalysisResult,
    val stackedCount: Int,
    val correlation: Float,
)

/**
 * Coherently aligns and averages consecutive pulses from the same speaker to suppress random
 * background noise, immediately resetting when polarity flips or waveform correlation drops.
 */
internal class PhasePulseStacker {
    private var avgBuffer: FloatArray? = null
    private var activeMode: PhaseTestSignalMode? = null
    private var lastRawVerdict: PolarityVerdict? = null
    var stackedCount: Int = 0
        private set
    private var verdictStreak: Int = 0

    fun reset() {
        avgBuffer = null
        activeMode = null
        lastRawVerdict = null
        stackedCount = 0
        verdictStreak = 0
    }

    fun windowMargins(sampleRate: Int, mode: PhaseTestSignalMode): Pair<Int, Int> {
        val isSub = mode == PhaseTestSignalMode.PULSE_SUB
        val pre = (sampleRate * (if (isSub) 0.065 else 0.040)).roundToInt()
        val post = (sampleRate * (if (isSub) 0.125 else 0.065)).roundToInt()
        return pre to post
    }

    fun ingestAndAnalyze(
        normSamples: FloatArray,
        singleAnalysis: MicAnalysisResult,
        sampleRate: Int,
        micInverted: Boolean,
        sensitivity: PhaseMicSensitivity,
        mode: PhaseTestSignalMode,
    ): StackedPulseResult {
        val validRaw = singleAnalysis.rawVerdict == PolarityVerdict.POSITIVE ||
            singleAnalysis.rawVerdict == PolarityVerdict.NEGATIVE
        val anchor = singleAnalysis.anchorSampleIndex
        if (!validRaw || anchor < 0) {
            return StackedPulseResult(singleAnalysis, stackedCount, 0f)
        }
        val (pre, post) = windowMargins(sampleRate, mode)
        val tplLen = pre + post
        if (anchor < pre || anchor + post > normSamples.size || tplLen < 128) {
            return StackedPulseResult(singleAnalysis, stackedCount, 0f)
        }

        val baselineLen = ((pre * 2) / 3).coerceAtLeast(1)
        var baseSum = 0.0
        for (i in 0 until baselineLen) baseSum += normSamples[anchor - pre + i]
        val baseMean = (baseSum / baselineLen).toFloat()
        val cand = FloatArray(tplLen) { i -> normSamples[anchor - pre + i] - baseMean }

        val currentAvg = avgBuffer
        if (currentAvg == null ||
            currentAvg.size != tplLen ||
            activeMode != mode ||
            lastRawVerdict != singleAnalysis.rawVerdict
        ) {
            avgBuffer = cand
            activeMode = mode
            lastRawVerdict = singleAnalysis.rawVerdict
            stackedCount = 1
            verdictStreak = 1
            return StackedPulseResult(singleAnalysis, 1, 1f)
        }

        val isSub = mode == PhaseTestSignalMode.PULSE_SUB
        val smoothRadius = if (isSub) max(2, (sampleRate * 0.00017).roundToInt()) else max(1, (sampleRate * 0.000085).roundToInt())
        val smAvg = PhaseCheckerRules.smoothBoxcar(currentAvg, smoothRadius)
        val smCand = PhaseCheckerRules.smoothBoxcar(cand, smoothRadius)

        val maxLag = (sampleRate * (if (isSub) 0.005 else 0.0015)).roundToInt()
        val lagStep = if (isSub) 2 else 1
        val coreStart = max(maxLag, pre - (sampleRate * (if (isSub) 0.005 else 0.002)).roundToInt())
        val coreEnd = min(tplLen - maxLag, pre + (sampleRate * (if (isSub) 0.052 else 0.014)).roundToInt())
        if (coreEnd <= coreStart) {
            avgBuffer = cand
            stackedCount = 1
            verdictStreak = 1
            return StackedPulseResult(singleAnalysis, 1, 1f)
        }

        var avgEnergy = 0.0
        for (k in coreStart until coreEnd) {
            val v = smAvg[k].toDouble()
            avgEnergy += v * v
        }
        val avgNorm = sqrt(avgEnergy)

        var bestLag = 0
        var bestCorr = -1f
        var lag = -maxLag
        while (lag <= maxLag) {
            var dot = 0.0
            var candEnergy = 0.0
            for (k in coreStart until coreEnd) {
                val a = smAvg[k].toDouble()
                val b = smCand[k + lag].toDouble()
                dot += a * b
                candEnergy += b * b
            }
            val denom = avgNorm * sqrt(candEnergy)
            val corr = if (denom > 1e-9) (dot / denom).toFloat() else 0f
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
            lag += lagStep
        }

        if (bestCorr < MIN_CORRELATION) {
            avgBuffer = cand
            activeMode = mode
            lastRawVerdict = singleAnalysis.rawVerdict
            stackedCount = 1
            verdictStreak = 1
            return StackedPulseResult(singleAnalysis, 1, bestCorr.coerceIn(-1f, 1f))
        }

        val nextCount = min(stackedCount + 1, MAX_STACK_WEIGHT)
        val wNew = 1f / nextCount
        val wOld = 1f - wNew
        for (i in 0 until tplLen) {
            val src = (i + bestLag).coerceIn(0, tplLen - 1)
            currentAvg[i] = wOld * currentAvg[i] + wNew * cand[src]
        }
        stackedCount = nextCount
        verdictStreak = min(verdictStreak + 1, 8)

        val avgAnalysis = PhaseCheckerRules.analyzeFloatWindow(
            samples = currentAvg,
            sampleRate = sampleRate,
            micInverted = micInverted,
            sensitivity = sensitivity,
            mode = mode,
            skipMeanSubtraction = true,
        )
        if (avgAnalysis.rawVerdict != singleAnalysis.rawVerdict) {
            avgBuffer = cand
            stackedCount = 1
            verdictStreak = 1
            return StackedPulseResult(singleAnalysis, 1, bestCorr)
        }

        val anyClipped = avgAnalysis.clipped || singleAnalysis.clipped
        val streakBonus = ((verdictStreak - 1).coerceIn(0, 4) * 0.03f) * bestCorr.coerceIn(0f, 1f)
        val maxCap = if (anyClipped) 0.85f else 0.99f
        val boostedConf = (avgAnalysis.confidence + streakBonus).coerceIn(0f, maxCap)
        return StackedPulseResult(
            analysis = avgAnalysis.copy(
                confidence = boostedConf,
                clipped = anyClipped,
            ),
            stackedCount = stackedCount,
            correlation = bestCorr,
        )
    }

    private companion object {
        const val MIN_CORRELATION = 0.55f
        const val MAX_STACK_WEIGHT = 6
    }
}
