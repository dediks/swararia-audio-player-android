package com.swararia.audiolab

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PhaseWavExporter {
    fun buildWaveFileBytes(
        mode: PhaseTestSignalMode,
        gain: Float = PhaseCheckerRules.DEFAULT_GENERATOR_GAIN,
        repeats: Int = 10,
        sampleRate: Int = PhaseCheckerRules.SAMPLE_RATE,
    ): ByteArray {
        val singleFrame = PhaseCheckerRules.buildStereoTestFrame(mode, gain, sampleRate)
        val safeRepeats = repeats.coerceIn(1, 30)
        val pcmSamples = singleFrame.size * safeRepeats
        val dataBytes = pcmSamples * 2
        val totalSize = 44 + dataBytes
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF WAVE header
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // AudioFormat = PCM
        buf.putShort(2) // Channels = Stereo
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 4) // ByteRate = sampleRate * 2ch * 2bytes
        buf.putShort(4) // BlockAlign = 4 bytes
        buf.putShort(16) // BitsPerSample = 16
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)

        repeat(safeRepeats) {
            for (s in singleFrame) buf.putShort(s)
        }
        return buf.array()
    }

    private fun buildShareIntent(
        context: Context,
        mode: PhaseTestSignalMode,
        gain: Float,
    ): Intent {
        val dir = File(context.cacheDir, "phase_export").apply {
            if (exists()) {
                listFiles()?.forEach { old -> old.delete() }
            } else {
                mkdirs()
            }
        }
        val tag = when (mode) {
            PhaseTestSignalMode.PULSE_FULL_RANGE -> "pulsa-full"
            PhaseTestSignalMode.PULSE_SUB -> "pulsa-sub"
            PhaseTestSignalMode.STEREO_IN_PHASE -> "stereo-inphase"
            PhaseTestSignalMode.STEREO_OUT_OF_PHASE -> "stereo-outphase"
        }
        val file = File(dir, "swararia-$tag-48k.wav")
        file.writeBytes(buildWaveFileBytes(mode = mode, gain = gain, repeats = 10))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.phaseprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SWR Player Phase Test Signal ($tag)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Bagikan File Uji Fasa (.wav)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun sharePhaseTestWav(
        context: Context,
        mode: PhaseTestSignalMode,
        gain: Float,
    ): Result<Unit> = runCatching {
        context.startActivity(buildShareIntent(context, mode, gain))
    }

    suspend fun sharePhaseTestWavAsync(
        context: Context,
        mode: PhaseTestSignalMode,
        gain: Float,
    ): Result<Unit> {
        val intentResult = withContext(Dispatchers.IO) {
            runCatching { buildShareIntent(context, mode, gain) }
        }
        return intentResult.mapCatching { chooser ->
            context.startActivity(chooser)
        }
    }
}
