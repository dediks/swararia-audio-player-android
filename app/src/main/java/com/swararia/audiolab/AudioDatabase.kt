package com.swararia.audiolab

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class AudioTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val addedAt: Long = System.currentTimeMillis(),
    val artworkKey: String? = null
)

@Entity(tableName = "rename_history")
data class RenameRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val fromName: String,
    val toName: String,
    val changedAt: Long = System.currentTimeMillis()
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<AudioTrack>>
    @Insert suspend fun insert(track: AudioTrack): Long
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun find(id: Long): AudioTrack?
    @Query("SELECT * FROM tracks WHERE uri = :uri") suspend fun findByUri(uri: String): AudioTrack?
    @Update suspend fun updateTrack(track: AudioTrack)
    @Query("SELECT * FROM rename_history ORDER BY id DESC LIMIT 1")
    fun observeLastRename(): Flow<RenameRecord?>
    @Query("SELECT * FROM rename_history ORDER BY id DESC LIMIT 1") suspend fun lastRename(): RenameRecord?
    @Insert suspend fun insertRename(record: RenameRecord): Long
    @Query("DELETE FROM rename_history WHERE id = :id") suspend fun removeRename(id: Long)
    @Query("DELETE FROM rename_history WHERE trackId = :id") suspend fun removeTrackHistory(id: Long)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun removeTrack(id: Long)
    @Query("SELECT COUNT(*) FROM tracks WHERE artworkKey = :key") suspend fun artworkReferences(key: String): Int
    @Query("UPDATE tracks SET artworkKey = :key WHERE id = :id") suspend fun setArtwork(id: Long, key: String)
}

@Database(entities = [AudioTrack::class, RenameRecord::class, DownloadJob::class], version = 2, exportSchema = true)
abstract class AudioDatabase : RoomDatabase() {
    /** Native library registers recovery on this DB instance, preserving existing queue/startup APIs.
     * Not persisted schema; no Android context is retained by the queue repository. */
    @Volatile internal var downloadPublicationRecovery: (suspend () -> Unit)? = null
    abstract fun tracks(): TrackDao
    abstract fun downloads(): DownloadDao
}
