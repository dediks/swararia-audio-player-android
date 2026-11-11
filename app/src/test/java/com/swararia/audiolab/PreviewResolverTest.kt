package com.swararia.audiolab

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewResolverTest {
    private val hit=SearchHit("abcdefghijk","Fixture","Channel",180,null,"https://www.youtube.com/watch?v=abcdefghijk")
    private val json="""{"id":"abcdefghijk","url":"https://r1.googlevideo.com/videoplayback","http_headers":{}}"""
    @Test fun replayUsesCacheAndFailureInvalidationForcesFreshResolution()=runBlocking {
        var calls=0
        val resolver=PreviewResolver({calls++;json})
        resolver.resolve(hit);resolver.resolve(hit)
        assertEquals(1,calls)
        resolver.invalidate(hit.videoId);resolver.resolve(hit)
        assertEquals(2,calls)
    }
    @Test fun networkChangeInvalidatesCachedStreamAndFailuresAreNotCached()=runBlocking {
        var network=1L;var calls=0;var fail=true
        val resolver=PreviewResolver({calls++;if(fail)error("blocked") else json},{network})
        try {resolver.resolve(hit);fail("expected failure")}catch(_:IllegalStateException){}
        fail=false;resolver.resolve(hit);assertEquals(2,calls)
        network=2L;resolver.resolve(hit);assertEquals(3,calls)
    }
}
