package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Android-free state owner. Invoke commands on the owning scope's single UI thread.
 * The caller owns the scope; close() cancels only this session's work, never sibling jobs.
 * Native repository cancellation must finish before busy becomes false.
 */
class SearchSession(
    private val searchRepository: AudioSearchRepository,
    private val scope: CoroutineScope,
    private val ingestBusy: () -> Boolean,
    private val reportMessage: (String) -> Unit,
    playback: PreviewPlayback? = null,
) {
    fun canStartOnlineOperation(): Boolean = OnlineWorkRules.canStart(
        searchState.value.busy, ingestBusy(), previewState.value.loading)
    private val searchGeneration = SearchGeneration()
    private val searchState = MutableStateFlow(YoutubeSearchState())
    val search = searchState.asStateFlow()
    private var searchJob: Job? = null
    private var playlistParent: YoutubeSearchState? = null
    private val previewOwner = playback ?: PreviewSession(searchRepository, scope, reportMessage)
    private val previewState = previewOwner.preview
    val preview = previewState

    fun search(query: String, provider: SearchProvider, kind: SearchKind = SearchKind.SONGS,
        sort: SearchSort = SearchSort.RELEVANCE) = startSearch(query, provider, kind, sort)

    fun loadMore() {
        val current = searchState.value
        if (!current.hasMore || current.busy) return
        startSearch(current.query, current.provider, current.kind, current.sort, append = true,
            playlistId = current.playlistId, playlistTitle = current.playlistTitle)
    }
    fun retrySearch() {
        val current = searchState.value
        startSearch(current.query, current.provider, current.kind, current.sort,
            append = current.loadedThrough > 0, playlistId = current.playlistId, playlistTitle = current.playlistTitle)
    }
    fun openPlaylist(hit: SearchHit) {
        if (hit.kind != SearchKind.PLAYLISTS || !canStartOnlineOperation()) return
        val current = searchState.value
        if (current.playlistId == null) playlistParent = current
        startSearch(current.query, current.provider, SearchKind.SONGS, current.sort,
            playlistId = hit.videoId, playlistTitle = hit.title)
    }
    fun leavePlaylist() {
        val current = searchState.value
        val parent = playlistParent
        if (parent == null) {
            startSearch(current.query, current.provider, SearchKind.PLAYLISTS, SearchSort.RELEVANCE)
            return
        }
        val ticket = searchGeneration.next()
        val pendingSearch = searchJob
        val pendingPreview = previewState.value.loading
        pendingSearch?.cancel()
        cancelPreview()
        fun restore() {
            if (searchGeneration.isCurrent(ticket)) {
                searchJob = null
                playlistParent = null
                searchState.value = parent
            }
        }
        if (pendingSearch?.isCompleted != false && !pendingPreview) restore()
        else {
            searchState.value = current.copy(busy = true, loadingMore = false, error = null)
            scope.launch { pendingSearch?.join(); previewState.first { !it.loading }; restore() }
        }
    }
    fun sortSearch(sort: SearchSort) { searchState.value = searchState.value.copy(sort = sort) }

    private fun startSearch(query: String, provider: SearchProvider, kind: SearchKind, sort: SearchSort,
        append: Boolean = false, playlistId: String? = null, playlistTitle: String? = null) {
        if (!canStartOnlineOperation()) {
            reportMessage("Tunggu proses yang sedang berjalan selesai terlebih dahulu."); return
        }
        val valid = try { YoutubeSearchRules.validatedQuery(query) }
            catch (failure: IllegalArgumentException) { reportMessage(failure.message ?: "Kata pencarian tidak valid."); return }
        if (playlistId != null && runCatching { YoutubeSearchRules.validatedPlaylistId(playlistId) }.isFailure) {
            reportMessage("Playlist tidak valid."); return
        }
        if (playlistId == null && !append) playlistParent = null
        cancelPreview()
        val previous = searchState.value
        val offset = if (append) previous.loadedThrough else 0
        if (offset >= YoutubeSearchRules.MAX_RESULTS) return
        val ticket = searchGeneration.next()
        searchState.value = YoutubeSearchState(busy = true, query = valid, provider = provider,
            kind = kind, sort = sort, results = if (append) previous.results else emptyList(),
            hasSearched = true, loadedThrough = offset, hasMore = append && previous.hasMore,
            loadingMore = append, incompleteCount = if (append) previous.incompleteCount else 0,
            playlistId = playlistId, playlistTitle = playlistTitle)
        searchJob = scope.launch {
            try {
                val page = searchRepository.searchPage(valid, provider, kind, offset, playlistId)
                if (searchGeneration.isCurrent(ticket)) {
                    val loaded = minOf(offset + YoutubeSearchRules.RESULT_LIMIT, YoutubeSearchRules.MAX_RESULTS)
                    searchState.value = searchState.value.copy(
                        results = YoutubeSearchRules.merge(searchState.value.results, page.hits),
                        loadedThrough = loaded, hasMore = page.rawCount >= YoutubeSearchRules.RESULT_LIMIT && loaded < YoutubeSearchRules.MAX_RESULTS,
                        incompleteCount = searchState.value.incompleteCount + page.incompleteCount, error = null)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) {
                if (searchGeneration.isCurrent(ticket)) searchState.value = searchState.value.copy(
                    error = (failure.message ?: "Pencarian belum berhasil.").take(300))
            } finally {
                if (searchGeneration.isCurrent(ticket)) searchState.value = searchState.value.copy(busy = false, loadingMore = false)
            }
        }
    }

    fun cancelSearch() {
        val ticket = searchGeneration.next()
        val job = searchJob
        job?.cancel()
        if (job != null && !job.isCompleted) {
            searchState.value = searchState.value.copy(busy = true, loadingMore = false, error = null)
            scope.launch {
                job.join()
                if (searchGeneration.isCurrent(ticket)) {
                    searchJob = null
                    searchState.value = searchState.value.copy(busy = false, loadingMore = false)
                }
            }
        } else {
            searchJob = null
            searchState.value = searchState.value.copy(busy = false, loadingMore = false, error = null)
        }
    }

    fun preview(hit: SearchHit) { if (canStartOnlineOperation()) previewOwner.preview(hit) }
    fun cancelPreview() = previewOwner.cancelPreview()
    fun previewError() = previewOwner.previewError()

    fun close() {
        searchGeneration.next()
        searchJob?.cancel(); previewOwner.close()
    }
}
