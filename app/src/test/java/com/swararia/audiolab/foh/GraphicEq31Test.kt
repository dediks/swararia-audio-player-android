package com.swararia.audiolab.foh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class GraphicEq31Test {
    @Test
    fun iso31BandsMetadataJsonRoundTripAndPresetMatching() {
        assertEquals(31, GraphicEqRules.BAND_COUNT)
        assertEquals(31, GraphicEqRules.CENTER_FREQUENCIES_HZ.size)
        assertEquals(31, GraphicEqRules.BAND_LABELS.size)
        assertEquals(20.0, GraphicEqRules.CENTER_FREQUENCIES_HZ.first(), 0.001)
        assertEquals(20_000.0, GraphicEqRules.CENTER_FREQUENCIES_HZ.last(), 0.001)
        for (i in 1 until GraphicEqRules.BAND_COUNT) {
            assertTrue(GraphicEqRules.CENTER_FREQUENCIES_HZ[i] > GraphicEqRules.CENTER_FREQUENCIES_HZ[i - 1])
        }

        val initial = GraphicEqState()
        assertTrue(initial.enabled)
        assertFalse(initial.isNonFlat)
        assertFalse(initial.isProcessingAudio)
        assertEquals("FLAT", initial.shortStatusLabel)

        val vocal = GraphicEqRules.presetById("vocal_mc")!!
        val applied = GraphicEqRules.applyPreset(initial, vocal)
        assertTrue(applied.isNonFlat)
        assertTrue(applied.isProcessingAudio)
        assertEquals("vocal_mc", applied.presetId)

        val edited = GraphicEqRules.withBandGain(applied, 17, 7.34f)
        assertEquals(7.3f, edited.bandGainsDb[17], 0.001f)
        assertEquals(GraphicEqRules.PRESET_CUSTOM, edited.presetId)

        val withPreamp = GraphicEqRules.withPreampDb(edited, -24f)
        assertEquals(GraphicEqRules.MIN_PREAMP_DB, withPreamp.preampDb, 0.001f)

        val disabled = withPreamp.copy(enabled = false, limiterEnabled = false)
        val encoded = GraphicEqRules.encodeJson(disabled)
        val decoded = GraphicEqRules.decodeJson(encoded)
        assertEquals(disabled.enabled, decoded.enabled)
        assertEquals(disabled.limiterEnabled, decoded.limiterEnabled)
        assertEquals(disabled.preampDb, decoded.preampDb, 0.001f)
        assertEquals(disabled.presetId, decoded.presetId)
        assertEquals(disabled.bandGainsDb, decoded.bandGainsDb)

        val corrupt = GraphicEqRules.decodeJson("{not-json")
        assertEquals(GraphicEqState(), corrupt)
    }

    @Test
    fun cascadeSolverHitsTargetGainsPreciselyAcrossSampleRates() {
        val sampleRates = doubleArrayOf(44_100.0, 48_000.0, 96_000.0)
        val scenarios: List<Pair<String, List<Float>>> = listOf(
            "Flat +6 dB" to List(31) { 6.0f },
            "Flat -9 dB" to List(31) { -9.0f },
            "FOH Dangdut" to GraphicEqRules.presetById("dangdut_live")!!.bandGainsDb,
            "MC Vocal" to GraphicEqRules.presetById("vocal_mc")!!.bandGainsDb,
            "1k/+12 1.25k/-12" to List(31) { idx ->
                when (idx) {
                    17 -> 12.0f
                    18 -> -12.0f
                    else -> 0.0f
                }
            },
            "Single 20Hz +12" to List(31) { idx -> if (idx == 0) 12.0f else 0.0f },
            "Single 1k +12" to List(31) { idx -> if (idx == 17) 12.0f else 0.0f },
            "Single 16k +9" to List(31) { idx -> if (idx == 29) 9.0f else 0.0f },
        )

        for (fs in sampleRates) {
            for ((name, target) in scenarios) {
                val coeffs = GraphicEqRules.solveCascadeCoefficients(target, fs)
                assertEquals(31, coeffs.size)
                for (c in coeffs) {
                    assertTrue("Unstable a2 at fs=$fs $name", abs(c.a2) < 1.0)
                    assertTrue("Unstable a1 at fs=$fs $name", abs(c.a1) < 1.0 + c.a2)
                }
                for (i in 0 until 31) {
                    val f = GraphicEqRules.CENTER_FREQUENCIES_HZ[i]
                    if (f >= fs * 0.485) continue
                    val actualDb = GraphicEqRules.evaluateCascadeDb(coeffs, f, fs)
                    val err = abs(actualDb - target[i])
                    assertTrue(
                        "Error too large for $name at ${f}Hz fs=$fs: target=${target[i]} actual=$actualDb err=$err",
                        err < 0.25,
                    )
                }
            }
        }
    }

    @Test
    fun dspEngineMeasuresExactSineGainAcrossLowMidAndHighBands() {
        val sampleRate = 48_000
        val engine = GraphicEq31DspEngine()
        engine.configure(sampleRate, 2)

        // +6.0 dB at 1 kHz (band 17), -9.0 dB at 63 Hz (band 5), 0 dB at 8 kHz (band 26)
        var eqState = GraphicEqState(enabled = true, limiterEnabled = true)
        eqState = GraphicEqRules.withBandGain(eqState, 17, 6.0f)
        eqState = GraphicEqRules.withBandGain(eqState, 5, -9.0f)
        engine.updateState(eqState, snapImmediately = true)

        assertEquals(6.0, measureSineGainDb(engine, sampleRate, 1000.0), 0.15)
        assertEquals(-9.0, measureSineGainDb(engine, sampleRate, 63.0), 0.15)
        assertEquals(0.0, measureSineGainDb(engine, sampleRate, 8000.0), 0.15)

        // All 31 bands at +6.0 dB -> flat +6 dB across bass, mid, high
        val allPlus6 = GraphicEqState(enabled = true, bandGainsDb = List(31) { 6.0f })
        engine.updateState(allPlus6, snapImmediately = true)
        assertEquals(6.0, measureSineGainDb(engine, sampleRate, 63.0), 0.15)
        assertEquals(6.0, measureSineGainDb(engine, sampleRate, 1000.0), 0.15)
        assertEquals(6.0, measureSineGainDb(engine, sampleRate, 8000.0), 0.15)

        // Preamp -6.0 dB cancels +6.0 dB band gain at 1 kHz
        val preampTrimmed = GraphicEqRules.withPreampDb(
            GraphicEqRules.withBandGain(GraphicEqState(enabled = true), 17, 6.0f),
            -6.0f,
        )
        engine.updateState(preampTrimmed, snapImmediately = true)
        assertEquals(0.0, measureSineGainDb(engine, sampleRate, 1000.0), 0.15)
        assertEquals(-6.0, measureSineGainDb(engine, sampleRate, 8000.0), 0.15)
    }

    @Test
    fun dspEngineBitExactBypassAndAllPcmEncodings() {
        val sampleRate = 48_000
        val frames = 12_000
        val pcm16Input = ByteArray(frames * 4)
        val inWrap = ByteBuffer.wrap(pcm16Input).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (sin(2.0 * PI * 1000.0 * i / sampleRate) * 4000.0).toInt().toShort()
            inWrap.putShort(s)
            inWrap.putShort(s)
        }

        val engine = GraphicEq31DspEngine()
        engine.configure(sampleRate, 2)

        // Disabled EQ with non-flat gains must be bit-identical pass-through
        val bypassed = GraphicEqRules.withBandGain(GraphicEqState(enabled = false), 17, 12.0f)
        engine.updateState(bypassed, snapImmediately = true)
        val outBypass = ByteBuffer.allocate(pcm16Input.size).order(ByteOrder.LITTLE_ENDIAN)
        engine.processBuffer(
            ByteBuffer.wrap(pcm16Input).order(ByteOrder.LITTLE_ENDIAN),
            outBypass,
            GraphicEq31DspEngine.PcmEncoding.PCM_16BIT,
        )
        assertArrayEquals(pcm16Input, outBypass.array())

        // Enabled +6 dB @ 1 kHz works accurately across 16-bit, 24-bit, 32-bit, float, and 8-bit PCM
        val boost1k = GraphicEqRules.withBandGain(GraphicEqState(enabled = true), 17, 6.0f)
        engine.updateState(boost1k, snapImmediately = true)
        for (encoding in GraphicEq31DspEngine.PcmEncoding.entries) {
            engine.flush()
            val gainMeasured = measureBufferEncodingGainDb(engine, sampleRate, 1000.0, encoding)
            assertEquals("Encoding $encoding failed +6 dB check", 6.0, gainMeasured, 0.25)
        }
    }

    @Test
    fun safetyLimiterPreventsClippingOnHotBoostedAudioAndCrossfadeIsClickFree() {
        val sampleRate = 48_000
        val engine = GraphicEq31DspEngine()
        engine.configure(sampleRate, 2)

        // +12 dB boost on 0.8 amplitude 1 kHz sine would hit ~3.18 without limiter -> must stay <= 1.0
        val boost12 = GraphicEqRules.withBandGain(GraphicEqState(enabled = true, limiterEnabled = true), 17, 12.0f)
        engine.updateState(boost12, snapImmediately = true)
        val hotFrames = 8_000
        val hotBuf = FloatArray(hotFrames * 2)
        for (i in 0 until hotFrames) {
            val s = (0.8 * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
            hotBuf[i * 2] = s
            hotBuf[i * 2 + 1] = s
        }
        engine.processInterleavedFloat(hotBuf, hotFrames)
        var maxSample = 0f
        for (v in hotBuf) {
            assertTrue(v.isFinite())
            if (abs(v) > maxSample) maxSample = abs(v)
        }
        assertTrue("Limiter failed to keep peak <= 1.0: max=$maxSample", maxSample <= 1.0f)
        assertTrue("Limiter crushed signal too low: max=$maxSample", maxSample > 0.85f)

        // Mid-stream switch from +9 dB to bypass via crossfade must produce finite, smooth samples
        val chunkFrames = 1024
        val chunk = FloatArray(chunkFrames * 2)
        engine.flush()
        engine.updateState(GraphicEqRules.withBandGain(GraphicEqState(enabled = true), 17, 9.0f), snapImmediately = true)
        fillSine(chunk, chunkFrames, sampleRate, 1000.0, 0.25, startFrame = 0)
        engine.processInterleavedFloat(chunk, chunkFrames)

        engine.updateState(GraphicEqState(enabled = false), snapImmediately = false)
        fillSine(chunk, chunkFrames, sampleRate, 1000.0, 0.25, startFrame = chunkFrames)
        engine.processInterleavedFloat(chunk, chunkFrames)
        for (v in chunk) {
            assertTrue(v.isFinite())
            assertTrue(abs(v) <= 0.9f)
        }
    }

    private fun fillSine(out: FloatArray, frames: Int, sampleRate: Int, freqHz: Double, amp: Double, startFrame: Int = 0) {
        for (i in 0 until frames) {
            val s = (amp * sin(2.0 * PI * freqHz * (startFrame + i) / sampleRate)).toFloat()
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
    }

    private fun measureSineGainDb(engine: GraphicEq31DspEngine, sampleRate: Int, freqHz: Double): Double {
        engine.flush()
        val totalFrames = 24_000
        val skipFrames = 16_000
        val amp = 0.06
        val samples = FloatArray(totalFrames * 2)
        fillSine(samples, totalFrames, sampleRate, freqHz, amp)
        engine.processInterleavedFloat(samples, totalFrames)

        var sumSqIn = 0.0
        var sumSqOut = 0.0
        val measuredFrames = totalFrames - skipFrames
        for (i in skipFrames until totalFrames) {
            val sIn = amp * sin(2.0 * PI * freqHz * i / sampleRate)
            val sOut = samples[i * 2].toDouble()
            sumSqIn += sIn * sIn
            sumSqOut += sOut * sOut
        }
        val rmsIn = sqrt(sumSqIn / measuredFrames)
        val rmsOut = sqrt(sumSqOut / measuredFrames)
        return 20.0 * log10(rmsOut / rmsIn)
    }

    private fun measureBufferEncodingGainDb(
        engine: GraphicEq31DspEngine,
        sampleRate: Int,
        freqHz: Double,
        encoding: GraphicEq31DspEngine.PcmEncoding,
    ): Double {
        val frames = 16_000
        val skipFrames = 10_000
        val amp = 0.18
        val bytesPerFrame = encoding.bytesPerSample * 2
        val inBuf = ByteBuffer.allocate(frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val norm = amp * sin(2.0 * PI * freqHz * i / sampleRate)
            repeat(2) {
                when (encoding) {
                    GraphicEq31DspEngine.PcmEncoding.PCM_16BIT ->
                        inBuf.putShort((norm * 32_768.0).toInt().toShort())
                    GraphicEq31DspEngine.PcmEncoding.PCM_FLOAT ->
                        inBuf.putFloat(norm.toFloat())
                    GraphicEq31DspEngine.PcmEncoding.PCM_24BIT -> {
                        val s24 = (norm * 8_388_608.0).toInt()
                        inBuf.put((s24 and 0xFF).toByte())
                        inBuf.put(((s24 ushr 8) and 0xFF).toByte())
                        inBuf.put(((s24 ushr 16) and 0xFF).toByte())
                    }
                    GraphicEq31DspEngine.PcmEncoding.PCM_32BIT ->
                        inBuf.putInt((norm * 2_147_483_648.0).toInt())
                    GraphicEq31DspEngine.PcmEncoding.PCM_8BIT ->
                        inBuf.put(((norm * 128.0).toInt() + 128).coerceIn(0, 255).toByte())
                }
            }
        }
        inBuf.flip()
        val outBuf = ByteBuffer.allocate(frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        engine.processBuffer(inBuf, outBuf, encoding)
        outBuf.flip()
        outBuf.position(skipFrames * bytesPerFrame)

        var sumSqIn = 0.0
        var sumSqOut = 0.0
        val measuredFrames = frames - skipFrames
        for (i in skipFrames until frames) {
            val normIn = amp * sin(2.0 * PI * freqHz * i / sampleRate)
            val normOut = when (encoding) {
                GraphicEq31DspEngine.PcmEncoding.PCM_16BIT -> {
                    val l = outBuf.short.toDouble() / 32_768.0
                    outBuf.short
                    l
                }
                GraphicEq31DspEngine.PcmEncoding.PCM_FLOAT -> {
                    val l = outBuf.float.toDouble()
                    outBuf.float
                    l
                }
                GraphicEq31DspEngine.PcmEncoding.PCM_24BIT -> {
                    val b0 = outBuf.get().toInt() and 0xFF
                    val b1 = outBuf.get().toInt() and 0xFF
                    val b2 = outBuf.get().toInt()
                    val l = (b0 or (b1 shl 8) or (b2 shl 16)).toDouble() / 8_388_608.0
                    outBuf.get(); outBuf.get(); outBuf.get()
                    l
                }
                GraphicEq31DspEngine.PcmEncoding.PCM_32BIT -> {
                    val l = outBuf.int.toDouble() / 2_147_483_648.0
                    outBuf.int
                    l
                }
                GraphicEq31DspEngine.PcmEncoding.PCM_8BIT -> {
                    val l = ((outBuf.get().toInt() and 0xFF) - 128).toDouble() / 128.0
                    outBuf.get()
                    l
                }
            }
            sumSqIn += normIn * normIn
            sumSqOut += normOut * normOut
        }
        return 20.0 * log10(sqrt(sumSqOut / measuredFrames) / sqrt(sumSqIn / measuredFrames))
    }
}
