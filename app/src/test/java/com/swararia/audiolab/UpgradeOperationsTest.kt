package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class UpgradeOperationsTest {
    private class Queue : DownloadQueueRepository {
        override val jobs=MutableStateFlow(emptyList<DownloadJob>())
        val calls=mutableListOf<String>()
        var gate:CompletableDeferred<Unit>?=null
        override suspend fun enqueue(requests:List<DownloadRequest>,authorized:Boolean):String { calls.add("enqueue");gate?.await();return ID }
        override suspend fun nextQueued(batchId:String):DownloadJob?=null
        override suspend fun markRunning(id:Long)=true
        override suspend fun progress(id:Long,progress:Float?,stage:String) {}
        override suspend fun complete(id:Long,trackId:Long) {}
        override suspend fun fail(id:Long,message:String) {}
        override suspend fun cancel(id:Long) {}
        override suspend fun cancelBatch(batchId:String) { calls.add("cancel:$batchId") }
        override suspend fun retryFailed(batchId:String) { calls.add("retry:$batchId") }
        override suspend fun retryJob(id:Long):String? { calls.add("retryJob:$id"); return ID }
        override suspend fun retryAllFailed():String? { calls.add("retryAll"); return ID }
        override suspend fun cancelQueuedJob(id:Long) { calls.add("cancelJob:$id") }
        override suspend fun removeJob(id:Long) { calls.add("removeJob:$id") }
        override suspend fun clearFinished():Int { calls.add("clearFinished"); return 2 }
        override suspend fun moveQueuedUp(id:Long):Boolean { calls.add("moveUp:$id"); return true }
        override suspend fun moveQueuedToFront(id:Long):Boolean { calls.add("moveFront:$id"); return true }
        override suspend fun requeueJob(id:Long):String? { calls.add("requeue:$id"); return ID }
        override suspend fun requeueJobs(ids:List<Long>):String? { calls.add("requeueAll:${ids.joinToString(",")}"); return ID }
        override suspend fun cancelQueuedJobs(ids:List<Long>):Int { calls.add("cancelAll:${ids.joinToString(",")}"); return ids.size }
        override suspend fun removeJobs(ids:List<Long>):Int { calls.add("removeAll:${ids.joinToString(",")}"); return ids.size }
        override suspend fun nextQueuedBatchId():String? = ID
        override suspend fun recoverInterrupted() { calls.add("recover") }
    }
    private class Fixture:AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val queue=Queue();val messages=mutableListOf<String>();var ingest=false;var deleted=0L
        val ops=UpgradeOperations(queue,scope,{!ingest},{ingest},{queue.jobs.value},{deleted=it},{messages.add(it)})
        init { ops.initialize() }
        override fun close() { scope.cancel() }
    }
    @Test(timeout=5000) fun consentAndExclusiveDispatchAreCheckedBeforeEnqueue()=runBlocking {
        Fixture().use { f ->
            f.queue.calls.clear();val request=listOf(DownloadRequest("https://example.invalid/audio","Fixture"))
            f.ops.enqueue(request,false) {};assertTrue(f.queue.calls.isEmpty())
            f.queue.gate=CompletableDeferred();var starts=0
            f.ops.enqueue(request,true) {starts++};assertTrue(f.ops.busy.value)
            f.ops.enqueue(request,true) {starts++};assertEquals(listOf("enqueue"),f.queue.calls)
            f.queue.gate!!.complete(Unit);yield();assertEquals(1,starts);assertFalse(f.ops.busy.value)
        }
    }
    @Test(timeout=5000) fun serviceStartFailureCancelsPersistedPendingBatch()=runBlocking {
        Fixture().use { f ->
            f.ops.enqueue(listOf(DownloadRequest("https://example.invalid/audio","Fixture")),true) {error("start failed")}
            assertTrue(f.queue.calls.contains("cancel:$ID"));assertFalse(f.ops.busy.value)
            assertTrue(f.messages.isNotEmpty())
        }
    }
    @Test(timeout=5000) fun retryKeepsBatchIdentityAndDeleteCallsSuccessOnlyAfterStorage()=runBlocking {
        Fixture().use { f ->
            var started="";f.ops.retry(ID) {started=it}
            assertEquals(ID,started);assertTrue(f.queue.calls.contains("retry:$ID"))
            f.ops.delete(7L) {assertEquals(7L,f.deleted)}
            assertFalse(f.ops.busy.value)
        }
    }
    @Test(timeout=5000) fun recoveryDoesNotTouchLiveNativeWorker()=runBlocking {
        val q=Queue();val s=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try { val o=UpgradeOperations(q,s,{false},{true},{emptyList()},{},{})
            o.initialize();assertTrue(q.calls.isEmpty());assertFalse(o.busy.value)
        } finally {s.cancel()}
    }
    @Test(timeout=5000) fun batchDeleteDeduplicatesIdsAndHandlesPartialFailures()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val queue=Queue()
        val deletedIds=mutableListOf<Long>()
        val messages=mutableListOf<String>()
        var succeeded=false
        val ops=UpgradeOperations(
            queue, scope, {true}, {false}, {emptyList()},
            deleteAudio = { id ->
                if (id == 2L) throw IllegalStateException("locked")
                deletedIds.add(id)
            },
            message = { messages.add(it) },
        )
        try {
            ops.initialize()
            ops.deleteBatch(listOf(1L, 2L, 1L, 3L)) { succeeded = true }
            assertEquals(listOf(1L, 3L), deletedIds)
            assertTrue(succeeded)
            assertFalse(ops.busy.value)
            assertTrue(messages.last().contains("2 audio dihapus"))
            assertTrue(messages.last().contains("sebagian gagal"))
        } finally { scope.cancel() }
    }
    @Test(timeout=5000) fun granularDownloadManagementDispatchesAndCleansFinished()=runBlocking {
        Fixture().use { f ->
            var started=""
            f.ops.retryJob(42L) { started=it }
            assertEquals(ID, started)
            assertTrue(f.queue.calls.contains("retryJob:42"))

            started=""
            f.ops.retryAllFailed { started=it }
            assertEquals(ID, started)
            assertTrue(f.queue.calls.contains("retryAll"))

            f.ops.cancelJob(7L)
            assertTrue(f.queue.calls.contains("cancelJob:7"))

            f.ops.removeJob(8L)
            assertTrue(f.queue.calls.contains("removeJob:8"))

            f.ops.clearFinished()
            assertTrue(f.queue.calls.contains("clearFinished"))
            assertTrue(f.messages.last().contains("2 riwayat"))
        }
    }
    @Test(timeout=5000) fun appendWhileIngestActiveAndQueueReorderingDispatchCleanly()=runBlocking {
        Fixture().use { f ->
            f.ingest = true
            var startedBatch = ""
            val req = listOf(DownloadRequest("https://example.invalid/new", "Added"))
            f.ops.enqueueOrAppend(req, true) { startedBatch = it }
            assertEquals(ID, startedBatch)
            assertTrue(f.queue.calls.contains("enqueue"))
            assertTrue(f.messages.last().contains("antrean aktif"))

            f.ops.moveJobUp(11L)
            f.ops.moveJobToFront(12L)
            f.ops.requeueJobs(listOf(21L, 22L)) { startedBatch = it }
            f.ops.cancelQueuedJobs(listOf(31L, 32L))
            f.ops.removeJobs(listOf(41L, 42L))

            assertTrue(f.queue.calls.contains("moveUp:11"))
            assertTrue(f.queue.calls.contains("moveFront:12"))
            assertTrue(f.queue.calls.contains("requeueAll:21,22"))
            assertTrue(f.queue.calls.contains("cancelAll:31,32"))
            assertTrue(f.queue.calls.contains("removeAll:41,42"))

            var retriedBatch = ""
            f.ops.retry(ID) { retriedBatch = it }
            assertEquals(ID, retriedBatch)
            assertTrue(f.queue.calls.contains("retry:$ID"))

            retriedBatch = ""
            f.ops.retryAllFailed { retriedBatch = it }
            assertEquals(ID, retriedBatch)
            assertTrue(f.queue.calls.contains("retryAll"))
            assertTrue(f.messages.last().contains("antrean aktif"))
        }
    }
    companion object { const val ID="11111111-1111-4111-8111-111111111111" }
}
