package com.swararia.audiolab

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface DownloadDao {
    @Query(
        "SELECT * FROM download_jobs ORDER BY " +
            "CASE status WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'FAILED' THEN 2 WHEN 'CANCELED' THEN 3 ELSE 4 END ASC, " +
            "CASE WHEN status = 'QUEUED' THEN createdAt ELSE -createdAt END ASC, " +
            "CASE WHEN status = 'QUEUED' THEN id ELSE -id END ASC LIMIT 120"
    )
    fun observeRecentJobs(): Flow<List<DownloadJob>>
    @Query("SELECT * FROM download_jobs WHERE batchId = (SELECT batchId FROM download_jobs ORDER BY id DESC LIMIT 1) ORDER BY id ASC")
    fun observeLatestBatch(): Flow<List<DownloadJob>>
    @Query("INSERT INTO download_jobs(batchId,sourceUrl,title,thumbnailUrl,status,progress,stage,error,trackId,authorized,createdAt) SELECT :batchId,:sourceUrl,:title,CASE WHEN instr(:thumbnailUrl, char(0)) = 0 AND length(CAST(:thumbnailUrl AS BLOB)) <= 4096 AND :thumbnailUrl NOT GLOB '*[?#]*' AND (:thumbnailUrl GLOB 'https://i.ytimg.com/*' OR :thumbnailUrl GLOB 'https://i[12349].ytimg.com/*' OR :thumbnailUrl GLOB 'https://yt3.ggpht.com/*' OR :thumbnailUrl GLOB 'https://lh3.googleusercontent.com/*' OR :thumbnailUrl GLOB 'https://yt3.googleusercontent.com/*') THEN :thumbnailUrl ELSE NULL END,'QUEUED',NULL,'Menunggu',NULL,NULL,1,:createdAt WHERE length(CAST(:sourceUrl AS BLOB)) = 43 AND instr(:sourceUrl,char(0))=0 AND substr(:sourceUrl,1,32) = 'https://www.youtube.com/watch?v=' AND substr(:sourceUrl,33) NOT GLOB '*[^A-Za-z0-9_-]*'")
    suspend fun insertSafe(batchId: String, sourceUrl: String, title: String, thumbnailUrl: String?, createdAt: Long): Long
    @Query("UPDATE download_jobs SET title = CASE WHEN :title IS NOT NULL AND length(trim(:title)) > 0 AND title IN ('Audio', 'Audio dari link') THEN :title ELSE title END, thumbnailUrl = COALESCE(thumbnailUrl, CASE WHEN instr(:thumbnailUrl, char(0)) = 0 AND length(CAST(:thumbnailUrl AS BLOB)) <= 4096 AND :thumbnailUrl NOT GLOB '*[?#]*' AND (:thumbnailUrl GLOB 'https://i.ytimg.com/*' OR :thumbnailUrl GLOB 'https://i[12349].ytimg.com/*' OR :thumbnailUrl GLOB 'https://yt3.ggpht.com/*' OR :thumbnailUrl GLOB 'https://lh3.googleusercontent.com/*' OR :thumbnailUrl GLOB 'https://yt3.googleusercontent.com/*') THEN :thumbnailUrl ELSE NULL END) WHERE id = :id AND status = 'RUNNING'")
    suspend fun updateResolvedMetadata(id: Long, title: String?, thumbnailUrl: String?): Int
    suspend fun insert(job: DownloadJob): Long {
        require(job.authorized && job.sourceUrl == DownloadQueueRules.validatedPublicSourceUrl(job.sourceUrl))
        return insertSafe(job.batchId, job.sourceUrl, job.title, DownloadQueueRules.durableThumbnail(job.thumbnailUrl), job.createdAt)
    }
    @Query("SELECT sourceUrl FROM download_jobs WHERE status IN ('QUEUED','RUNNING')") suspend fun activeUrls(): List<String>
    @Query("SELECT * FROM download_jobs WHERE batchId = :batchId AND status = 'QUEUED' ORDER BY createdAt ASC, id ASC LIMIT 1") suspend fun nextQueued(batchId: String): DownloadJob?
    @Query("UPDATE download_jobs SET status = 'RUNNING', progress = NULL, stage = 'Memulai', error = NULL WHERE id = :id AND status = 'QUEUED' AND authorized = 1 AND NOT EXISTS (SELECT 1 FROM download_jobs WHERE status = 'RUNNING')")
    suspend fun markRunning(id: Long): Int
    @Query("UPDATE download_jobs SET progress = :progress, stage = :stage WHERE id = :id AND status = 'RUNNING'") suspend fun progress(id: Long, progress: Float?, stage: String)
    @Query("UPDATE download_jobs SET status = 'COMPLETED', trackId = :trackId, progress = 1.0, stage = 'Selesai', error = NULL, publicationName = NULL, pendingUri = NULL, publicationBytes = NULL WHERE id = :id AND status = 'RUNNING'") suspend fun complete(id: Long, trackId: Long): Int
    @Query("UPDATE download_jobs SET status = 'FAILED', progress = NULL, stage = 'Gagal', error = :message WHERE id = :id AND status = 'RUNNING' AND publicationName IS NULL") suspend fun fail(id: Long, message: String)
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dibatalkan', error = NULL WHERE id = :id AND status IN ('QUEUED','RUNNING') AND publicationName IS NULL") suspend fun cancel(id: Long)
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dibatalkan', error = NULL WHERE batchId = :batchId AND status = 'QUEUED'") suspend fun cancelBatch(batchId: String)
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dibatalkan', error = NULL WHERE id = :id AND status = 'QUEUED' AND publicationName IS NULL") suspend fun cancelQueuedJob(id: Long): Int
    @Query("UPDATE download_jobs SET status = 'QUEUED', progress = NULL, stage = 'Menunggu', error = NULL, trackId = NULL WHERE batchId = :batchId AND status = 'FAILED' AND publicationName IS NULL AND trackId IS NULL AND NOT EXISTS (SELECT 1 FROM download_jobs AS active WHERE active.sourceUrl = download_jobs.sourceUrl AND active.status IN ('QUEUED','RUNNING'))") suspend fun retryFailed(batchId: String)
    @Query("SELECT batchId FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC LIMIT 1") suspend fun firstQueuedBatchId(): String?
    @Query("SELECT * FROM download_jobs WHERE status = 'FAILED' AND publicationName IS NULL AND trackId IS NULL ORDER BY id DESC") suspend fun failedRetryable(): List<DownloadJob>
    @Query("UPDATE download_jobs SET batchId = :targetBatchId, status = 'QUEUED', progress = NULL, stage = 'Menunggu', error = NULL, trackId = NULL WHERE id = :id AND status = 'FAILED' AND publicationName IS NULL AND trackId IS NULL AND NOT EXISTS (SELECT 1 FROM download_jobs AS active WHERE active.sourceUrl = download_jobs.sourceUrl AND active.status IN ('QUEUED','RUNNING'))") suspend fun retrySingleIntoBatch(id: Long, targetBatchId: String): Int
    @Query("DELETE FROM download_jobs WHERE id = :id AND status IN ('COMPLETED', 'FAILED', 'CANCELED') AND publicationName IS NULL AND pendingUri IS NULL") suspend fun removeTerminalJob(id: Long): Int
    @Query("DELETE FROM download_jobs WHERE status IN ('COMPLETED', 'CANCELED') AND publicationName IS NULL AND pendingUri IS NULL") suspend fun clearFinishedJobs(): Int
    @Query("UPDATE download_jobs SET status = 'FAILED', progress = NULL, stage = 'Terhenti', error = 'Unduhan terputus; coba ulang.' WHERE status = 'RUNNING' AND publicationName IS NULL") suspend fun recoverInterrupted()
    @Query("SELECT * FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC LIMIT 1") suspend fun nextAnyQueued(): DownloadJob?
    @Query("SELECT batchId FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC LIMIT 1") suspend fun nextQueuedBatchId(): String?
    @Query("SELECT * FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC") suspend fun queuedJobs(): List<DownloadJob>
    @Query("SELECT MAX(createdAt) FROM download_jobs WHERE status = 'QUEUED'") suspend fun maxQueuedCreatedAt(): Long?
    @Query("UPDATE download_jobs SET createdAt = :createdAt WHERE id = :id AND status = 'QUEUED'") suspend fun updateQueuedCreatedAt(id: Long, createdAt: Long): Int
    @Query("UPDATE download_jobs SET status = 'CANCELED', stage = 'Dibatalkan' WHERE status = 'QUEUED'") suspend fun cancelAllQueued(): Int
    @Query("UPDATE download_jobs SET status = 'QUEUED', progress = NULL, stage = 'Dijeda · menunggu', error = NULL WHERE id = :id AND status = 'RUNNING' AND publicationName IS NULL AND trackId IS NULL") suspend fun pauseRunningToQueued(id: Long): Int
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dilewati', error = NULL WHERE id = :id AND status = 'RUNNING' AND publicationName IS NULL AND trackId IS NULL") suspend fun skipRunningJob(id: Long): Int
    @Query("UPDATE download_jobs SET batchId = :targetBatch, status = 'QUEUED', progress = NULL, stage = 'Menunggu', error = NULL, createdAt = :createdAt WHERE id = :id AND status = 'FAILED' AND publicationName IS NULL AND trackId IS NULL") suspend fun retryIntoBatchAt(id: Long, targetBatch: String, createdAt: Long): Int
    @Query("SELECT * FROM download_jobs WHERE publicationName IS NOT NULL AND status = 'RUNNING'") suspend fun publications(): List<DownloadJob>
    @Query("SELECT * FROM download_jobs WHERE id = :id") suspend fun find(id: Long): DownloadJob?
    @Query("UPDATE download_jobs SET publicationName = :name, stage = 'Menyimpan audio' WHERE id = :id AND status = 'RUNNING' AND publicationName IS NULL") suspend fun beginPublication(id: Long, name: String): Int
    @Query("UPDATE download_jobs SET pendingUri = :uri WHERE id = :id AND status = 'RUNNING' AND publicationName IS NOT NULL AND pendingUri IS NULL") suspend fun associatePublication(id: Long, uri: String): Int
    @Query("UPDATE download_jobs SET publicationBytes = :bytes WHERE id = :id AND status = 'RUNNING' AND pendingUri IS NOT NULL AND publicationBytes IS NULL") suspend fun readyPublication(id: Long, bytes: Long): Int
    @Query("UPDATE download_jobs SET publicationName = NULL, pendingUri = NULL, publicationBytes = NULL WHERE id = :id AND status = 'RUNNING' AND publicationBytes IS NULL") suspend fun abandonPublication(id: Long): Int
    @Query("UPDATE download_jobs SET trackId = NULL WHERE trackId = :trackId") suspend fun clearTrack(trackId: Long)
}

