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
}
