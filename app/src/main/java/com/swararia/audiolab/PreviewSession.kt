package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URI

/** Commands are main-thread confined. Production resolver and playback callbacks live in the service. */
interface PreviewPlayback {
    val preview: StateFlow<PreviewState>
    fun preview(hit: SearchHit)
    fun cancelPreview()
    fun previewError()
    fun close()
}

class PreviewSession(
    private val searchRepository: AudioSearchRepository,
    private val scope: CoroutineScope,
    private val reportMessage: (String) -> Unit,
    private val onResolved: (PreviewMedia) -> Unit = {},
    private val onChanged: () -> Unit = {},
) : PreviewPlayback {
    private val previewGeneration = SearchGeneration()
    private val previewState = MutableStateFlow(PreviewState())
    override val preview = previewState.asStateFlow()
    private var previewJob: Job? = null
    override fun preview(hit: SearchHit) {
        if (hit.kind != SearchKind.SONGS || preview.value.loading) return
        val previous = previewJob
        val ticket = previewGeneration.next()
        val started = System.nanoTime()
        previewState.value = PreviewState(loading = true, hit = hit, requestStartedNanos = started)
        onChanged()
        previewJob = scope.launch {
            previous?.cancelAndJoin()
            try {
                val media = searchRepository.preview(hit)
                if (previewGeneration.isCurrent(ticket)) {
                    PreviewLease.validate(media)
                    previewState.value = PreviewState(hit = hit, media = media, requestStartedNanos = started)
                    onResolved(media) // Direct dispatch: no Compose collector/lifecycle dependency.
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                if (previewGeneration.isCurrent(ticket)) {
                    searchRepository.invalidatePreview(hit.videoId)
                    previewState.value = PreviewState(hit = hit, error = "Preview tidak tersedia dari sumber ini.")
                }
            } finally {
                if (previewGeneration.isCurrent(ticket) && previewState.value.loading)
                    previewState.value = previewState.value.copy(loading = false)
                onChanged()
            }
        }
    }
    override fun cancelPreview() {
        val ticket = previewGeneration.next()
        val job = previewJob
        job?.cancel()
        if (job != null && !job.isCompleted) {
            previewState.value = PreviewState(loading = true, hit = previewState.value.hit, canceling = true)
            scope.launch {
                job.join()
                if (previewGeneration.isCurrent(ticket)) { previewJob = null; previewState.value = PreviewState(); onChanged() }
            }
        } else { previewJob = null; previewState.value = PreviewState() }
        onChanged()
    }
    override fun previewError() {
        val hit = previewState.value.hit ?: return
        searchRepository.invalidatePreview(hit.videoId)
        val ticket = previewGeneration.next()
        val job = previewJob
        val failure = "Preview tidak dapat diputar. Sumber mungkin membatasi akses. Coba lagi atau pilih lagu lain."
        job?.cancel()
        val cleaning = job != null && !job.isCompleted
        previewState.value = PreviewState(hit = hit, error = failure, loading = cleaning, canceling = cleaning)
        if (cleaning) scope.launch {
            job?.join()
            if (previewGeneration.isCurrent(ticket)) {
                previewJob = null
                previewState.value = PreviewState(hit = hit, error = failure)
                onChanged()
            }
        } else previewJob = null
        onChanged()
        reportMessage(failure)
    }
    override fun close() { previewGeneration.next(); previewJob?.cancel() }
}

/** Immutable local snapshot, including deletion reconciliation while it is hidden by Preview. */
class PreviewLocalQueue<T>(val items: List<T>, private val id: String?, private val index: Int,
    private val position: Long, private val key: (T) -> String) {
    val restoreIndex: Int get() = if (items.isEmpty()) 0 else items.indexOfFirst { key(it) == id }.takeIf { it >= 0 } ?: index.coerceIn(items.indices)
    val restorePosition: Long get() = if (items.getOrNull(restoreIndex)?.let(key) == id) position else 0L
    fun forgetDeleted(deleted: String) = PreviewLocalQueue(items.filterNot { key(it) == deleted }, id, index, position, key)
}

/** RAM-only request lease. Same generation never starts twice; stale stops cannot stop replacements. */
class PreviewLease {
    var token: String = ""; private set
    var generation: Long = 0; private set
    fun begin(token: String, generation: Long): Boolean {
        if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(token) || generation <= this.generation) return false
        this.token = token; this.generation = generation
        return true
    }
    fun matches(token: String?, generation: Long) = this.token.isNotEmpty() && this.token == token && this.generation == generation
    companion object {
        fun validate(media: PreviewMedia) {
            require(Regex("[A-Za-z0-9_-]{11}").matches(media.videoId))
            OnlinePreviewRules.streamUri(media.url)
            require(media.headers.size <= 4)
            media.headers.forEach { (name, value) ->
                require(name in setOf("User-Agent", "Referer", "Origin", "Accept-Language"))
                require(value.length <= 2048 && value.none { it.isISOControl() })
                if (name == "Referer" || name == "Origin") {
                    val uri = URI(value)
                    require(uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("youtube.com", "www.youtube.com", "music.youtube.com"))
                }
            }
        }
    }
}
