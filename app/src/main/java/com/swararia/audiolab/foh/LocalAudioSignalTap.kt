package com.swararia.audiolab.foh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Process-local live PCM signal feed shared between PlaybackService and active Compose surfaces. */
internal object LocalAudioSignalBus {
    private val _state = MutableStateFlow(LocalAudioSignalSnapshot())
    val state: StateFlow<LocalAudioSignalSnapshot> = _state.asStateFlow()

    fun publish(snapshot: LocalAudioSignalSnapshot) {
        _state.value = snapshot
    }

    fun reset() {
        _state.value = LocalAudioSignalSnapshot()
    }
}

/** Collects the latest post-fader local playback signal snapshot in Compose. */
@Composable
internal fun rememberLocalAudioSignal(): State<LocalAudioSignalSnapshot> =
    LocalAudioSignalBus.state.collectAsState()

/**
 * Pass-through Media3 [BaseAudioProcessor] that inspects decoded PCM buffers inside [PlaybackService]
 * without modifying audio samples or requiring microphone/capture permissions.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalAudioSignalTapProcessor : BaseAudioProcessor() {
    private val queueLock = Any()
    private val pollLock = Any()
    private val frameQueue = ArrayDeque<RawPcmSignalFrame>(MAX_QUEUED_FRAMES)
    private val leftScratch = FloatArray(MAX_ANALYZE_FRAMES)
    private val rightScratch = FloatArray(MAX_ANALYZE_FRAMES)
    private val decodeMonoScratch = FloatArray(MONO_RING_CAPACITY)
    private val monoRing = FloatArray(MONO_RING_CAPACITY)
    private val pollRtaScratch = FloatArray(MONO_RING_CAPACITY)
    private var monoRingWritePos = 0
    private var monoRingCount = 0
    private var configuredSampleRateHz = DEFAULT_SAMPLE_RATE_HZ

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
        val accepted = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_8BIT,
            -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
        if (accepted != AudioProcessor.AudioFormat.NOT_SET) {
            synchronized(queueLock) {
                frameQueue.clear()
                monoRingWritePos = 0
                monoRingCount = 0
                configuredSampleRateHz = if (inputAudioFormat.sampleRate > 0) {
                    inputAudioFormat.sampleRate
                } else {
                    DEFAULT_SAMPLE_RATE_HZ
                }
            }
        }
        return accepted
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        val channelCount = format.channelCount
        if (bytesPerFrame > 0 && channelCount > 0) {
            val totalFrames = remaining / bytesPerFrame
            if (totalFrames > 0) {
                val slice = inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                val framesToDecode = minOf(totalFrames, MONO_RING_CAPACITY)
                val startFrame = totalFrames - framesToDecode
                if (startFrame > 0) {
                    slice.position(startFrame * bytesPerFrame)
                }
                val tailScratchCount = minOf(framesToDecode, MAX_ANALYZE_FRAMES)
                val decodedCount = decodeSliceIntoScratch(
                    slice = slice,
                    encoding = format.encoding,
                    channelCount = channelCount,
                    bytesPerFrame = bytesPerFrame,
                    framesToDecode = framesToDecode,
                    tailScratchCount = tailScratchCount,
                )
                if (decodedCount > 0) {
                    val frame = LocalSignalRules.analyzeFloatSamples(
                        left = leftScratch,
                        right = rightScratch,
                        frameCount = minOf(decodedCount, MAX_ANALYZE_FRAMES),
                    )
                    synchronized(queueLock) {
                        if (format.sampleRate > 0) {
                            configuredSampleRateHz = format.sampleRate
                        }
                        appendMonoToRingLocked(decodedCount)
                        while (frameQueue.size >= MAX_QUEUED_FRAMES) {
                            frameQueue.removeFirst()
                        }
                        frameQueue.addLast(frame)
                    }
                }
            }
        }

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    fun pollNextFrame(): RawPcmSignalFrame? {
        synchronized(pollLock) {
            var nextFrame: RawPcmSignalFrame?
            var snapCount = 0
            var snapSampleRate = DEFAULT_SAMPLE_RATE_HZ
            synchronized(queueLock) {
                if (frameQueue.isEmpty()) return null
                while (frameQueue.size > MAX_LATENCY_ALIGNED_FRAMES) {
                    frameQueue.removeFirst()
                }
                nextFrame = frameQueue.removeFirst()
                if (monoRingCount >= MIN_RTA31_SAMPLES && configuredSampleRateHz > 0) {
                    snapCount = monoRingCount
                    snapSampleRate = configuredSampleRateHz
                    val startIdx = (monoRingWritePos - snapCount + MONO_RING_CAPACITY) % MONO_RING_CAPACITY
                    val firstChunk = minOf(snapCount, MONO_RING_CAPACITY - startIdx)
                    System.arraycopy(monoRing, startIdx, pollRtaScratch, 0, firstChunk)
                    val secondChunk = snapCount - firstChunk
                    if (secondChunk > 0) {
                        System.arraycopy(monoRing, 0, pollRtaScratch, firstChunk, secondChunk)
                    }
                }
            }
            val baseFrame = nextFrame ?: return null
            if (snapCount < MIN_RTA31_SAMPLES) return baseFrame
            val rta31 = LocalSignalRules.analyze31BandRta(
                monoWindow = pollRtaScratch,
                sampleCount = snapCount,
                sampleRateHz = snapSampleRate,
            )
            return baseFrame.copy(bands31 = rta31)
        }
    }

    override fun onFlush() {
        synchronized(queueLock) {
            frameQueue.clear()
            monoRingWritePos = 0
            monoRingCount = 0
        }
    }

    override fun onReset() {
        synchronized(queueLock) {
            frameQueue.clear()
            monoRingWritePos = 0
            monoRingCount = 0
        }
    }

    private fun appendMonoToRingLocked(sampleCount: Int) {
        val count = minOf(sampleCount, MONO_RING_CAPACITY)
        val firstChunk = minOf(count, MONO_RING_CAPACITY - monoRingWritePos)
        System.arraycopy(decodeMonoScratch, 0, monoRing, monoRingWritePos, firstChunk)
        val secondChunk = count - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(decodeMonoScratch, firstChunk, monoRing, 0, secondChunk)
        }
        monoRingWritePos = (monoRingWritePos + count) % MONO_RING_CAPACITY
        monoRingCount = minOf(MONO_RING_CAPACITY, monoRingCount + count)
    }

    private fun decodeSliceIntoScratch(
        slice: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        bytesPerFrame: Int,
        framesToDecode: Int,
        tailScratchCount: Int,
    ): Int {
        val sampleBytes = bytesPerFrame / channelCount
        if (sampleBytes <= 0 || framesToDecode <= 0) return 0
        val extraChannelBytes = ((channelCount - 2).coerceAtLeast(0)) * sampleBytes
        val tailStartIndex = framesToDecode - tailScratchCount
        var count = 0
        while (count < framesToDecode && slice.remaining() >= bytesPerFrame) {
            val l = readSampleNormalized(slice, encoding)
            val r = if (channelCount >= 2) readSampleNormalized(slice, encoding) else l
            if (extraChannelBytes > 0) {
                slice.position(slice.position() + extraChannelBytes)
            }
            decodeMonoScratch[count] = (l + r) * 0.5f
            if (count >= tailStartIndex) {
                val tailIdx = count - tailStartIndex
                leftScratch[tailIdx] = l
                rightScratch[tailIdx] = r
            }
            count++
        }
        return count
    }

    private fun readSampleNormalized(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> (buffer.short.toFloat() / 32768f).coerceIn(-1f, 1f)
        C.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        C.ENCODING_PCM_24BIT -> {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            val sample24 = b0 or (b1 shl 8) or (b2 shl 16)
            (sample24 / 8_388_608f).coerceIn(-1f, 1f)
        }
        C.ENCODING_PCM_32BIT -> (buffer.int.toFloat() / 2_147_483_648f).coerceIn(-1f, 1f)
        C.ENCODING_PCM_8BIT -> (((buffer.get().toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
        else -> 0f
    }

    private companion object {
        const val MAX_ANALYZE_FRAMES = 512
        const val MAX_QUEUED_FRAMES = 24
        const val MAX_LATENCY_ALIGNED_FRAMES = 8
        const val MONO_RING_CAPACITY = 8192
        const val MIN_RTA31_SAMPLES = 256
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
    }
}
