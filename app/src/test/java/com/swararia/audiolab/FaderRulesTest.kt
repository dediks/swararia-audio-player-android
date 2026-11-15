package com.swararia.audiolab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaderRulesTest {
    @Test
    fun coldStartDefaultIsUnityAndSafe() {
        val state = FaderState()
        assertEquals(1f, state.normalizedPosition, 0.0001f)
        assertFalse(state.muted)
        assertFalse(state.ducked)
        assertEquals(1f, state.effectiveGain, 0.0001f)
        assertEquals("0.0 dB", state.dbLabel)
        assertEquals("0.0 dB", state.pillText)
        assertEquals(FaderTone.SAFE, state.pillTone)
        assertTrue(state.isUnity)
        assertFalse(state.isAttenuated)
    }

    @Test
    fun quadraticTaperAndDbFormattingHandleEdgeCasesCleanly() {
        assertEquals(1f, FaderRules.taperGain(1f), 0.0001f)
        assertEquals(0.25f, FaderRules.taperGain(0.50f), 0.0001f)
        assertEquals(0f, FaderRules.taperGain(0.005f), 0.0001f)
        assertEquals(0f, FaderRules.taperGain(0f), 0.0001f)

        assertEquals(0.50f, FaderRules.positionFromGain(0.25f), 0.0001f)
        assertEquals(0f, FaderRules.positionFromGain(-1f), 0.0001f)
        assertEquals(1f, FaderRules.positionFromGain(Float.NaN), 0.0001f)
        assertEquals(1f, FaderRules.clampPosition(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0f, FaderRules.clampPosition(-0.2f), 0.0001f)

        assertEquals("-12.0 dB", FaderRules.gainDecibelsLabel(0.25f))
        assertEquals("-∞ dB", FaderRules.gainDecibelsLabel(0f))
        assertEquals("0.0 dB", FaderRules.gainDecibelsLabel(0.999f))
    }

    @Test
    fun duckTogglesToMinus12DbAndRestoresPriorAboveDuckTrim() {
        val trim = FaderRules.withPosition(FaderState(), 0.80f)
        assertEquals(80, trim.percent)
        assertEquals("-3.9 dB", trim.pillText)
        assertEquals(FaderTone.SAFE, trim.pillTone)

        val ducked = FaderRules.toggleDuck(trim)
        assertTrue(ducked.ducked)
        assertFalse(ducked.muted)
        assertEquals(0.50f, ducked.position, 0.0001f)
        assertEquals(0.80f, ducked.preDuckPosition, 0.0001f)
        assertEquals(0.25f, ducked.effectiveGain, 0.0001f)
        assertEquals("-12.0 dB", ducked.dbLabel)
        assertEquals("DUCK -12dB", ducked.pillText)
        assertEquals(FaderTone.WARN, ducked.pillTone)

        val restored = FaderRules.toggleDuck(ducked)
        assertFalse(restored.ducked)
        assertEquals(0.80f, restored.position, 0.0001f)
        assertEquals(0.64f, restored.effectiveGain, 0.0001f)
        assertEquals(FaderTone.SAFE, restored.pillTone)

        // When already attenuated below Duck, second Duck tap restores to Unity instead of locking low.
        val low = FaderRules.withPosition(FaderState(), 0.20f)
        assertEquals(FaderTone.SAFE, low.pillTone)
        val duckFromLow = FaderRules.toggleDuck(low)
        assertEquals(1f, duckFromLow.preDuckPosition, 0.0001f)
        assertEquals(1f, FaderRules.toggleDuck(duckFromLow).position, 0.0001f)
    }

    @Test
    fun mutePreservesPositionAndDuckStateAndZeroSliderUsesSilentTone() {
        val ducked = FaderRules.toggleDuck(FaderState())
        val mutedWhileDucked = FaderRules.toggleMute(ducked)
        assertTrue(mutedWhileDucked.muted)
        assertTrue(mutedWhileDucked.ducked)
        assertEquals(0f, mutedWhileDucked.effectiveGain, 0.0001f)
        assertEquals("MUTED", mutedWhileDucked.dbLabel)
        assertEquals("MUTED", mutedWhileDucked.pillText)
        assertEquals(FaderTone.ALERT, mutedWhileDucked.pillTone)

        // Operators can reposition the fader while output remains safely muted.
        val movedWhileMuted = FaderRules.withPosition(mutedWhileDucked, 0.72f)
        assertTrue(movedWhileMuted.muted)
        assertFalse(movedWhileMuted.ducked)
        assertEquals(0.72f, movedWhileMuted.position, 0.0001f)
        assertEquals(0f, movedWhileMuted.effectiveGain, 0.0001f)

        val unmuted = FaderRules.toggleMute(movedWhileMuted)
        assertFalse(unmuted.muted)
        assertEquals(0.72f * 0.72f, unmuted.effectiveGain, 0.0001f)
        assertEquals(FaderTone.SAFE, unmuted.pillTone)

        // Zero slider unmuted shows dim SILENT indicator instead of red Mute/Clip alert.
        val pulledDown = FaderRules.withPosition(FaderState(), 0f)
        assertEquals(0f, pulledDown.effectiveGain, 0.0001f)
        assertEquals("-∞ dB", pulledDown.dbLabel)
        assertEquals("-∞ dB", pulledDown.pillText)
        assertEquals(FaderTone.SILENT, pulledDown.pillTone)

        val unity = FaderRules.resetUnity()
        assertTrue(unity.isUnity)
        assertEquals(1f, unity.effectiveGain, 0.0001f)
    }

    @Test
    fun phoneMediaVolumeClassificationHandlesMutedLowModerateOptimalAndEdgeBounds() {
        val full = PhoneMediaVolumeRules.classify(currentStep = 15, maxStep = 15)
        assertEquals(100, full.percent)
        assertEquals(PhoneMediaVolumeTier.OPTIMAL, full.tier)
        assertFalse(full.isMuted)
        assertFalse(full.shouldWarnPlayer)
        assertEquals("100%", full.percentLabel)
        assertEquals("15/15", full.stepLabel)

        val optimalThreshold = PhoneMediaVolumeRules.classify(currentStep = 12, maxStep = 15)
        assertEquals(80, optimalThreshold.percent)
        assertEquals(PhoneMediaVolumeTier.OPTIMAL, optimalThreshold.tier)
        assertFalse(optimalThreshold.shouldWarnPlayer)

        val moderate = PhoneMediaVolumeRules.classify(currentStep = 8, maxStep = 15)
        assertEquals(53, moderate.percent)
        assertEquals(PhoneMediaVolumeTier.MODERATE, moderate.tier)
        assertFalse(moderate.shouldWarnPlayer)

        val low = PhoneMediaVolumeRules.classify(currentStep = 7, maxStep = 15)
        assertEquals(47, low.percent)
        assertEquals(PhoneMediaVolumeTier.LOW, low.tier)
        assertTrue(low.shouldWarnPlayer)
        assertTrue(PhoneMediaVolumeRules.shortBannerText(low).contains("47%"))
        assertTrue(PhoneMediaVolumeRules.faderSheetDetailText(low).contains("47%"))

        val zeroStep = PhoneMediaVolumeRules.classify(currentStep = 0, maxStep = 15)
        assertEquals(0, zeroStep.percent)
        assertTrue(zeroStep.isMuted)
        assertEquals(PhoneMediaVolumeTier.MUTED, zeroStep.tier)
        assertTrue(zeroStep.shouldWarnPlayer)
        assertEquals("MUTED (0%)", zeroStep.percentLabel)

        val streamMutedWhileNonZero = PhoneMediaVolumeRules.classify(
            currentStep = 10,
            maxStep = 15,
            streamMuted = true,
        )
        assertTrue(streamMutedWhileNonZero.isMuted)
        assertEquals(0, streamMutedWhileNonZero.percent)
        assertEquals(0, streamMutedWhileNonZero.currentStep)
        assertEquals(PhoneMediaVolumeTier.MUTED, streamMutedWhileNonZero.tier)

        val nonzeroMinUnmuted = PhoneMediaVolumeRules.classify(
            currentStep = 1,
            maxStep = 15,
            minStep = 1,
            streamMuted = false,
        )
        assertFalse(nonzeroMinUnmuted.isMuted)
        assertEquals(1, nonzeroMinUnmuted.percent)
        assertEquals(PhoneMediaVolumeTier.LOW, nonzeroMinUnmuted.tier)

        val degenerateZeroSpan = PhoneMediaVolumeRules.classify(currentStep = 0, maxStep = 0)
        assertFalse(degenerateZeroSpan.isMuted)
        assertEquals(100, degenerateZeroSpan.percent)
        assertEquals(PhoneMediaVolumeTier.OPTIMAL, degenerateZeroSpan.tier)
    }
}
