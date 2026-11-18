package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalImportActionBanner(
    enabled: Boolean,
    onPickFiles: () -> Unit,
) {
    Surface(
        onClick = onPickFiles,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.safeBg,
        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(FohTheme.colors.surfaceDeep),
                contentAlignment = Alignment.Center,
            ) {
                Icon(UiIcons.Folder, contentDescription = null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pilih File Audio dari HP", style = MaterialTheme.typography.titleSmall, color = FohTheme.colors.safeAccent)
                Text("MP3 · WAV · FLAC · OPUS · M4A", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (enabled) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "Pilih",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) FohTheme.colors.surfaceDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
internal fun LocalIngestStatusBanner(
    ingest: IngestState,
    onCancel: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val progress = ingest.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, if (ingest.error) FohTheme.colors.alertBorder else FohTheme.colors.safeBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    ingest.busy -> FohSpinningIndicator(size = 18.dp, strokeWidth = 2.2.dp, color = FohTheme.colors.safeAccent)
                    ingest.error -> Icon(UiIcons.Warning, null, tint = FohTheme.colors.alertAccent, modifier = Modifier.size(18.dp))
                    else -> Icon(UiIcons.Check, null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = ingest.stage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ingest.error) FohTheme.colors.alertAccent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (ingest.busy && ingest.cancelable) {
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Batal", style = MaterialTheme.typography.labelMedium)
                    }
                } else if (!ingest.busy && onDismiss != null) {
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Tutup", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (ingest.busy) {
                FohOperationProgressBar(
                    progress = progress,
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceDeep,
                    height = 4.dp,
                )
            }
            if ((ingest.error || !ingest.busy) && ingest.detail.isNotBlank()) {
                Text(
                    ingest.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ingest.error) FohTheme.colors.alertAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LocalSavedTrackRow(track: AudioTrack) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackArtwork(
                artworkKey = track.artworkKey,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = UiPresentation.qualitySummary(track.displayName, track.sizeBytes, track.durationMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = FohTheme.colors.safeBg,
                border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(UiIcons.Check, null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(13.dp))
                    Text("Tersimpan", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = FohTheme.colors.safeAccent)
                }
            }
        }
    }
}

@Composable
internal fun SearchSkeletonRow() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.65f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FohShimmerBox(modifier = Modifier.size(46.dp), cornerRadius = 9.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FohShimmerBox(modifier = Modifier.fillMaxWidth(0.74f).height(13.dp), cornerRadius = 6.dp)
                FohShimmerBox(modifier = Modifier.fillMaxWidth(0.46f).height(10.dp), cornerRadius = 5.dp)
            }
            FohShimmerBox(modifier = Modifier.size(34.dp), cornerRadius = 17.dp)
        }
    }
}

@Composable
internal fun SearchIngestBanner(
    stage: String,
    cancelable: Boolean,
    onOpenDownloads: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        onClick = onOpenDownloads,
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.safeBg,
        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FohSpinningIndicator(size = 18.dp, strokeWidth = 2.2.dp, color = FohTheme.colors.safeAccent)
                Text(
                    text = stage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Lihat ›",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FohTheme.colors.safeAccent,
                )
                if (cancelable) {
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("Batal", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            FohIndeterminateBar(color = FohTheme.colors.safeAccent, trackColor = FohTheme.colors.surfaceDeep, height = 4.dp)
        }
    }
}

@Composable
internal fun SearchPlaylistContextHeader(
    title: String,
    channel: String?,
    thumbnailUrl: String?,
    songCount: Int,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YoutubeThumbnail(thumbnailUrl, title, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Playlist", style = MaterialTheme.typography.labelSmall, color = FohTheme.colors.safeAccent)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(channel, "$songCount lagu").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SearchEmptyState(
    hasSearched: Boolean,
    provider: SearchProvider,
    idle: Boolean,
    importIdle: Boolean = idle,
    queueIdle: Boolean,
    onOpenLink: () -> Unit,
    onOpenImport: () -> Unit,
    onSwitchProviderSearch: (SearchProvider) -> Unit,
) {
    if (hasSearched) {
        val alternate = if (provider == SearchProvider.YOUTUBE_MUSIC) SearchProvider.YOUTUBE else SearchProvider.YOUTUBE_MUSIC
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = FohTheme.colors.surfaceCard,
            border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Tidak ada hasil",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onSwitchProviderSearch(alternate) }, enabled = idle) {
                    Text("Cari di ${if (alternate == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube"}")
                }
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickEntryPad(
            icon = UiIcons.Link,
            title = "Tempel Link",
            subtitle = "YouTube / YT Music",
            accent = FohTheme.colors.safeAccent,
            iconBg = FohTheme.colors.safeBg,
            enabled = queueIdle,
            onClick = onOpenLink,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        QuickEntryPad(
            icon = UiIcons.Import,
            title = "Impor File",
            subtitle = "MP3 · WAV · FLAC",
            accent = MaterialTheme.colorScheme.onSurface,
            iconBg = FohTheme.colors.surfaceDeep,
            enabled = importIdle,
            onClick = onOpenImport,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
internal fun QuickEntryPad(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    iconBg: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
