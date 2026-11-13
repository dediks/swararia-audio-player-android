package com.swararia.audiolab

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Only owns copies created by this APK; no broad storage permission is used. */
class MediaLibrary(private val context: Context, private val database: AudioDatabase) : AudioLibraryRepository {
    override val tracks: Flow<List<AudioTrack>> = database.tracks().observeAll()
    override val lastRename: Flow<RenameRecord?> = database.tracks().observeLastRename()
    override val playlists: Flow<List<LibraryPlaylist>> = combine(
        database.playlists().observePlaylists(),
        database.playlists().observeEntries(),
    ) { playlistRows, entryRows ->
        val trackIdsByPlaylist = entryRows.groupBy({ it.playlistId }, { it.trackId })
        playlistRows.map { playlist ->
            LibraryPlaylist(
                id = playlist.id,
                name = playlist.name,
                createdAt = playlist.createdAt,
                trackIds = trackIdsByPlaylist[playlist.id].orEmpty(),
            )
        }
    }

    private data class ExtractedAudioMetadata(
        val durationMs: Long?,
        val artist: String?,
        val album: String?,
        val genre: String?,
    )

    private fun inspectAudioMetadata(uri: Uri): ExtractedAudioMetadata = runCatching {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val rawArtist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val rawAlbum = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val rawGenre = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ExtractedAudioMetadata(
                durationMs = durationMs,
                artist = LibraryRules.sanitizedTag(rawArtist, 120),
                album = LibraryRules.sanitizedTag(rawAlbum, 120),
                genre = LibraryRules.sanitizedTag(rawGenre, 60),
            )
        } finally {
            reader.release()
        }
    }.getOrDefault(ExtractedAudioMetadata(null, null, null, null))

    private val artwork = ArtworkStore(context)

    override suspend fun delete(trackId: Long): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val track = database.tracks().find(trackId) ?: return@withLock
            require(ManagedCopyRules.validUri(track.uri)) { "Lokasi audio bukan salinan yang dikelola aplikasi." }
            val uri = Uri.parse(track.uri)
            // Null cursor/errors are not evidence that a file is gone. Never delete DB state on uncertainty.
            if (managedItemExists(uri)) {
                resolver.delete(uri, null, null)
                check(!managedItemExists(uri)) { "File belum terhapus; library tetap disimpan. Coba lagi." }
            }
            // Independently verify the provider can no longer open this item.
            try {
                val descriptor = resolver.openAssetFileDescriptor(uri, "r")
                descriptor?.close()
                error("File masih dapat diakses atau penghapusan tidak dapat dipastikan. Library tetap disimpan.")
            } catch (_: java.io.FileNotFoundException) {
                // Expected only after the item query above confirmed absence.
            }
            // File and Room cannot commit atomically. Complete cleanup despite caller cancellation.
            withContext(NonCancellable) {
                try {
                    database.withTransaction {
                        database.downloads().clearTrack(track.id)
                        database.playlists().removeTrackFromAll(track.id)
                        database.tracks().removeTrackHistory(track.id)
                        database.tracks().removeTrack(track.id)
                    }
                } catch (failure: Exception) {
                    throw IllegalStateException("File sudah terhapus, tetapi pembaruan library gagal. Ulangi Hapus untuk membersihkan catatan; file tidak dapat dipulihkan.", failure)
                }
                // Artwork reclamation is optional; a cleanup failure must not imply audio deletion failed.
                runCatching {
                    track.artworkKey?.let { key ->
                        if (database.tracks().artworkReferences(key) == 0) artwork.remove(key)
                    }
                }
            }
        }
    }

    /** Requires exact collection/id plus the recorded managed path and owner if exposed. */
    private fun managedItemExists(uri: Uri): Boolean {
        val cursor = resolver.query(uri, null, null, null, null)
            ?: error("Penyimpanan tidak dapat diverifikasi. Library tidak diubah.")
        return cursor.use {
            if (!it.moveToFirst()) return@use false
            val id = it.getColumnIndex(MediaStore.Audio.Media._ID)
            val path = it.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            require(id >= 0 && it.getLong(id) == ContentUris.parseId(uri) && path >= 0 &&
                it.getString(path) == ManagedCopyRules.PATH) { "File bukan salinan audio yang dikelola aplikasi." }
            val owner = it.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            if (owner >= 0 && !it.isNull(owner)) require(it.getString(owner) == context.packageName) {
                "File dimiliki aplikasi lain; penghapusan ditolak."
            }
            true
        }
    }

    override suspend fun attachArtwork(trackId: Long, thumbnailUrl: String?): AudioTrack? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val track = database.tracks().find(trackId) ?: return@withLock null
            if (ArtworkStore.resolve(context, track.artworkKey) != null) return@withLock track
            val key = embeddedArtwork(Uri.parse(track.uri)) ?: artwork.cacheThumbnail(thumbnailUrl) ?: return@withLock track
            database.tracks().setArtwork(track.id, key)
            database.tracks().find(track.id)
        }
    }

    private fun embeddedArtwork(uri: Uri): String? = runCatching {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            reader.embeddedPicture?.let(artwork::cacheEmbedded)
        } finally { reader.release() }
    }.getOrNull()

    override suspend fun rename(trackId: Long, requestedTitle: String): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
        val track = database.tracks().find(trackId) ?: error("Audio tidak ada di library.")
        val uri = Uri.parse(track.uri)
        val oldName = actualName(uri)
        val requested = LibraryRules.renamedDisplayName(oldName, requestedTitle)
        require(requested != oldName) { "Nama belum berubah." }
        ensureNameAvailable(uri, requested)
        val changed = changeName(uri, requested)
        try {
            database.withTransaction {
                database.tracks().updateTrack(track.copy(displayName = changed))
                database.tracks().insertRename(RenameRecord(trackId = track.id, fromName = oldName, toName = changed))
            }
        } catch (failure: Throwable) {
            restoreAfterDatabaseFailure(uri, oldName, failure)
        }
        changed
        }
    }

    override suspend fun undoLastRename(): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
        val record = database.tracks().lastRename() ?: error("Belum ada perubahan nama yang dapat dibatalkan.")
        val track = database.tracks().find(record.trackId) ?: error("Audio sudah tidak ada di library.")
        val uri = Uri.parse(track.uri)
        require(actualName(uri) == record.toName) { "Nama file berubah di luar aplikasi. Pembatalan otomatis tidak aman." }
        ensureNameAvailable(uri, record.fromName)
        val restored = changeName(uri, record.fromName)
        try {
            database.withTransaction {
                database.tracks().updateTrack(track.copy(displayName = restored))
                database.tracks().removeRename(record.id)
            }
        } catch (failure: Throwable) {
            restoreAfterDatabaseFailure(uri, record.toName, failure)
        }
        restored
        }
    }

    private fun actualName(uri: Uri): String = resolver.query(uri,
        arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: error("File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi.")

    private fun ensureNameAvailable(uri: Uri, name: String) {
        resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media._ID}!=?",
            arrayOf("Music/SwarariaAudioLab/", name, ContentUris.parseId(uri).toString()), null)?.use { cursor ->
            require(!cursor.moveToFirst()) { "Nama sudah dipakai file lain. Pilih nama berbeda." }
        }
    }

    private fun changeName(uri: Uri, name: String): String {
        val changed = resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) }, null, null)
        check(changed > 0) { "HP tidak mengizinkan perubahan nama file." }
        return actualName(uri)
    }

    private suspend fun restoreAfterDatabaseFailure(uri: Uri, oldName: String, failure: Throwable): Nothing {
        val restored = withContext(NonCancellable + Dispatchers.IO) { runCatching { changeName(uri, oldName) == oldName }.getOrDefault(false) }
        if (!restored) throw IllegalStateException("Nama file berubah, tetapi penyimpanan library gagal. Periksa file di Music/SwarariaAudioLab sebelum mengulang.", failure)
        throw failure
    }
    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val publicationStore = object : DownloadQueuePublication.Store {
        override suspend fun begin(id: Long, name: String) { check(database.downloads().beginPublication(id, name) == 1) }
        override suspend fun create(name: String): String = (resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_TYPES.getValue(name.substringAfterLast('.')))
            put(MediaStore.Audio.Media.RELATIVE_PATH, ManagedCopyRules.PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }) ?: error("HP menolak pembuatan file audio.")).toString()
        override suspend fun associate(id: Long, uri: String) { check(database.downloads().associatePublication(id, uri) == 1) }
        override suspend fun ready(id: Long, bytes: Long) { check(database.downloads().readyPublication(id, bytes) == 1) }
        override suspend fun locate(name: String): String? {
            require(Regex("swararia-[0-9a-f-]{36}\\.[a-z0-9]+").matches(name))
            // API 29-compatible: recovery must also find our hidden insert-before-associate marker.
            @Suppress("DEPRECATION")
            val cursor = resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?",
                arrayOf(ManagedCopyRules.PATH, name), null) ?: error("Penyimpanan tidak dapat diverifikasi.")
            return cursor.use {
                if (!it.moveToFirst()) return@use null
                val uri = ContentUris.withAppendedId(collection, it.getLong(0)).toString()
                check(!it.moveToNext()) { "Salinan jurnal ambigu." }
                checkNotNull(inspect(uri))
                uri
            }
        }
        override suspend fun inspect(uri: String): DownloadQueuePublication.Media? {
            require(ManagedCopyRules.validUri(uri))
            val cursor = resolver.query(Uri.parse(uri), null, null, null, null) ?: error("Penyimpanan tidak dapat diverifikasi.")
            return cursor.use {
                if (!it.moveToFirst()) return@use null
                check(it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)) == ContentUris.parseId(Uri.parse(uri)))
                check(it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)) == ManagedCopyRules.PATH)
                // Recovery is stricter than old imports: ownership must be exposed and exact.
                check(it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) == context.packageName)
                val pending = it.getInt(it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)) != 0
                // Pending rows may still have stale indexed size after the writer closes.
                // Inspect the owned file without publishing it or reopening it for writes.
                val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "r")
                    ?: error("Salinan tidak dapat diverifikasi.")
                val bytes = descriptor.use { it.statSize }
                check(bytes >= 0) { "Ukuran salinan tidak dapat diverifikasi." }
                DownloadQueuePublication.Media(pending, bytes)
            }
        }
        override suspend fun discardPending(uri: String) {
            check(inspect(uri)?.pending == true)
            resolver.delete(Uri.parse(uri), null, null)
            check(inspect(uri) == null) { "Salinan sementara belum terhapus." }
        }
        override suspend fun abandon(id: Long) {
            database.withTransaction {
                check(database.downloads().abandonPublication(id) == 1)
                database.downloads().fail(id, "Unduhan terputus; coba ulang.")
            }
        }
        override suspend fun makeVisible(uri: String) {
            check(resolver.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
            check(inspect(uri)?.pending == false)
        }
        override suspend fun finish(id: Long, uri: String, bytes: Long): Long {
            val name = actualName(Uri.parse(uri))
            val meta = inspectAudioMetadata(Uri.parse(uri))
            val checkedAt = System.currentTimeMillis()
            return database.withTransaction {
                val job = checkNotNull(database.downloads().find(id))
                check(job.status == "RUNNING" && job.pendingUri == uri && job.publicationBytes == bytes)
                val trackId = database.tracks().findByUri(uri)?.id ?: database.tracks().insert(
                    AudioTrack(
                        uri = uri, displayName = name, sizeBytes = bytes, durationMs = meta.durationMs,
                        artist = meta.artist, album = meta.album, genre = meta.genre, metadataCheckedAt = checkedAt,
                    )
                )
                check(database.downloads().complete(id, trackId) == 1)
                trackId
            }
        }
    }

    init { database.downloadPublicationRecovery = { recoverDownloadPublications() } }

    /** Also invoked by existing startup queue recovery; shared mutation lock excludes a live publication. */
    suspend fun recoverDownloadPublications(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        mutationMutex.withLock {
            val publisher = DownloadQueuePublication(publicationStore)
            for (job in database.downloads().publications()) publisher.recover(job)
        }
    }

    /** Durable queue path: old one-argument API remains for non-queue callers. */
    suspend fun addDownloadedFile(file: File, jobId: Long): AudioTrack = withContext(NonCancellable + Dispatchers.IO) {
        mutationMutex.withLock {
            require(file.isFile && file.length() > 0 && file.extension.lowercase(Locale.ROOT) in AUDIO_TYPES)
            val name = "swararia-${java.util.UUID.randomUUID()}.${file.extension.lowercase(Locale.ROOT)}"
            val id = DownloadQueuePublication(publicationStore).publish(jobId, name) { uri ->
                // URI is journaled before restoring the friendly name; pre-association lookup keeps its unique marker.
                check(resolver.update(Uri.parse(uri), ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                }, null, null) == 1)
                val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "w") ?: error("Salinan tidak dapat ditulis.")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { destination ->
                    val copied = file.inputStream().use { it.copyTo(destination, 64 * 1024) }
                    destination.flush()
                    destination.fd.sync()
                    check(copied == file.length())
                    copied
                }
            }
            checkNotNull(database.tracks().find(id))
        }
    }

    suspend fun addDownloadedFile(file: File): AudioTrack = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0) { "Mesin tidak menghasilkan file audio." }
        val extension = file.extension.lowercase(Locale.ROOT)
        val mime = AUDIO_TYPES[extension] ?: error("Format audio belum didukung: $extension")
        file.inputStream().use { publish(it, file.name, mime) }
    }

    suspend fun importAudio(source: Uri): AudioTrack = withContext(Dispatchers.IO) {
        var incomingName: String? = null
        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) incomingName = cursor.getString(0)
        }
        val mime = resolver.getType(source).orEmpty()
        val raw = incomingName ?: "Audio"
        var extension = raw.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in AUDIO_TYPES) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty().lowercase(Locale.ROOT)
        }
        require(extension in AUDIO_TYPES) { "Impor mendukung MP3, M4A, Opus, Ogg, WebM, WAV, FLAC, dan AAC." }
        val clean = raw.substringBeforeLast('.', raw)
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("").trim().ifBlank { "Audio" }
        val title = StringBuilder()
        var byteCount = 0
        for (point in clean.codePoints().toArray()) {
            val part = String(Character.toChars(point))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (byteCount + size > 150) break
            title.append(part); byteCount += size
        }
        val name = LibraryRules.renamedDisplayName("audio.$extension", title.toString().ifBlank { "Audio" })
        val input = resolver.openInputStream(source) ?: error("File pilihan tidak dapat dibaca.")
        input.use { publish(it, name, AUDIO_TYPES.getValue(extension)) }
    }

    private suspend fun publish(input: InputStream, displayName: String, mime: String): AudioTrack = mutationMutex.withLock {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SwarariaAudioLab/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("HP menolak pembuatan file audio.")
        try {
            var copied = 0L
            val output = resolver.openOutputStream(uri, "w") ?: error("File baru tidak dapat ditulis.")
            output.use { destination ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) { destination.write(buffer, 0, read); copied += read }
                }
            }
            require(copied > 0) { "Sumber audio kosong; tidak ada file yang ditambahkan." }
            currentCoroutineContext().ensureActive()
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            check(published > 0) { "File tidak berhasil dipublikasikan ke penyimpanan HP." }
            var actualName = displayName
            resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) actualName = cursor.getString(0)
            }
            val meta = inspectAudioMetadata(uri)
            val track = AudioTrack(
                uri = uri.toString(), displayName = actualName, sizeBytes = copied, durationMs = meta.durationMs,
                artworkKey = embeddedArtwork(uri), artist = meta.artist, album = meta.album, genre = meta.genre,
                metadataCheckedAt = System.currentTimeMillis(),
            )
            val id = database.tracks().insert(track)
            return track.copy(id = id)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
            throw failure
        }
    }

    override suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val clean = LibraryRules.validatedPlaylistName(name)
        mutationMutex.withLock {
            require(database.playlists().findPlaylistByName(clean) == null) { "Playlist \"$clean\" sudah ada." }
            database.playlists().insertPlaylist(LocalPlaylist(name = clean))
        }
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String) = withContext(Dispatchers.IO) {
        val clean = LibraryRules.validatedPlaylistName(name)
        mutationMutex.withLock {
            check(database.playlists().findPlaylist(playlistId) != null) { "Playlist tidak ditemukan." }
            val existing = database.playlists().findPlaylistByName(clean)
            require(existing == null || existing.id == playlistId) { "Playlist \"$clean\" sudah ada." }
            database.playlists().renamePlaylist(playlistId, clean)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.playlists().deletePlaylist(playlistId)
        }
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                database.withTransaction {
                    check(database.playlists().findPlaylist(playlistId) != null) { "Playlist tidak ditemukan." }
                    check(database.tracks().find(trackId) != null) { "Audio tidak ada di library." }
                    val pos = database.playlists().nextPosition(playlistId)
                    database.playlists().insertEntry(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = pos))
                }
            }
        }
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.playlists().removeEntry(playlistId, trackId)
        }
    }

    override suspend fun scanMissingMetadata() = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = database.tracks().unscannedMetadata(25)
            if (pending.isEmpty()) break
            for (track in pending) {
                currentCoroutineContext().ensureActive()
                val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
                val meta = if (uri != null && ManagedCopyRules.validUri(track.uri)) inspectAudioMetadata(uri)
                else ExtractedAudioMetadata(null, null, null, null)
                val now = System.currentTimeMillis()
                database.tracks().updateMetadata(track.id, meta.durationMs, meta.artist, meta.album, meta.genre, now)
            }
        }
    }

    companion object {
        // Shared across instances to prevent delete/rename/artwork publication races.
        private val mutationMutex = Mutex()
        val AUDIO_TYPES = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "audio/mp4",
            "opus" to "audio/ogg", "ogg" to "audio/ogg", "webm" to "audio/webm",
            "wav" to "audio/wav", "flac" to "audio/flac", "aac" to "audio/aac"
        )
    }
}
