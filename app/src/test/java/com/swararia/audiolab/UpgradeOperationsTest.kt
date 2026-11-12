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
    companion object { const val ID="11111111-1111-4111-8111-111111111111" }
}
