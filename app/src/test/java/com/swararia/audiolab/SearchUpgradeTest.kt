package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class SearchUpgradeTest {
    @Test fun previewAcceptsOnlyBoundedGoogleHttpsStreamsAndSafeHeaders() {
        val root="""{"id":"BaW_jenozKc","title":"Song","url":"https://rr1---sn-test.googlevideo.com/videoplayback?x=1","http_headers":{"User-Agent":"Example agent","Authorization":"must not propagate","Referer":"https://www.youtube.com/"}}"""
        val media=OnlinePreviewRules.parse(root,"BaW_jenozKc","Song")
        assertEquals("BaW_jenozKc",media.videoId)
        assertEquals("Example agent",media.headers["User-Agent"])
        assertFalse(media.headers.containsKey("Authorization"))
        listOf("http://rr1.googlevideo.com/v", "https://evil.example/v", "https://googlevideo.com.evil.example/v", "https://user@rr1.googlevideo.com/v").forEach {
            assertThrows(IllegalArgumentException::class.java) { OnlinePreviewRules.parse(root.replace(media.url,it),"BaW_jenozKc","Song") }
        }
        assertThrows(IllegalArgumentException::class.java) { OnlinePreviewRules.parse(root,"aqz-KE-bpKQ","Other") }
        assertTrue(OnlinePreviewRules.arguments("BaW_jenozKc").contains("--skip-download"))
        assertEquals(120_000L,OnlinePreviewRules.PREVIEW_MS)
    }

    @Test fun artistIsNotInventedAsChannelAndPlaylistLinksAreCanonical() {
        val song=YoutubeSearchJson.parse("""{"entries":[{"id":"BaW_jenozKc","title":"Song","artist":"Artist only"}]}""").single()
        assertNull(song.channel)
        assertEquals("Artist only",song.artist)
        val id="PLabcdefghijklmnopqrstuv12345"
        val playlist=YoutubeSearchJson.parse("""{"entries":[{"id":"$id","title":"Collection","channel":"Creator","url":"https://bad.invalid/"}]}""",SearchKind.PLAYLISTS).single()
        assertEquals(SearchKind.PLAYLISTS,playlist.kind)
        assertEquals("https://www.youtube.com/playlist?list=$id",playlist.url)
        assertTrue(YoutubeSearchJson.parse("""{"entries":[{"id":"$id ","title":"Bad"}]}""",SearchKind.PLAYLISTS).isEmpty())
    }

    @Test fun sortingDeduplicatesAndDoesNotTrustOfficialWordsInNames() {
        fun hit(id:String,title:String,seconds:Long?,channel:String,verified:Boolean=false)=
            SearchHit(id,title,channel,seconds,null,"https://www.youtube.com/watch?v=$id",channelIsVerified=verified)
        val fake=hit("aaaaaaaaaaa","B",100,"Official Label")
        val topic=hit("bbbbbbbbbbb","A",200,"Artist - Topic")
        val verified=hit("ccccccccccc","C",50,"Publisher",true)
        val unknown=hit("ddddddddddd","D",null,"Other")
        assertEquals(listOf(verified,topic,fake,unknown),YoutubeSearchRules.sorted(listOf(fake,topic,verified,unknown),SearchSort.RELEVANCE))
        assertEquals(listOf(verified,fake,topic,unknown),YoutubeSearchRules.sorted(listOf(fake,unknown,topic,verified),SearchSort.DURATION_ASC))
        assertEquals(listOf(topic,fake,verified,unknown),YoutubeSearchRules.sorted(listOf(fake,unknown,topic,verified),SearchSort.DURATION_DESC))
        assertEquals(listOf(topic,fake,verified,unknown),YoutubeSearchRules.sorted(listOf(fake,unknown,topic,verified),SearchSort.TITLE))
        assertEquals(1,YoutubeSearchRules.merge(listOf(fake),listOf(fake.copy(title="Updated"))).size)
        assertFalse(YoutubeSearchRules.complete(unknown))
        assertTrue(YoutubeSearchRules.complete(verified))
    }

    @Test fun musicIsDefaultAndBothFiltersHaveBoundedPaging() {
        assertEquals(SearchProvider.YOUTUBE_MUSIC, YoutubeSearchState().provider)
        val args=YoutubeSearchRules.arguments("test music",SearchProvider.YOUTUBE_MUSIC,SearchKind.SONGS,10)
        assertEquals("11",args[args.indexOf("--playlist-start")+1])
        assertEquals("20",args[args.indexOf("--playlist-end")+1])
        assertTrue(args.last().endsWith("#songs"))
        val playlists=YoutubeSearchRules.arguments("test music",SearchProvider.YOUTUBE_MUSIC,SearchKind.PLAYLISTS)
        assertTrue(playlists.last().endsWith("#community+playlists"))
        assertTrue(YoutubeSearchRules.arguments("test",SearchProvider.YOUTUBE,SearchKind.PLAYLISTS).last().contains("sp=EgIQAw"))
        assertThrows(IllegalArgumentException::class.java) { YoutubeSearchRules.arguments("test",SearchProvider.YOUTUBE,SearchKind.SONGS,50) }
        assertThrows(IllegalArgumentException::class.java) { YoutubeSearchRules.arguments("test",SearchProvider.YOUTUBE,SearchKind.SONGS,-1) }
    }
}
