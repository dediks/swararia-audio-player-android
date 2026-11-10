package com.swararia.audiolab

import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class YoutubeSearchRulesTest {
    @Test fun staleOrCancelledRequestsCannotPublishResults() {
        val type = runCatching { Class.forName("com.swararia.audiolab.SearchGeneration") }.getOrNull()
        assertNotNull("Search generation guard is not implemented", type)
        val guard = type!!.getDeclaredConstructor().newInstance()
        val next = type.getMethod("next")
        val current = type.getMethod("isCurrent", java.lang.Long.TYPE)
        val first = next.invoke(guard) as Long
        assertEquals(true, current.invoke(guard, first))
        val second = next.invoke(guard) as Long
        assertEquals(false, current.invoke(guard, first))
        assertEquals(true, current.invoke(guard, second))
        next.invoke(guard)
        assertEquals(false, current.invoke(guard, second))
    }
    @Test fun requestsAreValidatedBoundedEncodedAndMetadataOnly() {
        val type = runCatching { Class.forName("com.swararia.audiolab.YoutubeSearchRules") }.getOrNull()
        assertNotNull("Bounded provider-specific search requests are not implemented", type)
        val owner = type!!.getField("INSTANCE").get(null)
        val validate = type.getMethod("validatedQuery", String::class.java)
        fun query(input: String): String = try { validate.invoke(owner, input) as String }
            catch (failure: InvocationTargetException) { throw failure.cause!! }
        assertEquals("Senja & musik", query("  Senja & musik  "))
        listOf("", "   ", "a".repeat(201), "hello\nworld", "a\u0000b").forEach {
            assertThrows(IllegalArgumentException::class.java) { query(it) }
        }
        val input = type.getMethod("input", String::class.java, SearchProvider::class.java)
        val original = "Senja & musik / café #1"
        assertEquals("ytsearch10:$original", input.invoke(owner, original, SearchProvider.YOUTUBE))
        val music = URI(input.invoke(owner, original, SearchProvider.YOUTUBE_MUSIC) as String)
        assertEquals("https", music.scheme)
        assertEquals("music.youtube.com", music.host)
        assertEquals("songs", music.fragment)
        assertEquals(original, URLDecoder.decode(music.rawQuery.substringAfter("q="), "UTF-8"))
        val args = type.getMethod("arguments", String::class.java, SearchProvider::class.java)
            .invoke(owner, original, SearchProvider.YOUTUBE) as List<*>
        assertTrue(args.containsAll(listOf("--ignore-config", "--no-cache-dir", "--flat-playlist", "--dump-single-json", "--skip-download")))
        assertEquals("10", args[args.indexOf("--playlist-end")+1])
        assertEquals("ytsearch10:$original", args.last())
        assertFalse(args.contains("--extract-audio"))
    }
}
