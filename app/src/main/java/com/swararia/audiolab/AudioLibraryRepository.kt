package com.swararia.audiolab

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.io.InputStream

sealed class UploadImportOutcome {
    abstract val track: AudioTrack
    abstract val linkedPlaylistId: Long?
    data class Created(override val track: AudioTrack, override val linkedPlaylistId: Long? = null) : UploadImportOutcome()
    data class Deduplicated(override val track: AudioTrack, override val linkedPlaylistId: Long? = null) : UploadImportOutcome()
}

data class ReadableManagedMedia(
    val track: AudioTrack,
    val mimeType: String,
    val sizeBytes: Long,
    val inputStream: InputStream,
)

/** Only the library capabilities required by presentation; storage retains ownership of file I/O. */
interface AudioLibraryRepository {
    val tracks: Flow<List<AudioTrack>>
    val lastRename: Flow<RenameRecord?>
    val playlists: Flow<List<LibraryPlaylist>> get() = emptyFlow()
    suspend fun rename(trackId: Long, requestedTitle: String): String
    suspend fun undoLastRename(): String
    suspend fun delete(trackId: Long)
    suspend fun attachArtwork(trackId: Long, thumbnailUrl: String?): AudioTrack?
    suspend fun createPlaylist(name: String): Long = 0L
    suspend fun renamePlaylist(playlistId: Long, name: String) {}
    suspend fun deletePlaylist(playlistId: Long) {}
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {}
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {}
    suspend fun scanMissingMetadata() {}
    suspend fun findTrackBySha256AndSize(sha256: String, sizeBytes: Long): AudioTrack? = null
    suspend fun importValidatedUpload(
        stagedFile: File,
        requestedName: String,
        expectedSha256: String,
        playlistName: String? = null,
    ): UploadImportOutcome = error("Upload tidak tersedia pada repository ini.")
    suspend fun openReadableTrack(trackId: Long): ReadableManagedMedia? = null
}
