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
    /** Invoke once at service/process startup, never during a live worker. */
    suspend fun recoverInterrupted()
}
