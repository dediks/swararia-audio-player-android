package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun durationLabelsDoNotDisplayUnknownDurationAsZero() {
        val method = runCatching { LibraryRules::class.java.getMethod("durationLabel", java.lang.Long::class.java) }.getOrNull()
        assertNotNull("Duration display rules are not implemented yet", method)
        for ((value, expected) in linkedMapOf<Long?, String>(null to "--:--", -1L to "--:--", 0L to "0:00", 65000L to "1:05", 3600000L to "1:00:00")) {
            assertEquals(expected, method!!.invoke(LibraryRules, value))
        }
    }

    @Test fun ingestAcceptsOnlyAnExplicitHttpsUrlWithoutEmbeddedCredentials() {
        val type = LibraryRules::class.java
        val method = runCatching { type.getMethod("validatedHttpsUrl", String::class.java) }.getOrNull()
        assertNotNull("HTTPS ingest validation is not implemented yet", method)
        assertEquals("https://example.com/audio.mp3", method!!.invoke(LibraryRules, "  https://example.com/audio.mp3  "))
        for (bad in listOf("", "http://example.com/a.mp3", "file:///etc/passwd", "--exec=bad", "https://", "https://a:b@example.com/audio.mp3", "https://example.com/a\n--exec")) {
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { method.invoke(LibraryRules, bad) }
            assertTrue("Expected user validation error for $bad", error.cause is IllegalArgumentException)
        }
    }

    @Test fun namingRulesAreImplementedAndPreserveTheAudioExtension() {
        val type = runCatching { Class.forName("com.swararia.audiolab.LibraryRules") }.getOrNull()
        assertNotNull("Library naming rules have not been implemented yet", type)
        val instance = type!!.getField("INSTANCE").get(null)
        val method = type.getMethod("renamedDisplayName", String::class.java, String::class.java)
        assertEquals("Acara malam.opus", method.invoke(instance, "lagu.opus", "Acara malam"))
    }
    @Test fun unsafeRenameInputIsRejected() {
        for (bad in listOf("", "   ", "../keluar", "folder/lagu", "folder\\lagu", "nama\nbaru", ".", "..", "a".repeat(200))) {
            assertThrows("Unsafe name must be rejected: $bad", IllegalArgumentException::class.java) {
                LibraryRules.renamedDisplayName("asli.opus", bad)
            }
        }
        assertEquals("Acara.opus", LibraryRules.renamedDisplayName("asli.opus", "Acara.OPUS"))
        assertEquals("Lagu café.opus", LibraryRules.renamedDisplayName("asli.opus", "Lagu café"))
    }

    @Test fun playlistNamesValidateLengthAndControlChars() {
        assertEquals("Fokus Malam", LibraryRules.validatedPlaylistName("  Fokus Malam  "))
        for (bad in listOf("", "   ", "Playlist\nBaru", "Tab\tName", "a".repeat(61))) {
            assertThrows("Invalid playlist name must be rejected: $bad", IllegalArgumentException::class.java) {
                LibraryRules.validatedPlaylistName(bad)
            }
        }
    }

    @Test fun audioTagSanitizationAndFormatLabelsWork() {
        assertEquals("NIKI", LibraryRules.sanitizedTag("  NIKI \n "))
        assertNull(LibraryRules.sanitizedTag(null))
        assertNull(LibraryRules.sanitizedTag("   "))
        assertNull(LibraryRules.sanitizedTag("<unknown>"))
        assertEquals("FLAC", LibraryRules.audioFormatLabel("track.flac"))
        assertEquals("AUDIO", LibraryRules.audioFormatLabel("noext"))
    }
}
