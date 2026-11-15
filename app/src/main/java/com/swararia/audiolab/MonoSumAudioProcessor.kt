package com.swararia.audiolab

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * In-place real-time Stereo-to-Mono summing [BaseAudioProcessor].
 *
 * Keeps the stream format unchanged so toggling Mono Sum while audio is playing requires no
 * AudioTrack reconfiguration or audible glitch while sending identical balanced `(L + R) * 0.5f`
 * output to both physical pins on 3.5mm/USB/Bluetooth routes.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MonoSumAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var monoEnabled: Boolean = false

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

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        if (monoEnabled && inputAudioFormat.channelCount == 2) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> MonoSumMath.sumStereoPcm16InPlace(out)
                C.ENCODING_PCM_FLOAT -> MonoSumMath.sumStereoFloatInPlace(out)
                C.ENCODING_PCM_24BIT -> MonoSumMath.sumStereoPcm24InPlace(out)
                C.ENCODING_PCM_32BIT -> MonoSumMath.sumStereoPcm32InPlace(out)
                C.ENCODING_PCM_8BIT -> MonoSumMath.sumStereoPcm8InPlace(out)
            }
        }
    }
}
