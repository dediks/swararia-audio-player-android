package com.swararia.audiolab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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
}
