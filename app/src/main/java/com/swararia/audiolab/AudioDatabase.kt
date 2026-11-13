package com.swararia.audiolab

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["uri"], unique = true), Index(value = ["sha256"])],
)
data class AudioTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val addedAt: Long = System.currentTimeMillis(),
    val artworkKey: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val metadataCheckedAt: Long? = null,
    val sha256: String? = null,
)

@Entity(tableName = "playlists", indices = [Index(value = ["name"], unique = true)])
data class LocalPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AudioTrack::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["trackId"])],
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

data class LibraryPlaylist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackIds: List<Long>,
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
    @Query("SELECT * FROM tracks WHERE metadataCheckedAt IS NULL ORDER BY id DESC LIMIT :limit")
    suspend fun unscannedMetadata(limit: Int = 50): List<AudioTrack>
    @Query("UPDATE tracks SET durationMs = COALESCE(durationMs, :durationMs), artist = :artist, album = :album, genre = :genre, metadataCheckedAt = :checkedAt WHERE id = :id")
    suspend fun updateMetadata(id: Long, durationMs: Long?, artist: String?, album: String?, genre: String?, checkedAt: Long)
    @Query("SELECT * FROM tracks WHERE sha256 = :sha256 AND sizeBytes = :sizeBytes ORDER BY id ASC LIMIT 1")
    suspend fun findBySha256AndSize(sha256: String, sizeBytes: Long): AudioTrack?
    @Query("SELECT * FROM tracks WHERE sha256 IS NULL ORDER BY id DESC LIMIT :limit")
    suspend fun unhashedTracks(limit: Int = 25): List<AudioTrack>
    @Query("UPDATE tracks SET sha256 = :sha256 WHERE id = :id")
    suspend fun updateSha256(id: Long, sha256: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt ASC, id ASC")
    fun observePlaylists(): Flow<List<LocalPlaylist>>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId ASC, position ASC, addedAt ASC")
    fun observeEntries(): Flow<List<PlaylistTrackCrossRef>>
    @Insert suspend fun insertPlaylist(playlist: LocalPlaylist): Long
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun findPlaylist(id: Long): LocalPlaylist?
    @Query("SELECT * FROM playlists WHERE LOWER(name) = LOWER(:name) LIMIT 1") suspend fun findPlaylistByName(name: String): LocalPlaylist?
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: PlaylistTrackCrossRef): Long
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeEntry(playlistId: Long, trackId: Long)
    @Query("DELETE FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun removeTrackFromAll(trackId: Long)
}

@Database(entities = [AudioTrack::class, RenameRecord::class, DownloadJob::class, LocalPlaylist::class, PlaylistTrackCrossRef::class], version = 4, exportSchema = true)
abstract class AudioDatabase : RoomDatabase() {
    /** Native library registers recovery on this DB instance, preserving existing queue/startup APIs.
     * Not persisted schema; no Android context is retained by the queue repository. */
    @Volatile internal var downloadPublicationRecovery: (suspend () -> Unit)? = null
    abstract fun tracks(): TrackDao
    abstract fun downloads(): DownloadDao
    abstract fun playlists(): PlaylistDao
}
