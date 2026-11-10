package com.swararia.audiolab

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

data class SearchActions(
    val preview: PreviewState,
    val search: (String, SearchProvider, SearchKind, SearchSort) -> Unit,
    val sort: (SearchSort) -> Unit,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
    val openPlaylist: (SearchHit) -> Unit,
    val leavePlaylist: () -> Unit,
    val previewSong: (SearchHit) -> Unit,
    val stopPreview: () -> Unit,
    val previewError: () -> Unit,
)

@Composable
fun AddAudioScreen(ingest: IngestState, search: YoutubeSearchState, controller: MediaController?,
    actions: SearchActions, modifier: Modifier, onDownload: (String, Boolean) -> Unit,
    onImport: () -> Unit, onCancel: () -> Unit, onCancelSearch: () -> Unit, onSettings: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var url by rememberSaveable { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(search.query) }
    var providerName by rememberSaveable { mutableStateOf(search.provider.name) }
    var kindName by rememberSaveable { mutableStateOf(search.kind.name) }
    var selected by remember { mutableStateOf<SearchHit?>(null) }
    val provider = SearchProvider.entries.firstOrNull { it.name == providerName } ?: SearchProvider.YOUTUBE_MUSIC
    val kind = SearchKind.entries.firstOrNull { it.name == kindName } ?: SearchKind.SONGS
    val idle = !ingest.busy && !search.busy && !actions.preview.loading
    val validQuery = remember(query) { runCatching { YoutubeSearchRules.validatedQuery(query) }.isSuccess }
    val validUrl = remember(url) { runCatching { LibraryRules.validatedHttpsUrl(url) }.getOrNull() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val scroll = rememberLazyListState()
    val sorted = remember(search.results, search.sort) { YoutubeSearchRules.sorted(search.results, search.sort) }
    val submit = {
        if (idle && validQuery) {
            keyboard?.hide()
            actions.search(query, provider, kind, search.sort)
        }
    }
    LaunchedEffect(search.query, search.provider, search.kind, search.playlistId) { scroll.scrollToItem(0) }
    Column(modifier) {
        SearchPreviewPlayer(actions.preview, controller, actions.stopPreview, actions.previewError)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tambah audio", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Image(painterResource(R.drawable.swararia_logo), null, Modifier.size(40.dp))
                }
            }
            item(key = "modes") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = mode == 1, onClick = { mode = 1; consent = false }, label = { Text("Cari lagu") })
                    FilterChip(selected = mode == 0, onClick = { mode = 0; consent = false; actions.stopPreview() }, label = { Text("Tempel tautan") })
                }
            }
            if (mode == 0) item(key = "link") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it; consent = false }, enabled = idle,
                        label = { Text("Tautan HTTPS berizin") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { url = it.trim(); consent = false } },
                            enabled = idle) { Text("Tempel") } })
                    ConsentLine(consent, { consent = it }, idle)
                    Button(onClick = {
                        if (idle && consent && validUrl != null) { consent = false; onDownload(validUrl, true) }
                    }, enabled = idle && consent && validUrl != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Unduh audio") }
                    if (url.isNotBlank() && validUrl == null) Text("Gunakan tautan HTTPS yang valid.", color = MaterialTheme.colorScheme.error)
                }
            }
            if (mode == 1) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SearchChoice(provider, SearchProvider.entries, { it.label }, idle, Modifier.weight(1f)) {
                                providerName = it.name; actions.stopPreview()
                            }
                            SearchChoice(kind, SearchKind.entries, { it.label }, idle, Modifier.weight(1f)) {
                                kindName = it.name; actions.stopPreview()
                                if (it == SearchKind.PLAYLISTS) actions.sort(SearchSort.RELEVANCE)
                            }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it; actions.stopPreview() }, enabled = idle,
                            label = { Text("Judul, artis, atau kata kunci") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(UiIcons.Search, null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }))
                        Button(onClick = submit, enabled = idle && validQuery,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (kind == SearchKind.SONGS) "Cari lagu" else "Cari playlist") }
                        val sorts = if (kind == SearchKind.PLAYLISTS) listOf(SearchSort.RELEVANCE, SearchSort.TITLE) else SearchSort.entries
                        SearchChoice(search.sort.takeIf { it in sorts } ?: SearchSort.RELEVANCE, sorts,
                            { "Urutkan: ${it.label}" }, !search.busy, Modifier.fillMaxWidth(), actions.sort)
                        Text("Urutan berlaku pada hasil yang sudah dimuat. Relevansi memprioritaskan kanal terverifikasi/indikasi kanal artis, bukan jaminan pemilik hak.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (search.playlistId != null) item(key = "playlist-context") {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Isi playlist: ${search.playlistTitle.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                            Text("Pilih satu lagu untuk preview atau konfirmasi unduhan.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { kindName = SearchKind.PLAYLISTS.name; actions.leavePlaylist() }, enabled = idle) { Text("Kembali ke hasil playlist") }
                        }
                    }
                }
                if (search.busy) item(key = "loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (search.loadingMore) "Memuat hasil berikutnya…" else "Mencari dan melengkapi kanal serta durasi…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onCancelSearch) { Text("Batalkan pencarian") }
                    }
                }
                search.error?.let { error -> item(key = "error") {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.retry, enabled = idle) { Text("Coba lagi") }
                } }
                if (search.incompleteCount > 0) item(key = "metadata-note") {
                    Text("${search.incompleteCount} hasil tidak ditampilkan karena kanal/durasi belum dapat dipastikan. Coba lagi atau muat hasil berikutnya.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!search.busy && sorted.isEmpty()) item(key = "empty") {
                    Text(if (search.hasSearched) "Belum ada hasil lengkap. Coba kata kunci lain atau muat lebih banyak."
                        else "YouTube Music dipilih sebagai sumber awal. Cari lagu atau playlist untuk mulai.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sorted.isNotEmpty()) item(key = "results-heading") {
                    Text("${sorted.size} hasil · ${search.provider.label}", style = MaterialTheme.typography.titleSmall)
                }
                items(sorted, key = { "${it.kind}-${it.videoId}" }) { hit ->
                    CatalogResult(hit, idle,
                        onSelect = { if (hit.kind == SearchKind.PLAYLISTS) { kindName = SearchKind.SONGS.name; actions.openPlaylist(hit) } else selected = hit },
                        onPreview = { actions.previewSong(hit) })
                }
                if (search.hasMore) item(key = "more") {
                    OutlinedButton(onClick = actions.loadMore, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (search.loadingMore) "Memuat…" else "Muat lebih banyak")
                    }
                }
                if (search.loadedThrough >= YoutubeSearchRules.MAX_RESULTS) item(key = "limit") {
                    Text("Batas ${YoutubeSearchRules.MAX_RESULTS} hasil per pencarian tercapai. Persempit kata kunci untuk hasil lain.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ingest.busy || ingest.error || ingest.detail.isNotBlank()) item(key = "ingest") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ingest.stage, style = MaterialTheme.typography.titleSmall)
                        if (ingest.detail.isNotBlank()) Text(ingest.detail, style = MaterialTheme.typography.bodySmall)
                        if (ingest.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = onCancel, enabled = ingest.cancelable) { Text("Batalkan proses") }
                        }
                    }
                }
            }
            item(key = "import") {
                OutlinedButton(onClick = onImport, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Impor audio dari HP") }
                Text("File asli tetap; aplikasi menyimpan salinan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "settings") { TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pengaturan & tentang") } }
        }
    }
    selected?.let { hit -> key(hit.videoId) {
        var authorized by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Unduh lagu ini?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                Text("Kanal: ${hit.channel.orEmpty()}")
                Text(hit.durationSeconds?.let { LibraryRules.durationLabel(it * 1000) }.orEmpty())
                ConsentLine(authorized, { authorized = it }, idle)
            } },
            confirmButton = { TextButton(onClick = { if (authorized && idle) { selected = null; onDownload(hit.url, true) } },
                enabled = authorized && idle) { Text("Unduh") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Batal") } })
    } }
}

