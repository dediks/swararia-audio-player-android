package com.swararia.audiolab.phasechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class PhaseCheckerRulesTest {
    @Test
    fun `full range and sub pulse frames keep zero dc balance and positive peak dominance`() {
        listOf(PhaseTestSignalMode.PULSE_FULL_RANGE, PhaseTestSignalMode.PULSE_SUB).forEach { mode ->
            val stereo = PhaseCheckerRules.buildStereoTestFrame(mode, gain = 0.7f)
            val monoCount = stereo.size / 2
            var sum = 0L
            var posPeak = 0
            var negPeak = 0
            for (i in 0 until monoCount) {
                val left = stereo[i * 2].toInt()
                val right = stereo[i * 2 + 1].toInt()
                assertEquals("Mono pulse must match L and R for $mode", left, right)
                sum += left
                if (left > posPeak) posPeak = left
                if (-left > negPeak) negPeak = -left
            }
            assertTrue("Expected near-zero DC sum for $mode, got $sum", abs(sum) <= 4L)
            assertTrue("Expected strong positive dominance for $mode ($posPeak vs $negPeak)", posPeak > negPeak * 3)
        }
    }

    @Test
    fun `stereo test modes emit exact in-phase and out-of-phase channel parity`() {
        val inPhase = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.STEREO_IN_PHASE, gain = 0.6f)
        val outPhase = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.STEREO_OUT_OF_PHASE, gain = 0.6f)
        var nonZeroFound = false
        for (i in 0 until inPhase.size / 2) {
            val lIn = inPhase[i * 2]
            val rIn = inPhase[i * 2 + 1]
            assertEquals(lIn, rIn)

            val lOut = outPhase[i * 2].toInt()
            val rOut = outPhase[i * 2 + 1].toInt()
            assertEquals(-lOut, rOut)
            if (lIn.toInt() != 0) nonZeroFound = true
        }
        assertTrue(nonZeroFound)
    }

    @Test
    fun `analyzeMicWindow detects positive and inverted pulses with calibrated flip`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.75f, sampleRate)
        // Extract a ~400 ms mono buffer centered past initial silence.
        val windowSamples = 19_200
        val posMono = ShortArray(windowSamples) { i ->
            val src = (i - 4_000).coerceIn(0, pulseStereo.size / 2 - 1)
            if (i >= 4_000) pulseStereo[src * 2] else 0
        }
        val negMono = ShortArray(windowSamples) { i -> (-posMono[i].toInt()).toShort() }

        val posResult = PhaseCheckerRules.analyzeMicWindow(posMono, sampleRate, micInverted = false)
        assertEquals(PolarityVerdict.POSITIVE, posResult.rawVerdict)
        assertEquals(PolarityVerdict.POSITIVE, posResult.effectiveVerdict)
        assertTrue("Confidence should be high: ${posResult.confidence}", posResult.confidence >= 0.7f)
        assertTrue("Asymmetry should be positive: ${posResult.asymmetry}", posResult.asymmetry > 0.3f)
        assertEquals(PhaseCheckerRules.PREVIEW_POINT_COUNT, posResult.waveformPreview.size)
        assertTrue("Peak index should locate pulse near injection offset", posResult.peakSampleIndex in 4_100..4_500)

        val negResult = PhaseCheckerRules.analyzeMicWindow(negMono, sampleRate, micInverted = false)
        assertEquals(PolarityVerdict.NEGATIVE, negResult.rawVerdict)
        assertEquals(PolarityVerdict.NEGATIVE, negResult.effectiveVerdict)
        assertTrue(negResult.asymmetry < -0.3f)

        val calibratedNeg = PhaseCheckerRules.analyzeMicWindow(negMono, sampleRate, micInverted = true)
        assertEquals(PolarityVerdict.NEGATIVE, calibratedNeg.rawVerdict)
        assertEquals(PolarityVerdict.POSITIVE, calibratedNeg.effectiveVerdict)
        assertTrue(calibratedNeg.asymmetry > 0.3f)
    }

    @Test
    fun `analyzeMicWindow rejects symmetric sine wave as ambiguous and silence as low signal`() {
        val sampleRate = 48_000
        val len = 16_000
        val sine = ShortArray(len) { i ->
            (sin(2.0 * PI * 250.0 * i / sampleRate) * 14_000.0).toInt().toShort()
        }
        val silence = ShortArray(len) { i -> if (i % 2 == 0) 20 else -20 }
        val burst = ShortArray(len) { i ->
            if (i in 6_000 until 6_768) {
                // Exactly 4 cycles of 250 Hz sine at 48 kHz -> zero DC, high SNR, equal + and - peaks.
                (sin(2.0 * PI * 250.0 * (i - 6_000) / sampleRate) * 18_000.0).toInt().toShort()
            } else 0
        }

        val sineResult = PhaseCheckerRules.analyzeMicWindow(sine, sampleRate)
        assertEquals(PolarityVerdict.AMBIGUOUS, sineResult.effectiveVerdict)

        val burstResult = PhaseCheckerRules.analyzeMicWindow(burst, sampleRate)
        assertEquals(PolarityVerdict.AMBIGUOUS, burstResult.effectiveVerdict)

        val silenceResult = PhaseCheckerRules.analyzeMicWindow(silence, sampleRate)
        assertEquals(PolarityVerdict.LOW_SIGNAL, silenceResult.effectiveVerdict)
    }

    @Test
    fun `analyzeMicWindow detects damped high-Q subwoofer ringing by leading wavefront and respects sensitivity`() {
        val sampleRate = 48_000
        val len = 19_200
        // Simulate high-Q sub impulse starting at sample 5000: +0.90 first half-cycle, -0.82 overshoot, +0.38 tail
        val dampedSubPos = ShortArray(len) { i ->
            val rel = i - 5_000
            val v = when {
                rel in 0 until 240 -> sin(PI * rel / 240.0) * 0.90
                rel in 240 until 520 -> -sin(PI * (rel - 240) / 280.0) * 0.82
                rel in 520 until 760 -> sin(PI * (rel - 520) / 240.0) * 0.38
                else -> 0.0
            }
            (v * 30_000.0).toInt().toShort()
        }
        val dampedSubNeg = ShortArray(len) { i -> (-dampedSubPos[i].toInt()).toShort() }

        val posResult = PhaseCheckerRules.analyzeMicWindow(dampedSubPos, sampleRate)
        assertEquals(PolarityVerdict.POSITIVE, posResult.effectiveVerdict)
        assertTrue(posResult.confidence >= 0.5f)

        val negResult = PhaseCheckerRules.analyzeMicWindow(dampedSubNeg, sampleRate)
        assertEquals(PolarityVerdict.NEGATIVE, negResult.effectiveVerdict)

        // Quiet pulse (~0.008 peak): gated at NORMAL, detected at HIGH sensitivity.
        val softPulse = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.05f, sampleRate)
        val softWindow = ShortArray(len) { i ->
            val src = i - 4_000
            if (src in 0 until softPulse.size / 2) (softPulse[src * 2].toInt() / 6).toShort() else 0
        }
        assertEquals(
            PolarityVerdict.LOW_SIGNAL,
            PhaseCheckerRules.analyzeMicWindow(softWindow, sampleRate, sensitivity = PhaseMicSensitivity.NORMAL).effectiveVerdict,
        )
        assertEquals(
            PolarityVerdict.POSITIVE,
            PhaseCheckerRules.analyzeMicWindow(softWindow, sampleRate, sensitivity = PhaseMicSensitivity.HIGH).effectiveVerdict,
        )
    }

    @Test
    fun `wav exporter writes valid RIFF WAVE header and expected payload size`() {
        val wav = PhaseWavExporter.buildWaveFileBytes(PhaseTestSignalMode.PULSE_FULL_RANGE, repeats = 2)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        // 2 repeats * 0.5s * 48000 Hz * 4 bytes/stereo-frame + 44 header bytes = 192044 bytes
        assertEquals(44 + 2 * 24_000 * 4, wav.size)
    }

    @Test
    fun `compareToReference classifies matching, inverted, pending, and none`() {
        assertEquals(ReferenceComparison.NONE, PhaseCheckerRules.compareToReference(PolarityVerdict.POSITIVE, null))
        assertEquals(ReferenceComparison.MATCHING, PhaseCheckerRules.compareToReference(PolarityVerdict.POSITIVE, PolarityVerdict.POSITIVE))
        assertEquals(ReferenceComparison.INVERTED, PhaseCheckerRules.compareToReference(PolarityVerdict.NEGATIVE, PolarityVerdict.POSITIVE))
        assertEquals(ReferenceComparison.PENDING, PhaseCheckerRules.compareToReference(PolarityVerdict.LOW_SIGNAL, PolarityVerdict.POSITIVE))
    }

    @Test
    fun `PULSE_SUB widens cluster and damped leading detection for slow subwoofer impulse`() {
        val sampleRate = 48_000
        val len = 20_000
        // Slow LF sub impulse (~8.5 ms leading edge + 22 ms overshoot + 18 ms ring) exceeding full-range 14 ms window.
        val slowSubPos = ShortArray(len) { i ->
            val rel = i - 4_500
            val v = when {
                rel in 0 until 410 -> sin(PI * rel / 410.0) * 0.90
                rel in 410 until 1_460 -> -sin(PI * (rel - 410) / 1_050.0) * 0.84
                rel in 1_460 until 2_320 -> sin(PI * (rel - 1_460) / 860.0) * 0.36
                else -> 0.0
            }
            (v * 28_000.0).toInt().toShort()
        }
        val subResult = PhaseCheckerRules.analyzeMicWindow(
            samples = slowSubPos,
            sampleRate = sampleRate,
            mode = PhaseTestSignalMode.PULSE_SUB,
        )
        assertEquals(PolarityVerdict.POSITIVE, subResult.effectiveVerdict)
        assertTrue(subResult.confidence >= 0.5f)
    }

    @Test
    fun `toggleHoldInversion flips readout polarity and preview while leaving reference invariant`() {
        val state = PhaseCheckerUiState(
            heldRawVerdict = PolarityVerdict.POSITIVE,
            heldEffectiveVerdict = PolarityVerdict.POSITIVE,
            asymmetry = 0.65f,
            waveformPreview = listOf(0.5f, -0.25f),
            referencePolarity = PolarityVerdict.POSITIVE,
        )
        val flipped = PhaseCheckerRules.toggleHoldInversion(
            heldRawVerdict = state.heldRawVerdict,
            currentAsymmetry = state.asymmetry,
            currentWaveform = state.waveformPreview,
            nextMicInverted = true,
        )
        val next = state.copy(
            micInverted = flipped.micInverted,
            heldEffectiveVerdict = flipped.heldEffectiveVerdict,
            asymmetry = flipped.asymmetry,
            waveformPreview = flipped.waveformPreview,
        )
        assertEquals(PolarityVerdict.NEGATIVE, next.heldEffectiveVerdict)
        assertEquals(-0.65f, next.asymmetry, 0.001f)
        assertEquals(listOf(-0.5f, 0.25f), next.waveformPreview)
        assertEquals(PolarityVerdict.POSITIVE, next.referencePolarity)
        assertEquals(
            ReferenceComparison.INVERTED,
            PhaseCheckerRules.compareToReference(next.heldEffectiveVerdict, next.referencePolarity),
        )
    }

    @Test
    fun `full range pulse preserves polarity and high confidence through realistic PA and mic HPF`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.75f, sampleRate)
        val rawWindow = DoubleArray(20_160) { i ->
            val src = i - 4_800
            if (src in 0 until pulseStereo.size / 2) pulseStereo[src * 2] / 32768.0 else 0.0
        }
        // PA Top 100 Hz 2nd-order HPF + 2 kHz LPF + 80 Hz 1st-order phone mic HPF.
        val filtered = firstOrderHpf(biquadLpf(biquadHpf(rawWindow, sampleRate, 100.0, 0.707), sampleRate, 2000.0, 0.707), sampleRate, 80.0)
        val posPcm = toPcm16(filtered)
        val negPcm = ShortArray(posPcm.size) { i -> (-posPcm[i].toInt()).toShort() }

        val posResult = PhaseCheckerRules.analyzeMicWindow(posPcm, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
        assertEquals(PolarityVerdict.POSITIVE, posResult.effectiveVerdict)
        assertTrue("Expected realistic confidence >= 0.75, got ${posResult.confidence}", posResult.confidence >= 0.75f)
        assertFalse(posResult.clipped)

        val negResult = PhaseCheckerRules.analyzeMicWindow(negPcm, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
        assertEquals(PolarityVerdict.NEGATIVE, negResult.effectiveVerdict)
        assertTrue("Expected realistic confidence >= 0.75, got ${negResult.confidence}", negResult.confidence >= 0.75f)
    }

    @Test
    fun `full range pulse preserves woofer polarity on 2-way crossover with inverted tweeter`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.75f, sampleRate)
        val raw = DoubleArray(20_160) { i ->
            val src = i - 4_800
            if (src in 0 until pulseStereo.size / 2) pulseStereo[src * 2] / 32768.0 else 0.0
        }
        val woofer = biquadLpf(biquadHpf(raw, sampleRate, 70.0, 0.707), sampleRate, 2000.0, 0.707)
        val tweeter = biquadHpf(raw, sampleRate, 2000.0, 0.707)
        val tweeterDelaySamples = (sampleRate * 0.00020).roundToInt()
        val combined = DoubleArray(raw.size) { i ->
            woofer[i] - (if (i >= tweeterDelaySamples) tweeter[i - tweeterDelaySamples] else 0.0)
        }
        val micFiltered = firstOrderHpf(combined, sampleRate, 60.0)

        val result = PhaseCheckerRules.analyzeMicWindow(toPcm16(micFiltered), sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
        assertEquals(PolarityVerdict.POSITIVE, result.effectiveVerdict)
        assertTrue(result.confidence >= 0.75f)
    }

    @Test
    fun `PULSE_SUB detects positive and inverted subwoofer polarity accurately despite bandpass overshoot`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_SUB, gain = 0.85f, sampleRate)
        val raw = DoubleArray(20_160) { i ->
            val src = i - 4_000
            if (src in 0 until pulseStereo.size / 2) pulseStereo[src * 2] / 32768.0 else 0.0
        }
        // Subwoofer bandpass: 35 Hz 2nd-order HPF + 95 Hz 4th-order LPF + 60 Hz 1st-order mic HPF.
        val subHp = biquadHpf(raw, sampleRate, 35.0, 0.9)
        val subLp = biquadLpf(biquadLpf(subHp, sampleRate, 95.0, 0.707), sampleRate, 95.0, 0.707)
        val micFiltered = firstOrderHpf(subLp, sampleRate, 60.0)

        val posPcm = toPcm16(micFiltered)
        val negPcm = ShortArray(posPcm.size) { i -> (-posPcm[i].toInt()).toShort() }

        val posResult = PhaseCheckerRules.analyzeMicWindow(posPcm, sampleRate, mode = PhaseTestSignalMode.PULSE_SUB)
        assertEquals(PolarityVerdict.POSITIVE, posResult.effectiveVerdict)
        assertTrue("Expected subwoofer confidence >= 0.72, got ${posResult.confidence}", posResult.confidence >= 0.72f)

        val negResult = PhaseCheckerRules.analyzeMicWindow(negPcm, sampleRate, mode = PhaseTestSignalMode.PULSE_SUB)
        assertEquals(PolarityVerdict.NEGATIVE, negResult.effectiveVerdict)
        assertTrue("Expected subwoofer confidence >= 0.72, got ${negResult.confidence}", negResult.confidence >= 0.72f)
    }

    @Test
    fun `random white noise and low-frequency rumble are rejected as ambiguous`() {
        val sampleRate = 48_000
        val rng = Random(42)
        val whiteNoise = ShortArray(19_200) {
            ((rng.nextDouble() * 2.0 - 1.0) * 4_500.0).roundToInt().toShort()
        }
        assertEquals(
            PolarityVerdict.AMBIGUOUS,
            PhaseCheckerRules.analyzeMicWindow(whiteNoise, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE).effectiveVerdict,
        )

        var rumbleState = 0.0
        val rumble = ShortArray(19_200) {
            rumbleState = 0.99 * rumbleState + 0.01 * (rng.nextDouble() * 2.0 - 1.0) * 18_000.0
            rumbleState.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        assertEquals(
            PolarityVerdict.AMBIGUOUS,
            PhaseCheckerRules.analyzeMicWindow(rumble, sampleRate, mode = PhaseTestSignalMode.PULSE_SUB).effectiveVerdict,
        )
    }

    @Test
    fun `PhasePulseStacker boosts SNR across noisy pulses and resets immediately when polarity flips`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.70f, sampleRate)
        val clean = DoubleArray(20_160) { i ->
            val src = i - 6_500
            if (src in 0 until pulseStereo.size / 2) pulseStereo[src * 2] / 32768.0 else 0.0
        }
        val filtered = firstOrderHpf(biquadHpf(clean, sampleRate, 90.0, 0.707), sampleRate, 70.0)
        val peak = filtered.maxOf { abs(it) }
        val rng = Random(7)

        val stacker = PhasePulseStacker()
        var firstSnr = 0f
        var lastSnr = 0f
        var lastConf = 0f
        repeat(4) { idx ->
            val noisy = FloatArray(filtered.size) { i ->
                val noise = (rng.nextDouble() * 2.0 - 1.0) * (peak / 3.5)
                (filtered[i] + noise).toFloat()
            }
            val single = PhaseCheckerRules.analyzeFloatWindow(noisy, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
            val stacked = stacker.ingestAndAnalyze(noisy, single, sampleRate, false, PhaseMicSensitivity.NORMAL, PhaseTestSignalMode.PULSE_FULL_RANGE)
            if (idx == 0) firstSnr = stacked.analysis.snrRatio
            lastSnr = stacked.analysis.snrRatio
            lastConf = stacked.analysis.confidence
            assertEquals(PolarityVerdict.POSITIVE, stacked.analysis.effectiveVerdict)
        }
        assertEquals(4, stacker.stackedCount)
        assertTrue("Expected SNR improvement from stacking ($firstSnr -> $lastSnr)", lastSnr > firstSnr * 1.4f)
        assertTrue("Expected boosted confidence <= 0.99f, got $lastConf", lastConf in 0.85f..0.99f)

        // Flip to negative speaker -> stacker must reset immediately to 1 pulse and report NEGATIVE.
        val inverted = FloatArray(filtered.size) { i -> (-filtered[i]).toFloat() }
        val invSingle = PhaseCheckerRules.analyzeFloatWindow(inverted, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
        val resetResult = stacker.ingestAndAnalyze(inverted, invSingle, sampleRate, false, PhaseMicSensitivity.NORMAL, PhaseTestSignalMode.PULSE_FULL_RANGE)
        assertEquals(PolarityVerdict.NEGATIVE, resetResult.analysis.effectiveVerdict)
        assertEquals(1, resetResult.stackedCount)
    }

    @Test
    fun `clipped microphone input marks clipped flag and caps confidence`() {
        val sampleRate = 48_000
        val pulseStereo = PhaseCheckerRules.buildStereoTestFrame(PhaseTestSignalMode.PULSE_FULL_RANGE, gain = 0.95f, sampleRate)
        val clippedWindow = ShortArray(19_200) { i ->
            val src = i - 4_000
            if (src in 0 until pulseStereo.size / 2) {
                (pulseStereo[src * 2].toInt() * 3).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else {
                0
            }
        }
        val result = PhaseCheckerRules.analyzeMicWindow(clippedWindow, sampleRate, mode = PhaseTestSignalMode.PULSE_FULL_RANGE)
        assertEquals(PolarityVerdict.POSITIVE, result.effectiveVerdict)
        assertTrue(result.clipped)
        assertTrue(result.confidence <= 0.85f)
    }

    private fun biquadHpf(input: DoubleArray, sampleRate: Int, fc: Double, q: Double): DoubleArray {
        val w0 = 2.0 * PI * fc / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + c) / 2.0) / a0
        val b1 = (-(1.0 + c)) / a0
        val b2 = ((1.0 + c) / 2.0) / a0
        val a1 = (-2.0 * c) / a0
        val a2 = (1.0 - alpha) / a0
        return runBiquad(input, b0, b1, b2, a1, a2)
    }

    private fun biquadLpf(input: DoubleArray, sampleRate: Int, fc: Double, q: Double): DoubleArray {
        val w0 = 2.0 * PI * fc / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 - c) / 2.0) / a0
        val b1 = (1.0 - c) / a0
        val b2 = ((1.0 - c) / 2.0) / a0
        val a1 = (-2.0 * c) / a0
        val a2 = (1.0 - alpha) / a0
        return runBiquad(input, b0, b1, b2, a1, a2)
    }

    private fun runBiquad(input: DoubleArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): DoubleArray {
        val out = DoubleArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in input.indices) {
            val x = input[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            out[i] = y
        }
        return out
    }

    private fun firstOrderHpf(input: DoubleArray, sampleRate: Int, fc: Double): DoubleArray {
        val rc = 1.0 / (2.0 * PI * fc)
        val dt = 1.0 / sampleRate
        val a = rc / (rc + dt)
        val out = DoubleArray(input.size)
        for (i in 1 until input.size) {
            out[i] = a * (out[i - 1] + input[i] - input[i - 1])
        }
        return out
    }

    private fun toPcm16(input: DoubleArray): ShortArray = ShortArray(input.size) { i ->
        (input[i] * 32760.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
