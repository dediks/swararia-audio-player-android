package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.brandskin.BrandSkinConfig
import com.swararia.audiolab.brandskin.PrestigeBrandEmblem
import java.util.Locale

@Composable
internal fun LibraryDialogHost(
    dialog: LibraryDialog?,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    selectedIds: Set<Long>,
    organizerBusy: Boolean,
    playlistActions: PlaylistUiActions,
    onPlaylistCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        LibraryDialog.CreatePlaylist -> CreateOrRenamePlaylistDialog(
            title = "Buat playlist baru",
            confirmLabel = "Buat",
            busy = organizerBusy,
            onDismiss = onDismiss,
            onConfirm = { name ->
                playlistActions.onCreatePlaylist(name, null)
                onPlaylistCreated()
            },
        )
        is LibraryDialog.RenamePlaylist -> key(dialog.playlist.id, dialog.playlist.name) {
            CreateOrRenamePlaylistDialog(
                title = "Ganti nama playlist",
                confirmLabel = "Simpan",
                initialName = dialog.playlist.name,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onConfirm = { name -> playlistActions.onRenamePlaylist(dialog.playlist.id, name) },
            )
        }
        is LibraryDialog.DeletePlaylist -> key(dialog.playlist.id) {
            DeletePlaylistDialog(
                playlist = dialog.playlist,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onDelete = { playlistId -> playlistActions.onDeletePlaylist(playlistId) },
            )
        }
        is LibraryDialog.PickPlaylistForTrack -> key(dialog.track.id) {
            PlaylistPickerDialog(
                track = dialog.track,
                playlists = playlists,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onToggleMembership = { playlistId, add ->
                    if (add) playlistActions.onAddTrackToPlaylist(playlistId, dialog.track.id)
                    else playlistActions.onRemoveTrackFromPlaylist(playlistId, dialog.track.id)
                },
                onCreatePlaylistWithTrack = { name, trackId ->
                    playlistActions.onCreatePlaylist(name, trackId)
                },
            )
        }
        LibraryDialog.BatchAddToPlaylist -> {
            val batchTracks = remember(tracks, selectedIds) { tracks.filter { it.id in selectedIds } }
            BatchPlaylistPickerDialog(
                selectedTracks = batchTracks,
                playlists = playlists,
                busy = organizerBusy,
                onDismiss = onDismiss,
                onAddTracksToPlaylist = { playlistId, ids ->
                    playlistActions.onAddTracksToPlaylist(playlistId, ids)
                },
                onCreatePlaylistWithTracks = { name, ids ->
                    playlistActions.onCreatePlaylistWithTracks(name, ids)
                },
            )
        }
        is LibraryDialog.AddTracksToPlaylist -> {
            playlists.firstOrNull { it.id == dialog.playlistId }?.let { targetPlaylist ->
                AddTracksToPlaylistDialog(
                    playlist = targetPlaylist,
                    tracks = tracks,
                    busy = organizerBusy,
                    onDismiss = onDismiss,
                    onToggleTrack = { trackId, add ->
                        if (add) playlistActions.onAddTrackToPlaylist(targetPlaylist.id, trackId)
                        else playlistActions.onRemoveTrackFromPlaylist(targetPlaylist.id, trackId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun LibraryEmptyPlaceholder(hasTracks: Boolean, onAdd: () -> Unit, onClearQuery: () -> Unit) {
    if (!hasTracks) EmptyMessage("Pustaka masih kosong", "Tambahkan tautan berizin atau impor audio dari HP.", UiIcons.Library) {
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tambah audio") }
    } else EmptyMessage("Audio tidak ditemukan", "Coba nama lain atau hapus kata pencarian.", UiIcons.Search) {
        TextButton(onClick = onClearQuery, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hapus pencarian") }
    }
}

@Composable
internal fun LibraryGroupHeader(
    group: LibraryTrackGroup<AudioTrack>,
    collapsed: Boolean,
    connected: Boolean,
    onToggle: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (collapsed) UiIcons.ChevronRight else UiIcons.ChevronDown,
                contentDescription = if (collapsed) "Buka grup ${group.label}" else "Tutup grup ${group.label}",
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    UiPresentation.groupSummary(group.items.size, group.totalDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connected && group.items.isNotEmpty()) {
                GlyphButton(UiIcons.Play, "Putar grup ${group.label}", onPlayAll)
            }
        }
    }
}

@Composable
internal fun trackSelectionPalette(
    selected: Boolean,
    wantsPlay: Boolean,
    selectionChecked: Boolean?,
    highlighted: Boolean = false,
): Pair<Color, BorderStroke> {
    val activeContainer = when {
        highlighted -> FohTheme.colors.safeBg
        selectionChecked == true -> FohTheme.colors.safeBg
        selected && wantsPlay -> FohTheme.colors.safeBg
        selected -> FohTheme.colors.warnBg
        else -> FohTheme.colors.surfaceCard
    }
    val borderStroke = when {
        highlighted || selectionChecked == true || (selected && wantsPlay) -> BorderStroke(1.dp, FohTheme.colors.safeBorder)
        selected -> BorderStroke(1.dp, FohTheme.colors.warnBorder)
        else -> BorderStroke(1.dp, FohTheme.colors.borderSubtle.copy(alpha = 0.7f))
    }
    return activeContainer to borderStroke
}

/** Shared artwork frame with a centered Play/Pause toggle overlay across Library track views. */
@Composable
internal fun LibraryPlayableArtwork(
    artworkKey: String?,
    selected: Boolean,
    wantsPlay: Boolean,
    connected: Boolean,
    showPlayControl: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    cornerRadius: Dp = 11.dp,
    buttonSize: Dp = 28.dp,
    iconSize: Dp = 14.dp,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        FohAnimatedTrackArtwork(
            artworkKey = artworkKey,
            active = selected,
            playing = wantsPlay,
            size = size,
            cornerRadius = cornerRadius,
        )
        if (showPlayControl) {
            LibraryArtworkPlayButton(
                selected = selected,
                wantsPlay = wantsPlay,
                enabled = connected,
                onClick = onTogglePlay,
                size = buttonSize,
                iconSize = iconSize,
            )
        }
    }
}

/** Circular frosted/FOH Play-Pause button overlaid at the center of track artwork. */
@Composable
internal fun LibraryArtworkPlayButton(
    selected: Boolean,
    wantsPlay: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    iconSize: Dp = 14.dp,
) {
    val foh = FohTheme.colors
    val isPlaying = selected && wantsPlay
    val containerColor = when {
        isPlaying -> foh.safeBg.copy(alpha = 0.92f)
        selected -> foh.warnBg.copy(alpha = 0.92f)
        else -> Color(0xAD080E0B)
    }
    val borderColor = when {
        isPlaying -> foh.safeBorder
        selected -> foh.warnBorder
        else -> Color.White.copy(alpha = 0.28f)
    }
    val contentColor = when {
        isPlaying -> foh.safeAccent
        selected -> foh.warnAccent
        else -> Color.White
    }
    val description = when {
        isPlaying -> "Jeda audio"
        selected -> "Lanjutkan audio"
        else -> "Putar audio"
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isPlaying) UiIcons.Pause else UiIcons.Play,
                contentDescription = description,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

internal fun libraryTrackTechSpec(track: AudioTrack, includeDuration: Boolean = false): String = listOfNotNull(
    LibraryRules.audioFormatLabel(track.displayName),
    UiPresentation.effectiveBitrateKbps(track.sizeBytes, track.durationMs)?.let { "$it kbps" },
    UiPresentation.fileSizeLabel(track.sizeBytes).takeIf { it.isNotEmpty() },
    if (includeDuration) LibraryRules.durationLabel(track.durationMs) else null,
).joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryCompactAudioRow(
    track: AudioTrack,
    selected: Boolean,
    wantsPlay: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    leadingIndex: Int? = null,
    selectionChecked: Boolean? = null,
    playlistBadge: String? = null,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPlay: () -> Unit,
    onCue: () -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onInspectDetails: () -> Unit = {},
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val inSelection = selectionChecked != null
    val (activeContainer, borderStroke) = trackSelectionPalette(
        selected = selected,
        wantsPlay = wantsPlay,
        selectionChecked = selectionChecked,
    )
    val compactSpec = UiPresentation.compactTrackSpec(track.displayName, track.sizeBytes, track.durationMs)
    val artistOrBadge = LibraryRules.sanitizedTag(track.artist) ?: playlistBadge

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = activeContainer,
        border = borderStroke,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { this.selected = selected || selectionChecked == true }
                .combinedClickable(
                    enabled = inSelection || connected,
                    onClickLabel = when {
                        inSelection -> "Pilih ${UiPresentation.title(track.displayName)}"
                        selected && wantsPlay -> "Buka pemutar penuh ${UiPresentation.title(track.displayName)}"
                        selected -> "Lanjutkan ${UiPresentation.title(track.displayName)}"
                        else -> "Putar ${UiPresentation.title(track.displayName)}"
                    },
                    onClick = { if (inSelection) onToggleSelect() else onPlay() },
                    onLongClick = { onLongPress() },
                )
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (inSelection) {
                Checkbox(
                    checked = selectionChecked == true,
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp),
                )
            } else if (leadingIndex != null) {
                Text(
                    text = String.format(Locale.ROOT, "%02d", leadingIndex),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (selected) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LibraryPlayableArtwork(
                artworkKey = track.artworkKey,
                selected = selected,
                wantsPlay = wantsPlay,
                connected = connected,
                showPlayControl = !inSelection,
                onTogglePlay = { if (selected) onTogglePlayPause() else onPlay() },
                size = 40.dp,
                cornerRadius = 9.dp,
                buttonSize = 24.dp,
                iconSize = 12.dp,
            )

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = when {
                        selected && wantsPlay -> FohTheme.colors.safeAccent
                        selected -> FohTheme.colors.warnAccent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artistOrBadge != null) {
                    Text(
                        text = artistOrBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (selected && !inSelection && wantsPlay) {
                FohWaveBarsIndicator(width = 11.dp, height = 10.dp)
            }

            Text(
                text = compactSpec,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (selected && wantsPlay) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )

            if (!inSelection) {
                LibraryTrackActionMenuButton(
                    track = track,
                    selected = selected,
                    connected = connected,
                    renameEnabled = renameEnabled,
                    compact = true,
                    onShowDetails = onInspectDetails,
                    onCue = onCue,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGridAudioCard(
    track: AudioTrack,
    selected: Boolean,
    wantsPlay: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    leadingIndex: Int? = null,
    selectionChecked: Boolean? = null,
    playlistBadge: String? = null,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPlay: () -> Unit,
    onCue: () -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onInspectDetails: () -> Unit = {},
    onRename: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inSelection = selectionChecked != null
    val (activeContainer, borderStroke) = trackSelectionPalette(
        selected = selected,
        wantsPlay = wantsPlay,
        selectionChecked = selectionChecked,
    )
    val formatLabel = LibraryRules.audioFormatLabel(track.displayName)
    val compactSpec = UiPresentation.compactTrackSpec(track.displayName, track.sizeBytes, track.durationMs)
    val subtitle = playlistBadge?.let { "↳ $it" }
        ?: LibraryRules.sanitizedTag(track.artist)
        ?: UiPresentation.fileSizeLabel(track.sizeBytes).ifEmpty { formatLabel }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = activeContainer,
        border = borderStroke,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.selected = selected || selectionChecked == true }
                .combinedClickable(
                    enabled = inSelection || connected,
                    onClickLabel = when {
                        inSelection -> "Pilih ${UiPresentation.title(track.displayName)}"
                        selected && wantsPlay -> "Buka pemutar penuh ${UiPresentation.title(track.displayName)}"
                        selected -> "Lanjutkan ${UiPresentation.title(track.displayName)}"
                        else -> "Putar ${UiPresentation.title(track.displayName)}"
                    },
                    onClick = { if (inSelection) onToggleSelect() else onPlay() },
                    onLongClick = { onLongPress() },
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp)
                    .aspectRatio(1.12f)
                    .clip(RoundedCornerShape(10.dp))
                    .let { base ->
                        if (selected) {
                            base.border(
                                width = 1.6.dp,
                                color = if (wantsPlay) FohTheme.colors.safeAccent else FohTheme.colors.warnAccent,
                                shape = RoundedCornerShape(10.dp),
                            )
                        } else {
                            base
                        }
                    },
            ) {
                TrackArtwork(
                    artworkKey = track.artworkKey,
                    modifier = Modifier.fillMaxSize(),
                )

                if (inSelection) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xCC080E0B),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Box(modifier = Modifier.padding(2.dp)) {
                            Checkbox(
                                checked = selectionChecked == true,
                                onCheckedChange = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else if (selected && wantsPlay) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Color(0xCC080E0B),
                        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FohWaveBarsIndicator(width = 11.dp, height = 9.dp)
                            Text(
                                text = "PLAY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = FohTheme.colors.safeAccent,
                            )
                        }
                    }
                } else if (selected) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Color(0xCC080E0B),
                        border = BorderStroke(1.dp, FohTheme.colors.warnBorder),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = "STANDBY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FohTheme.colors.warnAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                } else if (leadingIndex != null) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Color(0xBA080E0B),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = String.format(Locale.ROOT, "#%02d", leadingIndex),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xBD080E0B))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = compactSpec,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                if (!inSelection) {
                    LibraryArtworkPlayButton(
                        selected = selected,
                        wantsPlay = wantsPlay,
                        enabled = connected,
                        onClick = { if (selected) onTogglePlayPause() else onPlay() },
                        size = 38.dp,
                        iconSize = 18.dp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        selected && wantsPlay -> FohTheme.colors.safeAccent
                        selected -> FohTheme.colors.warnAccent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!inSelection) {
                        LibraryTrackActionMenuButton(
                            track = track,
                            selected = selected,
                            connected = connected,
                            renameEnabled = renameEnabled,
                            compact = true,
                            onShowDetails = onInspectDetails,
                            onCue = onCue,
                            onAddToPlaylist = onAddToPlaylist,
                            onRemoveFromPlaylist = onRemoveFromPlaylist,
                            onRename = onRename,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryMiniBadge(
    label: String,
    containerColor: Color,
    borderColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun RundownReorderIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    containerColor: Color,
    borderColor: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) containerColor else FohTheme.colors.surfaceDeep.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (enabled) borderColor else FohTheme.colors.borderSubtle.copy(alpha = 0.4f),
        ),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = 0.28f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun WelcomeBrandBanner(
    brand: BrandSkinConfig,
    onSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.safeBg,
        border = BorderStroke(1.dp, foh.safeBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrestigeBrandEmblem(
                logoKey = brand.customLogoKey,
                size = 28.dp,
                shimmerEnabled = brand.prestigeAnimations,
            )
            Text(
                text = "Pasang Logo & Nama Rental",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = foh.safeAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onSetup,
                shape = RoundedCornerShape(999.dp),
                color = foh.safeAccent,
            ) {
                Text(
                    text = "Atur",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = foh.surfaceDeep,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            CompactCircleButton(
                icon = UiIcons.Close,
                description = "Tutup kartu sambutan",
                onClick = onDismiss,
                size = 30.dp,
                iconSize = 16.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
