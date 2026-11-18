package com.swararia.audiolab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CreateOrRenamePlaylistDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val validated = remember(name) { runCatching { LibraryRules.validatedPlaylistName(name) } }
    val canSave = !busy && validated.isSuccess && validated.getOrNull() != initialName.trim()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Queue, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("Nama playlist") },
                    placeholder = { Text("Misal: Favorit Malam, Lofi Fokus") },
                    isError = name.isNotBlank() && validated.isFailure,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val valid = validated.getOrNull()
                        if (!busy && valid != null && valid != initialName.trim()) {
                            onConfirm(valid); onDismiss()
                        }
                    }),
                )
                if (name.isNotBlank() && validated.isFailure) {
                    Text(
                        validated.exceptionOrNull()?.message ?: "Nama playlist tidak valid.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { validated.getOrNull()?.let { onConfirm(it); onDismiss() } },
                enabled = canSave,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") }
        },
    )
}

@Composable
internal fun DeletePlaylistDialog(
    playlist: LibraryPlaylist,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Hapus playlist?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playlist \"${playlist.name}\" akan dihapus.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "File audio asli di pustaka HP tetap aman dan tidak ikut terhapus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!busy) { onDelete(playlist.id); onDismiss() } },
                enabled = !busy,
            ) { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") }
        },
    )
}

@Composable
internal fun PlaylistPickerDialog(
    track: AudioTrack,
    playlists: List<LibraryPlaylist>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggleMembership: (Long, Boolean) -> Unit,
    onCreatePlaylistWithTrack: (String, Long) -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Queue, null) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Masukkan ke playlist")
                Text(
                    UiPresentation.title(track.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (playlists.isEmpty() && !creating) {
                    Text(
                        "Belum ada playlist. Buat playlist pertama Anda di bawah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playlists.forEach { playlist ->
                    val included = track.id in playlist.trackIds
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (included) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy) { onToggleMembership(playlist.id, !included) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = included,
                                onCheckedChange = { checked -> if (!busy) onToggleMembership(playlist.id, checked) },
                                enabled = !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${playlist.trackIds.size} track",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                InlineCreatePlaylistSection(
                    creating = creating,
                    hasExistingPlaylists = playlists.isNotEmpty(),
                    busy = busy,
                    onStartCreate = { creating = true },
                    onCancelCreate = { creating = false },
                    onCreate = { clean -> onCreatePlaylistWithTrack(clean, track.id) },
                )
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Selesai") }
        },
    )
}

@Composable
internal fun BatchPlaylistPickerDialog(
    selectedTracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAddTracksToPlaylist: (Long, List<Long>) -> Unit,
    onCreatePlaylistWithTracks: (String, List<Long>) -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    val selectedIds = remember(selectedTracks) { selectedTracks.map { it.id } }
    val selectedSet = remember(selectedIds) { selectedIds.toSet() }
    val subtitle = remember(selectedTracks) {
        val first = selectedTracks.firstOrNull()?.let { UiPresentation.title(it.displayName) }.orEmpty()
        when {
            selectedTracks.isEmpty() -> "0 track dipilih"
            selectedTracks.size == 1 -> first
            else -> "$first & ${selectedTracks.size - 1} lainnya"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Queue, null) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Masukkan ${selectedIds.size} track ke playlist")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                playlists.forEach { playlist ->
                    val existing = playlist.trackIds.count { it in selectedSet }
                    val allIncluded = selectedIds.isNotEmpty() && existing == selectedIds.size
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (allIncluded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy && !allIncluded && selectedIds.isNotEmpty()) {
                                    onAddTracksToPlaylist(playlist.id, selectedIds)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val membershipInfo = when {
                                    allIncluded -> "Semua sudah ada di playlist"
                                    existing > 0 -> "$existing dari ${selectedIds.size} sudah ada"
                                    else -> "${playlist.trackIds.size} track"
                                }
                                Text(
                                    membershipInfo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (allIncluded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (allIncluded) {
                                Icon(UiIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            } else {
                                Text(
                                    "+ Tambah",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                InlineCreatePlaylistSection(
                    creating = creating,
                    hasExistingPlaylists = playlists.isNotEmpty(),
                    busy = busy,
                    onStartCreate = { creating = true },
                    onCancelCreate = { creating = false },
                    onCreate = { clean -> onCreatePlaylistWithTracks(clean, selectedIds) },
                )
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Selesai") }
        },
    )
}

@Composable
private fun InlineCreatePlaylistSection(
    creating: Boolean,
    hasExistingPlaylists: Boolean,
    busy: Boolean,
    onStartCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    val validatedNew = remember(newName) { runCatching { LibraryRules.validatedPlaylistName(newName) } }
    if (creating) {
        HorizontalDivider()
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            enabled = !busy,
            singleLine = true,
            label = { Text("Nama playlist baru") },
            placeholder = { Text("Misal: Wedding Akad, Pengajian") },
            isError = newName.isNotBlank() && validatedNew.isFailure,
            modifier = Modifier.fillMaxWidth(),
        )
        if (newName.isNotBlank() && validatedNew.isFailure) {
            Text(
                validatedNew.exceptionOrNull()?.message ?: "Nama playlist tidak valid.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (hasExistingPlaylists) {
                TextButton(onClick = { newName = ""; onCancelCreate() }, enabled = !busy) { Text("Batal") }
            }
            TextButton(
                onClick = {
                    val clean = validatedNew.getOrNull() ?: return@TextButton
                    onCreate(clean)
                    newName = ""
                    onCancelCreate()
                },
                enabled = !busy && validatedNew.isSuccess,
            ) { Text("Buat & tambahkan") }
        }
    } else {
        OutlinedButton(
            onClick = onStartCreate,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Icon(UiIcons.Plus, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Buat playlist baru")
        }
    }
}

@Composable
internal fun AddTracksToPlaylistDialog(
    playlist: LibraryPlaylist,
    tracks: List<AudioTrack>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggleTrack: (Long, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val includedIds = remember(playlist.trackIds) { playlist.trackIds.toHashSet() }
    val filtered = remember(tracks, query) {
        val q = query.trim()
        if (q.isEmpty()) tracks else tracks.filter {
            matchesTrackQuery(it, q)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Plus, null) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tambah lagu ke playlist")
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cari lagu atau artis") },
                    singleLine = true,
                    leadingIcon = { Icon(UiIcons.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item(key = "empty-track-filter", contentType = "empty") {
                            Text(
                                "Tidak ada lagu yang cocok.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }, contentType = { "dialog-track-row" }) { track ->
                            val included = track.id in includedIds
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !busy) { onToggleTrack(track.id, !included) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = included,
                                    onCheckedChange = { checked -> if (!busy) onToggleTrack(track.id, checked) },
                                    enabled = !busy,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        UiPresentation.title(track.displayName),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        UiPresentation.trackSubtitle(track.displayName, track.artist, track.durationMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Selesai") }
        },
    )
}
