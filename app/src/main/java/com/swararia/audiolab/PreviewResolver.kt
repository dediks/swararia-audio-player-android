package com.swararia.audiolab

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source resolution/cache policy independent of Android/native execution. No stream prefetching. */
class PreviewResolver(
    private val extract: suspend (String) -> String,
    private val networkKey: () -> Any? = { Unit },
    private val cache: PreviewUrlCache = PreviewUrlCache(),
    private val timing: (Boolean, Long) -> Unit = { _, _ -> },
) {
    private var network: Any? = networkKey()
    suspend fun resolve(hit: SearchHit): PreviewMedia {
        require(hit.kind == SearchKind.SONGS && Regex("[A-Za-z0-9_-]{11}").matches(hit.videoId))
        currentCoroutineContext().ensureActive()
        val started=System.nanoTime()
        val active=networkKey()
        synchronized(this) { if(active!=network) {cache.clear();network=active} }
        cache.get(hit.videoId)?.let {
            timing(true,((System.nanoTime()-started)/1_000_000L).coerceAtLeast(0))
            return it.copy(title=hit.title.take(200))
        }
        val output=extract(hit.videoId)
        currentCoroutineContext().ensureActive()
        val media=OnlinePreviewRules.parse(output,hit.videoId,hit.title)
        if(networkKey()==active)cache.put(media)
        timing(false,((System.nanoTime()-started)/1_000_000L).coerceAtLeast(0))
        return media
    }
    fun invalidate(videoId:String)=cache.invalidate(videoId)
}
