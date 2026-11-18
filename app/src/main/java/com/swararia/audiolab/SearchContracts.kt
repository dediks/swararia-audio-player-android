package com.swararia.audiolab

/** Metadata/stream boundary. Implementations own I/O; callers own screen/session state. */
interface AudioSearchRepository {
    suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind,
        offset: Int = 0, playlistId: String? = null): SearchPage
    suspend fun playlistPage(playlistId: String, offset: Int = 0, limit: Int = YoutubeSearchRules.MAX_RESULTS): SearchPage =
        searchPage("", SearchProvider.YOUTUBE_MUSIC, SearchKind.SONGS, offset, playlistId)
    suspend fun preview(hit: SearchHit): PreviewMedia
    suspend fun audioQuality(videoId: String): AudioStreamQuality? = null
    fun invalidatePreview(videoId: String) {}
}

data class SearchPage(
    val hits: List<SearchHit>,
    val rawCount: Int,
    val incompleteCount: Int,
    val playlistMeta: PlaylistHeaderMeta? = null,
)
