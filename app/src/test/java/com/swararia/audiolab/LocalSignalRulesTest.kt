package com.swararia.audiolab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class LocalSignalRulesTest {
    @Test
    fun silencePcmYieldsZeroMetersAndMinusInfinityDb() {
        val silence = ShortArray(512)
        val raw = LocalSignalRules.analyzePcm16(silence, silence.size, channelCount = 2)
        assertEquals(0f, raw.peakL, 0.0001f)
        assertEquals(0f, raw.peakR, 0.0001f)
        assertEquals(0f, raw.rmsL, 0.0001f)
        assertEquals(0f, raw.rmsR, 0.0001f)
        assertTrue(raw.bands.all { it == 0f })

        val next = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = raw,
            playing = true,
            postFaderGain = 1f,
            muted = false,
            nowMs = 100L,
        )
        assertEquals(0f, next.levelL, 0.0001f)
        assertEquals(0f, next.levelR, 0.0001f)
        assertEquals("-∞ dB", next.peakDbLabel)
    }

    @Test
    fun stereoChannelSeparationDetectsLeftOnlyAndRightOnlySignals() {
        val frames = 256
        val leftOnly = ShortArray(frames * 2)
        val rightOnly = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (16384.0 * sin(2.0 * PI * 440.0 * i / 48_000.0)).roundToInt().toShort()
            leftOnly[i * 2] = sample
            rightOnly[i * 2 + 1] = sample
        }

        val leftFrame = LocalSignalRules.analyzePcm16(leftOnly, leftOnly.size, channelCount = 2)
        assertTrue(leftFrame.peakL > 0.45f)
        assertEquals(0f, leftFrame.peakR, 0.0001f)

        val rightFrame = LocalSignalRules.analyzePcm16(rightOnly, rightOnly.size, channelCount = 2)
        assertEquals(0f, rightFrame.peakL, 0.0001f)
        assertTrue(rightFrame.peakR > 0.45f)
    }

    @Test
    fun fftSeparatesLow125HzAndHigh6000HzSineWavesIntoDistinctBands() {
        val frames = LocalSignalRules.FFT_SIZE
        val sampleRate = 48_000.0
        val lowWave = FloatArray(frames) { i ->
            (0.8 * sin(2.0 * PI * 125.0 * i / sampleRate)).toFloat()
        }
        val highWave = FloatArray(frames) { i ->
            (0.8 * sin(2.0 * PI * 6000.0 * i / sampleRate)).toFloat()
        }

        val lowFrame = LocalSignalRules.analyzeFloatSamples(lowWave, lowWave, frames)
        val highFrame = LocalSignalRules.analyzeFloatSamples(highWave, highWave, frames)

        val lowPeakBand = lowFrame.bands.indices.maxByOrNull { lowFrame.bands[it] } ?: -1
        val highPeakBand = highFrame.bands.indices.maxByOrNull { highFrame.bands[it] } ?: -1

        assertTrue("Expected low frequency to peak in lowest bands, got band $lowPeakBand", lowPeakBand <= 1)
        assertTrue("Expected 6kHz to peak around band 11, got band $highPeakBand", highPeakBand in 10..12)
        assertTrue(lowFrame.bands[0] > 0.5f)
        assertTrue(lowFrame.bands[11] < 0.1f)
        assertTrue(highFrame.bands[11] > 0.7f)
        assertTrue(highFrame.bands[0] < 0.1f)
    }

    @Test
    fun postFaderGainAndMuteScaleMeterLevelsAndReadout() {
        val raw = RawPcmSignalFrame(
            peakL = 1f,
            peakR = 1f,
            rmsL = 0.7f,
            rmsR = 0.7f,
            bands = List(LocalSignalRules.BAND_COUNT) { 0.9f },
        )

        val unity = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = raw,
            playing = true,
            postFaderGain = 1f,
            muted = false,
            nowMs = 1000L,
        )
        val ducked = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = raw,
            playing = true,
            postFaderGain = 0.25f, // -12 dB
            muted = false,
            nowMs = 1000L,
        )
        val muted = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = raw,
            playing = true,
            postFaderGain = 1f,
            muted = true,
            nowMs = 1000L,
        )

        assertEquals("0.0 dB", unity.peakDbLabel)
        assertEquals("-12.0 dB", ducked.peakDbLabel)
        assertTrue(ducked.levelL < unity.levelL)
        assertTrue(ducked.bands[0] < unity.bands[0])

        assertEquals("MUTED", muted.peakDbLabel)
        assertEquals(0f, muted.levelL, 0.0001f)
        assertEquals(0f, muted.levelR, 0.0001f)
        assertTrue(muted.bands.all { it == 0f })
    }

    @Test
    fun peakHoldPersistsDuringHoldWindowThenDecaysToIdle() {
        val hotRaw = RawPcmSignalFrame(
            peakL = 0.9f,
            peakR = 0.5f,
            rmsL = 0.6f,
            rmsR = 0.3f,
            bands = List(LocalSignalRules.BAND_COUNT) { 0.8f },
        )

        val peak = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = hotRaw,
            playing = true,
            postFaderGain = 1f,
            muted = false,
            nowMs = 1000L,
        )
        assertTrue(peak.active)
        assertTrue(peak.peakHoldL > 0.9f)

        // While paused within PEAK_HOLD_MS (550ms), meter level drops but peakHold stays latched.
        val held = LocalSignalRules.stepBallistics(
            previous = peak,
            rawFrame = null,
            playing = false,
            postFaderGain = 1f,
            muted = false,
            nowMs = 1300L,
        )
        assertTrue(held.levelL < peak.levelL)
        assertEquals(peak.peakHoldL, held.peakHoldL, 0.0001f)
        assertTrue(held.active)

        // After hold window expires, repeated idle ticks decay everything cleanly to inactive zero.
        var state = held
        var timeMs = 1600L
        repeat(80) {
            state = LocalSignalRules.stepBallistics(
                previous = state,
                rawFrame = null,
                playing = false,
                postFaderGain = 1f,
                muted = false,
                nowMs = timeMs,
            )
            timeMs += 33L
        }
        assertFalse(state.active)
        assertEquals(0f, state.levelL, 0.0001f)
        assertEquals(0f, state.peakHoldL, 0.0001f)
        assertTrue(state.bands.all { it == 0f })
        assertTrue(state.bandsL.all { it == 0f })
        assertTrue(state.bandsR.all { it == 0f })
        assertEquals("-∞ dB", state.peakDbLabel)
    }

    @Test
    fun stereoRtaSeparatesLeftAndRightFrequencyContent() {
        val frames = LocalSignalRules.FFT_SIZE
        val sampleRate = 48_000.0
        val leftBass = FloatArray(frames) { i ->
            (0.8 * sin(2.0 * PI * 180.0 * i / sampleRate)).toFloat()
        }
        val rightTreble = FloatArray(frames) { i ->
            (0.8 * sin(2.0 * PI * 6000.0 * i / sampleRate)).toFloat()
        }

        val raw = LocalSignalRules.analyzeFloatSamples(leftBass, rightTreble, frames)
        assertTrue("Expected Left channel low band strong", raw.bandsL[0] > 0.5f)
        assertTrue("Expected Left channel 6kHz silent", raw.bandsL[11] < 0.1f)
        assertTrue("Expected Right channel 6kHz strong", raw.bandsR[11] > 0.7f)
        assertTrue("Expected Right channel low band silent", raw.bandsR[0] < 0.1f)

        val stepped = LocalSignalRules.stepBallistics(
            previous = LocalAudioSignalSnapshot(),
            rawFrame = raw,
            playing = true,
            postFaderGain = 1f,
            muted = false,
            nowMs = 100L,
        )
        assertTrue(stepped.bandsL[0] > 0.5f)
        assertTrue(stepped.bandPeaksL[0] >= stepped.bandsL[0])
        assertTrue(stepped.bandsR[11] > 0.7f)
        assertTrue(stepped.bandPeaksR[11] >= stepped.bandsR[11])
    }
}
