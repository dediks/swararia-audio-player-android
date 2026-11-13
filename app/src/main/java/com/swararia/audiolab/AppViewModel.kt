package com.swararia.audiolab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Activity state adapter; dependencies are supplied at the platform composition root. */
class AppViewModel(
    private val library: AudioLibraryRepository,
    private val searchRepository: AudioSearchRepository,
    val ingest: StateFlow<IngestState>,
    private val downloadQueue: DownloadQueueRepository,
    previewPlayback: PreviewPlayback? = null,
    desktopRemoteApi: DesktopRemoteApi,
) : ViewModel() {
    val desktopRemote = DesktopRemoteSession(desktopRemoteApi, viewModelScope)
    private val session: SearchSession = SearchSession(searchRepository, viewModelScope,
        { ingest.value.busy || busy.value || operations.busy.value }, ::message, previewPlayback)
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
    fun previewError() {
        preview.value.hit?.videoId?.let(searchRepository::invalidatePreview)
        session.previewError()
    }
    override fun onCleared() { desktopRemote.disconnect(); session.close(); super.onCleared() }
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastRename = library.lastRename.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val busy = MutableStateFlow(false)
    val organizerBusy = busy.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    val jobs = downloadQueue.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playbackDeletion = PlaybackDeletion()
    private val operations: UpgradeOperations = UpgradeOperations(downloadQueue, viewModelScope,
        { session.canStartOnlineOperation() }, { ingest.value.busy }, { jobs.value },
        { id -> playbackDeletion.delete(id) { library.delete(id) } }, ::message)
    val operationBusy = operations.busy
    init {
        operations.initialize()
        viewModelScope.launch { runCatching { library.scanMissingMetadata() } }
    }
    fun enqueueDownloads(requests: List<DownloadRequest>, authorized: Boolean, start: (String) -> Unit) {
        if (!canStartOnlineOperation()) { message("Tunggu proses yang sedang berjalan."); return }
        cancelPreview(); operations.enqueue(requests, authorized, start)
    }
    fun retryDownloads(batchId: String, start: (String) -> Unit) {
        if (!canStartOnlineOperation()) return
        cancelPreview(); operations.retry(batchId, start)
    }
    fun cancelQueuedBatch(batchId: String) = operations.cancelQueued(batchId)
    fun delete(trackId: Long, onSuccess: () -> Unit) = operations.delete(trackId, onSuccess)
    fun message(text: String) { viewModelScope.launch { messageChannel.send(text) } }
    fun rename(trackId: Long, title: String, onSuccess: () -> Unit) {
        if (busy.value || operations.busy.value) return
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
        if (busy.value || operations.busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { messageChannel.send("Nama dikembalikan: ${library.undoLastRename()}") }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messageChannel.send(failure.message ?: "Pembatalan gagal.") }
            finally { busy.value = false }
        }
    }

    fun createPlaylist(name: String, initialTrackId: Long? = null) = runPlaylistAction("Gagal membuat playlist.") {
        val cleanName = LibraryRules.validatedPlaylistName(name)
        val playlistId = library.createPlaylist(cleanName)
        if (initialTrackId != null) {
            library.addTrackToPlaylist(playlistId, initialTrackId)
            "Playlist \"$cleanName\" dibuat & lagu ditambahkan."
        } else {
            "Playlist \"$cleanName\" dibuat."
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) = runPlaylistAction("Gagal mengubah nama playlist.") {
        val cleanName = LibraryRules.validatedPlaylistName(name)
        library.renamePlaylist(playlistId, cleanName)
        "Playlist diganti menjadi \"$cleanName\"."
    }

    fun deletePlaylist(playlistId: Long) = runPlaylistAction("Gagal menghapus playlist.") {
        library.deletePlaylist(playlistId)
        "Playlist dihapus."
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) = runPlaylistAction("Gagal menambahkan lagu ke playlist.") {
        library.addTrackToPlaylist(playlistId, trackId)
        "Lagu ditambahkan ke playlist."
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = runPlaylistAction("Gagal mengeluarkan lagu dari playlist.") {
        library.removeTrackFromPlaylist(playlistId, trackId)
        "Lagu dikeluarkan dari playlist."
    }

    private fun runPlaylistAction(fallbackError: String, block: suspend () -> String) {
        if (busy.value || operations.busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { messageChannel.send(block()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messageChannel.send(failure.message ?: fallbackError) }
            finally { busy.value = false }
        }
    }
}
