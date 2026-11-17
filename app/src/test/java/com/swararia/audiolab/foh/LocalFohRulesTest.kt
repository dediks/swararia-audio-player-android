package com.swararia.audiolab.foh

import android.media.AudioDeviceInfo
import com.swararia.audiolab.AudioTrack
import com.swararia.audiolab.PlaybackMode
import com.swararia.audiolab.SessionPlayback
import com.swararia.audiolab.SessionQueueItem
import com.swararia.audiolab.UiIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFohRulesTest {
    @Test
    fun countdownFormattingAndEndingThreshold() {
        assertEquals("--:--", LocalFohRules.formatCountdown(null))
        assertEquals("-01:42", LocalFohRules.formatCountdown(102_000L))
        assertEquals("-00:09", LocalFohRules.formatCountdown(9_000L))
        assertEquals("-1:02:05", LocalFohRules.formatCountdown(3_725_000L))

        val normal = LocalFohRules.countdownState(positionMs = 10_000L, durationMs = 120_000L, isPlaying = true)
        assertEquals("-01:50", normal.countdownText)
        assertFalse(normal.warningActive)

        val endingSoonPlaying = LocalFohRules.countdownState(positionMs = 111_000L, durationMs = 120_000L, isPlaying = true)
        assertEquals("-00:09", endingSoonPlaying.countdownText)
        assertTrue(endingSoonPlaying.warningActive)

        val endingSoonPaused = LocalFohRules.countdownState(positionMs = 111_000L, durationMs = 120_000L, isPlaying = false)
        assertFalse(endingSoonPaused.warningActive)

        val finished = LocalFohRules.countdownState(positionMs = 120_000L, durationMs = 120_000L, isPlaying = true)
        assertFalse(finished.warningActive)
    }

    @Test
    fun endingAlertTextReflectsPlaybackModeWhenPlayingUnder15Seconds() {
        assertEquals(
            "Ending in 9s · Auto-Stop (Mode Once)",
            LocalFohRules.endingAlertText(9_400L, PlaybackMode.ONCE, isPlaying = true),
        )
        assertEquals(
            "Ending in 12s · Auto-Next (Continue)",
            LocalFohRules.endingAlertText(12_000L, PlaybackMode.CONTINUE, isPlaying = true),
        )
        assertEquals(
            "Ending in 5s · Looping Track",
            LocalFohRules.endingAlertText(5_000L, PlaybackMode.LOOP_TRACK, isPlaying = true),
        )
        assertNull(LocalFohRules.endingAlertText(20_000L, PlaybackMode.ONCE, isPlaying = true))
        assertNull(LocalFohRules.endingAlertText(9_000L, PlaybackMode.ONCE, isPlaying = false))
    }

    @Test
    fun audioOutputClassificationPrioritizesWiredThenBluetoothThenSpeaker() {
        val speakerOnly = LocalFohRules.classifyOutputDevices(
            listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Speaker"),
        )
        assertEquals(AudioOutputKind.SPEAKER, speakerOnly.kind)
        assertEquals("Output: Phone Speaker", speakerOnly.label)

        val bluetooth = LocalFohRules.classifyOutputDevices(
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Speaker",
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Stage Receiver",
            ),
        )
        assertEquals(AudioOutputKind.BLUETOOTH, bluetooth.kind)
        assertEquals("Bluetooth Audio", bluetooth.label)

        val wiredOverridesBluetooth = LocalFohRules.classifyOutputDevices(
            listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "TWS",
                AudioDeviceInfo.TYPE_USB_HEADSET to "USB DAC",
            ),
        )
        assertEquals(AudioOutputKind.LINE_OR_USB, wiredOverridesBluetooth.kind)
        assertEquals("USB / Line Out", wiredOverridesBluetooth.label)
    }

    @Test
    fun nextUpAndTrackMetaLabelsFormatCleanly() {
        val track1 = AudioTrack(id = 1L, uri = "file://1", displayName = "01_Opening_Gala.wav", sizeBytes = 1024L, durationMs = 225_000L)
        val track2 = AudioTrack(id = 2L, uri = "file://2", displayName = "02_Backsound_MC.mp3", sizeBytes = 512L, durationMs = 150_000L, artist = "FOH Bed")
        val playback = SessionPlayback(
            connected = true,
            mediaId = "1",
            currentIndex = 0,
            queue = listOf(
                SessionQueueItem(0, "1", "01_Opening_Gala.wav"),
                SessionQueueItem(1, "2", "02_Backsound_MC.mp3"),
            ),
        )
        val byId = mapOf("1" to track1, "2" to track2)

        assertEquals("WAV", LocalFohRules.formatTag(track1))
        assertEquals("FOH Bed · MP3 · 2:30", LocalFohRules.cueItemSubtitle(track2))
        assertEquals("Next Up: 02 Backsound MC", LocalFohRules.nextUpLabel(playback, byId, PlaybackMode.CONTINUE))
        assertEquals("Auto-Stop · Next Ready: 02 Backsound MC", LocalFohRules.nextUpLabel(playback, byId, PlaybackMode.ONCE))
        assertEquals("Loop Active · Track Diulang", LocalFohRules.nextUpLabel(playback, byId, PlaybackMode.LOOP_TRACK))
    }

    @Test
    fun localPlayerViewModesIncludeCueListPadsEqAndArtInOrder() {
        assertEquals(
            listOf(
                LocalPlayerViewMode.CUE_LIST,
                LocalPlayerViewMode.PADS,
                LocalPlayerViewMode.EQ,
                LocalPlayerViewMode.ART,
            ),
            LocalPlayerViewMode.entries,
        )
        assertEquals("Cue", LocalPlayerViewMode.CUE_LIST.label)
        assertEquals("Pads", LocalPlayerViewMode.PADS.label)
        assertEquals("EQ", LocalPlayerViewMode.EQ.label)
        assertEquals("Art", LocalPlayerViewMode.ART.label)
    }

    @Test
    fun playbackModeIconsMapDistinctVectorsForAllModes() {
        val once = UiIcons.forPlaybackMode(PlaybackMode.ONCE)
        val cont = UiIcons.forPlaybackMode(PlaybackMode.CONTINUE)
        val loop = UiIcons.forPlaybackMode(PlaybackMode.LOOP_TRACK)

        assertEquals("ModeOnce", once.name)
        assertEquals("ModeContinue", cont.name)
        assertEquals("ModeLoopTrack", loop.name)
        assertEquals(3, setOf(once, cont, loop).size)
    }

    @Test
    fun initialRootTabAndSetupStatusPillReflectFohReadiness() {
        assertEquals(MainRootTab.PUSTAKA, LocalFohRules.initialRootTab(hasActiveTrackOrQueue = false))
        assertEquals(MainRootTab.LIVE_DECK, LocalFohRules.initialRootTab(hasActiveTrackOrQueue = true))

        val usbRoute = AudioOutputRoute(AudioOutputKind.LINE_OR_USB, "USB / Line Out", "Connected")
        val speakerRoute = AudioOutputRoute(AudioOutputKind.SPEAKER, "Output: Phone Speaker", "Speaker")

        val ready = LocalFohRules.setupStatusPill(
            route = usbRoute,
            safeModeEnabled = true,
            needsDndPermission = false,
            guardEnabled = true,
        )
        assertEquals("FOH Ready", ready.label)
        assertEquals(FohReadinessLevel.READY, ready.level)

        val attentionSpeaker = LocalFohRules.setupStatusPill(
            route = speakerRoute,
            safeModeEnabled = true,
            needsDndPermission = false,
            guardEnabled = true,
        )
        assertEquals("Perlu Cek", attentionSpeaker.label)
        assertEquals(FohReadinessLevel.ATTENTION, attentionSpeaker.level)

        val attentionDnd = LocalFohRules.setupStatusPill(
            route = usbRoute,
            safeModeEnabled = true,
            needsDndPermission = true,
            guardEnabled = true,
        )
        assertEquals("Perlu Cek", attentionDnd.label)
        assertEquals(FohReadinessLevel.ATTENTION, attentionDnd.level)
    }
}
