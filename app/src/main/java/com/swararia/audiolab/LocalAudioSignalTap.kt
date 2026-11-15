package com.swararia.audiolab

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
    private val frameQueue = ArrayDeque<RawPcmSignalFrame>(MAX_QUEUED_FRAMES)
    private val leftScratch = FloatArray(MAX_ANALYZE_FRAMES)
    private val rightScratch = FloatArray(MAX_ANALYZE_FRAMES)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_8BIT,
            -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
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
                val framesToRead = minOf(totalFrames, MAX_ANALYZE_FRAMES)
                val startFrame = totalFrames - framesToRead
                if (startFrame > 0) {
                    slice.position(startFrame * bytesPerFrame)
                }
                val decoded = decodeSliceIntoScratch(
                    slice = slice,
                    encoding = format.encoding,
                    channelCount = channelCount,
                    bytesPerFrame = bytesPerFrame,
                    framesToRead = framesToRead,
                )
                if (decoded > 0) {
                    val frame = LocalSignalRules.analyzeFloatSamples(leftScratch, rightScratch, decoded)
                    synchronized(queueLock) {
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
        synchronized(queueLock) {
            if (frameQueue.isEmpty()) return null
            while (frameQueue.size > MAX_LATENCY_ALIGNED_FRAMES) {
                frameQueue.removeFirst()
            }
            return frameQueue.removeFirst()
        }
    }

    override fun onFlush() {
        synchronized(queueLock) {
            frameQueue.clear()
        }
    }

    override fun onReset() {
        synchronized(queueLock) {
            frameQueue.clear()
        }
    }

    private fun decodeSliceIntoScratch(
        slice: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        bytesPerFrame: Int,
        framesToRead: Int,
    ): Int {
        val sampleBytes = bytesPerFrame / channelCount
        if (sampleBytes <= 0) return 0
        val extraChannelBytes = ((channelCount - 2).coerceAtLeast(0)) * sampleBytes
        var count = 0
        while (count < framesToRead && slice.remaining() >= bytesPerFrame) {
            val l = readSampleNormalized(slice, encoding)
            val r = if (channelCount >= 2) readSampleNormalized(slice, encoding) else l
            if (extraChannelBytes > 0) {
                slice.position(slice.position() + extraChannelBytes)
            }
            leftScratch[count] = l
            rightScratch[count] = r
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
    }
}
