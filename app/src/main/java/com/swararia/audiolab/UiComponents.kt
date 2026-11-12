package com.swararia.audiolab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.swararia_logo), contentDescription = null,
        modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

private val artworkBitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Only resolves the validated private cache key; never extracts media or opens the DB. */
@Composable
internal fun TrackArtwork(artworkKey: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, artworkKey) {
        value = null
        if (artworkKey != null) value = artworkBitmaps.get(artworkKey) ?: runInterruptible(Dispatchers.IO) {
            try {
                val file = ArtworkStore.resolve(context, artworkKey)
                if (file == null || !file.isFile || file.length() !in 1..4_194_304L) null
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16_777_216L) null
                    else {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) > 512 * sample) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?.also { artworkBitmaps.put(artworkKey, it) }
                    }
                }
            } catch (_: IOException) { null }
              catch (_: SecurityException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Image(painterResource(R.drawable.cover_0), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
internal fun GlyphButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    iconSize: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (filled) Modifier.background(if (enabled) colors.primary else colors.surfaceVariant, CircleShape) else Modifier)) {
        Icon(icon, contentDescription = description,
            tint = if (!enabled) colors.onSurface.copy(alpha = .38f) else if (filled) colors.onPrimary else colors.onSurface,
            modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun EmptyMessage(title: String, description: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}
