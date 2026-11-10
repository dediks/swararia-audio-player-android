package com.swararia.audiolab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val library = (application as SwarariaApp).library
    private val searchRepository = YoutubeSearchRepository(application)
    private val searchGeneration = SearchGeneration()
    private val searchState = MutableStateFlow(YoutubeSearchState())
    val search = searchState.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String, provider: SearchProvider) {
        val ticket = searchGeneration.next()
        searchJob?.cancel()
        searchJob = null
        val valid = try { YoutubeSearchRules.validatedQuery(query) }
        catch (failure: IllegalArgumentException) {
            searchState.value = YoutubeSearchState(query = query.take(200), provider = provider,
                error = failure.message, hasSearched = true)
            return
        }
        if (IngestStatus.state.value.busy) {
            searchState.value = YoutubeSearchState(query = valid, provider = provider,
                error = "Tunggu proses audio selesai sebelum mencari.", hasSearched = true)
            return
        }
        searchState.value = YoutubeSearchState(busy = true, query = valid, provider = provider, hasSearched = true)
        searchJob = viewModelScope.launch {
            try {
                val hits = searchRepository.search(valid, provider)
                if (searchGeneration.isCurrent(ticket)) searchState.value = searchState.value.copy(results = hits, error = null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (searchGeneration.isCurrent(ticket)) searchState.value = searchState.value.copy(
                    results = emptyList(), error = (failure.message ?: "Pencarian belum berhasil.").take(300))
            } finally {
                if (searchGeneration.isCurrent(ticket)) searchState.value = searchState.value.copy(busy = false)
            }
        }
    }

    fun cancelSearch() {
        searchGeneration.next()
        searchJob?.cancel()
        searchJob = null
        searchState.value = searchState.value.copy(busy = false, error = null,
            hasSearched = searchState.value.results.isNotEmpty())
    }

    override fun onCleared() {
        searchGeneration.next()
        searchJob?.cancel()
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
                onSuccess()
                messageChannel.send("Nama diubah: $actual")
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
