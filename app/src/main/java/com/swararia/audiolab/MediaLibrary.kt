package com.swararia.audiolab

import android.app.Application
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

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

class SwarariaApp : Application() {
    val database: AudioDatabase by lazy {
        Room.databaseBuilder(this, AudioDatabase::class.java, "audio-library-spike.db").build()
    }
    val library: MediaLibrary by lazy { MediaLibrary(this, database) }
}

/** Only owns copies created by this APK; no broad storage permission is used. */
class MediaLibrary(private val context: Context, private val database: AudioDatabase) {
    val tracks: Flow<List<AudioTrack>> = database.tracks().observeAll()
    val lastRename: Flow<RenameRecord?> = database.tracks().observeLastRename()

    suspend fun rename(trackId: Long, requestedTitle: String): String = withContext(Dispatchers.IO) {
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

    suspend fun undoLastRename(): String = withContext(Dispatchers.IO) {
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

    private suspend fun publish(input: InputStream, displayName: String, mime: String): AudioTrack {
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
            val duration = runCatching {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(context, uri)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally { reader.release() }
            }.getOrNull()
            val track = AudioTrack(uri = uri.toString(), displayName = actualName, sizeBytes = copied, durationMs = duration)
            val id = database.tracks().insert(track)
            return track.copy(id = id)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
            throw failure
        }
    }

    companion object {
        val AUDIO_TYPES = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "audio/mp4",
            "opus" to "audio/ogg", "ogg" to "audio/ogg", "webm" to "audio/webm",
            "wav" to "audio/wav", "flac" to "audio/flac", "aac" to "audio/aac"
        )
    }
}
