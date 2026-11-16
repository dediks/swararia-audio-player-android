package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class LegacyManagedUriRulesTest {
    @Test fun acceptsOnlyCanonicalPublicMusicFilesForLegacyAndroid() {
        assertTrue(ManagedCopyRules.validLegacyFileUri("file:///storage/emulated/0/Music/SwarariaAudioLab/lagu.mp3"))
        assertTrue(ManagedCopyRules.validLegacyFileUri("file:///storage/emulated/0/Music/SwarariaAudioLab/Live%20Set.flac"))
        assertTrue(ManagedCopyRules.isManagedUri("file:///storage/emulated/0/Music/SwarariaAudioLab/lagu.wav", sdkInt = 26))
        assertTrue(ManagedCopyRules.isManagedUri("file:///storage/emulated/0/Music/SwarariaAudioLab/lagu.opus", sdkInt = 28))
        assertFalse(ManagedCopyRules.isManagedUri("file:///storage/emulated/0/Music/SwarariaAudioLab/lagu.mp3", sdkInt = 29))

        val rejected = listOf(
            "file:///a",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/.swararia-pending/lagu.mp3",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/../secret.mp3",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/sub/lagu.mp3",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/lagu%2f2.mp3",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/lagu%2e.mp3",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/script.sh",
            "file:///storage/emulated/0/Music/SwarariaAudioLab/lagu.mp3?x=1",
            "content://media/external/audio/media/12",
        )
        for (bad in rejected) {
            assertFalse("Expected false for $bad", ManagedCopyRules.validLegacyFileUri(bad))
        }
    }
}
