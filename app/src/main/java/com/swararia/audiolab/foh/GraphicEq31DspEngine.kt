package com.swararia.audiolab.foh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Pure 64-bit double-precision real-time 31-band graphic equalizer DSP engine.
 *
 * Uses Transposed Direct Form II biquad sections indexed by original ISO band slot so toggling
 * individual bands preserves neighbor filter state, plus a 6 ms crossfade on state transitions and
 * a transparent stereo-linked safety limiter above -0.5 dBFS.
 */
internal class GraphicEq31DspEngine {
    enum class PcmEncoding(val bytesPerSample: Int) {
        PCM_8BIT(1),
        PCM_16BIT(2),
        PCM_24BIT(3),
        PCM_32BIT(4),
        PCM_FLOAT(4),
    }

    private class PreparedSnapshot(
        val passThrough: Boolean,
        val preampLinear: Double,
        val limiterEnabled: Boolean,
        val activeMask: BooleanArray,
        val activeIndices: IntArray,
        val b0: DoubleArray,
        val b1: DoubleArray,
        val b2: DoubleArray,
        val a1: DoubleArray,
        val a2: DoubleArray,
    )

    private data class PendingUpdate(
        val state: GraphicEqState,
        val previewBypass: Boolean,
        val snapImmediately: Boolean,
    )

    private val stateLock = Any()
    private var desiredState = GraphicEqState()
    private var desiredPreviewBypass = false
    private val pendingUpdate = AtomicReference<PendingUpdate?>(null)

    private var sampleRateHz = 48_000
    private var channelCount = 2
    private var hasProcessedAudio = false

    private var curSnapshot: PreparedSnapshot = buildSnapshot(desiredState, false, sampleRateHz)
    private var prevSnapshot: PreparedSnapshot? = null
    private var curZ1 = DoubleArray(GraphicEqRules.BAND_COUNT * channelCount)
    private var curZ2 = DoubleArray(GraphicEqRules.BAND_COUNT * channelCount)
    private var prevZ1 = DoubleArray(GraphicEqRules.BAND_COUNT * channelCount)
    private var prevZ2 = DoubleArray(GraphicEqRules.BAND_COUNT * channelCount)
    private var frameWork = DoubleArray(channelCount)
    private var prevFrameWork = DoubleArray(channelCount)

    private var crossfadeRemaining = 0
    private var crossfadeTotal = 1
    private var limiterEnv = 1.0
    private var attackCoeff = computeEnvelopeCoeff(0.0003, sampleRateHz)
    private var releaseCoeff = computeEnvelopeCoeff(0.060, sampleRateHz)

    fun configure(sampleRate: Int, channels: Int) {
        val safeRate = sampleRate.coerceIn(8_000, 384_000)
        val safeChannels = channels.coerceIn(1, 8)
        val geometryChanged = safeRate != sampleRateHz || safeChannels != channelCount
        sampleRateHz = safeRate
        channelCount = safeChannels
        attackCoeff = computeEnvelopeCoeff(0.0003, safeRate)
        releaseCoeff = computeEnvelopeCoeff(0.060, safeRate)

        if (geometryChanged) {
            val totalSlots = GraphicEqRules.BAND_COUNT * safeChannels
            curZ1 = DoubleArray(totalSlots)
            curZ2 = DoubleArray(totalSlots)
            prevZ1 = DoubleArray(totalSlots)
            prevZ2 = DoubleArray(totalSlots)
            frameWork = DoubleArray(safeChannels)
            prevFrameWork = DoubleArray(safeChannels)
        }
        val stateSnap: GraphicEqState
        val bypassSnap: Boolean
        synchronized(stateLock) {
            stateSnap = desiredState
            bypassSnap = desiredPreviewBypass
            pendingUpdate.set(null)
        }
        curSnapshot = buildSnapshot(stateSnap, bypassSnap, safeRate)
        prevSnapshot = null
        crossfadeRemaining = 0
        hasProcessedAudio = false
        limiterEnv = 1.0
    }

    fun updateState(state: GraphicEqState, snapImmediately: Boolean = false) {
        synchronized(stateLock) {
            desiredState = state
            pendingUpdate.set(PendingUpdate(state, desiredPreviewBypass, snapImmediately))
        }
    }

