package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryCompactHeader(
    section: LibrarySectionTab,
    trackCount: Int,
    playlistCount: Int,
    selectionMode: Boolean,
    query: String,
    groupMode: LibraryGroupMode,
    alphabetical: Boolean,
    importEnabled: Boolean,
    actionBusy: Boolean,
    onSelectSection: (LibrarySectionTab) -> Unit,
    onImport: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectGroupMode: (LibraryGroupMode) -> Unit,
    onToggleSort: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    var appMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoyalBrandLogo(size = 28.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SETELO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "by Swararia",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (section == LibrarySectionTab.ALL_TRACKS) {
                FohHeaderChipButton(
                    label = "Import",
                    icon = UiIcons.Plus,
                    active = false,
                    enabled = importEnabled,
                    onClick = onImport,
                )
                FohHeaderChipButton(
                    label = "Select",
                    icon = UiIcons.Check,
                    active = selectionMode,
                    enabled = !actionBusy && trackCount > 0,
                    onClick = onToggleSelectionMode,
                )
            } else {
                FohHeaderChipButton(
                    label = "New Playlist",
                    icon = UiIcons.Plus,
                    active = true,
                    enabled = !actionBusy,
                    onClick = onCreatePlaylist,
                )
            }
            Box {
                CompactCircleButton(
                    icon = UiIcons.More,
                    description = "Menu Pustaka",
                    onClick = { appMenu = true },
                    size = 36.dp,
                    iconSize = 20.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Riwayat nama & urungkan") },
                        leadingIcon = { Icon(UiIcons.History, null) },
                        onClick = { appMenu = false; onHistory() },
                    )
                    DropdownMenuItem(
                        text = { Text("Pengaturan & tentang") },
                        leadingIcon = { Icon(UiIcons.Settings, null) },
                        onClick = { appMenu = false; onSettings() },
                    )
                }
            }
        }

        FohSegmentedBar(
            options = LibrarySectionTab.entries,
            selected = section,
            label = { tab ->
                val count = if (tab == LibrarySectionTab.ALL_TRACKS) trackCount else playlistCount
                "${tab.label} ($count)"
            },
            onSelect = onSelectSection,
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    if (section == LibrarySectionTab.ALL_TRACKS) "Cari track, artist, format, atau playlist…"
                    else "Cari nama playlist atau track di dalamnya…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(UiIcons.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { GlyphButton(UiIcons.Close, "Hapus pencarian pustaka", { onQueryChange("") }) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = FohTheme.colors.borderSubtle,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        if (section == LibrarySectionTab.ALL_TRACKS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LibraryGroupMode.entries.forEach { mode ->
                        FohFilterChip(
                            label = mode.label,
                            selected = mode == groupMode,
                            onClick = { onSelectGroupMode(mode) },
                        )
                    }
                }
                Box {
                    Surface(
                        onClick = { sortMenu = true },
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        modifier = Modifier.semantics {
                            contentDescription = "Urutkan audio: ${if (alphabetical) "A–Z" else "Terbaru"}"
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = if (alphabetical) "A–Z" else "Terbaru",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                UiIcons.ChevronDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Terbaru") },
                            onClick = { onToggleSort(false); sortMenu = false },
                            trailingIcon = if (!alphabetical) ({ Icon(UiIcons.Check, null) }) else null,
                        )
                        DropdownMenuItem(
                            text = { Text("A–Z") },
                            onClick = { onToggleSort(true); sortMenu = false },
                            trailingIcon = if (alphabetical) ({ Icon(UiIcons.Check, null) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryBatchActionBar(
    selectedCount: Int,
    totalVisibleCount: Int,
    busy: Boolean,
    onToggleSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "$selectedCount dipilih",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = FohTheme.colors.safeAccent,
                    modifier = Modifier.weight(1f),
                )
                if (totalVisibleCount > 0) {
                    TextButton(
                        onClick = onToggleSelectAll,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (selectedCount >= totalVisibleCount) "Reset" else "Pilih Semua",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                TextButton(
                    onClick = onCancel,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Batal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onAddToPlaylist,
                    enabled = !busy && selectedCount > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.safeBg,
                        contentColor = FohTheme.colors.safeAccent,
                    ),
                    modifier = Modifier
                        .weight(1.35f)
                        .heightIn(min = 44.dp),
                ) {
                    Icon(UiIcons.Queue, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ Ke Playlist", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                FilledTonalButton(
                    onClick = onDeleteSelected,
                    enabled = !busy && selectedCount > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.alertBg,
                        contentColor = FohTheme.colors.alertAccent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    Icon(UiIcons.Delete, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Hapus", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun PlaylistShowCard(
    playlist: LibraryPlaylist,
    tracks: List<AudioTrack>,
    trackById: Map<Long, AudioTrack>,
    totalDurationMs: Long,
    isActiveShow: Boolean,
    connected: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onLoadCue: () -> Unit,
    onPlayShow: () -> Unit,
    onAddTracks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val previewLine = remember(playlist.trackIds, trackById) {
        UiPresentation.playlistPreviewSummary(playlist.trackIds, { id -> trackById[id]?.displayName })
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, if (isActiveShow) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = FohTheme.colors.safeBg,
                    border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(UiIcons.Queue, null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActiveShow) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = FohTheme.colors.safeBg,
                                border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
                            ) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FohTheme.colors.safeAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = UiPresentation.playlistStatsSummary(tracks.size, totalDurationMs),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isActiveShow) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    CompactCircleButton(
                        icon = UiIcons.More,
                        description = "Menu playlist ${playlist.name}",
                        onClick = { menu = true },
                        size = 34.dp,
                        iconSize = 18.dp,
                        containerColor = FohTheme.colors.surfaceDeep,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Tambah lagu") },
                            leadingIcon = { Icon(UiIcons.Plus, null) },
                            enabled = !busy,
                            onClick = { menu = false; onAddTracks() },
                        )
                        DropdownMenuItem(
                            text = { Text("Ganti nama") },
                            leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = !busy,
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(UiIcons.Delete, null) },
                            enabled = !busy,
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            Surface(
                onClick = onOpen,
                shape = RoundedCornerShape(10.dp),
                color = FohTheme.colors.surfaceDeep,
                border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = previewLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onLoadCue,
                    enabled = connected && tracks.isNotEmpty() && !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.warnBg,
                        contentColor = FohTheme.colors.warnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                ) {
                    Icon(UiIcons.CueStart, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Load Cue (00:00)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onPlayShow,
                    enabled = connected && tracks.isNotEmpty() && !busy,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                ) {
                    Icon(UiIcons.Play, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Play Show", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(10.dp),
                    color = FohTheme.colors.surfaceDeep,
                    border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            UiIcons.ChevronRight,
                            contentDescription = "Buka rundown ${playlist.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistRundownHeader(
    playlist: LibraryPlaylist,
    tracks: List<AudioTrack>,
    connected: Boolean,
    busy: Boolean,
    reorderMode: Boolean,
    onBack: () -> Unit,
    onLoadCue: () -> Unit,
    onPlayShow: () -> Unit,
    onAddTracks: () -> Unit,
    onToggleReorder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val totalMs = remember(tracks) { tracks.sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) } }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = FohTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactCircleButton(
                    icon = UiIcons.Back,
                    description = "Kembali ke daftar playlist",
                    onClick = onBack,
                    size = 38.dp,
                    iconSize = 20.dp,
                    containerColor = FohTheme.colors.surfaceDeep,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${UiPresentation.playlistStatsSummary(tracks.size, totalMs)} · Rundown Order",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = FohTheme.colors.safeAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    CompactCircleButton(
                        icon = UiIcons.More,
                        description = "Opsi playlist",
                        onClick = { menu = true },
                        size = 36.dp,
                        iconSize = 18.dp,
                        containerColor = FohTheme.colors.surfaceDeep,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Ganti nama playlist") },
                            leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = !busy,
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(UiIcons.Delete, null) },
                            enabled = !busy,
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onLoadCue,
                    enabled = connected && tracks.isNotEmpty() && !busy,
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FohTheme.colors.warnBg,
                        contentColor = FohTheme.colors.warnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.CueStart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Load Cue (00:00)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onPlayShow,
                    enabled = connected && tracks.isNotEmpty() && !busy,
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp),
                ) {
                    Icon(UiIcons.Play, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Play Show", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onAddTracks,
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp),
                ) {
                    Icon(UiIcons.Plus, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Add Tracks", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
                Surface(
                    onClick = onToggleReorder,
                    enabled = !busy && tracks.size > 1,
                    shape = RoundedCornerShape(10.dp),
                    color = if (reorderMode) FohTheme.colors.safeBg else FohTheme.colors.surfaceDeep,
                    border = BorderStroke(
                        1.dp,
                        if (reorderMode) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            UiIcons.Reorder,
                            contentDescription = null,
                            tint = if (tracks.size <= 1 || busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else if (reorderMode) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (reorderMode) "Reorder: ON" else "Reorder",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (tracks.size <= 1 || busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else if (reorderMode) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RundownSyncNoticeBanner() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FohTheme.colors.safeBg.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, FohTheme.colors.safeBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(UiIcons.Check, null, tint = FohTheme.colors.safeAccent, modifier = Modifier.size(16.dp))
            Text(
                text = "Urutan rundown tersimpan otomatis ke database & sinkron dengan Cue List saat Load Cue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
