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
    val addedAt: Long = System.currentTimeMillis()
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
    @Update suspend fun updateTrack(track: AudioTrack)
    @Query("SELECT * FROM rename_history ORDER BY id DESC LIMIT 1")
    fun observeLastRename(): Flow<RenameRecord?>
    @Query("SELECT * FROM rename_history ORDER BY id DESC LIMIT 1") suspend fun lastRename(): RenameRecord?
    @Insert suspend fun insertRename(record: RenameRecord): Long
    @Query("DELETE FROM rename_history WHERE id = :id") suspend fun removeRename(id: Long)
}

@Database(entities = [AudioTrack::class, RenameRecord::class], version = 1, exportSchema = true)
abstract class AudioDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
}
