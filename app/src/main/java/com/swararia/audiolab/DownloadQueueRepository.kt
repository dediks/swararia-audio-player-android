package com.swararia.audiolab

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_jobs WHERE batchId = (SELECT batchId FROM download_jobs ORDER BY id DESC LIMIT 1) ORDER BY id ASC")
    fun observeLatestBatch(): Flow<List<DownloadJob>>
    @Query("INSERT INTO download_jobs(batchId,sourceUrl,title,thumbnailUrl,status,progress,stage,error,trackId,authorized,createdAt) SELECT :batchId,:sourceUrl,:title,CASE WHEN instr(:thumbnailUrl, char(0)) = 0 AND length(CAST(:thumbnailUrl AS BLOB)) <= 4096 AND :thumbnailUrl NOT GLOB '*[?#]*' AND (:thumbnailUrl GLOB 'https://i.ytimg.com/*' OR :thumbnailUrl GLOB 'https://i[12349].ytimg.com/*' OR :thumbnailUrl GLOB 'https://yt3.ggpht.com/*' OR :thumbnailUrl GLOB 'https://lh3.googleusercontent.com/*') THEN :thumbnailUrl ELSE NULL END,'QUEUED',NULL,'Menunggu',NULL,NULL,1,:createdAt WHERE length(CAST(:sourceUrl AS BLOB)) = 43 AND instr(:sourceUrl,char(0))=0 AND substr(:sourceUrl,1,32) = 'https://www.youtube.com/watch?v=' AND substr(:sourceUrl,33) NOT GLOB '*[^A-Za-z0-9_-]*'")
    suspend fun insertSafe(batchId: String, sourceUrl: String, title: String, thumbnailUrl: String?, createdAt: Long): Long
    suspend fun insert(job: DownloadJob): Long {
        require(job.authorized && job.sourceUrl == DownloadQueueRules.validatedPublicSourceUrl(job.sourceUrl))
        return insertSafe(job.batchId, job.sourceUrl, job.title, DownloadQueueRules.durableThumbnail(job.thumbnailUrl), job.createdAt)
    }
    @Query("SELECT sourceUrl FROM download_jobs WHERE status IN ('QUEUED','RUNNING')") suspend fun activeUrls(): List<String>
    @Query("SELECT * FROM download_jobs WHERE batchId = :batchId AND status = 'QUEUED' ORDER BY id ASC LIMIT 1") suspend fun nextQueued(batchId: String): DownloadJob?
    @Query("UPDATE download_jobs SET status = 'RUNNING', progress = NULL, stage = 'Memulai', error = NULL WHERE id = :id AND status = 'QUEUED' AND authorized = 1 AND NOT EXISTS (SELECT 1 FROM download_jobs WHERE status = 'RUNNING')")
    suspend fun markRunning(id: Long): Int
    @Query("UPDATE download_jobs SET progress = :progress, stage = :stage WHERE id = :id AND status = 'RUNNING'") suspend fun progress(id: Long, progress: Float?, stage: String)
    @Query("UPDATE download_jobs SET status = 'COMPLETED', trackId = :trackId, progress = 1.0, stage = 'Selesai', error = NULL, publicationName = NULL, pendingUri = NULL, publicationBytes = NULL WHERE id = :id AND status = 'RUNNING'") suspend fun complete(id: Long, trackId: Long): Int
    @Query("UPDATE download_jobs SET status = 'FAILED', progress = NULL, stage = 'Gagal', error = :message WHERE id = :id AND status = 'RUNNING' AND publicationName IS NULL") suspend fun fail(id: Long, message: String)
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dibatalkan', error = NULL WHERE id = :id AND status IN ('QUEUED','RUNNING') AND publicationName IS NULL") suspend fun cancel(id: Long)
    @Query("UPDATE download_jobs SET status = 'CANCELED', progress = NULL, stage = 'Dibatalkan', error = NULL WHERE batchId = :batchId AND status = 'QUEUED'") suspend fun cancelBatch(batchId: String)
    @Query("UPDATE download_jobs SET status = 'QUEUED', progress = NULL, stage = 'Menunggu', error = NULL, trackId = NULL WHERE batchId = :batchId AND status = 'FAILED' AND publicationName IS NULL AND trackId IS NULL AND NOT EXISTS (SELECT 1 FROM download_jobs AS active WHERE active.sourceUrl = download_jobs.sourceUrl AND active.status IN ('QUEUED','RUNNING'))") suspend fun retryFailed(batchId: String)
    @Query("UPDATE download_jobs SET status = 'FAILED', progress = NULL, stage = 'Terhenti', error = 'Unduhan terputus; coba ulang.' WHERE status = 'RUNNING' AND publicationName IS NULL") suspend fun recoverInterrupted()
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
    override val jobs = dao.observeLatestBatch()
    override suspend fun enqueue(requests: List<DownloadRequest>, authorized: Boolean): String {
        val urls = DownloadQueueRules.validate(requests.map { it.sourceUrl }, authorized)
        val normalized = requests.associateBy { DownloadQueueRules.validatedPublicSourceUrl(it.sourceUrl) }
        val batchId = UUID.randomUUID().toString()
        database.withTransaction {
            val active = dao.activeUrls().toSet()
            val fresh = urls.filterNot { it in active }
            require(fresh.isNotEmpty()) { "Audio sudah ada di antrean aktif." }
            val now = System.currentTimeMillis()
            for (url in fresh) {
                val request = normalized.getValue(url)
                dao.insert(DownloadJob(batchId = batchId, sourceUrl = url,
                    title = request.title.take(300).ifBlank { "Audio" },
                    thumbnailUrl = DownloadQueueRules.durableThumbnail(request.thumbnailUrl),
                    status = "QUEUED", progress = null, stage = "Menunggu", error = null,
                    trackId = null, authorized = true, createdAt = now))
            }
        }
        return batchId
    }
    override suspend fun nextQueued(batchId: String) = dao.nextQueued(batchId)
    override suspend fun markRunning(id: Long) = dao.markRunning(id) == 1
    override suspend fun progress(id: Long, progress: Float?, stage: String) = dao.progress(id, DownloadQueueRules.validProgress(progress), stage.take(300))
    override suspend fun complete(id: Long, trackId: Long) = database.withTransaction {
        if (database.tracks().find(trackId) != null) dao.complete(id, trackId)
        else dao.fail(id, "Audio tidak ada di library.")
        Unit
    }
    // Native exception strings may contain private URLs, credentials, or output paths.
    override suspend fun fail(id: Long, message: String) = dao.fail(id, "Unduhan gagal. Coba ulang atau periksa sumber dan koneksi.")
    override suspend fun cancel(id: Long) = dao.cancel(id)
    override suspend fun cancelBatch(batchId: String) = dao.cancelBatch(batchId)
    override suspend fun retryFailed(batchId: String) = dao.retryFailed(batchId)
    override suspend fun recoverInterrupted() {
        // Existing startup callers get native reconciliation too, not just a RUNNING -> FAILED SQL sweep.
        database.downloadPublicationRecovery?.invoke()
        check(dao.publications().isEmpty()) { "Publikasi audio perlu dipulihkan sebelum mencoba ulang." }
        dao.recoverInterrupted()
    }
}
