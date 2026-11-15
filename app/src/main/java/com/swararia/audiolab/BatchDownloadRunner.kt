package com.swararia.audiolab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Pure sequential worker. The service owns exclusive execution and native cleanup. */
class SkipCurrentJobException : Exception("Item dilewati")

class PauseQueueException : Exception("Antrean dijeda")

class BatchDownloadRunner(
    private val queue: DownloadQueueRepository,
    private val isPaused: () -> Boolean = { false },
    private val nextQueuedOverride: (suspend (String) -> DownloadJob?)? = null,
) {
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
            while (!canceled.get() && !isPaused()) {
                currentCoroutineContext().ensureActive()
                val job = nextQueuedOverride?.invoke(batchId) ?: queue.nextQueued(batchId) ?: return
                // Protect the CAS return boundary: cancellation after the SQL update must not strand RUNNING.
                if (!withContext(NonCancellable) { queue.markRunning(job.id) }) return
                val control = Control(queue, job.id)
                try {
                    currentCoroutineContext().ensureActive()
                    if (canceled.get()) throw CancellationException("Dibatalkan")
                    if (isPaused()) throw PauseQueueException()
                    download(job, control)
                    check(control.committed) { "Download returned without publication" }
                } catch (_: SkipCurrentJobException) {
                    if (!control.committed) withContext(NonCancellable) {
                        if (!queue.skipRunningJob(job.id)) queue.cancel(job.id)
                    }
                    continue
                } catch (_: PauseQueueException) {
                    if (!control.committed) withContext(NonCancellable) {
                        queue.pauseRunningJob(job.id)
                    }
                    return
                } catch (cancel: CancellationException) {
                    if (!control.committed) withContext(NonCancellable) {
                        if (isPaused() && !canceled.get()) queue.pauseRunningJob(job.id)
                        else queue.cancel(job.id)
                    }
                    if (isPaused() && !canceled.get()) return
                    throw cancel
                } catch (failure: Exception) {
                    if (!control.committed) withContext(NonCancellable) {
                        when {
                            canceled.get() -> queue.cancel(job.id)
                            isPaused() -> queue.pauseRunningJob(job.id)
                            failure is IngestUserMessageException ->
                                queue.fail(job.id, IngestFailureMessages.sanitize(failure.userMessage))
                            else -> queue.fail(job.id, IngestFailureMessages.GENERIC)
                        }
                    }
                    if (isPaused() && !canceled.get()) return
                }
            }
        } catch (cancel: CancellationException) {
            if (!isPaused() || canceled.get()) {
                withContext(NonCancellable) { queue.cancelBatch(batchId) }
            }
            throw cancel
        }
    }
}
