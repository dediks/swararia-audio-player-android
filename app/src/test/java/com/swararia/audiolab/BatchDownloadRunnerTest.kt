package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class BatchDownloadRunnerTest {
    private class Queue(count: Int = 3) : DownloadQueueRepository {
        override val jobs = MutableStateFlow((1..count).map { id -> DownloadJob(id.toLong(), "batch", "https://example.org/$id", "Audio $id", null, "QUEUED", null, "Menunggu", null, null, true, 0) })
        val events = mutableListOf<String>()
        var loseClaim = false
        var claimGate: (suspend () -> Unit)? = null
        override suspend fun enqueue(requests: List<DownloadRequest>, authorized: Boolean) = "batch"
        override suspend fun nextQueued(batchId: String) = jobs.value.firstOrNull { it.batchId == batchId && it.status == "QUEUED" }
        private fun change(id: Long, action: (DownloadJob) -> DownloadJob) { jobs.value = jobs.value.map { if (it.id == id) action(it) else it } }
        override suspend fun markRunning(id: Long): Boolean { events += "claim:$id"; if (loseClaim) return false; check(jobs.value.none { it.status == "RUNNING" }); change(id) { it.copy(status = "RUNNING") }; claimGate?.invoke(); return true }
        override suspend fun progress(id: Long, progress: Float?, stage: String) { events += "progress:$id"; change(id) { it.copy(progress = progress, stage = stage) } }
        override suspend fun complete(id: Long, trackId: Long) { events += "complete:$id"; change(id) { it.copy(status = "COMPLETED", trackId = trackId) } }
        override suspend fun fail(id: Long, message: String) { events += "fail:$id"; change(id) { it.copy(status = "FAILED", error = message) } }
        override suspend fun cancel(id: Long) { events += "cancel:$id"; change(id) { it.copy(status = "CANCELED") } }
        override suspend fun cancelBatch(batchId: String) { events += "cancelBatch"; jobs.value = jobs.value.map { if (it.status == "QUEUED" && it.batchId == batchId) it.copy(status = "CANCELED") else it } }
        override suspend fun retryFailed(batchId: String) = Unit
        override suspend fun recoverInterrupted() { events += "recover" }
    }

    @Test fun failureContinuesWithoutLeakingCredentials() = runBlocking {
        val queue = Queue()
        BatchDownloadRunner(queue).run("batch") { job, control ->
            if (job.id == 2L) error("https://secret/?token=credential")
            control.commit { job.id }
        }
        assertEquals(listOf("COMPLETED", "FAILED", "COMPLETED"), queue.jobs.value.map { it.status })
        assertFalse(queue.jobs.value[1].error.orEmpty().contains("secret"))
    }

    @Test fun cancellationResolvesRunningAfterNativeCleanup() = runBlocking {
        val queue = Queue()
        try {
            BatchDownloadRunner(queue).run("batch") { _, _ ->
                try { throw CancellationException("stop") }
                finally { queue.events += "native-cleaned" }
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf("CANCELED", "CANCELED", "CANCELED"), queue.jobs.value.map { it.status })
        assertTrue(queue.events.indexOf("native-cleaned") < queue.events.indexOf("cancel:1"))
    }

    @Test fun requestCancelPersistsPendingWhileCurrentPublishes() = runBlocking {
        val queue = Queue()
        val runner = BatchDownloadRunner(queue)
        runner.run("batch") { job, control ->
            runner.cancel("batch")
            assertEquals(listOf("RUNNING", "CANCELED", "CANCELED"), queue.jobs.value.map { it.status })
            control.commit { job.id }
        }
        assertEquals(listOf("COMPLETED", "CANCELED", "CANCELED"), queue.jobs.value.map { it.status })
    }

    @Test fun completionSurvivesCallerCancellation() = runBlocking {
        val queue = Queue(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = launch {
            BatchDownloadRunner(queue).run("batch") { _, control ->
                control.commit { entered.complete(Unit); release.await(); 99L }
            }
        }
        entered.await(); worker.cancel(); release.complete(Unit); worker.join()
        assertEquals("COMPLETED", queue.jobs.value.single().status)
        assertEquals(99L, queue.jobs.value.single().trackId)
    }

    @Test fun missingPublicationIsFailureNotCompletion() = runBlocking {
        val queue = Queue(1)
        BatchDownloadRunner(queue).run("batch") { _, _ -> Unit }
        assertEquals("FAILED", queue.jobs.value.single().status)
    }

    @Test fun cancellationDuringClaimCannotStrandRunningRow() = runBlocking {
        val queue = Queue()
        val claimed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queue.claimGate = { claimed.complete(Unit); release.await() }
        val worker = launch { BatchDownloadRunner(queue).run("batch") { _, _ -> fail("Canceled claim must not download") } }
        claimed.await(); worker.cancel(); release.complete(Unit); worker.join()
        assertTrue(queue.jobs.value.all { it.status == "CANCELED" })
    }

    @Test fun exceptionAfterCompletionCannotDowngradeCommittedAudio() = runBlocking {
        val queue = Queue(1)
        BatchDownloadRunner(queue).run("batch") { _, control ->
            control.commit { 99L }
            error("Optional cleanup failed")
        }
        assertEquals("COMPLETED", queue.jobs.value.single().status)
        assertEquals(99L, queue.jobs.value.single().trackId)
    }

    @Test fun durableCommitDoesNotIssueASecondQueueCompletion() = runBlocking {
        val queue = Queue(1)
        BatchDownloadRunner(queue).run("batch") { job, control ->
            control.commitDurably { queue.complete(job.id, 99L) }
            error("Optional artwork failed")
        }
        assertEquals(listOf("claim:1", "complete:1"), queue.events)
        assertEquals("COMPLETED", queue.jobs.value.single().status)
    }

    @Test fun lostClaimReturnsWithoutSpinning() = runBlocking {
        val queue = Queue().apply { loseClaim = true }
        BatchDownloadRunner(queue).run("batch") { _, _ -> fail("Must not execute") }
        assertEquals(listOf("claim:1"), queue.events)
    }

    @Test fun sequentialOrderAndRealProgress() = runBlocking {
        val queue = Queue()
        BatchDownloadRunner(queue).run("batch") { job, control ->
            control.progress(.25f, "Mengunduh")
            control.commit { queue.events += "publish:${job.id}"; job.id + 100 }
        }
        assertEquals(listOf("claim:1", "progress:1", "publish:1", "complete:1", "claim:2", "progress:2", "publish:2", "complete:2", "claim:3", "progress:3", "publish:3", "complete:3"), queue.events)
        assertEquals(listOf(101L, 102L, 103L), queue.jobs.value.map { it.trackId })
    }
}
