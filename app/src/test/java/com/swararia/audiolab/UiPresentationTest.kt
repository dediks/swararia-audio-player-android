package com.swararia.audiolab
import org.junit.Assert.*
import org.junit.Test
class UiPresentationTest {
    @Test fun displayOnlyFormattingKeepsFilesAndStableArtworkIdentity() {
        val type=runCatching { Class.forName("com.swararia.audiolab.UiPresentation") }.getOrNull()
        assertNotNull("UI presentation rules are not implemented",type)
        val instance=type!!.getField("INSTANCE").get(null)
        val title=type.getMethod("title",String::class.java)
        assertEquals("Hujan di jendela",title.invoke(instance,"Hujan_di_jendela_[BaW_jenozKc].m4a"))
        assertEquals("Dr. Dre",title.invoke(instance,"Dr. Dre.mp3"))
        assertEquals("catatan.proyek",title.invoke(instance,"catatan.proyek"))
        assertEquals(".mp3",title.invoke(instance,".mp3"))
        assertEquals("Rekaman baru",title.invoke(instance,"Rekaman__baru.WAV"))
        val metadata=type.getMethod("metadata",String::class.java,java.lang.Long::class.java)
        assertEquals("M4A · 4:12",metadata.invoke(instance,"audio.m4a",252000L))
        assertEquals("FLAC · --:--",metadata.invoke(instance,"audio.flac",null))
        val artwork=type.getMethod("artworkIndex",java.lang.Long.TYPE)
        listOf(0L,1L,-1L,Long.MIN_VALUE,Long.MAX_VALUE).forEach { id ->
            val index=artwork.invoke(instance,id) as Int
            assertTrue(index in 0..4)
            assertEquals(index,artwork.invoke(instance,id))
        }
    }

    @Test fun trackSubtitleAndGroupingSortNamedGroupsBeforeFallback() {
        assertEquals("Tulus · FLAC · 3:05", UiPresentation.trackSubtitle("Monokrom.flac", "Tulus", 185_000L))
        assertEquals("FLAC · 3:05", UiPresentation.trackSubtitle("Monokrom.flac", "   ", 185_000L))
        assertEquals("2 lagu · 3:00", UiPresentation.groupSummary(2, 180_000L))
        assertEquals("2 lagu", UiPresentation.groupSummary(2, 0L))

        val rows = listOf(
            Triple("a.mp3", "", 60_000L),
            Triple("b.mp3", "Zedd", 90_000L),
            Triple("c.mp3", "Adhitia", 120_000L),
            Triple("d.mp3", "Adhitia", 30_000L),
        )
        val grouped = UiPresentation.groupItems(
            rows,
            keySelector = { it.second },
            durationSelector = { it.third },
            fallbackLabel = "Artis Tidak Diketahui",
        )
        assertEquals(listOf("Adhitia", "Zedd", "Artis Tidak Diketahui"), grouped.map { it.label })
        assertEquals(150_000L, grouped.first().totalDurationMs)
        assertEquals(2, grouped.first().items.size)
    }

    @Test fun playlistWorkflowSummariesAndBadgesFormatCleanly() {
        assertEquals(listOf("All Tracks", "Playlists"), LibrarySectionTab.entries.map { it.label })
        assertEquals(listOf("All", "Artist", "Album", "Format"), LibraryGroupMode.entries.map { it.label })
        assertEquals("5 tracks · Total 22:45", UiPresentation.playlistStatsSummary(5, 1_365_000L))
        assertEquals("2 tracks", UiPresentation.playlistStatsSummary(2, 0L))

        val names = mapOf(
            1L to "01_Opening_Gala.wav",
            2L to "Instrumen_Akad.mp3",
            3L to "Doa_Penutup.opus",
            4L to "Backsound_MC.flac",
            5L to "Closing.m4a",
        )
        assertEquals(
            "Playlist kosong · tap + untuk tambah track",
            UiPresentation.playlistPreviewSummary(emptyList(), names::get),
        )
        assertEquals(
            "01 01 Opening Gala · 02 Instrumen Akad · 03 Doa Penutup · +2",
            UiPresentation.playlistPreviewSummary(listOf(1L, 2L, 3L, 4L, 5L), names::get),
        )
        assertNull(UiPresentation.playlistMembershipBadge(emptyList()))
        assertEquals("Wedding Akad", UiPresentation.playlistMembershipBadge(listOf("Wedding Akad")))
        assertEquals("Wedding Akad +2", UiPresentation.playlistMembershipBadge(listOf("Wedding Akad", "MC Bed", "Finale")))
    }

    @Test fun searchErrorsAndRecentQueriesStayUserFriendly() {
        assertEquals(
            "Koneksi terlalu lambat. Periksa Wi-Fi atau paket data lalu tekan Coba lagi.",
            UiPresentation.friendlySearchError("java.net.SocketTimeoutException: Connect timed out"),
        )
        assertEquals(
            "Tidak tersambung ke internet. Periksa koneksi lalu tekan Coba lagi.",
            UiPresentation.friendlySearchError("Unable to resolve host \"music.youtube.com\""),
        )
        assertEquals(
            "Kata pencarian tidak valid.",
            UiPresentation.friendlySearchError("Kata pencarian tidak valid."),
        )

        var recents = UiPresentation.recordRecentQuery(emptyList(), "  Hadrah Banjari  ")
        recents = UiPresentation.recordRecentQuery(recents, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        recents = UiPresentation.recordRecentQuery(recents, "Sholawat")
        recents = UiPresentation.recordRecentQuery(recents, "hadrah banjari")
        assertEquals(listOf("hadrah banjari", "Sholawat"), recents)
    }

    @Test fun audioQualityMetricsShowBitrateSizeAndHighBitrateBadge() {
        assertEquals(134, UiPresentation.effectiveBitrateKbps(6_118_928L, 366_000L))
        assertEquals("5.8 MB", UiPresentation.fileSizeLabel(6_118_928L))
        assertEquals("500 KB", UiPresentation.fileSizeLabel(512_000L))
        assertEquals(
            "OPUS · 134 kbps · 5.8 MB · 6:06",
            UiPresentation.qualitySummary("I_Will_Remember.opus", 6_118_928L, 366_000L),
        )
        assertNull(UiPresentation.highBitrateBadge("I_Will_Remember.opus", 6_118_928L, 366_000L))

        assertEquals(256, UiPresentation.effectiveBitrateKbps(11_712_000L, 366_000L))
        assertEquals(
            "Toto · M4A · 256 kbps · 11.2 MB · 6:06",
            UiPresentation.trackSubtitleWithQuality("I_Will_Remember.m4a", "Toto", 11_712_000L, 366_000L),
        )
        assertEquals("HQ 256k", UiPresentation.highBitrateBadge("I_Will_Remember.m4a", 11_712_000L, 366_000L))
        assertEquals("HQ 256k", UiPresentation.highBitrateBadge("I_Will_Remember.opus", 11_712_000L, 366_000L))
        assertEquals("LOSSLESS", UiPresentation.highBitrateBadge("Monokrom.flac", 24_000_000L, 185_000L))
        assertNull(UiPresentation.effectiveBitrateKbps(0L, 185_000L))
        assertNull(UiPresentation.effectiveBitrateKbps(5_000_000L, null))
        assertEquals("FLAC · --:--", UiPresentation.qualitySummary("Monokrom.flac", 0L, null))
    }
}