    fun setPreviewBypass(bypass: Boolean) {
        synchronized(stateLock) {
            if (desiredPreviewBypass == bypass) return
            desiredPreviewBypass = bypass
            pendingUpdate.set(PendingUpdate(desiredState, bypass, snapImmediately = false))
        }
    }

    fun flush() {
        curZ1.fill(0.0)
        curZ2.fill(0.0)
        prevZ1.fill(0.0)
        prevZ2.fill(0.0)
        prevSnapshot = null
        crossfadeRemaining = 0
        hasProcessedAudio = false
        limiterEnv = 1.0
        applyPendingIfNeeded(forceSnap = true)
    }

    fun reset() {
        flush()
    }

    /** Processes interleaved float samples (`frameCount * channelCount`) in place. */
    fun processInterleavedFloat(samples: FloatArray, frameCount: Int) {
        if (frameCount <= 0 || channelCount <= 0) return
        applyPendingIfNeeded(forceSnap = !hasProcessedAudio)

        val cur = curSnapshot
        val prev = prevSnapshot
        if (cur.passThrough && prev == null && crossfadeRemaining <= 0) {
            hasProcessedAudio = true
            return
        }

        val chCount = channelCount
        val limit = minOf(frameCount, samples.size / chCount)
        for (frame in 0 until limit) {
            val base = frame * chCount
            for (ch in 0 until chCount) {
                val raw = samples[base + ch]
                val safe = if (raw.isFinite()) raw.toDouble() else 0.0
                frameWork[ch] = safe
                if (prev != null && crossfadeRemaining > 0) {
                    prevFrameWork[ch] = safe
                }
            }
            processFrameInPlace()
            for (ch in 0 until chCount) {
                samples[base + ch] = frameWork[ch].toFloat()
            }
        }
        hasProcessedAudio = true
    }

    /**
     * Reads complete PCM frames from [inputBuffer] and writes processed PCM frames to [outputBuffer],
     * advancing both buffer positions cleanly.
     */
    fun processBuffer(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, encoding: PcmEncoding) {
        val bytesPerFrame = encoding.bytesPerSample * channelCount
        val remaining = inputBuffer.remaining()
        if (remaining < bytesPerFrame || bytesPerFrame <= 0) return
        val totalFrames = remaining / bytesPerFrame
        val totalBytes = totalFrames * bytesPerFrame

        applyPendingIfNeeded(forceSnap = !hasProcessedAudio)

        val cur = curSnapshot
        val prev = prevSnapshot
        if (cur.passThrough && prev == null && crossfadeRemaining <= 0) {
            val inSlice = inputBuffer.slice()
            inSlice.limit(totalBytes)
            outputBuffer.put(inSlice)
            inputBuffer.position(inputBuffer.position() + totalBytes)
            hasProcessedAudio = true
            return
        }

        val inLe = inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val outLe = outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val chCount = channelCount

        for (frame in 0 until totalFrames) {
            for (ch in 0 until chCount) {
                val sample = readNormalizedSample(inLe, encoding)
                frameWork[ch] = sample
                if (prev != null && crossfadeRemaining > 0) {
                    prevFrameWork[ch] = sample
                }
            }
            processFrameInPlace()
            for (ch in 0 until chCount) {
                writeNormalizedSample(outLe, encoding, frameWork[ch])
            }
        }

        inputBuffer.position(inputBuffer.position() + totalBytes)
        outputBuffer.position(outputBuffer.position() + totalBytes)
        hasProcessedAudio = true
    }

    private fun applyPendingIfNeeded(forceSnap: Boolean) {
        val update = pendingUpdate.getAndSet(null) ?: return
        val nextSnap = buildSnapshot(update.state, update.previewBypass, sampleRateHz)
        if (forceSnap || update.snapImmediately || !hasProcessedAudio) {
            curSnapshot = nextSnap
            prevSnapshot = null
            crossfadeRemaining = 0
            for (b in 0 until GraphicEqRules.BAND_COUNT) {
                if (!nextSnap.activeMask[b]) {
                    val base = b * channelCount
                    for (ch in 0 until channelCount) {
                        curZ1[base + ch] = 0.0
                        curZ2[base + ch] = 0.0
                    }
                }
            }
            return
        }

        prevSnapshot = curSnapshot
        System.arraycopy(curZ1, 0, prevZ1, 0, curZ1.size)
        System.arraycopy(curZ2, 0, prevZ2, 0, curZ2.size)
        for (b in 0 until GraphicEqRules.BAND_COUNT) {
            if (!nextSnap.activeMask[b]) {
                val base = b * channelCount
                for (ch in 0 until channelCount) {
                    curZ1[base + ch] = 0.0
                    curZ2[base + ch] = 0.0
                }
            }
        }
        curSnapshot = nextSnap
        crossfadeTotal = (sampleRateHz * 0.006).roundToInt().coerceIn(64, 512)
        crossfadeRemaining = crossfadeTotal
    }

