package com.swararia.audiolab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Activity state adapter; dependencies are supplied at the platform composition root. */
class AppViewModel(
    private val library: AudioLibraryRepository,
    searchRepository: AudioSearchRepository,
    val ingest: StateFlow<IngestState>,
) : ViewModel() {
    private val session = SearchSession(searchRepository, viewModelScope, { ingest.value.busy }, ::message)
    val search = session.search
    val preview = session.preview
    fun canStartOnlineOperation() = session.canStartOnlineOperation()
    fun search(query: String, provider: SearchProvider, kind: SearchKind = SearchKind.SONGS,
        sort: SearchSort = SearchSort.RELEVANCE) = session.search(query, provider, kind, sort)
    fun loadMore() = session.loadMore()
    fun retrySearch() = session.retrySearch()
    fun openPlaylist(hit: SearchHit) = session.openPlaylist(hit)
    fun leavePlaylist() = session.leavePlaylist()
    fun sortSearch(sort: SearchSort) = session.sortSearch(sort)
    fun cancelSearch() = session.cancelSearch()
    fun preview(hit: SearchHit) = session.preview(hit)
    fun cancelPreview() = session.cancelPreview()
    fun previewError() = session.previewError()
    override fun onCleared() { session.close(); super.onCleared() }
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
