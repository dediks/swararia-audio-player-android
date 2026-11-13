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
}
