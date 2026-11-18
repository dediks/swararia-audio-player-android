package com.swararia.audiolab

import android.content.ContentResolver
import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

internal data class ExtractedAudioMetadata(
    val durationMs: Long?,
    val artist: String?,
    val album: String?,
    val genre: String?,
)

internal object AudioFileInspector {
    private inline fun <T> withMediaMetadataRetriever(
        context: Context,
        uri: Uri,
        block: (MediaMetadataRetriever) -> T,
    ): T {
        val reader = MediaMetadataRetriever()
        return try {
            if ("file".equals(uri.scheme, ignoreCase = true) && uri.path != null) {
                reader.setDataSource(uri.path)
            } else {
                reader.setDataSource(context, uri)
            }
            block(reader)
        } finally {
            runCatching { reader.release() }
        }
    }

    fun inspectAudioMetadata(context: Context, uri: Uri): ExtractedAudioMetadata = runCatching {
        withMediaMetadataRetriever(context, uri) { reader ->
            val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val artist = LibraryRules.sanitizedTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), 120)
                ?: LibraryRules.sanitizedTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), 120)
            val album = LibraryRules.sanitizedTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), 120)
            val genre = LibraryRules.sanitizedTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE), 60)
            ExtractedAudioMetadata(
                durationMs = durationMs,
                artist = artist,
                album = album,
                genre = genre,
            )
        }
    }.getOrDefault(ExtractedAudioMetadata(null, null, null, null))

    fun extractEmbeddedArtwork(context: Context, uri: Uri, artwork: ArtworkStore): String? = runCatching {
        withMediaMetadataRetriever(context, uri) { reader ->
            reader.embeddedPicture?.let(artwork::cacheEmbedded)
        }
    }.getOrNull()

    fun inspectTechnicalDetails(
        context: Context,
        uri: Uri,
        fallbackDurationMs: Long?,
    ): TrackTechnicalDetails {
        var mimeType: String? = null
        var sampleRateHz: Int? = null
        var channelCount: Int? = null
        var extractorBitrateBps: Long? = null
        var bitsPerSample: Int? = null
        var durationMs: Long? = null

        val extractor = MediaExtractor()
        try {
            if ("file".equals(uri.scheme, ignoreCase = true) && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            for (idx in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(idx)
                val mime = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null
                if (mime?.startsWith("audio/", ignoreCase = true) == true) {
                    mimeType = mime
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        extractorBitrateBps = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong().takeIf { it > 0L }
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = (format.getLong(MediaFormat.KEY_DURATION) / 1000L).takeIf { it > 0L }
                    }
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        bitsPerSample = when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                            AudioFormat.ENCODING_PCM_8BIT -> 8
                            AudioFormat.ENCODING_PCM_16BIT -> 16
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            AudioFormat.ENCODING_PCM_32BIT,
                            AudioFormat.ENCODING_PCM_FLOAT,
                            -> 32
                            else -> null
                        }
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        var retrieverBitrateBps: Long? = null
        var retrieverDurationMs: Long? = null
        var retrieverMime: String? = null
        var retrieverSampleRate: Int? = null
        var retrieverBitsPerSample: Int? = null
        runCatching {
            withMediaMetadataRetriever(context, uri) { reader ->
                retrieverBitrateBps = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                retrieverDurationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                retrieverMime = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retrieverSampleRate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                    retrieverBitsPerSample = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            }
        }

        return TrackTechnicalDetails(
            mimeType = mimeType ?: retrieverMime,
            bitrateBps = extractorBitrateBps ?: retrieverBitrateBps,
            sampleRateHz = sampleRateHz ?: retrieverSampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample ?: retrieverBitsPerSample,
            durationMs = durationMs ?: retrieverDurationMs ?: fallbackDurationMs,
        )
    }

    fun validateAudioFileContent(file: File) {
        val extractor = MediaExtractor()
        val hasAudioTrack = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { idx ->
                val mime = extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("audio/", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
        require(hasAudioTrack) { "File tidak berisi aliran audio yang valid." }

        val reader = MediaMetadataRetriever()
        val retrieverOk = try {
            reader.setDataSource(file.absolutePath)
            val hasAudio = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            hasAudio.equals("yes", ignoreCase = true) || (durationMs != null && durationMs > 0L)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { reader.release() }
        }
        require(retrieverOk) { "Metadata audio gagal diverifikasi." }
    }

    suspend fun computeUriSha256AndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long>? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            shaHex(digest.digest()) to total
        }
    }.getOrNull()

    fun computeUriSha256(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            shaHex(digest.digest())
        }
    }.getOrNull()

    fun shaHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}
