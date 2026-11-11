package com.swararia.audiolab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

@Composable
internal fun TrackArtwork(id: Long?, modifier: Modifier = Modifier) {
    val image = if (id == null) R.drawable.swararia_logo else when (UiPresentation.artworkIndex(id)) {
        0 -> R.drawable.cover_0
        1 -> R.drawable.cover_1
        2 -> R.drawable.cover_2
        3 -> R.drawable.cover_3
        else -> R.drawable.cover_4
    }
    Image(painterResource(image), contentDescription = null, modifier = modifier,
        contentScale = if (id == null) ContentScale.Fit else ContentScale.Crop)
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