@Composable
private fun ConsentLine(checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = change, enabled = enabled)
        Text("Saya memiliki izin untuk mengunduh audio ini.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CatalogResult(hit: SearchHit, enabled: Boolean, onSelect: () -> Unit, onPreview: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.size(76.dp, 60.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Kanal: ${hit.channel.orEmpty()}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (hit.kind == SearchKind.PLAYLISTS) hit.itemCount?.let { "Playlist · $it lagu" } ?: "Playlist · lihat daftar lagu"
                        else hit.durationSeconds?.let { LibraryRules.durationLabel(it * 1000L) }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    YoutubeSearchRules.originLabel(hit)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (hit.kind == SearchKind.SONGS) TextButton(onClick = onPreview, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(UiIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Preview 30s")
                }
                TextButton(onClick = onSelect, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (hit.kind == SearchKind.PLAYLISTS) "Lihat lagu" else "Unduh")
                }
            }
        }
    }
}

@Composable
private fun <T> SearchChoice(value: T, choices: List<T>, label: (T) -> String, enabled: Boolean,
    modifier: Modifier, selected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(label(value), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(UiIcons.ChevronDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { option -> DropdownMenuItem(text = { Text(label(option)) },
                onClick = { expanded = false; selected(option) }, enabled = enabled) }
        }
    }
}
