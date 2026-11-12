package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Single-threaded presentation-operation owner. Storage/native effects are injected and testable. */
class UpgradeOperations(
    private val queue: DownloadQueueRepository,
    private val scope: CoroutineScope,
    private val canStart: () -> Boolean,
    private val ingestActive: () -> Boolean,
    private val currentJobs: () -> List<DownloadJob>,
    private val deleteAudio: suspend (Long) -> Unit,
    private val message: (String) -> Unit,
) {
    private val state=MutableStateFlow(true)
    val busy=state.asStateFlow()
    fun initialize() {
        scope.launch {
            try { if(!ingestActive()) queue.recoverInterrupted() }
            catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Queue belum dapat dimuat. Coba buka ulang aplikasi.")}
            finally {state.value=false}
        }
    }
    private fun allowed():Boolean {
        if(state.value || !canStart()) {message("Tunggu proses yang sedang berjalan.");return false}
        return true
    }
    fun enqueue(requests:List<DownloadRequest>,authorized:Boolean,start:(String)->Unit) {
        if(!allowed())return
        if(!authorized) {message("Konfirmasi izin penggunaan audio terlebih dahulu.");return}
        if(currentJobs().any {it.status in setOf("QUEUED","RUNNING")}) {
            message("Queue sebelumnya belum selesai. Jalankan atau batalkan dahulu.");return
        }
        state.value=true
        scope.launch {
            var batch:String?=null
            try {batch=queue.enqueue(requests,true);start(batch)}
            catch(c:CancellationException) {
                withContext(NonCancellable) {batch?.let {queue.cancelBatch(it)}}
                throw c
            } catch(_:Exception) {
                batch?.let {runCatching {queue.cancelBatch(it)}}
                message("Download belum dapat dimulai. Periksa queue atau coba lagi.")
            } finally {state.value=false}
        }
    }
    fun retry(batchId:String,start:(String)->Unit) {
        if(!validBatch(batchId) || !allowed())return
        state.value=true
        scope.launch {
            try {queue.retryFailed(batchId);start(batchId)}
            catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Queue belum dapat dijalankan. Coba lagi.")}
            finally {state.value=false}
        }
    }
    fun cancelQueued(batchId:String) {
        if(!validBatch(batchId))return
        scope.launch {
            try {queue.cancelBatch(batchId)}
            catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Pembatalan queue belum tersimpan. Coba lagi.")}
        }
    }
    fun delete(trackId:Long,onSuccess:()->Unit) {
        if(!allowed())return
        state.value=true
        scope.launch {
            try {deleteAudio(trackId);onSuccess();message("Audio dihapus.")}
            catch(c:CancellationException) {throw c}
            catch(f:Exception) {message(f.message?.take(300) ?: "Audio belum dapat dihapus.")}
            finally {state.value=false}
        }
    }
    private fun validBatch(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
}
