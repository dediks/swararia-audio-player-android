package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class PlaybackRulesTest {
    @Test fun fadeIsLinearAndClampedToExactlyThreeSeconds() {
        assertEquals(0.8f, FadeRules.volumeAt(0.8f, -10), 0.0001f)
        assertEquals(0.8f, FadeRules.volumeAt(0.8f, 0), 0.0001f)
        assertEquals(0.4f, FadeRules.volumeAt(0.8f, 1500), 0.0001f)
        assertEquals(0f, FadeRules.volumeAt(0.8f, 3000), 0f)
        assertEquals(0f, FadeRules.volumeAt(0.8f, Long.MAX_VALUE), 0f)
        assertEquals(0f, FadeRules.volumeAt(0f, 1500), 0f)
    }

    @Test fun fadeMayStartOnlyForAnActivelyPlayingNonBusyPlayer() {
        assertTrue(FadeRules.canStart(isPlaying = true, busy = false))
        assertFalse(FadeRules.canStart(isPlaying = false, busy = false))
        assertFalse(FadeRules.canStart(isPlaying = true, busy = true))
    }

    @Test fun nativeModesDoNotRebuildTheQueue() {
        assertTrue(PlaybackMode.LOOP_TRACK.repeatCurrent)
        assertFalse(PlaybackMode.LOOP_TRACK.pauseAtItemEnd)
        assertFalse(PlaybackMode.CONTINUE.repeatCurrent)
        assertFalse(PlaybackMode.CONTINUE.pauseAtItemEnd)
        assertFalse(PlaybackMode.ONCE.repeatCurrent)
        assertTrue(PlaybackMode.ONCE.pauseAtItemEnd)
        PlaybackMode.entries.forEach { assertEquals(it, PlaybackMode.fromStored(it.name)) }
    }
    @Test fun continueIsTheSafeDefaultForMissingOrUnknownPreference() {
        assertEquals(PlaybackMode.CONTINUE, PlaybackMode.fromStored(null))
        assertEquals(PlaybackMode.CONTINUE, PlaybackMode.fromStored("bad-mode"))
        assertEquals(PlaybackMode.CONTINUE, PlaybackMode.fromStored("CONTINUE"))
    }

    @Test fun globalFadeRulesComputeLinearRampsAndProportionalInterruptions() {
        assertEquals(0f, FadeRules.rampVolumeAt(0f, 1f, 0, 1000), 0.0001f)
        assertEquals(0.5f, FadeRules.rampVolumeAt(0f, 1f, 500, 1000), 0.0001f)
        assertEquals(1f, FadeRules.rampVolumeAt(0f, 1f, 1000, 1000), 0.0001f)
        assertEquals(0.7f, FadeRules.rampVolumeAt(0.4f, 1f, 500, 1000), 0.0001f)
        assertEquals(0.4f, FadeRules.volumeAt(0.8f, 1000, 2000), 0.0001f)
        assertEquals(3000L, FadeRules.manualDurationMs(0L))
        assertEquals(5000L, FadeRules.manualDurationMs(5000L))
        assertEquals(1000L, FadeRules.proportionalDurationMs(2000L, fromVolume = 0.5f, toVolume = 0f, nominalVolume = 1f))
        assertTrue(FadeRules.shouldBypassFade(previewActive = true, immediate = false))
        assertTrue(FadeRules.shouldBypassFade(previewActive = false, immediate = true))
        assertFalse(FadeRules.shouldBypassFade(previewActive = false, immediate = false))
        assertEquals("Off", FadeRules.shortLabel(0L))
        assertEquals("0.3s", FadeRules.shortLabel(300L))
        assertEquals("2s", FadeRules.shortLabel(2000L))
    }

    @Test fun fadeSwitchAutoPlayRespectsPendingStopAndPauseIntent() {
        assertTrue(FadeRules.shouldAutoPlayOnSwitch(playWhenReady = true, isPlaying = true, fadingOutToStopOrPause = false))
        assertTrue(FadeRules.shouldAutoPlayOnSwitch(playWhenReady = true, isPlaying = false, fadingOutToStopOrPause = false))
        assertFalse(FadeRules.shouldAutoPlayOnSwitch(playWhenReady = false, isPlaying = false, fadingOutToStopOrPause = false))
        assertFalse(FadeRules.shouldAutoPlayOnSwitch(playWhenReady = true, isPlaying = true, fadingOutToStopOrPause = true))
        assertFalse(FadeRules.shouldAutoPlayOnSwitch(playWhenReady = true, isPlaying = false, fadingOutToStopOrPause = true))
    }

    @Test fun fadeControlAllowsStartDuringPlaybackAndHardCutDuringFadeOut() {
        assertTrue(FadeRules.canTapFadeControl(fadeSupported = true, playing = true, fadeBusy = false, pending = false))
        assertTrue(FadeRules.canTapFadeControl(fadeSupported = true, playing = true, fadeBusy = true, pending = false))
        assertTrue(FadeRules.canTapFadeControl(fadeSupported = true, playing = false, fadeBusy = true, pending = false))
        assertFalse(FadeRules.canTapFadeControl(fadeSupported = true, playing = false, fadeBusy = false, pending = false))
        assertFalse(FadeRules.canTapFadeControl(fadeSupported = true, playing = true, fadeBusy = true, pending = true))
        assertFalse(FadeRules.canTapFadeControl(fadeSupported = false, playing = true, fadeBusy = true, pending = false))
    }
}
