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
}