    private fun processFrameInPlace() {
        val cur = curSnapshot
        val prev = prevSnapshot
        val chCount = channelCount

        if (!cur.passThrough) {
            runBiquadCascade(cur, frameWork, curZ1, curZ2, chCount)
        }

        if (prev != null && crossfadeRemaining > 0) {
            if (!prev.passThrough) {
                runBiquadCascade(prev, prevFrameWork, prevZ1, prevZ2, chCount)
            }
            val mixCur = (crossfadeTotal - crossfadeRemaining + 1).toDouble() / crossfadeTotal.toDouble()
            val mixPrev = 1.0 - mixCur
            for (ch in 0 until chCount) {
                frameWork[ch] = mixPrev * prevFrameWork[ch] + mixCur * frameWork[ch]
            }
            crossfadeRemaining--
            if (crossfadeRemaining <= 0) {
                prevSnapshot = null
            }
        }

        if (cur.limiterEnabled && !cur.passThrough) {
            applyStereoSafetyLimiter(frameWork, chCount)
        } else {
            limiterEnv = 1.0
        }
    }

    private fun runBiquadCascade(
        snap: PreparedSnapshot,
        frame: DoubleArray,
        z1: DoubleArray,
        z2: DoubleArray,
        chCount: Int,
    ) {
        val activeCount = snap.activeIndices.size
        val preamp = snap.preampLinear
        for (ch in 0 until chCount) {
            var y = frame[ch] * preamp
            for (k in 0 until activeCount) {
                val band = snap.activeIndices[k]
                val sIdx = band * chCount + ch
                val b0 = snap.b0[k]
                val b1 = snap.b1[k]
                val b2 = snap.b2[k]
                val a1 = snap.a1[k]
                val a2 = snap.a2[k]
                val out = b0 * y + z1[sIdx]
                z1[sIdx] = b1 * y - a1 * out + z2[sIdx]
                z2[sIdx] = b2 * y - a2 * out
                y = out
            }
            frame[ch] = y
        }
    }

    private fun applyStereoSafetyLimiter(frame: DoubleArray, chCount: Int) {
        var framePeak = 0.0
        for (ch in 0 until chCount) {
            val a = abs(frame[ch])
            if (a > framePeak) framePeak = a
        }
        val targetGain = if (framePeak > LIMIT_THRESHOLD) LIMIT_THRESHOLD / framePeak else 1.0
        limiterEnv = if (targetGain < limiterEnv) {
            attackCoeff * limiterEnv + (1.0 - attackCoeff) * targetGain
        } else {
            releaseCoeff * limiterEnv + (1.0 - releaseCoeff) * targetGain
        }
        for (ch in 0 until chCount) {
            val scaled = frame[ch] * limiterEnv
            val absScaled = abs(scaled)
            frame[ch] = if (absScaled > SOFT_CEIL_START) {
                val excess = (absScaled - SOFT_CEIL_START) / (1.0 - SOFT_CEIL_START)
                val shaped = SOFT_CEIL_START + (HARD_CEIL - SOFT_CEIL_START) * tanh(excess)
                if (scaled < 0.0) -shaped else shaped
            } else {
                scaled
            }
        }
    }

