package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OrganizerScreen(tracks: List<AudioTrack>, lastRename: RenameRecord?, busy: Boolean,
                    modifier: Modifier = Modifier,
                    onRename: (Long, String, () -> Unit) -> Unit, onUndo: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<AudioTrack?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    val visible = remember(tracks, search) { tracks.filter { it.displayName.contains(search, ignoreCase = true) } }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Organizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Ubah nama file library tanpa mengonversi audionya.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(search, { search = it }, label = { Text("Cari nama audio") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (lastRename != null) OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Terakhir: ${lastRename.fromName} → ${lastRename.toName}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onUndo, enabled = !busy) { Text("Batalkan perubahan nama terakhir") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (tracks.isEmpty()) "Belum ada audio.\nUnduh atau impor lewat tab Tambah." else "Tidak ada nama yang cocok.")
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(visible, key = { it.id }) { track ->
                TrackRow(track, "Ubah nama") {
                    if (!busy) { title = track.displayName.substringBeforeLast('.', track.displayName); editing = track }
                }
            }
        }
    }
    editing?.let { track ->
        val validated = runCatching { LibraryRules.renamedDisplayName(track.displayName, title) }
        AlertDialog(onDismissRequest = { if (!busy) editing = null },
            title = { Text("Ubah nama audio") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Nama tanpa ekstensi") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = title.isNotBlank() && validated.isFailure)
                Text("Format .${track.displayName.substringAfterLast('.', "")} tetap sama.", style = MaterialTheme.typography.bodySmall)
                if (validated.isSuccess) Text("Nama file: ${validated.getOrNull()}", style = MaterialTheme.typography.bodySmall)
                else Text(validated.exceptionOrNull()?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && validated.isSuccess && validated.getOrNull() != track.displayName,
                onClick = { onRename(track.id, title) { editing = null } }) { Text("Simpan nama") } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !busy) { Text("Batal") } })
    }
}