class RoomDownloadQueueRepository(private val database: AudioDatabase) : DownloadQueueRepository {
    private val dao get() = database.downloads()
    override val jobs = dao.observeRecentJobs()
    override suspend fun enqueue(requests: List<DownloadRequest>, authorized: Boolean): String {
        val urls = DownloadQueueRules.validate(requests.map { it.sourceUrl }, authorized)
        val normalized = requests.associateBy { DownloadQueueRules.validatedPublicSourceUrl(it.sourceUrl) }
        val batchId = UUID.randomUUID().toString()
        database.withTransaction {
            val active = dao.activeUrls().toSet()
            val fresh = urls.filterNot { it in active }
            require(fresh.isNotEmpty()) { "Audio sudah ada di antrean aktif." }
            val startTs = maxOf(System.currentTimeMillis(), (dao.maxQueuedCreatedAt() ?: 0L) + 1L)
            fresh.forEachIndexed { index, url ->
                val request = normalized.getValue(url)
                dao.insert(DownloadJob(batchId = batchId, sourceUrl = url,
                    title = request.title.take(300).ifBlank { "Audio" },
                    thumbnailUrl = DownloadQueueRules.durableThumbnail(request.thumbnailUrl),
                    status = "QUEUED", progress = null, stage = "Menunggu", error = null,
                    trackId = null, authorized = true, createdAt = startTs + index))
            }
        }
        return batchId
    }
    override suspend fun nextQueued(batchId: String) = dao.nextQueued(batchId)
    override suspend fun nextAnyQueued() = dao.nextAnyQueued()
    override suspend fun nextQueuedBatchId() = dao.nextQueuedBatchId()
    override suspend fun markRunning(id: Long) = dao.markRunning(id) == 1
    override suspend fun progress(id: Long, progress: Float?, stage: String) = dao.progress(id, DownloadQueueRules.validProgress(progress), stage.take(300))
    override suspend fun complete(id: Long, trackId: Long) = database.withTransaction {
        if (database.tracks().find(trackId) != null) dao.complete(id, trackId)
        else dao.fail(id, "Audio tidak ada di library.")
        Unit
    }
    // Native exception strings may contain private URLs, credentials, or output paths.
    override suspend fun fail(id: Long, message: String) =
        dao.fail(id, IngestFailureMessages.sanitize(message))
    override suspend fun cancel(id: Long) = dao.cancel(id)
    override suspend fun cancelBatch(batchId: String) = dao.cancelBatch(batchId)
    override suspend fun cancelAllQueued() = dao.cancelAllQueued()
    override suspend fun pauseRunningJob(id: Long) = dao.pauseRunningToQueued(id) == 1
    override suspend fun skipRunningJob(id: Long) = dao.skipRunningJob(id) == 1
    override suspend fun retryFailed(batchId: String) = dao.retryFailed(batchId)
    override suspend fun cancelQueuedJob(id: Long) {
        dao.cancelQueuedJob(id)
    }
    override suspend fun cancelQueuedJobs(ids: List<Long>): Int = database.withTransaction {
        var changed = 0
        for (id in ids.distinct()) changed += dao.cancelQueuedJob(id)
        changed
    }
    override suspend fun moveQueuedUp(id: Long): Boolean = reorderQueued(id, toFront = false)
    override suspend fun moveQueuedToFront(id: Long): Boolean = reorderQueued(id, toFront = true)
    private suspend fun reorderQueued(id: Long, toFront: Boolean): Boolean = database.withTransaction {
        val current = dao.queuedJobs()
        val idx = current.indexOfFirst { it.id == id }
        if (idx <= 0) return@withTransaction false
        val reordered = current.toMutableList()
        val item = reordered.removeAt(idx)
        val targetIndex = if (toFront) 0 else idx - 1
        reordered.add(targetIndex, item)
        val base = current.minOf { it.createdAt }.coerceAtLeast(1L)
        reordered.forEachIndexed { pos, entry ->
            dao.updateQueuedCreatedAt(entry.id, base + pos)
        }
        true
    }
    override suspend fun requeueJob(id: Long): String? = requeueJobs(listOf(id))
    override suspend fun requeueJobs(ids: List<Long>): String? = database.withTransaction {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return@withTransaction null
        val targetBatch = dao.firstQueuedBatchId() ?: UUID.randomUUID().toString()
        val seenUrls = dao.activeUrls().toMutableSet()
        var nextTs = maxOf(System.currentTimeMillis(), (dao.maxQueuedCreatedAt() ?: 0L) + 1L)
        var promoted = 0
        for (id in uniqueIds) {
            val job = dao.find(id) ?: continue
            if (job.status == "RUNNING") continue
            if (!seenUrls.add(job.sourceUrl)) continue
            when (job.status) {
                "FAILED" -> {
                    if (job.publicationName == null && job.trackId == null &&
                        dao.retryIntoBatchAt(job.id, targetBatch, nextTs) == 1
                    ) {
                        nextTs++
                        promoted++
                    }
                }
                "CANCELED", "COMPLETED" -> {
                    dao.insert(
                        DownloadJob(
                            batchId = targetBatch,
                            sourceUrl = job.sourceUrl,
                            title = job.title,
                            thumbnailUrl = job.thumbnailUrl,
                            status = "QUEUED",
                            progress = null,
                            stage = "Menunggu",
                            error = null,
                            trackId = null,
                            authorized = true,
                            createdAt = nextTs++,
                        )
                    )
                    dao.removeTerminalJob(job.id)
                    promoted++
                }
            }
        }
        if (promoted > 0) targetBatch else dao.firstQueuedBatchId()
    }
    override suspend fun removeJobs(ids: List<Long>): Int = database.withTransaction {
        var removed = 0
        for (id in ids.distinct()) removed += dao.removeTerminalJob(id)
        removed
    }
    override suspend fun updateResolvedMetadata(id: Long, title: String?, thumbnailUrl: String?) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
        val safeThumb = DownloadQueueRules.durableThumbnail(thumbnailUrl)
        if (cleanTitle != null || safeThumb != null) {
            dao.updateResolvedMetadata(id, cleanTitle, safeThumb)
        }
    }
    override suspend fun retryJob(id: Long): String? = database.withTransaction {
        val job = dao.find(id) ?: return@withTransaction null
        if (job.status != "FAILED" || job.publicationName != null || job.trackId != null) return@withTransaction null
        val targetBatch = dao.firstQueuedBatchId() ?: job.batchId
        if (dao.retrySingleIntoBatch(id, targetBatch) == 1) targetBatch else null
    }
    override suspend fun retryAllFailed(): String? = database.withTransaction {
        val failed = dao.failedRetryable()
        if (failed.isEmpty()) return@withTransaction null
        val targetBatch = dao.firstQueuedBatchId() ?: UUID.randomUUID().toString()
        val seenUrls = dao.activeUrls().toMutableSet()
        var promoted = 0
        for (job in failed) {
            if (seenUrls.add(job.sourceUrl) && dao.retrySingleIntoBatch(job.id, targetBatch) == 1) {
                promoted++
            }
        }
        if (promoted > 0) targetBatch else dao.firstQueuedBatchId()
    }
    override suspend fun removeJob(id: Long) {
        dao.removeTerminalJob(id)
    }
    override suspend fun clearFinished(): Int = dao.clearFinishedJobs()
    override suspend fun recoverInterrupted() {
        // Existing startup callers get native reconciliation too, not just a RUNNING -> FAILED SQL sweep.
        database.downloadPublicationRecovery?.invoke()
        check(dao.publications().isEmpty()) { "Publikasi audio perlu dipulihkan sebelum mencoba ulang." }
        dao.recoverInterrupted()
    }
}
