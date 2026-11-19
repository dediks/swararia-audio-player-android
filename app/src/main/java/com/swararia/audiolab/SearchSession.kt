package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private var qualityJob: Job? = null
    private var playlistParent: YoutubeSearchState? = null
    private data class SearchCacheKey(val query: String, val provider: SearchProvider, val kind: SearchKind)
    private val resultCache = object : LinkedHashMap<SearchCacheKey, YoutubeSearchState>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchCacheKey, YoutubeSearchState>?): Boolean = size > 8
    }
    private val previewOwner = playback ?: PreviewSession(searchRepository, scope, reportMessage)
    private val previewState = previewOwner.preview
    val preview = previewState

    fun search(query: String, provider: SearchProvider, kind: SearchKind = SearchKind.SONGS,
        sort: SearchSort = SearchSort.RELEVANCE) = startSearch(query, provider, kind, sort)

    /** Switches provider/kind/query seamlessly, cancelling any running search/preview and reusing cached tab pages. */
    fun switchSearch(
        query: String,
        provider: SearchProvider,
        kind: SearchKind = SearchKind.SONGS,
        sort: SearchSort = SearchSort.RELEVANCE,
    ) {
        if (ingestBusy()) {
            reportMessage("Tunggu proses audio selesai terlebih dahulu.")
            return
        }
        val valid = try { YoutubeSearchRules.validatedQuery(query) }
            catch (failure: IllegalArgumentException) { reportMessage(failure.message ?: "Kata pencarian tidak valid."); return }
        playlistParent = null
        val cacheKey = SearchCacheKey(valid, provider, kind)
        val previousJob = searchJob
        val previewLoading = previewState.value.loading
        cancelPreview()
        qualityJob?.cancel()
        if (previousJob?.isCompleted != false && !previewLoading) {
            resultCache[cacheKey]?.let { cached ->
                val ticket = searchGeneration.next()
                searchJob = null
                searchState.value = cached.copy(sort = sort, busy = false, loadingMore = false, error = null)
                launchQualityProbe(ticket, cached.results, cacheKey)
                return
            }
        }
        val ticket = searchGeneration.next()
        previousJob?.cancel()
        searchState.value = YoutubeSearchState(
            busy = true,
            query = valid,
            provider = provider,
            kind = kind,
            sort = sort,
            results = emptyList(),
            hasSearched = true,
            loadedThrough = 0,
            hasMore = false,
            loadingMore = false,
            incompleteCount = 0,
            playlistId = null,
            playlistTitle = null,
        )
        launchSearchJob(ticket, valid, provider, kind, offset = 0, playlistId = null,
            previousJob = previousJob, waitForCleanup = true, useCache = true)
    }

    fun loadMore() {
        val current = searchState.value
        if (!current.hasMore || current.busy) return
        startSearch(
            current.query, current.provider, current.kind, current.sort,
            append = true,
            playlistId = current.playlistId,
            playlistTitle = current.playlistTitle,
            playlistChannel = current.playlistChannel,
            playlistThumbnailUrl = current.playlistThumbnailUrl,
            playlistTotalCount = current.playlistTotalCount,
        )
    }
    fun retrySearch() {
        val current = searchState.value
        startSearch(current.query, current.provider, current.kind, current.sort,
            append = current.loadedThrough > 0, playlistId = current.playlistId, playlistTitle = current.playlistTitle,
            playlistChannel = current.playlistChannel, playlistThumbnailUrl = current.playlistThumbnailUrl,
            playlistTotalCount = current.playlistTotalCount)
    }
    fun openPlaylist(hit: SearchHit) {
        if (hit.kind != SearchKind.PLAYLISTS || !canStartOnlineOperation()) return
        val current = searchState.value
        if (current.playlistId == null) playlistParent = current
        startSearch(
            current.query, current.provider, SearchKind.SONGS, current.sort,
            playlistId = hit.videoId,
            playlistTitle = hit.title,
            playlistChannel = hit.channel,
            playlistThumbnailUrl = hit.thumbnailUrl,
            playlistTotalCount = hit.itemCount,
        )
    }
    fun openPlaylistUrl(playlistId: String) {
        val validId = try { YoutubeSearchRules.validatedPlaylistId(playlistId.trim()) }
            catch (failure: IllegalArgumentException) {
                reportMessage(failure.message ?: "ID playlist tidak valid."); return
            }
        openPlaylist(
            SearchHit(
                videoId = validId,
                title = "Memuat playlist…",
                channel = null,
                durationSeconds = null,
                thumbnailUrl = null,
                url = "https://www.youtube.com/playlist?list=$validId",
                kind = SearchKind.PLAYLISTS,
            ),
        )
    }
    fun leavePlaylist() {
        val current = searchState.value
        val parent = playlistParent
        if (parent == null) {
            if (current.query.isNotBlank()) {
                startSearch(current.query, current.provider, SearchKind.PLAYLISTS, SearchSort.RELEVANCE)
            } else {
                searchGeneration.next()
                searchJob?.cancel()
                qualityJob?.cancel()
                cancelPreview()
                searchJob = null
                searchState.value = YoutubeSearchState(provider = current.provider)
            }
            return
        }
        val ticket = searchGeneration.next()
        val pendingSearch = searchJob
        val pendingPreview = previewState.value.loading
        pendingSearch?.cancel()
        qualityJob?.cancel()
        cancelPreview()
        fun restore() {
            if (searchGeneration.isCurrent(ticket)) {
                searchJob = null
                playlistParent = null
                searchState.value = parent
                launchQualityProbe(ticket, parent.results, cacheKey = SearchCacheKey(parent.query, parent.provider, parent.kind))
            }
        }
        if (pendingSearch?.isCompleted != false && !pendingPreview) restore()
        else {
            searchState.value = current.copy(busy = true, loadingMore = false, error = null)
            scope.launch { pendingSearch?.join(); previewState.first { !it.loading }; restore() }
        }
    }
    fun sortSearch(sort: SearchSort) { searchState.value = searchState.value.copy(sort = sort) }

    private fun startSearch(
        query: String,
        provider: SearchProvider,
        kind: SearchKind,
        sort: SearchSort,
        append: Boolean = false,
        playlistId: String? = null,
        playlistTitle: String? = null,
        playlistChannel: String? = null,
        playlistThumbnailUrl: String? = null,
        playlistTotalCount: Int? = null,
    ) {
        if (!canStartOnlineOperation()) {
            reportMessage("Tunggu proses yang sedang berjalan selesai terlebih dahulu."); return
        }
        val valid = if (playlistId == null) {
            try { YoutubeSearchRules.validatedQuery(query) }
            catch (failure: IllegalArgumentException) { reportMessage(failure.message ?: "Kata pencarian tidak valid."); return }
        } else {
            if (query.any { it.isISOControl() }) {
                reportMessage("Kata pencarian mengandung karakter yang tidak didukung."); return
            }
            query.trim().take(200)
        }
        if (playlistId != null && runCatching { YoutubeSearchRules.validatedPlaylistId(playlistId) }.isFailure) {
            reportMessage("Playlist tidak valid."); return
        }
        if (playlistId == null && !append) playlistParent = null
        if (!append) cancelPreview()
        qualityJob?.cancel()
        val previous = searchState.value
        val offset = if (append) previous.loadedThrough else 0
        if (offset >= YoutubeSearchRules.MAX_RESULTS) return
        val ticket = searchGeneration.next()
        searchState.value = YoutubeSearchState(
            busy = true,
            query = valid,
            provider = provider,
            kind = kind,
            sort = sort,
            results = if (append) previous.results else emptyList(),
            hasSearched = if (playlistId != null && !append) previous.hasSearched else true,
            loadedThrough = offset,
            hasMore = append && previous.hasMore,
            loadingMore = append,
            incompleteCount = if (append) previous.incompleteCount else 0,
            playlistId = playlistId,
            playlistTitle = playlistTitle,
            playlistChannel = playlistChannel,
            playlistThumbnailUrl = playlistThumbnailUrl,
            playlistTotalCount = playlistTotalCount,
        )
        launchSearchJob(ticket, valid, provider, kind, offset, playlistId,
            previousJob = null, waitForCleanup = false, useCache = false)
    }

    private fun launchSearchJob(
        ticket: Long,
        valid: String,
        provider: SearchProvider,
        kind: SearchKind,
        offset: Int,
        playlistId: String?,
        previousJob: Job?,
        waitForCleanup: Boolean,
        useCache: Boolean,
    ) {
        searchJob = scope.launch {
            try {
                if (waitForCleanup) {
                    previousJob?.join()
                    previewState.first { !it.loading }
                    if (!searchGeneration.isCurrent(ticket)) return@launch
                    if (useCache && playlistId == null && offset == 0) {
                        resultCache[SearchCacheKey(valid, provider, kind)]?.let { cached ->
                            searchState.value = cached.copy(
                                sort = searchState.value.sort,
                                busy = false,
                                loadingMore = false,
                                error = null,
                            )
                            launchQualityProbe(ticket, cached.results, SearchCacheKey(valid, provider, kind))
                            return@launch
                        }
                    }
                    if (ingestBusy()) {
                        reportMessage("Tunggu proses audio selesai terlebih dahulu.")
                        return@launch
                    }
                }
                val pageLimit = if (playlistId != null) YoutubeSearchRules.MAX_RESULTS else YoutubeSearchRules.RESULT_LIMIT
                val page = if (playlistId != null) {
                    searchRepository.playlistPage(playlistId, offset, pageLimit)
                } else {
                    searchRepository.searchPage(valid, provider, kind, offset, null)
                }
                if (searchGeneration.isCurrent(ticket)) {
                    val loaded = minOf(offset + pageLimit, YoutubeSearchRules.MAX_RESULTS)
                    val currentState = searchState.value
                    val nextState = currentState.copy(
                        results = YoutubeSearchRules.merge(currentState.results, page.hits),
                        loadedThrough = loaded,
                        hasMore = page.rawCount >= pageLimit && loaded < YoutubeSearchRules.MAX_RESULTS,
                        incompleteCount = currentState.incompleteCount + page.incompleteCount,
                        playlistTitle = page.playlistMeta?.title ?: currentState.playlistTitle,
                        playlistChannel = page.playlistMeta?.channel ?: currentState.playlistChannel,
                        playlistThumbnailUrl = page.playlistMeta?.thumbnailUrl ?: currentState.playlistThumbnailUrl,
                        playlistTotalCount = page.playlistMeta?.totalCount ?: currentState.playlistTotalCount,
                        error = null,
                    )
                    searchState.value = nextState
                    val activeCacheKey = if (playlistId == null) SearchCacheKey(valid, provider, kind) else null
                    if (activeCacheKey != null) {
                        resultCache[activeCacheKey] = nextState.copy(busy = false, loadingMore = false)
                    }
                    launchQualityProbe(ticket, page.hits, activeCacheKey)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (searchGeneration.isCurrent(ticket)) {
                    val userMessage = if (failure is Error) {
                        "Mesin pencarian tidak kompatibel atau gagal dijalankan di perangkat ini."
                    } else {
                        failure.message?.takeIf { it.isNotBlank() } ?: "Pencarian belum berhasil."
                    }
                    searchState.value = searchState.value.copy(
                        error = userMessage.take(300),
                    )
                }
            } finally {
                if (searchGeneration.isCurrent(ticket)) {
                    searchState.value = searchState.value.copy(busy = false, loadingMore = false)
                }
            }
        }
    }

    fun cancelSearch() {
        val ticket = searchGeneration.next()
        qualityJob?.cancel()
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

    private fun launchQualityProbe(
        ticket: Long,
        hits: List<SearchHit>,
        cacheKey: SearchCacheKey?,
    ) {
        val targets = hits
            .asSequence()
            .filter { it.kind == SearchKind.SONGS && it.bitrateKbps == null }
            .map { it.videoId }
            .distinct()
            .take(YoutubeSearchRules.RESULT_LIMIT)
            .toList()
        if (targets.isEmpty() || ingestBusy()) return
        qualityJob?.cancel()
        qualityJob = scope.launch {
            val permits = Semaphore(3)
            val updates = Channel<Pair<String, AudioStreamQuality>>(targets.size)
            val producers = launch {
                try {
                    coroutineScope {
                        targets.forEach { videoId ->
                            launch {
                                permits.withPermit {
                                    if (!searchGeneration.isCurrent(ticket) || ingestBusy()) return@withPermit
                                    val quality = try {
                                        searchRepository.audioQuality(videoId)
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    if (quality != null) updates.trySend(videoId to quality)
                                }
                            }
                        }
                    }
                } finally {
                    updates.close()
                }
            }
            for ((videoId, quality) in updates) {
                if (!searchGeneration.isCurrent(ticket)) break
                val current = searchState.value
                var changed = false
                val updatedHits = current.results.map { hit ->
                    if (hit.videoId == videoId && hit.bitrateKbps == null) {
                        changed = true
                        hit.copy(bitrateKbps = quality.bitrateKbps, audioCodec = quality.audioCodec ?: hit.audioCodec)
                    } else hit
                }
                if (changed) {
                    val updatedState = current.copy(results = updatedHits)
                    searchState.value = updatedState
                    if (cacheKey != null) {
                        resultCache[cacheKey] = updatedState.copy(busy = false, loadingMore = false)
                    }
                }
            }
            producers.join()
        }
    }

    fun preview(hit: SearchHit) { if (canStartOnlineOperation()) previewOwner.preview(hit) }
    fun cancelPreview() = previewOwner.cancelPreview()
    fun previewError() = previewOwner.previewError()

    fun close() {
        searchGeneration.next()
        qualityJob?.cancel()
        searchJob?.cancel(); previewOwner.close()
    }
}
