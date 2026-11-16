package com.swararia.audiolab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Real source images only. No generated covers or network access during offline resolution. */
class ArtworkStore(private val context: Context) {
    /** Synchronous metadata path. Bounds decoding/compression so stored cache entries stay <= MAX_BYTES. */
    fun cacheEmbedded(bytes: ByteArray): String? {
        if (Looper.myLooper() == Looper.getMainLooper() || bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (!ArtworkRules.validDimensions(bounds.outWidth, bounds.outHeight)) return null
            val storable = if (bytes.size <= ArtworkRules.MAX_BYTES) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                bitmap.recycle()
                bytes
            } else {
                compressToBoundedJpeg(bytes, bounds.outWidth, bounds.outHeight) ?: return null
            }
            val key = MessageDigest.getInstance("SHA-256").digest(storable).joinToString("") { "%02x".format(it) }
            synchronized(storageLock) {
                val directory = File(context.filesDir, "artwork")
                check(directory.mkdirs() || directory.isDirectory)
                val destination = File(directory, "$key.img")
                if (destination.isFile) return key
                // Never evict referenced artwork: refuse additional images at the disk budget.
                val files = directory.listFiles() ?: return null
                if (files.sumOf { it.length() } + storable.size > MAX_STORE_BYTES || files.size >= MAX_IMAGES) return null
                val temporary = File.createTempFile("incoming-", ".tmp", directory)
                try {
                    temporary.outputStream().use { it.write(storable); it.fd.sync() }
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
            key
        }.getOrNull()
    }

    private fun compressToBoundedJpeg(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        var sample = 1
        while (maxOf(width, height) / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        try {
            for (quality in intArrayOf(88, 75, 60, 45)) {
                val out = ByteArrayOutputStream(minOf(ArtworkRules.MAX_BYTES, 65_536))
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    val encoded = out.toByteArray()
                    if (encoded.isNotEmpty() && encoded.size <= ArtworkRules.MAX_BYTES) return encoded
                }
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun cacheThumbnail(url: String?): String? = withContext(Dispatchers.IO) {
        if (!ThumbnailPolicy.allowed(url)) return@withContext null
        var connection: HttpsURLConnection? = null
        val started = System.nanoTime()
        val deadline = java.util.Timer("artwork-deadline", true)
        try {
            currentCoroutineContext().ensureActive()
            connection = URL(url!!).openConnection() as HttpsURLConnection
            val activeConnection = connection
            // Socket inactivity timeouts alone permit endless trickle responses. Also close at elapsed budget.
            deadline.schedule(object : java.util.TimerTask() {
                override fun run() { runCatching { activeConnection.disconnect() } }
            }, ArtworkRules.TRANSFER_BUDGET_MS)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            if (connection.responseCode != 200) return@withContext null
            val length = connection.contentLengthLong
            if (length > MAX_TRANSFER_BYTES) return@withContext null
            val bytes = connection.inputStream.use { ThumbnailTransfer.read(it, MAX_TRANSFER_BYTES) }
            currentCoroutineContext().ensureActive()
            if (!ArtworkRules.transferWithinBudget(started, System.nanoTime())) return@withContext null
            cacheEmbedded(bytes)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null // Optional source artwork must never fail an audio import/download.
        } finally { deadline.cancel(); connection?.disconnect() }
    }

    internal fun remove(key: String) = synchronized(storageLock) { resolve(context, key)?.delete(); Unit }

    companion object {
        private val storageLock = Any()
        private const val MAX_STORE_BYTES = 32L * 1024 * 1024
        private const val MAX_IMAGES = 256
        private const val MAX_TRANSFER_BYTES = 1_048_576
        internal const val MAX_INPUT_BYTES = 4_194_304
        fun resolve(context: Context, key: String?): File? {
            if (!ArtworkRules.validKey(key)) return null
            return runCatching {
                val directory = File(context.filesDir, "artwork").canonicalFile
                val file = File(directory, "$key.img")
                file.takeIf { it.canonicalFile.parentFile == directory && it.isFile && it.length() in 1..ArtworkRules.MAX_BYTES.toLong() }
            }.getOrNull()
        }
    }
}
