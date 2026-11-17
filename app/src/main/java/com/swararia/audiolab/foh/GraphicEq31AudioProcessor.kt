package com.swararia.audiolab.foh

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * In-place real-time 31-band ISO 1/3-octave Graphic Equalizer [BaseAudioProcessor].
 *
 * Preserves stream format across preset/slider/bypass changes so live adjustments never trigger
 * `AudioTrack` reconfiguration or playback dropouts.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class GraphicEq31AudioProcessor : BaseAudioProcessor() {
    private val engine = GraphicEq31DspEngine()
    private var configuredEncoding: GraphicEq31DspEngine.PcmEncoding? = null

    fun updateState(state: GraphicEqState, snapImmediately: Boolean = false) {
        engine.updateState(state, snapImmediately)
    }

    fun setPreviewBypass(bypass: Boolean) {
        engine.setPreviewBypass(bypass)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.sampleRate <= 0) {
            configuredEncoding = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val mapped = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> GraphicEq31DspEngine.PcmEncoding.PCM_16BIT
            C.ENCODING_PCM_FLOAT -> GraphicEq31DspEngine.PcmEncoding.PCM_FLOAT
            C.ENCODING_PCM_24BIT -> GraphicEq31DspEngine.PcmEncoding.PCM_24BIT
            C.ENCODING_PCM_32BIT -> GraphicEq31DspEngine.PcmEncoding.PCM_32BIT
            C.ENCODING_PCM_8BIT -> GraphicEq31DspEngine.PcmEncoding.PCM_8BIT
            else -> null
        } ?: run {
            configuredEncoding = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configuredEncoding = mapped
        engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = configuredEncoding ?: return
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val out = replaceOutputBuffer(remaining)
        engine.processBuffer(inputBuffer, out, encoding)
        out.flip()
    }

    override fun onFlush() {
        engine.flush()
    }

    override fun onReset() {
        configuredEncoding = null
        engine.reset()
    }
}
