package com.swararia.audiolab

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FohChannelRulesTest {
    @Test
    fun monoSumMathAveragesPcm16AndFloatWithoutClipping() {
        val pcm16 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm16.putShort(20_000).putShort(0) // L=20000, R=0 -> 10000
        pcm16.putShort((-12_000).toShort()).putShort(4_000) // L=-12000, R=4000 -> -4000
        pcm16.flip()
        MonoSumMath.sumStereoPcm16InPlace(pcm16)
        assertEquals(10_000.toShort(), pcm16.getShort(0))
        assertEquals(10_000.toShort(), pcm16.getShort(2))
        assertEquals((-4_000).toShort(), pcm16.getShort(4))
        assertEquals((-4_000).toShort(), pcm16.getShort(6))

        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        floats.putFloat(1.0f).putFloat(1.0f) // Full-scale correlated -> stays 1.0f
        floats.putFloat(-0.8f).putFloat(0.2f) // (-0.8 + 0.2) * 0.5 = -0.3f
        floats.flip()
        MonoSumMath.sumStereoFloatInPlace(floats)
        assertEquals(1.0f, floats.getFloat(0), 0.0001f)
        assertEquals(1.0f, floats.getFloat(4), 0.0001f)
        assertEquals(-0.3f, floats.getFloat(8), 0.0001f)
        assertEquals(-0.3f, floats.getFloat(12), 0.0001f)
    }

    @Test
    fun monoSumMathAveragesPcm24AndPcm32Cleanly() {
        val pcm24 = ByteBuffer.allocate(6)
        // Frame 0: L = +400_000, R = -100_000 -> +150_000
        val l24 = 400_000
        val r24 = -100_000
        pcm24.put((l24 and 0xFF).toByte()).put(((l24 ushr 8) and 0xFF).toByte()).put(((l24 ushr 16) and 0xFF).toByte())
        pcm24.put((r24 and 0xFF).toByte()).put(((r24 ushr 8) and 0xFF).toByte()).put(((r24 ushr 16) and 0xFF).toByte())
        pcm24.flip()
        MonoSumMath.sumStereoPcm24InPlace(pcm24)
        val expected24 = 150_000
        assertEquals((expected24 and 0xFF).toByte(), pcm24.get(0))
        assertEquals(((expected24 ushr 8) and 0xFF).toByte(), pcm24.get(1))
        assertEquals(((expected24 ushr 16) and 0xFF).toByte(), pcm24.get(2))
        assertEquals(pcm24.get(0), pcm24.get(3))
        assertEquals(pcm24.get(1), pcm24.get(4))
        assertEquals(pcm24.get(2), pcm24.get(5))

        val pcm32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm32.putInt(1_000_000_000).putInt(-500_000_000)
        pcm32.flip()
        MonoSumMath.sumStereoPcm32InPlace(pcm32)
        assertEquals(250_000_000, pcm32.getInt(0))
        assertEquals(250_000_000, pcm32.getInt(4))
    }

    @Test
    fun soundboardSlotSerializationEngineChoiceAndTrimMathAreSafe() {
        assertTrue(SoundboardRules.shouldUseSoundPool(4_200L))
        assertFalse(SoundboardRules.shouldUseSoundPool(8_500L))
        assertFalse(SoundboardRules.shouldUseSoundPool(null))

        assertEquals(1.0f, SoundboardRules.trimDbToLinear(0f), 0.001f)
        assertEquals(0.2512f, SoundboardRules.trimDbToLinear(-12f), 0.01f)
        assertEquals("0 dB", SoundboardRules.formatTrimDb(0.2f))
        assertEquals("-6 dB", SoundboardRules.formatTrimDb(-6f))

        val slots = SoundboardRules.defaultSlots().toMutableList()
        slots[2] = SoundPadSlot(bankIndex = 0, slotIndex = 2, trackId = 42L, label = "Fanfare Opening", trimDb = -3f)
        slots[9] = SoundPadSlot(bankIndex = 1, slotIndex = 1, trackId = 77L, label = "Buzzer Salah", trimDb = -99f)

        val encoded = SoundboardRules.encodeSlotsJson(slots)
        val decoded = SoundboardRules.decodeSlotsJson(encoded)
        assertEquals(SoundboardRules.BANK_COUNT * SoundboardRules.SLOTS_PER_BANK, decoded.size)
        assertEquals(42L, decoded[2].trackId)
        assertEquals("Fanfare Opening", decoded[2].label)
        assertEquals(-3f, decoded[2].trimDb, 0.01f)
        assertEquals(77L, decoded[9].trackId)
        assertEquals(SoundboardRules.TRIM_MIN_DB, decoded[9].trimDb, 0.01f)
        assertFalse(decoded[0].isAssigned)
    }

    @Test
    fun fohSafeModeRulesEvaluateDndFocusAndRouteFlagsCorrectly() {
        val activeAll = FohSafeModeSettings(enabled = true, autoDnd = true, focusLock = true)
        assertTrue(FohSafeModeRules.shouldApplySystemDnd(activeAll, policyAccessGranted = true))
        assertFalse(FohSafeModeRules.shouldApplySystemDnd(activeAll, policyAccessGranted = false))
        assertTrue(FohSafeModeRules.needsDndPermissionPrompt(activeAll, policyAccessGranted = false))
        assertFalse(FohSafeModeRules.needsDndPermissionPrompt(activeAll, policyAccessGranted = true))
        assertTrue(FohSafeModeRules.shouldIgnoreExternalFocusLoss(activeAll))

        val safeOff = activeAll.copy(enabled = false)
        assertFalse(FohSafeModeRules.shouldApplySystemDnd(safeOff, policyAccessGranted = true))
        assertFalse(FohSafeModeRules.shouldIgnoreExternalFocusLoss(safeOff))

        assertTrue(FohSafeModeRules.isSamsungManufacturer("samsung"))
        assertTrue(FohSafeModeRules.isSamsungManufacturer("Samsung"))
        assertFalse(FohSafeModeRules.isSamsungManufacturer("Xiaomi"))

        val btRoute = LocalFohRules.classifyOutputDevices(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Stage BT"))
        val lineRoute = LocalFohRules.classifyOutputDevices(listOf(AudioDeviceInfo.TYPE_USB_DEVICE to "DAC"))
        assertTrue(FohSafeModeRules.shouldWarnBluetooth(btRoute))
        assertFalse(FohSafeModeRules.shouldWarnBluetooth(lineRoute))
    }

    @Test
    fun momentPresetsAndWavSynthesizerGenerateValidStereoRiffWaveFiles() {
        val presetSlots = SoundboardRules.defaultPresetSlots()
        assertEquals(SoundboardRules.BANK_COUNT * SoundboardRules.SLOTS_PER_BANK, presetSlots.size)
        assertTrue(presetSlots.all { it.isAssigned })
        assertTrue(presetSlots.any { it.presetId == "applause" && it.label == "Tepuk Tangan" })
        assertTrue(presetSlots.any { it.presetId == "laughter" && it.label == "Tertawa Ramai" })
        assertTrue(presetSlots.any { it.presetId == "tense_suspense" && it.label == "Momen Tegang" })

        val modified = presetSlots.toMutableList()
        modified[0] = modified[0].copy(trimDb = -6f)
        val roundTripped = SoundboardRules.decodeSlotsJson(SoundboardRules.encodeSlotsJson(modified))
        assertEquals("applause", roundTripped[0].presetId)
        assertEquals("Tepuk Tangan", roundTripped[0].label)
        assertEquals(-6f, roundTripped[0].trimDb, 0.01f)
        assertEquals(null, roundTripped[0].trackId)

        val partial = SoundboardRules.defaultSlots().toMutableList()
        partial[0] = SoundPadSlot(bankIndex = 0, slotIndex = 0, trackId = 99L, label = "Custom Track", trimDb = -3f)
        val merged = SoundboardRules.mergeEmptyWithDefaultPresets(partial)
        assertEquals(99L, merged[0].trackId)
        assertEquals(null, merged[0].presetId)
        assertEquals("laughter", merged[1].presetId)
        assertTrue(SoundboardRules.isSlotPlayable(merged[1], emptySet()))

        for (preset in SoundboardRules.MOMENT_PRESETS) {
            val wav = SoundboardWavPresets.buildPresetWavBytes(preset.id)
            assertTrue("Preset ${preset.id} must use SoundPool path", SoundboardRules.shouldUseSoundPool(preset.durationMs))
            assertTrue("Preset ${preset.id} wav header too short", wav.size > 44)
            assertTrue("Preset ${preset.id} wav must fit <1MB SoundPool cap", wav.size < 1_000_000)

            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
            val header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1.toShort(), header.getShort(20)) // PCM
            assertEquals(2.toShort(), header.getShort(22)) // Stereo
            assertEquals(SoundboardWavPresets.SAMPLE_RATE, header.getInt(24))
            assertEquals(16.toShort(), header.getShort(34)) // Bits per sample
            val dataBytes = header.getInt(40)
            assertEquals(wav.size - 44, dataBytes)

            val pcm = ByteBuffer.wrap(wav, 44, dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            var maxAbs = 0
            while (pcm.remaining() >= 2) {
                val sample = kotlin.math.abs(pcm.getShort().toInt())
                if (sample > maxAbs) maxAbs = sample
            }
            assertTrue("Preset ${preset.id} must have non-zero audio signal", maxAbs > 500)
        }

        val tmpDir = java.nio.file.Files.createTempDirectory("swararia_pad_wav_test").toFile()
        try {
            val exported = SoundboardWavPresets.ensurePresetWavFile(tmpDir, "tense_suspense")
            assertTrue(exported != null && exported.exists() && exported.length() > 44L)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
