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
    private fun canRun(allowAppend:Boolean, busyMessage:String):Boolean {
        if(!allowAppend) return allowed()
        if(state.value) {message(busyMessage);return false}
        return true
    }
    fun enqueue(requests:List<DownloadRequest>,authorized:Boolean,start:(String)->Unit) {
        enqueueInternal(requests, authorized, allowActiveQueue = false, start = start)
    }
    fun enqueueOrAppend(requests:List<DownloadRequest>,authorized:Boolean,start:(String)->Unit) {
        enqueueInternal(requests, authorized, allowActiveQueue = true, start = start)
    }
    private fun enqueueInternal(
        requests:List<DownloadRequest>,
        authorized:Boolean,
        allowActiveQueue:Boolean,
        start:(String)->Unit,
    ) {
        if(!allowActiveQueue) {
            if(!allowed())return
        } else {
            if(state.value) {message("Tunggu proses antrean selesai disimpan.");return}
        }
        if(!authorized) {message("Konfirmasi izin penggunaan audio terlebih dahulu.");return}
        if(!allowActiveQueue && currentJobs().any {it.status in setOf("QUEUED","RUNNING")}) {
            message("Queue sebelumnya belum selesai. Jalankan atau batalkan dahulu.");return
        }
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            var batch:String?=null
            try {
                batch=queue.enqueue(requests,true)
                if (alreadyRunning) {
                    val count = requests.size
                    message(if (count == 1) "Ditambahkan ke antrean aktif." else "$count audio ditambahkan ke antrean aktif.")
                }
                start(batch)
            }
            catch(c:CancellationException) {
                withContext(NonCancellable) {batch?.let {queue.cancelBatch(it)}}
                throw c
            } catch(e:IllegalArgumentException) {
                message(e.message?.take(200) ?: "Audio sudah ada di antrean aktif.")
            } catch(_:Exception) {
                batch?.let {runCatching {queue.cancelBatch(it)}}
                message("Download belum dapat dimulai. Periksa queue atau coba lagi.")
            } finally {state.value=false}
        }
    }
    fun retry(batchId:String, allowAppend:Boolean=true, start:(String)->Unit) {
        if(!validBatch(batchId) || !canRun(allowAppend, "Tunggu proses antrean selesai."))return
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            try {
                queue.retryFailed(batchId)
                if (alreadyRunning) message("Item gagal ditambahkan ke antrean aktif.")
                start(batchId)
            }
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
    fun retryJob(jobId:Long, allowAppend:Boolean=true, start:(String)->Unit) {
        if(!canRun(allowAppend, "Tunggu proses antrean selesai."))return
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            try {
                val batch=queue.retryJob(jobId)
                if(batch!=null) {
                    if (alreadyRunning) message("Item gagal ditambahkan ke antrean aktif.")
                    start(batch)
                } else message("Item gagal belum siap diulang atau sudah di antrean.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Queue belum dapat dijalankan. Coba lagi.")}
            finally {state.value=false}
        }
    }
    fun retryAllFailed(allowAppend:Boolean=true, start:(String)->Unit) {
        if(!canRun(allowAppend, "Tunggu proses antrean selesai."))return
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            try {
                val batch=queue.retryAllFailed()
                if(batch!=null) {
                    if (alreadyRunning) message("Item gagal ditambahkan ke antrean aktif.")
                    start(batch)
                } else message("Tidak ada item gagal yang siap diulang.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Queue belum dapat dijalankan. Coba lagi.")}
            finally {state.value=false}
        }
    }
    fun cancelJob(jobId:Long) {
        scope.launch {
            try {queue.cancelQueuedJob(jobId)}
            catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Pembatalan item belum tersimpan. Coba lagi.")}
        }
    }
    fun removeJob(jobId:Long) {
        scope.launch {
            try {queue.removeJob(jobId)}
            catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Riwayat unduhan belum dapat dihapus.")}
        }
    }
    fun clearFinished() {
        scope.launch {
            try {
                val removed=queue.clearFinished()
                if(removed>0) message("$removed riwayat selesai dibersihkan.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Riwayat unduhan belum dapat dibersihkan.")}
        }
    }
    fun moveJobUp(jobId:Long) {
        scope.launch {
            try {
                if(!queue.moveQueuedUp(jobId)) message("Item sudah berada di urutan paling atas.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Urutan antrean belum dapat diubah.")}
        }
    }
    fun moveJobToFront(jobId:Long) {
        scope.launch {
            try {
                if(queue.moveQueuedToFront(jobId)) message("Diprioritaskan ke antrean berikutnya.")
                else message("Item sudah berada di urutan paling atas.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Urutan antrean belum dapat diubah.")}
        }
    }
    fun requeueJob(jobId:Long, allowAppend:Boolean=true, start:(String)->Unit) {
        if(!allowAppend && !allowed())return
        if(allowAppend && state.value) {message("Tunggu proses antrean selesai.");return}
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            try {
                val batch=queue.requeueJob(jobId)
                if(batch!=null) {
                    if(alreadyRunning) message("Ditambahkan kembali ke antrean aktif.")
                    start(batch)
                } else {
                    message("Audio sudah ada di antrean aktif.")
                }
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Item belum dapat diunduh ulang.")}
            finally {state.value=false}
        }
    }
    fun requeueJobs(jobIds:List<Long>, start:(String)->Unit) {
        val ids = jobIds.distinct()
        if(ids.isEmpty() || state.value)return
        val alreadyRunning = ingestActive()
        state.value=true
        scope.launch {
            try {
                val batch=queue.requeueJobs(ids)
                if(batch!=null) {
                    if(alreadyRunning) message("${ids.size} item ditambahkan kembali ke antrean.")
                    start(batch)
                } else {
                    message("Tidak ada item baru yang perlu diunduh ulang.")
                }
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Sebagian item belum dapat diunduh ulang.")}
            finally {state.value=false}
        }
    }
    fun cancelQueuedJobs(jobIds:List<Long>) {
        val ids=jobIds.distinct()
        if(ids.isEmpty())return
        scope.launch {
            try {
                val canceled=queue.cancelQueuedJobs(ids)
                if(canceled>0) message("$canceled antrean dibatalkan.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Pembatalan item belum tersimpan.")}
        }
    }
    fun removeJobs(jobIds:List<Long>) {
        val ids=jobIds.distinct()
        if(ids.isEmpty())return
        scope.launch {
            try {
                val removed=queue.removeJobs(ids)
                if(removed>0) message("$removed riwayat dihapus dari daftar.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Riwayat unduhan belum dapat dihapus.")}
        }
    }
    fun continueQueue(start:(String)->Unit) {
        if(!allowed())return
        state.value=true
        scope.launch {
            try {
                val batch=queue.nextQueuedBatchId() ?: currentJobs().firstOrNull {it.status=="QUEUED"}?.batchId
                if(batch!=null) start(batch) else message("Tidak ada antrean yang menunggu.")
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {message("Antrean belum dapat dilanjutkan.")}
            finally {state.value=false}
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
    fun deleteBatch(trackIds:List<Long>,onSuccess:()->Unit) {
        val distinct=trackIds.distinct()
        if(distinct.isEmpty() || !allowed())return
        state.value=true
        scope.launch {
            var deletedCount=0
            var firstError:Exception?=null
            try {
                for(id in distinct) {
                    try {deleteAudio(id);deletedCount++}
                    catch(c:CancellationException) {throw c}
                    catch(f:Exception) {if(firstError==null) firstError=f}
                }
                if(deletedCount>0) onSuccess()
                val summary=when {
                    firstError==null -> "$deletedCount audio dihapus."
                    deletedCount==0 -> firstError?.message?.take(300) ?: "Audio belum dapat dihapus."
                    else -> "$deletedCount audio dihapus; sebagian gagal: ${firstError?.message?.take(120).orEmpty()}"
                }
                message(summary)
            } finally {state.value=false}
        }
    }
    private fun validBatch(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
}
