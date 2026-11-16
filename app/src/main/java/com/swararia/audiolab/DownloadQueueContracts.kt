package com.swararia.audiolab

import kotlinx.coroutines.flow.Flow

interface DownloadQueueRepository {
    val jobs: Flow<List<DownloadJob>>
    suspend fun enqueue(requests: List<DownloadRequest>, authorized: Boolean): String
    suspend fun nextQueued(batchId: String): DownloadJob?
    suspend fun markRunning(id: Long): Boolean
    suspend fun progress(id: Long, progress: Float?, stage: String)
    suspend fun complete(id: Long, trackId: Long)
    suspend fun fail(id: Long, message: String)
    /** Caller stops native work and resolves publication before canceling RUNNING. */
    suspend fun cancel(id: Long)
    suspend fun cancelBatch(batchId: String)
    suspend fun retryFailed(batchId: String)
    suspend fun retryJob(id: Long): String? = null
    suspend fun retryAllFailed(): String? = null
    suspend fun cancelQueuedJob(id: Long) = cancel(id)
    suspend fun removeJob(id: Long) = Unit
    suspend fun clearFinished(): Int = 0
    suspend fun nextAnyQueued(): DownloadJob? = null
    suspend fun nextQueuedBatchId(): String? = null
    suspend fun cancelAllQueued(): Int = 0
    suspend fun pauseRunningJob(id: Long): Boolean = false
    suspend fun skipRunningJob(id: Long): Boolean = false
    suspend fun moveQueuedUp(id: Long): Boolean = false
    suspend fun moveQueuedToFront(id: Long): Boolean = false
    suspend fun requeueJob(id: Long): String? = retryJob(id)
    suspend fun requeueJobs(ids: List<Long>): String? = null
    suspend fun cancelQueuedJobs(ids: List<Long>): Int = 0
    suspend fun removeJobs(ids: List<Long>): Int = 0
    /** Invoke once at service/process startup, never during a live worker. */
    suspend fun recoverInterrupted()
}

internal object IngestFailureMessages {
    const val GENERIC = "Unduhan gagal. Coba ulang atau periksa sumber dan koneksi."
    const val UNSUPPORTED_16KB_PAGE_SIZE =
        "Perangkat memakai halaman memori 16 KB yang belum didukung mesin unduh bawaan."
    const val INSUFFICIENT_STORAGE =
        "Ruang penyimpanan HP hampir penuh. Kosongkan minimal 150 MB lalu coba lagi."
    const val IMPORT_FAILED =
        "File audio tidak dapat diimpor. Pastikan format didukung dan tidak rusak."

    private val allowedUserMessages = setOf(
        UNSUPPORTED_16KB_PAGE_SIZE,
        INSUFFICIENT_STORAGE,
        IMPORT_FAILED,
    )

    fun sanitize(rawMessage: String?): String =
        rawMessage?.takeIf { it in allowedUserMessages } ?: GENERIC
}

internal class IngestUserMessageException(val userMessage: String) : Exception(userMessage)
