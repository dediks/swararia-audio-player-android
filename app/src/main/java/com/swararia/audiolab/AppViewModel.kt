package com.swararia.audiolab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val library = (application as SwarariaApp).library
    private val searchRepository = YoutubeSearchRepository(application)
    private val searchGeneration = SearchGeneration()
    private val searchState = MutableStateFlow(YoutubeSearchState())
    val search = searchState.asStateFlow()
    private var searchJob: Job? = null
    private val previewGeneration = SearchGeneration()
    private val previewState = MutableStateFlow(PreviewState())
    val preview = previewState.asStateFlow()
    private var previewJob: Job? = null

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
        if (hit.kind != SearchKind.PLAYLISTS) return
        val current = searchState.value
        startSearch(current.query, current.provider, SearchKind.SONGS, current.sort,
            playlistId = hit.videoId, playlistTitle = hit.title)
    }
    fun leavePlaylist() {
        val current = searchState.value
        startSearch(current.query, current.provider, SearchKind.PLAYLISTS, SearchSort.RELEVANCE)
    }
    fun sortSearch(sort: SearchSort) { searchState.value = searchState.value.copy(sort = sort) }

    private fun startSearch(query: String, provider: SearchProvider, kind: SearchKind, sort: SearchSort,
        append: Boolean = false, playlistId: String? = null, playlistTitle: String? = null) {
        if (IngestStatus.state.value.busy || searchState.value.busy || previewState.value.loading) {
            message("Tunggu proses yang sedang berjalan selesai terlebih dahulu."); return
        }
        val valid = try { YoutubeSearchRules.validatedQuery(query) }
            catch (failure: IllegalArgumentException) { message(failure.message ?: "Kata pencarian tidak valid."); return }
        if (playlistId != null && runCatching { YoutubeSearchRules.validatedPlaylistId(playlistId) }.isFailure) {
            message("Playlist tidak valid."); return
        }
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
        searchJob = viewModelScope.launch {
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
            viewModelScope.launch {
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

    fun preview(hit: SearchHit) {
        if (hit.kind != SearchKind.SONGS || IngestStatus.state.value.busy || searchState.value.busy || previewState.value.loading) return
        val previous = previewJob
        val ticket = previewGeneration.next()
        previewState.value = PreviewState(loading = true, hit = hit)
        previewJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            try {
                val media = searchRepository.preview(hit)
                if (previewGeneration.isCurrent(ticket)) previewState.value = PreviewState(hit = hit, media = media)
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) {
                if (previewGeneration.isCurrent(ticket)) previewState.value = PreviewState(hit = hit,
                    error = (failure.message ?: "Preview tidak tersedia dari sumber ini.").take(300))
            } finally {
                if (previewGeneration.isCurrent(ticket) && previewState.value.loading)
                    previewState.value = previewState.value.copy(loading = false)
            }
        }
    }
    fun cancelPreview() {
        val ticket = previewGeneration.next()
        val job = previewJob
        job?.cancel()
        if (job != null && !job.isCompleted) {
            previewState.value = PreviewState(loading = true, hit = previewState.value.hit)
            viewModelScope.launch {
                job.join()
                if (previewGeneration.isCurrent(ticket)) { previewJob = null; previewState.value = PreviewState() }
            }
        } else { previewJob = null; previewState.value = PreviewState() }
    }
    fun previewError() {
        cancelPreview()
        message("Preview tidak dapat diputar. Sumber mungkin membatasi akses. Coba lagi atau pilih lagu lain.")
    }

    override fun onCleared() {
        searchGeneration.next(); previewGeneration.next()
        searchJob?.cancel(); previewJob?.cancel()
        super.onCleared()
    }
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastRename = library.lastRename.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val busy = MutableStateFlow(false)
    val organizerBusy = busy.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    fun message(text: String) { viewModelScope.launch { messageChannel.send(text) } }
    fun rename(trackId: Long, title: String, onSuccess: () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val actual = library.rename(trackId, title)
                onSuccess(); messageChannel.send("Nama diubah: $actual")
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) { messageChannel.send(failure.message ?: "Perubahan nama gagal.") }
              finally { busy.value = false }
        }
    }
    fun undoRename() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { messageChannel.send("Nama dikembalikan: ${library.undoLastRename()}") }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messageChannel.send(failure.message ?: "Pembatalan gagal.") }
            finally { busy.value = false }
        }
    }
}
