package com.swararia.audiolab

/** Metadata/stream boundary. Implementations own I/O; callers own screen/session state. */
interface AudioSearchRepository {
    suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind,
        offset: Int = 0, playlistId: String? = null): SearchPage
    suspend fun preview(hit: SearchHit): PreviewMedia
}

data class SearchPage(val hits: List<SearchHit>, val rawCount: Int, val incompleteCount: Int)
