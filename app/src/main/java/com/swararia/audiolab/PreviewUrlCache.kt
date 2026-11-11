package com.swararia.audiolab

/** Bounded memory-only cache. Never persist/log signed URLs or headers.
 * Cache lifetime respects upstream expiry and resets if the wall clock moves backward.
 */
class PreviewUrlCache(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val media: PreviewMedia, val created: Long, val deadline: Long)
    private val entries=LinkedHashMap<String,Entry>(8,.75f,true)
    @Synchronized fun put(media: PreviewMedia) {
        entries.remove(media.videoId)
        if(!Regex("[A-Za-z0-9_-]{11}").matches(media.videoId)) return
        val uri=runCatching { OnlinePreviewRules.streamUri(media.url) }.getOrNull() ?: return
        val now=clock()
        val ttl=runCatching { Math.addExact(now,60_000L) }.getOrNull() ?: return
        val expires=uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=')=="expire" }
        if(expires.size>1) return
        val deadline=if(expires.isEmpty()) ttl else {
            val seconds=expires.single().substringAfter('=',"").toLongOrNull() ?: return
            val ms=runCatching { Math.multiplyExact(seconds,1000L) }.getOrNull() ?: return
            val safe=runCatching { Math.subtractExact(ms,5000L) }.getOrNull() ?: return
            minOf(ttl,safe)
        }
        if(deadline<=now) return
        if(entries.size>=8) entries.remove(entries.keys.first())
        entries[media.videoId]=Entry(media.copy(headers=media.headers.toMap()),now,deadline)
    }
    @Synchronized fun get(videoId: String): PreviewMedia? {
        val entry=entries[videoId] ?: return null
        val now=clock()
        if(now<entry.created || now>=entry.deadline) { entries.remove(videoId);return null }
        return entry.media.copy(headers=entry.media.headers.toMap())
    }
    @Synchronized fun invalidate(videoId: String) { entries.remove(videoId) }
    @Synchronized fun clear() { entries.clear() }
}
