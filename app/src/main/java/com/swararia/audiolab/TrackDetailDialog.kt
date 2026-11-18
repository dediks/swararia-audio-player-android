package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackDetailDialog(
    track: AudioTrack,
    playlistNames: List<String>,
    connected: Boolean,
    renameEnabled: Boolean,
    onInspect: suspend (AudioTrack) -> TrackTechnicalDetails?,
    onPlay: () -> Unit,
    onCue: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inspected by produceState<Pair<Boolean, TrackTechnicalDetails?>>(
        initialValue = true to null,
        track.id,
        track.uri,
    ) {
        val loaded = runCatching { onInspect(track) }.getOrNull()
        value = false to loaded
    }
    val inspecting = inspected.first
    val technical = inspected.second
    val effectiveDurationMs = technical?.durationMs ?: track.durationMs
    val qualityBadge = remember(track.displayName, track.sizeBytes, effectiveDurationMs) {
        UiPresentation.highBitrateBadge(track.displayName, track.sizeBytes, effectiveDurationMs)
    }
    val artistTag = LibraryRules.sanitizedTag(track.artist)
    val albumTag = LibraryRules.sanitizedTag(track.album)
    val genreTag = LibraryRules.sanitizedTag(track.genre)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = foh.surfaceDeck,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = foh.borderSubtle) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DETAIL LAGU & SPEK AUDIO",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = foh.safeAccent,
                )
                TextButton(onClick = onDismiss) {
                    Text("Tutup")
                }
            }

            // Hero header: unlimited wrapping so long song title is never cut off.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(
                    artworkKey = track.artworkKey,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = UiPresentation.title(track.displayName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val creatorLine = listOfNotNull(artistTag, albumTag).joinToString(" · ")
                    if (creatorLine.isNotEmpty()) {
                        Text(
                            text = creatorLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = foh.surfaceDeep,
                            border = BorderStroke(1.dp, foh.borderSubtle),
                        ) {
                            Text(
                                text = LibraryRules.audioFormatLabel(track.displayName),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                        if (qualityBadge != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = foh.safeBg,
                                border = BorderStroke(1.dp, foh.safeBorder),
                            ) {
                                Text(
                                    text = qualityBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Bold,
                                    color = foh.safeAccent,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Technical Audio Specs Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Spesifikasi Audio",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (inspecting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FohSpinningIndicator(size = 13.dp, strokeWidth = 1.8.dp, color = foh.safeAccent)
                                Text(
                                    text = "Membaca header…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                    TrackDetailSpecRow(
                        label = "Format / Codec",
                        value = UiPresentation.codecDisplayLabel(track.displayName, technical?.mimeType),
                        monospace = true,
                    )
                    TrackDetailSpecRow(
                        label = "Bit Rate",
                        value = UiPresentation.bitrateDetailLabel(track.sizeBytes, effectiveDurationMs, technical?.bitrateBps),
                        monospace = true,
                        highlightAccent = true,
                    )
                    TrackDetailSpecRow(
                        label = "Sample Rate",
                        value = UiPresentation.sampleRateLabel(technical?.sampleRateHz),
                        monospace = true,
                    )
                    TrackDetailSpecRow(
                        label = "Channel",
                        value = UiPresentation.channelLayoutLabel(technical?.channelCount),
                        monospace = true,
                    )
                    if (technical?.bitsPerSample != null) {
                        TrackDetailSpecRow(
                            label = "Bit Depth",
                            value = "${technical.bitsPerSample}-bit",
                            monospace = true,
                        )
                    }
                    TrackDetailSpecRow(
                        label = "Durasi",
                        value = LibraryRules.durationLabel(effectiveDurationMs),
                        monospace = true,
                    )
                    TrackDetailSpecRow(
                        label = "Ukuran File",
                        value = UiPresentation.exactFileSizeLabel(track.sizeBytes),
                        monospace = true,
                    )
                }
            }

            // Library & Tag Metadata Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            text = "File & Metadata Pustaka",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                        TrackDetailSpecRow(label = "Nama File Asli", value = track.displayName, monospace = true)
                        artistTag?.let { TrackDetailSpecRow(label = "Artis", value = it) }
                        albumTag?.let { TrackDetailSpecRow(label = "Album", value = it) }
                        genreTag?.let { TrackDetailSpecRow(label = "Genre", value = it) }
                        TrackDetailSpecRow(
                            label = "Playlist",
                            value = if (playlistNames.isEmpty()) "Belum masuk playlist" else playlistNames.joinToString(", "),
                        )
                        TrackDetailSpecRow(
                            label = "Ditambahkan",
                            value = UiPresentation.addedDateTimeLabel(track.addedAt),
                            monospace = true,
                        )
                        track.sha256?.takeIf { it.isNotBlank() }?.let { digest ->
                            TrackDetailSpecRow(label = "SHA-256", value = digest, monospace = true)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onCue,
                    enabled = connected,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = foh.warnBg,
                        contentColor = foh.warnAccent,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Icon(UiIcons.CueStart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Cue", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onPlay,
                    enabled = connected,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.2f).heightIn(min = 44.dp),
                ) {
                    Icon(UiIcons.Play, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Putar", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onRename,
                    enabled = renameEnabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Icon(UiIcons.Rename, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Nama", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun TrackDetailSpecRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    highlightAccent: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (highlightAccent) FontWeight.SemiBold else FontWeight.Medium,
            color = if (highlightAccent) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
