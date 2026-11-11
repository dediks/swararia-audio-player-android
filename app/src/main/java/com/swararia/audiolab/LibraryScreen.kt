package com.swararia.audiolab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun LibraryPage(
    tracks: List<AudioTrack>,
    currentId: String?,
    connected: Boolean,
    ingest: IngestState,
    search: YoutubeSearchState,
    organizerBusy: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onRename: (AudioTrack) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }
    val visible = remember(tracks, query, alphabetical) {
        val matching = tracks.filter {
            it.displayName.contains(query.trim(), ignoreCase = true) ||
                UiPresentation.title(it.displayName).contains(query.trim(), ignoreCase = true)
        }
        if (alphabetical) matching.sortedWith(compareBy<AudioTrack> {
            UiPresentation.title(it.displayName).lowercase(Locale.ROOT)
        }.thenBy { it.id })
        else matching.sortedWith(compareByDescending<AudioTrack> { it.addedAt }.thenByDescending { it.id })
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "library-header") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pustaka", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    GlyphButton(UiIcons.Plus, "Tambah audio", onAdd, Modifier.size(52.dp), filled = true)
                    Box {
                        GlyphButton(UiIcons.More, "Menu Pustaka", { appMenu = true })
                        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                            DropdownMenuItem(text = { Text("Riwayat nama & urungkan") },
                                leadingIcon = { Icon(UiIcons.History, null) },
                                onClick = { appMenu = false; onHistory() })
                            DropdownMenuItem(text = { Text("Pengaturan & tentang") },
                                leadingIcon = { Icon(UiIcons.Settings, null) },
                                onClick = { appMenu = false; onSettings() })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandLogo(24.dp)
                    Text("Swararia · Audio tersimpan di HP", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Cari audio") },
                    leadingIcon = { Icon(UiIcons.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ GlyphButton(UiIcons.Close, "Hapus pencarian pustaka", { query = "" }) }) else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp), colors = searchFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (query.isBlank()) "${tracks.size} audio" else "${visible.size} dari ${tracks.size} audio",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Urutkan audio: ${if (alphabetical) "A–Z" else "Terbaru"}" }) {
                            Text(if (alphabetical) "A–Z" else "Terbaru", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(UiIcons.ChevronDown, null, Modifier.padding(start = 6.dp).size(20.dp))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("Terbaru") }, onClick = { alphabetical = false; sortMenu = false },
                                trailingIcon = if (!alphabetical) ({ Icon(UiIcons.Check, null) }) else null)
                            DropdownMenuItem(text = { Text("A–Z") }, onClick = { alphabetical = true; sortMenu = false },
                                trailingIcon = if (alphabetical) ({ Icon(UiIcons.Check, null) }) else null)
                        }
                    }
                }
            }
        }
        if (ingest.busy || search.busy) item(key = "active-operation") {
            Surface(onClick = onAdd, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (ingest.busy) ingest.stage else "Mencari di ${search.provider.label}…",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(UiIcons.ChevronRight, "Lihat proses", Modifier.size(20.dp))
                }
            }
        }
        if (!connected && tracks.isNotEmpty()) item(key = "player-disconnected") {
            Text("Pemutar belum terhubung. Audio tetap bisa dicari dan diubah namanya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        if (organizerBusy) item(key = "rename-progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (visible.isEmpty()) item(key = "library-empty") {
            if (tracks.isEmpty()) EmptyMessage("Pustaka masih kosong", "Tambahkan tautan berizin atau impor audio dari HP.", UiIcons.Library) {
                Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tambah audio") }
            } else EmptyMessage("Audio tidak ditemukan", "Coba nama lain atau hapus kata pencarian.", UiIcons.Search) {
                TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hapus pencarian") }
            }
        }
        items(visible, key = { it.id }) { track ->
            LibraryAudioRow(track, selected = track.id.toString() == currentId, connected = connected,
                renameEnabled = !organizerBusy, onPlay = { onPlay(track) }, onRename = { onRename(track) })
        }
    }
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun LibraryAudioRow(
    track: AudioTrack,
    selected: Boolean,
    connected: Boolean,
    renameEnabled: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { this.selected = selected },
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(enabled = connected, onClickLabel = "Putar ${UiPresentation.title(track.displayName)}",
                    onClick = onPlay).padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackArtwork(track.id, Modifier.size(56.dp).clip(RoundedCornerShape(7.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(UiPresentation.title(track.displayName), style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(UiPresentation.metadata(track.displayName, track.durationMs), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    GlyphButton(UiIcons.More, "Menu audio ${UiPresentation.title(track.displayName)}", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Ubah nama") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = renameEnabled, onClick = { menu = false; onRename() })
                    }
                }
            }
            if (!selected) HorizontalDivider(Modifier.padding(start = 78.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        }
    }
}
