package com.swararia.audiolab

/** Room journal bridges non-transactional MediaStore. Uncertainty keeps the job RUNNING (not retryable). */
class DownloadQueuePublication(private val store: Store) {
    data class Media(val pending: Boolean, val bytes: Long)
    interface Store {
        suspend fun begin(id: Long, name: String)
        suspend fun create(name: String): String
        suspend fun associate(id: Long, uri: String)
        suspend fun ready(id: Long, bytes: Long)
        suspend fun locate(name: String): String?
        /** Must verify exact managed URI, path, owner and fail closed on provider errors. */
        suspend fun inspect(uri: String): Media?
        suspend fun discardPending(uri: String)
        suspend fun abandon(id: Long)
        suspend fun makeVisible(uri: String)
        /** Track insertion/reuse AND job completion in ONE Room transaction. */
        suspend fun finish(id: Long, uri: String, bytes: Long): Long
    }
    suspend fun publish(id: Long, name: String, copy: suspend (String) -> Long): Long {
        store.begin(id, name) // intent BEFORE creation closes the insert->URI journal crash gap
        val uri = store.create(name)
        store.associate(id, uri)
        val bytes = copy(uri)
        check(bytes > 0)
        store.ready(id, bytes) // only a closed, complete copy may become visible
        return finish(id, uri, bytes)
    }
    private suspend fun finish(id: Long, uri: String, bytes: Long): Long {
        val media = checkNotNull(store.inspect(uri)) { "Salinan jurnal tidak ditemukan." }
        check(media.bytes == bytes && bytes > 0) { "Ukuran salinan jurnal berubah." }
        if (media.pending) store.makeVisible(uri)
        // No deletion on failure here: already visible audio is reconciled on the next service start.
        return store.finish(id, uri, bytes)
    }
    suspend fun recover(job: DownloadJob) {
        val name = job.publicationName ?: return
        val uri = job.pendingUri ?: store.locate(name)
        if (job.publicationBytes != null) {
            checkNotNull(uri) { "Salinan siap publikasi tidak ditemukan; jangan unduh ulang otomatis." }
            finish(job.id, uri, job.publicationBytes)
        } else {
            if (uri != null) {
                val media = store.inspect(uri)
                if (media != null) {
                    check(media.pending) { "Salinan terlihat tanpa jurnal lengkap; perlu pemeriksaan." }
                    store.discardPending(uri) // verified hidden partial only
                }
            }
            store.abandon(job.id)
        }
    }
}
