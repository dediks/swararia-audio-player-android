package com.swararia.audiolab

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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
}
