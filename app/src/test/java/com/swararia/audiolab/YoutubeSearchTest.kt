package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class YoutubeSearchTest {
    @Test fun displayMetadataCannotOverflowDurationsOrFloodText() {
        val title = "a".repeat(800)
        val channel = "b".repeat(600)
        val json = """{"entries":[{"id":"BaW_jenozKc","title":"$title","channel":"$channel","duration":1e100}]}"""
        val hit = YoutubeSearchJson.parse(json).single()
        assertNull(hit.durationSeconds)
        assertTrue(hit.title.length <= 200)
        assertTrue(hit.channel!!.length <= 160)
    }
    @Test fun malformedOversizedOrDeepResponsesAreRejected() {
        assertTrue(YoutubeSearchJson.parse("""{"entries":[]}""").isEmpty())
        listOf("", "null", "{}", "[]", "{broken", """{"entries":{}}""", """{"entries":[]} {"extra":true}""").forEach { json ->
            assertThrows("Invalid response must not masquerade as no results", IllegalArgumentException::class.java) { YoutubeSearchJson.parse(json) }
        }
        val huge = "{\"entries\":[],\"extra\":\"" + "a".repeat(2_000_001) + "\"}"
        assertThrows(IllegalArgumentException::class.java) { YoutubeSearchJson.parse(huge) }
        val deep = "{\"entries\":[],\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        assertThrows(IllegalArgumentException::class.java) { YoutubeSearchJson.parse(deep) }
    }
    @Test fun onlyUniquePublicVideoEntriesBecomeSafeHits() {
        val json = """{"entries":[
          {"id":"bad id","title":"Invalid"},
          {"id":"BaW_jenozKc","title":"  Public song  ","duration":-1,"thumbnails":[{"url":"http://127.0.0.1/a"},{"url":"https://i.ytimg.com/vi/BaW_jenozKc/hqdefault.jpg","width":320}]},
          {"id":"BaW_jenozKc","title":"Duplicate"},
          {"id":"aqz-KE-bpKQ","title":"Other song","uploader":"Channel"},
          {"id":"xxxxxxxxxxx","title":"Live","is_live":true},
          {"id":"yyyyyyyyyyy","title":"Private","availability":"private"},
          {"id":"zzzzzzzzzzz","title":"Playlist","_type":"playlist"},
          {"id":"11111111111","title":"  "},
          {"id":"22222222222 ","title":"Malformed ID must not be repaired"}
        ]}"""
        val hits = YoutubeSearchJson.parse(json)
        assertEquals(listOf("BaW_jenozKc", "aqz-KE-bpKQ"), hits.map { it.videoId })
        assertEquals("Public song", hits[0].title)
        assertNull(hits[0].durationSeconds)
        assertEquals("https://i.ytimg.com/vi/BaW_jenozKc/hqdefault.jpg", hits[0].thumbnailUrl)
        assertEquals("Channel", hits[1].channel)
    }
    @Test fun flatVideoMetadataIsParsedIntoCanonicalYoutubeHit() {
        val type = runCatching { Class.forName("com.swararia.audiolab.YoutubeSearchJson") }.getOrNull()
        assertNotNull("Metadata-only YouTube search parser is not implemented yet", type)
        val output = """{"entries":[{"_type":"url","ie_key":"Youtube","id":"BaW_jenozKc","title":"Test song","channel":"Artist","duration":42.8,"url":"https://attacker.invalid/not-used"}]}"""
        val hits = type!!.getMethod("parse", String::class.java).invoke(type.getField("INSTANCE").get(null), output) as List<*>
        assertEquals(1, hits.size)
        val hit = hits.single()!!
        assertEquals("BaW_jenozKc", hit.javaClass.getMethod("getVideoId").invoke(hit))
        assertEquals("Test song", hit.javaClass.getMethod("getTitle").invoke(hit))
        assertEquals("Artist", hit.javaClass.getMethod("getChannel").invoke(hit))
        assertEquals(42L, hit.javaClass.getMethod("getDurationSeconds").invoke(hit))
        assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", hit.javaClass.getMethod("getUrl").invoke(hit))
    }
}
