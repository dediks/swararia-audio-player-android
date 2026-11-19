package com.swararia.audiolab.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swararia.audiolab.FohFilterChip
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.LibraryRules
import com.swararia.audiolab.UiIcons
import java.util.Locale

private fun formatTransferBytes(bytes: Long): String {
    if (bytes >= 1024L * 1024L) {
        return String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }
    if (bytes >= 1024L) return "${bytes / 1024L} KB"
    return "$bytes B"
}

@Composable
internal fun RemoteTransferActionStrip(
    state: DesktopRemoteUiState,
    onOpenFolderSync: () -> Unit,
    onOpenUpload: () -> Unit,
    onCancelFolderSync: () -> Unit,
    onCancelUpload: () -> Unit,
) {
    val showSync = state.canSyncFolders || state.folderSync.running || state.folderSync.lastSummary != null
    val showUpload = state.canUploadToDesktop || state.batchUpload.running || state.batchUpload.lastSummary != null
    if (!showSync && !showUpload) return

    val foh = FohTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.canSyncFolders || state.canUploadToDesktop) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.canSyncFolders) {
                    OutlinedButton(
                        onClick = onOpenFolderSync,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, foh.safeBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(UiIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = foh.safeAccent)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.folderSync.running) "Progres Sync…" else "Sinkron Folder",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.canUploadToDesktop) {
                    OutlinedButton(
                        onClick = onOpenUpload,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, foh.safeBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(UiIcons.ArrowUp, contentDescription = null, modifier = Modifier.size(16.dp), tint = foh.safeAccent)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.batchUpload.running) "Mengunggah…" else "Unggah ke PC",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (state.folderSync.running) {
            val sync = state.folderSync
            val progress = if (sync.totalFiles > 0) (sync.completedFiles.toFloat() / sync.totalFiles.toFloat()).coerceIn(0f, 1f) else 0f
            ActiveTransferStatusBar(
                title = "Sinkronisasi (${sync.completedFiles}/${sync.totalFiles})",
                subtitle = sync.currentFileName ?: "Memproses manifest…",
                progress = if (sync.totalFiles > 0) progress else null,
                onCancel = onCancelFolderSync,
            )
        } else if (state.batchUpload.running) {
            val up = state.batchUpload
            val progress = if (up.totalFiles > 0) (up.completedFiles.toFloat() / up.totalFiles.toFloat()).coerceIn(0f, 1f) else 0f
            ActiveTransferStatusBar(
                title = "Mengunggah ke PC (${up.completedFiles}/${up.totalFiles})",
                subtitle = up.currentFileName ?: "Menyiapkan stream…",
                progress = if (up.totalFiles > 0) progress else null,
                onCancel = onCancelUpload,
            )
        }
    }
}

@Composable
private fun ActiveTransferStatusBar(
    title: String,
    subtitle: String,
    progress: Float?,
    onCancel: () -> Unit,
) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = foh.safeBg,
        border = BorderStroke(1.dp, foh.safeBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = foh.safeAccent)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Batal", color = foh.alertAccent, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                FohIndeterminateBar(height = 3.dp)
            }
        }
    }
}

