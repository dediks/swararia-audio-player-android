package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class PreviewUrlCacheTest {
    private fun media(id:String="abcdefghijk",expires:Long=1_700_000_100L)=PreviewMedia(id,"Fixture","https://r1.googlevideo.com/videoplayback?expire=$expires",mapOf("User-Agent" to "Fixture"))
    @Test fun upstreamExpiryAndClockRollbackInvalidateEarly() {
        var now=1_700_000_000_000L
        val cache=PreviewUrlCache({now})
        cache.put(media(expires=1_700_000_010L))
        now+=5_000L
        assertNull(cache.get("abcdefghijk"))
        now=1_700_000_000_000L
        cache.put(media());now--
        assertNull(cache.get("abcdefghijk"))
    }
    @Test fun cacheCanBeInvalidatedAndRemainsBounded() {
        val cache=PreviewUrlCache({1_700_000_000_000L})
        repeat(9) { cache.put(media("abcdefghij$it")) }
        assertNull(cache.get("abcdefghij0"))
        cache.invalidate("abcdefghij8");assertNull(cache.get("abcdefghij8"))
        cache.clear();assertNull(cache.get("abcdefghij7"))
    }
    @Test fun malformedOrExpiredOrNonGoogleStreamsAreNotCached() {
        val cache=PreviewUrlCache({1_700_000_000_000L})
        for(url in listOf("https://evil.invalid/videoplayback","http://r1.googlevideo.com/videoplayback",
            "https://r1.googlevideo.com/videoplayback?expire=invalid", "https://r1.googlevideo.com/videoplayback?expire=9223372036854775807")) {
            cache.put(media().copy(url=url));assertNull(cache.get("abcdefghijk"))
        }
        cache.put(media(expires=1L));assertNull(cache.get("abcdefghijk"))
    }

    @Test fun validatedUrlCanBeReusedOnlyWithinShortLifetime() {
        var now=1_700_000_000_000L
        val cache=PreviewUrlCache({now})
        val item=media()
        cache.put(item)
        assertEquals(item,cache.get(item.videoId))
        now+=60_000L
        assertNull(cache.get(item.videoId))
    }
}
