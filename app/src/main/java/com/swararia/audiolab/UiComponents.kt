package com.swararia.audiolab

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** Shared compact circular transport/toggle button reused by collapsed players and top audio pills. */
@Composable
internal fun CompactCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    iconSize: Dp = 17.dp,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize),
            )
        }
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

@Composable
internal fun FohDeckButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    val sizedModifier = modifier.heightIn(min = 52.dp)
    when {
        filled -> androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
        outlined -> androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
        else -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
    }
}

/** Shared header pill action shared across Library and Search screens. */
@Composable
internal fun FohHeaderChipButton(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (active) FohTheme.colors.safeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (active) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else if (active) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else if (active) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Full-width 2-choice (or multi-choice) scope segmented bar matching the Library tab look. */
@Composable
internal fun <T> FohSegmentedBar(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) FohTheme.colors.safeBg else Color.Transparent,
                    border = if (active) BorderStroke(1.dp, FohTheme.colors.safeBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            active -> FohTheme.colors.safeAccent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** Compact pill filter chip shared across Library grouping and Search result type filters. */
@Composable
internal fun FohFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) FohTheme.colors.safeBg else FohTheme.colors.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (selected) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle,
        ),
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    selected -> FohTheme.colors.safeAccent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Compact 18 dp spinner shared by status banners in Library and Search. */
@Composable
internal fun CircularProgressIndicatorCompact() {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
}