@Composable
internal fun FolderSyncDialog(
    state: DesktopRemoteFolderSyncState,
    canSyncFolders: Boolean,
    onRefreshFolders: () -> Unit,
    onStartSync: (List<String>) -> Unit,
    onCancelSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    var selectedIds by remember(state.folders) {
        mutableStateOf(state.folders.filter { it.trackCount > 0 }.map { it.folderId }.toSet())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = foh.surfaceCard,
            border = BorderStroke(1.dp, foh.borderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .heightIn(max = 620.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(UiIcons.Folder, contentDescription = null, tint = foh.safeAccent, modifier = Modifier.size(20.dp))
                    Text(
                        text = "Sinkron Folder & Playlist (PC → HP)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefreshFolders, enabled = canSyncFolders && !state.loadingFolders && !state.running, modifier = Modifier.size(32.dp)) {
                        Icon(UiIcons.Refresh, contentDescription = "Segarkan daftar folder", modifier = Modifier.size(18.dp))
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = foh.warnBg,
                    border = BorderStroke(1.dp, foh.warnBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Fase ini hanya menyinkronkan audio terkompresi (.mp3, .m4a, .aac, .opus, .ogg, .webm) maks. 150 MB/file. File .wav & .flac dilewati otomatis; duplikat SHA-256 langsung dilewati.",
                        style = MaterialTheme.typography.labelSmall,
                        color = foh.warnAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                if (state.hostWarnings.isNotEmpty()) {
                    NoticeCard(
                        text = state.hostWarnings.joinToString(" "),
                        icon = UiIcons.Warning,
                    )
                }

                if (state.loadingFolders) {
                    FohIndeterminateBar(height = 3.dp)
                    Text("Memuat daftar folder dari PC…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (state.folders.isEmpty()) {
                    NoticeCard(
                        text = if (canSyncFolders) "Belum ada folder lagu terkompresi yang dibagikan PC."
                        else "Aktifkan 'Izinkan sinkronisasi folder & playlist (HTTPS)' pada modal Android Remote di PC.",
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FohFilterChip(
                            label = "Pilih Semua",
                            selected = selectedIds.size == state.folders.count { it.trackCount > 0 },
                            onClick = { selectedIds = state.folders.filter { it.trackCount > 0 }.map { it.folderId }.toSet() },
                        )
                        FohFilterChip(
                            label = "Kosongkan",
                            selected = selectedIds.isEmpty(),
                            onClick = { selectedIds = emptySet() },
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 120.dp, max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.folders, key = { it.folderId }) { folder ->
                            val checked = folder.folderId in selectedIds
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (checked) foh.safeBg else foh.surfaceDeep,
                                border = BorderStroke(1.dp, if (checked) foh.safeBorder else foh.borderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !state.running && folder.trackCount > 0) {
                                        selectedIds = if (checked) selectedIds - folder.folderId else selectedIds + folder.folderId
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        enabled = !state.running && folder.trackCount > 0,
                                        onCheckedChange = null,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(folder.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val warnParts = buildList {
                                            add("${folder.trackCount} lagu")
                                            add(formatTransferBytes(folder.totalBytes))
                                            if (folder.excludedWavFlacCount > 0) add("${folder.excludedWavFlacCount} WAV/FLAC skip")
                                            if (folder.oversizedCount > 0) add("${folder.oversizedCount} >150MB skip")
                                        }
                                        Text(warnParts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.running) {
                    val pct = if (state.totalFiles > 0) (state.completedFiles.toFloat() / state.totalFiles.toFloat()).coerceIn(0f, 1f) else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Mengimpor (${state.completedFiles}/${state.totalFiles}): ${state.currentFileName ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = foh.safeAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(5.dp))
                    }
                }
                state.error?.let { NoticeCard(text = it, isError = true, icon = UiIcons.Warning) }
                state.lastSummary?.let { NoticeCard(text = it, icon = UiIcons.Check) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.running) {
                        TextButton(onClick = onCancelSync) { Text("Batalkan", color = foh.alertAccent) }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Tutup") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onStartSync(selectedIds.toList()) },
                            enabled = canSyncFolders && selectedIds.isNotEmpty() && !state.loadingFolders,
                        ) {
                            Text("Sinkronkan (${selectedIds.size})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UploadToDesktopDialog(
    localTracks: List<DesktopRemoteLocalTrackSummary>,
    localPlaylists: List<DesktopRemoteLocalPlaylistSummary>,
    batchState: DesktopRemoteBatchUploadState,
    canUpload: Boolean,
    onStartUpload: (List<Long>, String?) -> Unit,
    onCancelUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foh = FohTheme.colors
    var pickByPlaylist by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var targetSubfolder by remember { mutableStateOf("") }
    var selectedTrackIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }

    val filteredTracks = remember(localTracks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) localTracks else localTracks.filter { it.displayName.contains(q, ignoreCase = true) }
    }

    val effectiveIds: List<Long> = remember(pickByPlaylist, selectedTrackIds, selectedPlaylistId, localPlaylists) {
        if (pickByPlaylist) {
            localPlaylists.firstOrNull { it.id == selectedPlaylistId }?.trackIds.orEmpty()
        } else {
            selectedTrackIds.toList()
        }
    }

    val tracksById = remember(localTracks) { localTracks.associateBy { it.trackId } }
    val selectedSummaries = remember(effectiveIds, tracksById) { effectiveIds.mapNotNull { tracksById[it] } }
    val selectedWavFlacCount = remember(selectedSummaries) { selectedSummaries.count { it.isWavOrFlac } }
    val selectedOverCount = remember(selectedSummaries) { selectedSummaries.count { !it.isWavOrFlac && it.sizeBytes > 150L * 1024L * 1024L } }
    val selectedValidCount = remember(selectedSummaries) {
        selectedSummaries.count { it.isSupportedCompressedAudio && it.sizeBytes in 1L..(150L * 1024L * 1024L) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = foh.surfaceCard,
            border = BorderStroke(1.dp, foh.borderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .heightIn(max = 650.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(UiIcons.ArrowUp, contentDescription = null, tint = foh.safeAccent, modifier = Modifier.size(20.dp))
                    Text(
                        text = "Unggah Audio ke PC (HP → PC)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = foh.warnBg,
                    border = BorderStroke(1.dp, foh.warnBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Hanya audio terkompresi (.mp3/.m4a/.aac/.opus/.ogg/.webm) maks. 150 MB/file yang dapat diunggah. File WAV/FLAC dilewati dengan peringatan pada fase ini.",
                        style = MaterialTheme.typography.labelSmall,
                        color = foh.warnAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FohFilterChip(
                        label = "Pilih Lagu (${localTracks.size})",
                        selected = !pickByPlaylist,
                        onClick = { pickByPlaylist = false },
                    )
                    FohFilterChip(
                        label = "Per Playlist (${localPlaylists.size})",
                        selected = pickByPlaylist,
                        onClick = { pickByPlaylist = true },
                    )
                }

                if (!pickByPlaylist) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { if (it.length <= 80) searchQuery = it },
                        singleLine = true,
                        label = { Text("Cari lagu di Library HP") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = targetSubfolder,
                    onValueChange = { if (it.length <= 60) targetSubfolder = it },
                    singleLine = true,
                    enabled = !batchState.running,
                    label = { Text("Folder playlist tujuan di PC (opsional)") },
                    placeholder = { Text("Contoh: Show Malam") },
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(min = 120.dp, max = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (pickByPlaylist) {
                        if (localPlaylists.isEmpty()) {
                            item("no_pl") { NoticeCard("Belum ada playlist di Library HP ini.") }
                        } else {
                            items(localPlaylists, key = { "pl_${it.id}" }) { pl ->
                                val selected = pl.id == selectedPlaylistId
                                val wavCount = pl.trackIds.mapNotNull { tracksById[it] }.count { it.isWavOrFlac }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) foh.safeBg else foh.surfaceDeep,
                                    border = BorderStroke(1.dp, if (selected) foh.safeBorder else foh.borderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !batchState.running && pl.trackIds.isNotEmpty()) {
                                            selectedPlaylistId = pl.id
                                            if (targetSubfolder.isBlank()) targetSubfolder = pl.name.take(60)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        RadioButton(selected = selected, onClick = null, enabled = !batchState.running && pl.trackIds.isNotEmpty())
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(pl.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            val meta = if (wavCount > 0) "${pl.trackIds.size} lagu ($wavCount WAV/FLAC dilewati)" else "${pl.trackIds.size} lagu"
                                            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (filteredTracks.isEmpty()) {
                            item("no_trk") { NoticeCard("Tidak ada lagu ditemukan di Library HP.") }
                        } else {
                            items(filteredTracks, key = { "trk_${it.trackId}" }) { track ->
                                val selected = track.trackId in selectedTrackIds
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) foh.safeBg else foh.surfaceDeep,
                                    border = BorderStroke(1.dp, if (selected) foh.safeBorder else foh.borderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !batchState.running) {
                                            selectedTrackIds = if (selected) selectedTrackIds - track.trackId else selectedTrackIds + track.trackId
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(checked = selected, onCheckedChange = null, enabled = !batchState.running)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(track.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val tag = buildString {
                                                append(LibraryRules.audioFormatLabel(track.displayName))
                                                append(" · ")
                                                append(formatTransferBytes(track.sizeBytes))
                                                if (track.isWavOrFlac) append(" · ⚠️ WAV/FLAC dilewati")
                                                else if (track.sizeBytes > 150L * 1024L * 1024L) append(" · ⚠️ >150 MB")
                                            }
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (track.isWavOrFlac) foh.warnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedWavFlacCount > 0 || selectedOverCount > 0) {
                    val notes = buildList {
                        if (selectedWavFlacCount > 0) add("$selectedWavFlacCount file .wav/.flac akan dilewati (fase ini hanya audio terkompresi).")
                        if (selectedOverCount > 0) add("$selectedOverCount file >150 MB akan dilewati.")
                    }
                    NoticeCard(text = notes.joinToString(" "), icon = UiIcons.Warning)
                }

                if (batchState.running) {
                    val filePct = if (batchState.currentFileTotalBytes > 0L) {
                        ((batchState.currentFileSentBytes.toDouble() / batchState.currentFileTotalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                    } else 0
                    val overall = if (batchState.totalFiles > 0) (batchState.completedFiles.toFloat() / batchState.totalFiles.toFloat()).coerceIn(0f, 1f) else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Mengunggah (${batchState.completedFiles}/${batchState.totalFiles}): ${batchState.currentFileName ?: ""} ($filePct%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = foh.safeAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().height(5.dp))
                    }
                }
                batchState.error?.let { NoticeCard(text = it, isError = true, icon = UiIcons.Warning) }
                batchState.lastSummary?.let { NoticeCard(text = it, icon = UiIcons.Check) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (batchState.running) {
                        TextButton(onClick = onCancelUpload) { Text("Batalkan", color = foh.alertAccent) }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Tutup") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onStartUpload(effectiveIds, targetSubfolder.trim().ifBlank { null }) },
                            enabled = canUpload && selectedValidCount > 0,
                        ) {
                            Text("Unggah ($selectedValidCount)")
                        }
                    }
                }
            }
        }
    }
}
