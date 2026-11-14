package com.swararia.audiolab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity state adapter; dependencies are supplied at the platform composition root. */
class AppViewModel(
    private val library: AudioLibraryRepository,
    private val searchRepository: AudioSearchRepository,
    val ingest: StateFlow<IngestState>,
    private val downloadQueue: DownloadQueueRepository,
    previewPlayback: PreviewPlayback? = null,
    desktopRemoteApi: DesktopRemoteApi,
    desktopRemoteStore: DesktopRemotePersistence? = null,
    private val remoteServer: AndroidRemoteServerController? = null,
    desktopRemoteMediaImporter: DesktopRemoteMediaImporter? = null,
) : ViewModel() {
    val desktopRemote = DesktopRemoteSession(
        desktopRemoteApi,
        viewModelScope,
        persistence = desktopRemoteStore,
        mediaImporter = desktopRemoteMediaImporter,
    )
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
    override fun onCleared() { desktopRemote.release(); session.close(); super.onCleared() }
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val pendingPlaylistOrders = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    val playlists = combine(library.playlists, pendingPlaylistOrders) { list, overrides ->
        if (overrides.isEmpty()) list
        else list.map { playlist ->
            val candidate = overrides[playlist.id]
            if (candidate != null && candidate.size == playlist.trackIds.size && candidate.toSet() == playlist.trackIds.toSet()) {
                playlist.copy(trackIds = candidate)
            } else {
                playlist
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastRename = library.lastRename.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val busy = MutableStateFlow(false)
    val organizerBusy = busy.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    val jobs = downloadQueue.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playbackDeletion = PlaybackDeletion()
    private val operations: UpgradeOperations = UpgradeOperations(downloadQueue, viewModelScope,
        { session.canStartOnlineOperation() }, { ingest.value.busy }, { jobs.value },
        { id ->
            playbackDeletion.delete(id) { library.delete(id) }
            remoteServer?.notifyLibraryDeletionsChanged()
        }, ::message)
    val operationBusy = operations.busy
    val remoteServerState: StateFlow<RemoteServerUiState> =
        remoteServer?.state ?: MutableStateFlow(RemoteServerUiState())
    val remoteServerActions = RemoteServerUiActions(
        onRefreshInterfaces = ::refreshServerInterfaces,
        onStartServer = ::startRemoteServer,
        onStopServer = ::stopRemoteServer,
        onRefreshNativeInvite = ::refreshServerNativeInvite,
        onRefreshWebPin = ::refreshServerWebPin,
        onSetMediaTransferEnabled = ::setServerMediaTransferEnabled,
        onSetPauseTransferDuringPlayback = ::setServerPauseTransferDuringPlayback,
        onApproveDevice = ::approveServerDevice,
        onSetDeviceTransferAllowed = ::setServerDeviceTransferAllowed,
        onDenyOrRevokeDevice = ::denyOrRevokeServerDevice,
        onClearError = ::clearServerError,
    )
    init {
        operations.initialize()
        remoteServer?.attachDeletedTrackChecker { id -> !playbackDeletion.canPlay(id) }
        viewModelScope.launch {
            try {
                library.scanMissingMetadata()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
            }
        }
    }
    fun refreshServerInterfaces() { remoteServer?.refreshInterfaces() }
    fun startRemoteServer(bindIp: String, port: Int) { remoteServer?.startServer(bindIp, port) }
    fun stopRemoteServer() { remoteServer?.stopServer() }
    fun refreshServerNativeInvite() { remoteServer?.refreshNativeInvite() }
    fun refreshServerWebPin() { remoteServer?.refreshWebPin() }
    fun setServerMediaTransferEnabled(allowed: Boolean) { remoteServer?.setMediaTransferEnabled(allowed) }
    fun setServerPauseTransferDuringPlayback(pause: Boolean) { remoteServer?.setPauseTransferDuringPlayback(pause) }
    fun approveServerDevice(deviceId: String, role: String, allowTransfer: Boolean) {
        remoteServer?.approveDevice(deviceId, role, allowTransfer)
    }
    fun setServerDeviceTransferAllowed(deviceId: String, allow: Boolean) {
        remoteServer?.setDeviceTransferAllowed(deviceId, allow)
    }
    fun denyOrRevokeServerDevice(deviceId: String, deny: Boolean = false) {
        remoteServer?.denyOrRevokeDevice(deviceId, deny)
    }
    fun clearServerError() { remoteServer?.clearError() }
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
    fun deleteBatch(trackIds: List<Long>, onSuccess: () -> Unit) = operations.deleteBatch(trackIds, onSuccess)
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

    private val reorderMutex = Mutex()

    fun createPlaylist(name: String, initialTrackId: Long? = null) =
        createPlaylistWithTracks(name, listOfNotNull(initialTrackId))

    fun createPlaylistWithTracks(name: String, trackIds: List<Long>) = runPlaylistAction("Gagal membuat playlist.") {
        val cleanName = LibraryRules.validatedPlaylistName(name)
        val playlistId = library.createPlaylist(cleanName)
        val distinctIds = trackIds.distinct()
        if (distinctIds.isNotEmpty()) {
            val added = library.addTracksToPlaylist(playlistId, distinctIds)
            "Playlist \"$cleanName\" dibuat ($added track ditambahkan)."
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

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) = addTracksToPlaylist(playlistId, listOf(trackId))

    fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) = runPlaylistAction("Gagal menambahkan track ke playlist.") {
        val added = library.addTracksToPlaylist(playlistId, trackIds)
        if (added > 0) "$added track ditambahkan ke playlist."
        else "Semua track terpilih sudah ada di playlist."
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = runPlaylistAction("Gagal mengeluarkan track dari playlist.") {
        library.removeTrackFromPlaylist(playlistId, trackId)
        "Track dikeluarkan dari playlist."
    }

    fun reorderPlaylistTracks(playlistId: Long, orderedTrackIds: List<Long>) {
        if (operations.busy.value) return
        val snapshot = orderedTrackIds.toList()
        pendingPlaylistOrders.update { it + (playlistId to snapshot) }
        viewModelScope.launch {
            try {
                reorderMutex.withLock {
                    library.reorderPlaylistTracks(playlistId, snapshot)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                messageChannel.send(failure.message ?: "Gagal menyimpan urutan rundown.")
            } finally {
                pendingPlaylistOrders.update { current ->
                    if (current[playlistId] == snapshot) current - playlistId else current
                }
            }
        }
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