    private fun buildSnapshot(
        state: GraphicEqState,
        previewBypass: Boolean,
        sampleRate: Int,
    ): PreparedSnapshot {
        if (previewBypass || !state.enabled || !state.isNonFlat) {
            return PreparedSnapshot(
                passThrough = true,
                preampLinear = 1.0,
                limiterEnabled = state.limiterEnabled,
                activeMask = BooleanArray(GraphicEqRules.BAND_COUNT),
                activeIndices = IntArray(0),
                b0 = DoubleArray(0),
                b1 = DoubleArray(0),
                b2 = DoubleArray(0),
                a1 = DoubleArray(0),
                a2 = DoubleArray(0),
            )
        }

        val preampLinear = 10.0.pow(GraphicEqRules.clampPreampDb(state.preampDb) / 20.0)
        val coeffs = GraphicEqRules.solveCascadeCoefficients(state.bandGainsDb, sampleRate.toDouble())
        val activeMask = BooleanArray(GraphicEqRules.BAND_COUNT)
        val activeList = ArrayList<Int>(GraphicEqRules.BAND_COUNT)
        for (i in 0 until GraphicEqRules.BAND_COUNT) {
            if (!coeffs[i].isIdentity) {
                activeMask[i] = true
                activeList.add(i)
            }
        }
        val count = activeList.size
        val indices = IntArray(count)
        val b0 = DoubleArray(count)
        val b1 = DoubleArray(count)
        val b2 = DoubleArray(count)
        val a1 = DoubleArray(count)
        val a2 = DoubleArray(count)
        for (k in 0 until count) {
            val idx = activeList[k]
            val c = coeffs[idx]
            indices[k] = idx
            b0[k] = c.b0
            b1[k] = c.b1
            b2[k] = c.b2
            a1[k] = c.a1
            a2[k] = c.a2
        }
        val passThrough = count == 0 && abs(preampLinear - 1.0) < 1e-6
        return PreparedSnapshot(
            passThrough = passThrough,
            preampLinear = preampLinear,
            limiterEnabled = state.limiterEnabled,
            activeMask = activeMask,
            activeIndices = indices,
            b0 = b0,
            b1 = b1,
            b2 = b2,
            a1 = a1,
            a2 = a2,
        )
    }

    private fun readNormalizedSample(buf: ByteBuffer, encoding: PcmEncoding): Double = when (encoding) {
        PcmEncoding.PCM_16BIT -> buf.short.toDouble() / 32_768.0
        PcmEncoding.PCM_FLOAT -> buf.float.toDouble().let { if (it.isFinite()) it else 0.0 }
        PcmEncoding.PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            (b0 or (b1 shl 8) or (b2 shl 16)).toDouble() / 8_388_608.0
        }
        PcmEncoding.PCM_32BIT -> buf.int.toDouble() / 2_147_483_648.0
        PcmEncoding.PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128).toDouble() / 128.0
    }

    private fun writeNormalizedSample(buf: ByteBuffer, encoding: PcmEncoding, value: Double) {
        val safe = if (value.isFinite()) value else 0.0
        when (encoding) {
            PcmEncoding.PCM_16BIT -> {
                val s = (safe * 32_768.0).roundToInt().coerceIn(-32_768, 32_767).toShort()
                buf.putShort(s)
            }
            PcmEncoding.PCM_FLOAT -> {
                buf.putFloat(safe.toFloat())
            }
            PcmEncoding.PCM_24BIT -> {
                val s24 = (safe * 8_388_608.0).roundToInt().coerceIn(-8_388_608, 8_388_607)
                buf.put((s24 and 0xFF).toByte())
                buf.put(((s24 ushr 8) and 0xFF).toByte())
                buf.put(((s24 ushr 16) and 0xFF).toByte())
            }
            PcmEncoding.PCM_32BIT -> {
                val s32 = (safe * 2_147_483_648.0).let { v ->
                    when {
                        v >= 2_147_483_647.0 -> Int.MAX_VALUE
                        v <= -2_147_483_648.0 -> Int.MIN_VALUE
                        else -> v.roundToInt()
                    }
                }
                buf.putInt(s32)
            }
            PcmEncoding.PCM_8BIT -> {
                val u8 = ((safe * 128.0).roundToInt() + 128).coerceIn(0, 255).toByte()
                buf.put(u8)
            }
        }
    }

    private companion object {
        const val LIMIT_THRESHOLD = 0.94406088 // -0.5 dBFS
        const val SOFT_CEIL_START = 0.96605088 // -0.3 dBFS
        const val HARD_CEIL = 0.998

        fun computeEnvelopeCoeff(timeSec: Double, sampleRate: Int): Double =
            exp(-1.0 / (max(1.0, timeSec * sampleRate.toDouble())))
    }
}
