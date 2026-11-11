package com.swararia.audiolab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Pure sequential worker. The service owns exclusive execution and native cleanup. */
class BatchDownloadRunner(private val queue: DownloadQueueRepository) {
    private val canceled = AtomicBoolean(false)
    /** Pending rows cancel immediately; running resolves only after download/native cleanup returns. */
    suspend fun cancel(batchId: String) {
        canceled.set(true)
        withContext(NonCancellable) { queue.cancelBatch(batchId) }
    }
    class Control internal constructor(private val queue: DownloadQueueRepository, private val id: Long) {
        internal var committed = false
        suspend fun progress(value: Float?, stage: String) = queue.progress(id, value, stage)
        /** Production journal publisher commits track + job together; no second completion write. */
        suspend fun commitDurably(publication: suspend () -> Unit) = withContext(NonCancellable) {
            check(!committed)
            publication()
            committed = true
        }
        /** A committed MediaStore/library copy must receive its durable completion despite cancellation. */
        suspend fun commit(publication: suspend () -> Long) = withContext(NonCancellable) {
            check(!committed)
            val trackId = publication()
            queue.complete(id, trackId)
            committed = true
        }
    }
    suspend fun run(batchId: String, download: suspend (DownloadJob, Control) -> Unit) {
        try {
            while (!canceled.get()) {
                currentCoroutineContext().ensureActive()
                val job = queue.nextQueued(batchId) ?: return
                // Protect the CAS return boundary: cancellation after the SQL update must not strand RUNNING.
                if (!withContext(NonCancellable) { queue.markRunning(job.id) }) return
                val control = Control(queue, job.id)
                try {
                    currentCoroutineContext().ensureActive()
                    if (canceled.get()) throw CancellationException("Dibatalkan")
                    download(job, control)
                    check(control.committed) { "Download returned without publication" }
                } catch (cancel: CancellationException) {
                    if (!control.committed) withContext(NonCancellable) { queue.cancel(job.id) }
                    throw cancel
                } catch (_: Exception) {
                    if (!control.committed) withContext(NonCancellable) {
                        if (canceled.get()) queue.cancel(job.id)
                        else queue.fail(job.id, "Unduhan gagal. Coba ulang atau periksa sumber dan koneksi.")
                    }
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { queue.cancelBatch(batchId) }
            throw cancel
        }
    }
}
