package com.swararia.audiolab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private val thumbnailCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
fun YoutubeThumbnail(url: String?, title: String, modifier: Modifier = Modifier) {
    val safeUrl = url?.takeIf { ThumbnailPolicy.allowed(it) }
    val bitmap by produceState<Bitmap?>(null, safeUrl) {
        value = null
        if (safeUrl != null) {
            value = thumbnailCache.get(safeUrl) ?: withTimeoutOrNull(6000) {
                runInterruptible(Dispatchers.IO) {
                    loadYoutubeThumbnail(safeUrl)?.also { thumbnailCache.put(safeUrl, it) }
                }
            }
        }
    }
    Box(modifier) {
        val current = bitmap
        if (current != null) Image(current.asImageBitmap(), contentDescription = "Pratinjau $title",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Image(painterResource(R.drawable.cover_0), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** No redirects, cookies or authorization; allowlisted HTTPS hosts only. */
private fun loadYoutubeThumbnail(url: String): Bitmap? {
    if (!ThumbnailPolicy.allowed(url)) return null
    var connection: HttpsURLConnection? = null
    return try {
        val request = URL(url).openConnection() as HttpsURLConnection
        connection = request
        request.instanceFollowRedirects = false
        request.connectTimeout = 2500
        request.readTimeout = 3500
        request.setRequestProperty("Accept", "image/*")
        if (request.responseCode != 200 || request.contentLengthLong > 524_288L) null
        else {
            val bytes = request.inputStream.use { ThumbnailTransfer.read(it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
                bounds.outWidth.toLong() * bounds.outHeight > 16_777_216L) null
            else {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: IOException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: SecurityException) { null }
      finally { connection?.disconnect() }
}
