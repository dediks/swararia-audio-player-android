package com.swararia.audiolab

import kotlinx.coroutines.flow.Flow

/** Only the library capabilities required by presentation; storage retains ownership of file I/O. */
interface AudioLibraryRepository {
    val tracks: Flow<List<AudioTrack>>
    val lastRename: Flow<RenameRecord?>
    suspend fun rename(trackId: Long, requestedTitle: String): String
    suspend fun undoLastRename(): String
}
