package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
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
    onImport: () -> Unit, onCancel: () -> Unit, onCancelSearch: () -> Unit, onSettings: () -> Unit,
    upgrade: UpgradeUiActions, onDownloadBatch: (List<SearchHit>, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    var providerName by rememberSaveable { mutableStateOf(search.provider.name) }
    var kindName by rememberSaveable { mutableStateOf(search.kind.name) }
    var menu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pending by remember { mutableStateOf<List<SearchHit>?>(null) }
    var playlistChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistThumbnail by rememberSaveable { mutableStateOf<String?>(null) }
    val provider = SearchProvider.entries.firstOrNull { it.name == providerName } ?: SearchProvider.YOUTUBE_MUSIC
    val kind = SearchKind.entries.firstOrNull { it.name == kindName } ?: SearchKind.SONGS
    val idle = !ingest.busy && !search.busy && !actions.preview.loading && !upgrade.operationBusy
    val validQuery = remember(query) { runCatching { YoutubeSearchRules.validatedQuery(query) }.isSuccess }
    val keyboard = LocalSoftwareKeyboardController.current
    val resultScroll = rememberLazyListState()
    val playlistScroll = rememberLazyListState()
    val inPlaylist = search.playlistId != null
    val sorted = remember(search.results, search.sort) { YoutubeSearchRules.sorted(search.results, search.sort) }
    val loadedSongs = sorted.filter { it.kind == SearchKind.SONGS }
    val selectedSongs = loadedSongs.filter { it.videoId in selectedIds }
    val leave = {
        actions.leavePlaylist()
    }
    val submit = {
        if (idle && validQuery) {
            keyboard?.hide()
            selectionMode = false; selectedIds = arrayListOf()
            actions.search(query, provider, kind, search.sort)
        }
    }
    val toggle: (SearchHit) -> Unit = { hit ->
        if (hit.kind == SearchKind.SONGS) selectedIds = ArrayList(selectedIds.toMutableList().apply {
            if (!remove(hit.videoId)) add(hit.videoId)
        })
    }
    LaunchedEffect(search.query, search.provider, search.playlistId) {
        selectionMode = false; selectedIds = arrayListOf(); pending = null
    }
    BackHandler(enabled = selectionMode || inPlaylist) {
        if (selectionMode) { selectionMode = false; selectedIds = arrayListOf() }
        else leave()
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = if (inPlaylist) playlistScroll else resultScroll,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "header") {
                if (selectionMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(UiIcons.Close, "Keluar selection", { selectionMode = false; selectedIds = arrayListOf() })
                    Text("${selectedSongs.size} selected", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selectedIds = arrayListOf() }) { Text("Clear") }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (inPlaylist) GlyphButton(UiIcons.Back, "Kembali ke hasil Playlist", leave)
                    Text(if (inPlaylist) "Playlist" else "Search", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f))
                    BrandLogo(32.dp)
                    Box {
                        GlyphButton(UiIcons.More, "Menu Search", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Download link") }, enabled = idle,
                                leadingIcon = { Icon(UiIcons.Link, null) }, onClick = { menu = false; actions.stopPreview(); linkDialog = true })
                            DropdownMenuItem(text = { Text("Import") }, enabled = idle,
                                leadingIcon = { Icon(UiIcons.Import, null) }, onClick = { menu = false; onImport() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                }
            }
            if (inPlaylist) item(key = "playlist-context") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YoutubeThumbnail(playlistThumbnail, search.playlistTitle.orEmpty(), Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(search.playlistTitle.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(playlistChannel, "${loadedSongs.size} lagu dimuat").joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!inPlaylist && !selectionMode) {
                item(key = "query") {
                    OutlinedTextField(value = query, onValueChange = { query = it; actions.stopPreview() },
                        enabled = idle, placeholder = { Text("Search lagu atau channel") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(UiIcons.Search, null) },
                        trailingIcon = {
                            GlyphButton(UiIcons.ChevronRight, "Search", submit, enabled = idle && validQuery)
                        }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }))
                }
                item(key = "filters") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchChoice(provider, SearchProvider.entries, { if (it == SearchProvider.YOUTUBE_MUSIC) "YT Music" else "YouTube" },
                            idle, Modifier) { providerName = it.name; actions.stopPreview() }
                        SearchKind.entries.forEach { option ->
                            FilterChip(selected = kind == option, enabled = idle,
                                onClick = {
                                    kindName = option.name; actions.stopPreview()
                                    if (option == SearchKind.PLAYLISTS) actions.sort(SearchSort.RELEVANCE)
                                }, label = { Text(option.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            item(key = "results-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectionMode) "Pilih lagu" else "${sorted.size} hasil", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    if (selectionMode) TextButton(onClick = { selectedIds = ArrayList(loadedSongs.map { it.videoId }) }, enabled = loadedSongs.isNotEmpty()) {
                        Text("Select all")
                    } else {
                        val sorts = if (!inPlaylist && kind == SearchKind.PLAYLISTS) listOf(SearchSort.RELEVANCE, SearchSort.TITLE) else SearchSort.entries
                        SearchChoice(search.sort.takeIf { it in sorts } ?: SearchSort.RELEVANCE, sorts, { it.label }, !search.busy, Modifier, actions.sort)
                    }
                }
            }
            if (search.busy) item(key = "loading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(if (search.loadingMore) "Memuat lagi…" else "Mencari…", modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelSearch) { Text("Cancel") }
                }
            }
            search.error?.let { error -> item(key = "error") {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.retry, enabled = idle) { Text("Retry") }
            } }
            if (!search.busy && sorted.isEmpty()) item(key = "empty") {
                Text(if (search.hasSearched) "Tidak ada hasil lengkap." else "Cari lagu atau Playlist.",
                    modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(sorted, key = { "${it.kind}-${it.videoId}" }) { hit ->
                CatalogResult(hit, enabled = idle, selectionMode = selectionMode, selected = hit.videoId in selectedIds,
                    previewActive = actions.preview.hit?.videoId == hit.videoId && (actions.preview.loading || actions.preview.media != null),
                    onTap = {
                        if (selectionMode) toggle(hit)
                        else if (hit.kind == SearchKind.PLAYLISTS) {
                            actions.stopPreview(); playlistChannel = hit.channel; playlistThumbnail = hit.thumbnailUrl
                            actions.openPlaylist(hit)
                        } else actions.previewSong(hit)
                    }, onLongPress = {
                        if (hit.kind == SearchKind.SONGS) { actions.stopPreview(); selectionMode = true; toggle(hit) }
                    }, onPreview = {
                        if (actions.preview.hit?.videoId == hit.videoId && (actions.preview.loading || actions.preview.media != null)) actions.stopPreview()
                        else actions.previewSong(hit)
                    }, onDownload = { pending = listOf(hit) }, onToggle = { toggle(hit) })
            }
            if (search.incompleteCount > 0) item(key = "incomplete") {
                Text("${search.incompleteCount} hasil tanpa metadata dilewati.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (search.hasMore) item(key = "more") {
                TextButton(onClick = actions.loadMore, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Muat lagi") }
            }
            if (ingest.busy) item(key = "ingest") {
                Text(ingest.stage)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onCancel, enabled = ingest.cancelable) { Text("Cancel") }
            }
        }
        if (selectionMode) Button(onClick = { pending = selectedSongs.toList() },
            enabled = idle && selectedSongs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
            Icon(UiIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${selectedSongs.size})")
        }
    }
    pending?.let { hits ->
        DownloadConsentDialog(hits, busy = !idle, onDismiss = { pending = null }, onConfirm = {
            if (idle) {
                pending = null
                selectionMode = false; selectedIds = arrayListOf()
                if (hits.size == 1) { val hit = hits.single(); onDownloadBatch(listOf(hit), true) }
                else onDownloadBatch(hits, true)
            }
        })
    }
    if (linkDialog) DownloadLinkDialog(!idle, onDismiss = { linkDialog = false }, onDownload = { url ->
        if (idle) { linkDialog = false; onDownload(url, true) }
    })
}

/** Dense, stateless row: text and thumbnail are metadata, never illustrative catalog data. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogResult(hit: SearchHit, enabled: Boolean, selectionMode: Boolean, selected: Boolean,
    previewActive: Boolean, onTap: () -> Unit, onLongPress: () -> Unit,
    onPreview: () -> Unit, onDownload: () -> Unit, onToggle: () -> Unit) {
    Surface(color = if (selected || previewActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress,
                    onLongClickLabel = "Pilih lagu")
                .semantics { this.selected = selected }.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val details = if (hit.kind == SearchKind.PLAYLISTS) hit.itemCount?.let { "$it lagu" } ?: "Playlist"
                        else hit.durationSeconds?.let { LibraryRules.durationLabel(it * 1000L) } ?: "—"
                    Text(listOfNotNull(hit.channel?.takeIf { it.isNotBlank() }, details).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selectionMode && hit.kind == SearchKind.SONGS) Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
                else if (hit.kind == SearchKind.SONGS) {
                    GlyphButton(if (previewActive) UiIcons.Stop else UiIcons.Play, if (previewActive) "Stop Preview" else "Preview ${hit.title}",
                        onPreview, enabled = enabled || previewActive)
                    GlyphButton(UiIcons.Download, "Download ${hit.title}", onDownload, enabled = enabled)
                } else GlyphButton(UiIcons.ChevronRight, "Buka Playlist ${hit.title}", onTap, enabled = enabled)
            }
            HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
}

@Composable
private fun <T> SearchChoice(value: T, choices: List<T>, label: (T) -> String, enabled: Boolean,
    modifier: Modifier, selected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(label(value)); Spacer(Modifier.width(4.dp)); Icon(UiIcons.ChevronDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { option -> DropdownMenuItem(text = { Text(label(option)) },
                onClick = { expanded = false; selected(option) }, enabled = enabled,
                trailingIcon = if (option == value) ({ Icon(UiIcons.Check, null) }) else null) }
        }
    }
}
