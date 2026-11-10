package com.swararia.audiolab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

/** Presentation only: storage, playback-session ownership and ingest remain outside this UI. */
@Composable
fun SwarariaUi(
    tracks: List<AudioTrack>,
    controller: MediaController?,
    ingest: IngestState,
    search: YoutubeSearchState,
    lastRename: RenameRecord?,
    organizerBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    onDownload: (String, Boolean) -> Unit,
    onImport: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onRename: (Long, String, () -> Unit) -> Unit,
    onUndo: () -> Unit,
    onSearch: (String, SearchProvider) -> Unit,
    onCancelSearch: () -> Unit,
) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AudioTrack?>(null) }
    val pages = rememberSaveableStateHolder()
    val playback = rememberSwarariaPlayback(controller)
    val current = tracks.firstOrNull { it.id.toString() == playback.mediaId }
    val playTrack: (AudioTrack) -> Unit = { track ->
        if (playback.connected) {
            if (track.id.toString() == playback.mediaId) toggleSwarariaPlayback(controller)
            else onPlay(track)
        }
    }

    BackHandler(enabled = fullPlayer || destination != 0) {
        if (fullPlayer) fullPlayer = false else destination = 0
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!fullPlayer) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (playback.mediaId != null) MiniPlayer(
                    current, playback, controller, onOpen = { fullPlayer = true }
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ) {
                    NavigationBarItem(
                        selected = destination == 0,
                        onClick = { destination = 0 },
                        icon = { Icon(UiIcons.Library, contentDescription = null) },
                        label = { Text("Pustaka") },
                        colors = navigationColors(),
                    )
                    NavigationBarItem(
                        selected = destination == 1,
                        onClick = { destination = 1 },
                        icon = { Icon(UiIcons.Plus, contentDescription = null) },
                        label = { Text("Tambah audio") },
                        colors = navigationColors(),
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (fullPlayer) FocusedPlayer(
            tracks = tracks, current = current, playback = playback, controller = controller,
            modifier = contentModifier, onBack = { fullPlayer = false },
            onRename = { editing = it }, onSettings = { settings = true },
        ) else pages.SaveableStateProvider(destination) {
            if (destination == 0) LibraryPage(
                tracks = tracks, currentId = playback.mediaId, connected = playback.connected,
                ingest = ingest, search = search, organizerBusy = organizerBusy,
                modifier = contentModifier, onAdd = { destination = 1 }, onPlay = playTrack,
                onRename = { editing = it }, onHistory = { history = true },
                onSettings = { settings = true },
            ) else AddAudioPage(
                ingest = ingest, search = search, modifier = contentModifier,
                onDownload = { url, authorized -> if (!ingest.busy && !search.busy && authorized) onDownload(url, true) },
                onImport = { if (!ingest.busy && !search.busy) onImport() },
                onCancel = { if (ingest.busy && ingest.cancelable) onCancel() },
                onSearch = { query, provider -> if (!ingest.busy && !search.busy) onSearch(query, provider) },
                onCancelSearch = onCancelSearch, onSettings = { settings = true },
            )
        }
    }
    editing?.let { track ->
        key(track.id, track.displayName) {
            RenameAudioDialog(track, organizerBusy, onDismiss = { editing = null }, onRename = onRename)
        }
    }
    if (history) RenameHistoryDialog(lastRename, organizerBusy, onDismiss = { history = false }, onUndo = onUndo)
    if (settings) EngineSettingsDialog(ingest, search.busy, onDismiss = { settings = false },
        onUpdate = { if (!ingest.busy && !search.busy) onUpdate() },
        onCancel = { if (ingest.busy && ingest.cancelable) onCancel() })
}

@Composable
private fun LibraryPage(
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

@Composable
private fun RenameAudioDialog(
    track: AudioTrack,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: (Long, String, () -> Unit) -> Unit,
) {
    // The editor uses the real filename, never the cosmetic library title.
    var title by rememberSaveable(track.id, track.displayName) {
        mutableStateOf(track.displayName.substringBeforeLast('.', track.displayName))
    }
    val validated = remember(track.displayName, title) { runCatching { LibraryRules.renamedDisplayName(track.displayName, title) } }
    val canSave = !busy && validated.isSuccess && validated.getOrNull() != track.displayName
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(UiIcons.Rename, null) }, title = { Text("Ubah nama audio") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("File saat ini: ${track.displayName}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !busy,
                    label = { Text("Nama tanpa ekstensi") }, modifier = Modifier.fillMaxWidth(),
                    isError = validated.isFailure, maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onRename(track.id, title, onDismiss) }))
                Text("Format file tetap sama. Audio tidak dikonversi.", style = MaterialTheme.typography.bodySmall)
                if (validated.isSuccess) Text("Nama file: ${validated.getOrNull()}", style = MaterialTheme.typography.bodySmall)
                else Text(validated.exceptionOrNull()?.message ?: "Nama tidak valid.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onRename(track.id, title, onDismiss) }, enabled = canSave) { Text("Simpan nama") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } })
}

@Composable
private fun RenameHistoryDialog(record: RenameRecord?, busy: Boolean, onDismiss: () -> Unit, onUndo: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(UiIcons.History, null) },
        title = { Text("Riwayat perubahan nama") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (record == null) Text("Belum ada perubahan nama yang dapat diurungkan.")
                else {
                    Text("Perubahan terakhir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sebelum: ${record.fromName}")
                        Text("Sekarang: ${record.toName}")
                    } }
                    Text("Urungkan untuk mengembalikan nama sebelumnya. Riwayat yang lebih lama akan tampil jika masih tersedia.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onUndo, enabled = record != null && !busy) { Text("Urungkan nama terakhir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun AddAudioPage(
    ingest: IngestState,
    search: YoutubeSearchState,
    modifier: Modifier,
    onDownload: (String, Boolean) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onSearch: (String, SearchProvider) -> Unit,
    onCancelSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(if (search.busy) 1 else 0) }
    var url by rememberSaveable { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(search.query) }
    var providerName by rememberSaveable { mutableStateOf(search.provider.name) }
    var providerMenu by remember { mutableStateOf(false) }
    var selectedHit by remember { mutableStateOf<SearchHit?>(null) }
    val provider = SearchProvider.entries.firstOrNull { it.name == providerName } ?: SearchProvider.YOUTUBE
    val canStart = !ingest.busy && !search.busy
    val urlValidation = remember(url) { runCatching { LibraryRules.validatedHttpsUrl(url) } }
    val queryValidation = remember(query) { runCatching { YoutubeSearchRules.validatedQuery(query) } }
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submitSearch: () -> Unit = {
        if (canStart && queryValidation.isSuccess) {
            keyboard?.hide()
            onSearch(query, provider)
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "add-header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tambah audio", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    BrandLogo(40.dp)
                }
                Text("Dari tautan, pencarian, atau file di HP.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "add-modes") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp)) {
                Tab(selected = mode == 0, onClick = { mode = 0; authorized = false },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (mode == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).heightIn(min = 52.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text("Tautan") })
                Tab(selected = mode == 1, onClick = { mode = 1; authorized = false },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (mode == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).heightIn(min = 52.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text("Cari") })
            }
        }
        if (mode == 0) item(key = "paste-form") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("TAUTAN AUDIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = url, onValueChange = { url = it; authorized = false }, enabled = canStart,
                    label = { Text("Tautan HTTPS") }, placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), maxLines = 4,
                    isError = url.isNotBlank() && urlValidation.isFailure,
                    trailingIcon = {
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { url = it.trim(); authorized = false }
                        }, enabled = canStart, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tempel") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    supportingText = if (url.isNotBlank() && urlValidation.isFailure) ({
                        Text(urlValidation.exceptionOrNull()?.message ?: "Tautan tidak valid.")
                    }) else null)
                RightsConfirmation(authorized, { authorized = it }, canStart)
                if (!ingest.busy) Button(onClick = {
                    val validUrl = urlValidation.getOrNull()
                    if (canStart && authorized && validUrl != null) {
                        authorized = false
                        keyboard?.hide()
                        onDownload(validUrl, true)
                    }
                }, enabled = canStart && authorized && urlValidation.isSuccess,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(UiIcons.Download, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Unduh audio")
                }
            }
        } else item(key = "search-form") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CARI DI SUMBER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = canStart,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .semantics { contentDescription = "Sumber pencarian: ${provider.label}" }) {
                        Text(provider.label, modifier = Modifier.weight(1f))
                        Icon(UiIcons.ChevronDown, null, Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        SearchProvider.entries.forEach { source ->
                            DropdownMenuItem(text = { Text(source.label) }, enabled = canStart,
                                trailingIcon = if (source == provider) ({ Icon(UiIcons.Check, null) }) else null,
                                onClick = { providerName = source.name; providerMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, enabled = canStart,
                    label = { Text("Judul atau kata kunci") }, leadingIcon = { Icon(UiIcons.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ GlyphButton(UiIcons.Close, "Hapus kata pencarian", { query = "" }, enabled = canStart) }) else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    isError = query.isNotBlank() && queryValidation.isFailure,
                    supportingText = if (query.isNotBlank() && queryValidation.isFailure) ({
                        Text(queryValidation.exceptionOrNull()?.message ?: "Kata pencarian tidak valid.")
                    }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                Button(onClick = submitSearch, enabled = canStart && queryValidation.isSuccess,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(UiIcons.Search, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Cari audio")
                }
                Text("Pencarian hanya mengambil informasi. Pilih hasil lalu konfirmasi izin sebelum mengunduh.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (search.busy) item(key = "search-loading") {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Mencari di ${search.provider.label}…", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(search.query, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Unduhan, impor, dan pembaruan mesin menunggu pencarian selesai.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCancelSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Batalkan pencarian") }
                }
            }
        }
        if (ingest.busy || ingest.error || ingest.progress != null || ingest.detail.isNotBlank() || ingest.stage != "Siap menambah audio") {
            item(key = "ingest-status") { IngestStatusCard(ingest, onCancel) }
        }
        item(key = "local-import") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ATAU FILE LOKAL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onImport, enabled = canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(UiIcons.Import, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("Impor audio dari HP")
                }
                Text("Audio yang selesai ditambahkan akan muncul di Pustaka. Impor membuat salinan; file asli tetap.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (mode == 1 && !search.busy) {
            when {
                search.error != null -> item(key = "search-error") {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Pencarian belum berhasil", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(search.error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = { onSearch(search.query, search.provider) },
                                enabled = canStart && runCatching { YoutubeSearchRules.validatedQuery(search.query) }.isSuccess) { Text("Coba lagi") }
                        }
                    }
                }
                !search.hasSearched -> item(key = "search-idle") {
                    EmptyMessage("Temukan audio", "Pilih YouTube atau YouTube Music, isi kata kunci, lalu tekan Cari audio.", UiIcons.Search)
                }
                search.results.isEmpty() -> item(key = "search-empty") {
                    EmptyMessage("Tidak ada hasil", "Tidak ada hasil untuk “${search.query}” di ${search.provider.label}. Coba kata kunci lain.", UiIcons.Search)
                }
                else -> {
                    item(key = "search-results-heading") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${search.results.size} hasil · ${search.provider.label}", style = MaterialTheme.typography.titleSmall)
                            Text("Untuk “${search.query}”", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(search.results, key = { "search-${it.videoId}" }) { hit ->
                        SearchResultRow(hit, search.provider, canStart, onSelect = { selectedHit = hit })
                    }
                }
            }
        }
        item(key = "engine-settings") {
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(UiIcons.Settings, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text("Pengaturan & tentang", modifier = Modifier.weight(1f))
                Icon(UiIcons.ChevronRight, null, Modifier.size(20.dp))
            }
        }
    }
    selectedHit?.let { hit ->
        key(hit.videoId, hit.url) {
            SearchDownloadDialog(hit, search.provider, canStart, onDismiss = { selectedHit = null },
                onDownload = { link, consent -> selectedHit = null; onDownload(link, consent) })
        }
    }
}

@Composable
private fun RightsConfirmation(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked,
        enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text("Saya memiliki izin untuk mengunduh audio ini.", style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, provider: SearchProvider, enabled: Boolean, onSelect: () -> Unit) {
    Surface(onClick = onSelect, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 84.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.size(width = 80.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(hit.channel ?: provider.label, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(hit.durationSeconds?.let { LibraryRules.durationLabel(it * 1000L) } ?: "Durasi belum tersedia",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(UiIcons.Download, contentDescription = "Pilih untuk konfirmasi unduhan", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SearchDownloadDialog(
    hit: SearchHit,
    provider: SearchProvider,
    canStart: Boolean,
    onDismiss: () -> Unit,
    onDownload: (String, Boolean) -> Unit,
) {
    var authorized by remember { mutableStateOf(false) }
    val validUrl = remember(hit.url) { runCatching { LibraryRules.validatedHttpsUrl(hit.url) }.isSuccess }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Unduh audio ini?") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                YoutubeThumbnail(hit.thumbnailUrl, hit.title, Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(12.dp)))
                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                Text(hit.channel ?: provider.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("File audio akan disimpan di HP, bukan diputar dari layanan sumber.", style = MaterialTheme.typography.bodySmall)
                RightsConfirmation(authorized, { authorized = it }, canStart)
                if (!canStart) Text("Tunggu proses atau pencarian selesai, atau batalkan proses yang aktif.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!validUrl) Text("Tautan hasil tidak valid. Lakukan pencarian kembali.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canStart && authorized && validUrl) onDownload(hit.url, true) },
                enabled = canStart && authorized && validUrl) { Text("Unduh audio") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun IngestStatusCard(state: IngestState, onCancel: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val progress = state.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.stage, style = MaterialTheme.typography.titleLarge,
                    color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (progress != null) Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (state.busy) {
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(if (progress == null) "Proses berjalan; persentase belum tersedia." else "Progres dari mesin unduh",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.busy && !state.error && state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (state.error) Text("Proses belum berhasil. Lihat detail untuk penyebabnya, lalu coba lagi atau impor file lokal.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy && state.cancelable) OutlinedButton(onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Batalkan proses") }
            if (state.busy && !state.cancelable) Text("Tahap ini perlu diselesaikan sebelum proses baru dimulai.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.detail.isNotBlank()) TextButton(onClick = { details = true }) { Text("Detail proses") }
        }
    }
    if (details) DiagnosticsDialog(state, onDismiss = { details = false })
}

@Composable
private fun DiagnosticsDialog(state: IngestState, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Detail proses") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.stage, style = MaterialTheme.typography.titleSmall)
                    Text("yt-dlp: ${state.engineVersion}", style = MaterialTheme.typography.bodySmall)
                    Text(state.detail.ifBlank { "Belum ada keluaran diagnostik." }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun EngineSettingsDialog(
    ingest: IngestState,
    searchBusy: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { BrandLogo(36.dp) }, title = { Text("Pengaturan & tentang") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Mesin audio", style = MaterialTheme.typography.titleMedium)
                Text("yt-dlp: ${ingest.engineVersion}", style = MaterialTheme.typography.bodyMedium)
                Text("Pembaruan opsional mengambil versi stabil dari GitHub resmi. Memerlukan internet dan tidak memasang Termux.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onUpdate, enabled = !ingest.busy && !searchBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Perbarui mesin yt-dlp") }
                if (searchBusy) Text("Selesaikan atau batalkan pencarian sebelum memperbarui mesin.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ingest.busy) {
                    Text(ingest.stage, style = MaterialTheme.typography.bodyMedium)
                    if (ingest.cancelable) TextButton(onClick = onCancel) { Text("Batalkan proses") }
                }
                TextButton(onClick = { details = true }) { Text("Buka diagnostik") }
                HorizontalDivider()
                Text("Tentang Swararia", style = MaterialTheme.typography.titleMedium)
                Text("Audio tersimpan di Music/SwarariaAudioLab. Impor membuat salinan tanpa mengubah file asli. Pemutar memakai file lokal; pencarian, tautan, dan pembaruan memerlukan internet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tanpa akun, server pendamping, atau analitik aplikasi. Unduh hanya audio yang boleh Anda gunakan. Engine downloader dan FFmpeg memiliki kewajiban lisensi; lihat source dan catatan lisensi sebelum distribusi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
    if (details) DiagnosticsDialog(ingest, onDismiss = { details = false })
}

private data class SessionQueueItem(val index: Int, val mediaId: String, val title: String?)

private data class SessionPlayback(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val wantsPlay: Boolean = false,
    val idle: Boolean = true,
    val ended: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val canPlay: Boolean = false,
    val canSeek: Boolean = false,
    val canStop: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val canChooseQueue: Boolean = false,
    val queue: List<SessionQueueItem> = emptyList(),
    val error: String? = null,
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun readSwarariaPlayback(p: MediaController): SessionPlayback {
    if (!p.isConnected) return SessionPlayback()
    val queue = if (p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) List(p.mediaItemCount) { index ->
        val item = p.getMediaItemAt(index)
        SessionQueueItem(index, item.mediaId, item.mediaMetadata.title?.toString())
    } else emptyList()
    return SessionPlayback(
        connected = true, mediaId = p.currentMediaItem?.mediaId,
        title = p.mediaMetadata.title?.toString(), currentIndex = p.currentMediaItemIndex,
        playing = p.isPlaying, wantsPlay = playbackRequestsPause(p), idle = p.playbackState == Player.STATE_IDLE,
        ended = p.playbackState == Player.STATE_ENDED,
        buffering = p.playbackState == Player.STATE_BUFFERING,
        positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET },
        canPlay = p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
        canSeek = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && p.isCurrentMediaItemSeekable,
        canStop = p.isCommandAvailable(Player.COMMAND_STOP),
        hasPrevious = p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && p.hasPreviousMediaItem(),
        hasNext = p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && p.hasNextMediaItem(),
        canChooseQueue = p.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM), queue = queue,
        error = p.playerError?.let { "${it.errorCodeName}: ${it.message.orEmpty()}" },
    )
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun rememberSwarariaPlayback(controller: MediaController?): SessionPlayback {
    var state by remember(controller) { mutableStateOf(SessionPlayback()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        val p = controller ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!p.isConnected) { state = SessionPlayback(); return@repeatOnLifecycle }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { state = readSwarariaPlayback(p) }
            }
            p.addListener(listener)
            try {
                state = readSwarariaPlayback(p)
                // Events handle paused/idle changes. The position ticker exists only while playing and visible.
                snapshotFlow { state.playing }.collectLatest { playing ->
                    if (playing) while (isActive) {
                        delay(500)
                        if (!p.isConnected) { state = SessionPlayback(); break }
                        state = state.copy(positionMs = p.currentPosition.coerceAtLeast(0))
                    }
                }
            } finally { p.removeListener(listener) }
        }
    }
    return state
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private inline fun connectedCommand(controller: MediaController?, command: Int, action: (MediaController) -> Unit) {
    val p = controller ?: return
    if (p.isConnected && p.isCommandAvailable(command)) action(p)
}

private fun playbackRequestsPause(p: Player): Boolean =
    p.playWhenReady && p.playbackState != Player.STATE_IDLE &&
        p.playbackState != Player.STATE_ENDED && p.playerError == null

private fun toggleSwarariaPlayback(controller: MediaController?) {
    connectedCommand(controller, Player.COMMAND_PLAY_PAUSE) { p ->
        if (p.currentMediaItem != null) {
            if (playbackRequestsPause(p)) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED && p.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) p.seekToDefaultPosition()
                if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                p.play()
            }
        }
    }
}

private fun playbackTitle(current: AudioTrack?, playback: SessionPlayback): String =
    current?.let { UiPresentation.title(it.displayName) }
        ?: playback.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) }
        ?: if (playback.mediaId == null) "Pilih audio dari Pustaka" else "Audio tidak tersedia"

private fun playbackStatus(playback: SessionPlayback): String = when {
    !playback.connected -> "Pemutar belum terhubung"
    playback.error != null -> "Pemutaran bermasalah"
    playback.buffering -> "Menyiapkan audio…"
    playback.idle && playback.mediaId != null -> "Dihentikan"
    playback.playing -> "Audio lokal"
    playback.ended -> "Selesai · Audio lokal"
    playback.mediaId != null -> "Dijeda · Audio lokal"
    else -> "Pilih audio dari Pustaka"
}

@Composable
private fun MiniPlayer(current: AudioTrack?, playback: SessionPlayback, controller: MediaController?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Buka pemutar lengkap" }) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackArtwork(current?.id ?: playback.mediaId?.toLongOrNull(), Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(playbackTitle(current, playback), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(playbackStatus(playback), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                GlyphButton(if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                    if (playback.wantsPlay) "Jeda audio" else "Lanjutkan audio", { toggleSwarariaPlayback(controller) },
                    enabled = playback.connected && playback.canPlay, filled = true)
                GlyphButton(UiIcons.Next, "Audio berikutnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
                }, enabled = playback.connected && playback.hasNext)
            }
            playback.durationMs?.let { duration ->
                LinearProgressIndicator(progress = { (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun FocusedPlayer(
    tracks: List<AudioTrack>,
    current: AudioTrack?,
    playback: SessionPlayback,
    controller: MediaController?,
    modifier: Modifier,
    onBack: () -> Unit,
    onRename: (AudioTrack) -> Unit,
    onSettings: () -> Unit,
) {
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var seekPosition by remember(playback.mediaId, playback.currentIndex) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * .39f, 320.dp).coerceAtLeast(80.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(UiIcons.Back, "Kembali", onBack)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        playback.buffering -> "MENYIAPKAN AUDIO"
                        playback.playing -> "SEDANG DIPUTAR"
                        playback.mediaId != null -> "PEMUTAR AUDIO"
                        else -> "PEMUTAR"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrandLogo(20.dp); Text("Swararia", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Box {
                    GlyphButton(UiIcons.More, "Menu pemutar", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Ubah nama audio") }, leadingIcon = { Icon(UiIcons.Rename, null) },
                            enabled = current != null, onClick = { menu = false; current?.let(onRename) })
                        DropdownMenuItem(text = { Text("Pengaturan & tentang") }, leadingIcon = { Icon(UiIcons.Settings, null) },
                            onClick = { menu = false; onSettings() })
                    }
                }
            }
            TrackArtwork(current?.id ?: playback.mediaId?.toLongOrNull(),
                Modifier.size(coverSize).clip(RoundedCornerShape(24.dp)))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(playbackTitle(current, playback), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(current?.let { "${UiPresentation.metadata(it.displayName, playback.durationMs ?: it.durationMs)} · Audio lokal" }
                    ?: playbackStatus(playback), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth()) {
                val duration = playback.durationMs ?: 1L
                val displayed = if (playback.durationMs == null) 0f else (seekPosition ?: playback.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
                Slider(value = displayed, onValueChange = { seekPosition = it },
                    valueRange = 0f..duration.toFloat(), enabled = playback.connected && playback.canSeek && playback.durationMs != null,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi pemutaran audio" },
                    onValueChangeFinished = {
                        val requested = seekPosition
                        seekPosition = null
                        if (requested != null) connectedCommand(controller, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) { p ->
                            if (p.currentMediaItem?.mediaId == playback.mediaId && p.currentMediaItemIndex == playback.currentIndex) p.seekTo(requested.toLong())
                        }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LibraryRules.durationLabel(seekPosition?.toLong() ?: playback.positionMs.takeIf { playback.connected && playback.mediaId != null }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(LibraryRules.durationLabel(playback.durationMs), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (playback.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!playback.connected) Text("Pemutar belum terhubung. Kembali ke Pustaka untuk memilih audio setelah tersambung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(UiIcons.Previous, "Audio sebelumnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) { p -> if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() }
                }, modifier = Modifier.size(56.dp), enabled = playback.connected && playback.hasPrevious)
                GlyphButton(if (playback.wantsPlay) UiIcons.Pause else UiIcons.Play,
                    if (playback.wantsPlay) "Jeda audio" else "Putar atau lanjutkan audio", { toggleSwarariaPlayback(controller) },
                    modifier = Modifier.size(80.dp), enabled = playback.connected && playback.mediaId != null && playback.canPlay,
                    filled = true, iconSize = 32.dp)
                GlyphButton(UiIcons.Next, "Audio berikutnya", {
                    connectedCommand(controller, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
                }, modifier = Modifier.size(56.dp), enabled = playback.connected && playback.hasNext)
            }
            TextButton(onClick = { connectedCommand(controller, Player.COMMAND_STOP) { it.stop() } },
                enabled = playback.connected && playback.mediaId != null && playback.canStop,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(UiIcons.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
            }
            Surface(onClick = { queueOpen = true }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(UiIcons.Queue, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Antrean", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${playback.queue.size} audio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (queueOpen) QueueDialog(tracks, playback, controller, onDismiss = { queueOpen = false })
}

@Composable
private fun QueueDialog(tracks: List<AudioTrack>, playback: SessionPlayback, controller: MediaController?, onDismiss: () -> Unit) {
    val byId = remember(tracks) { tracks.associateBy { it.id.toString() } }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(UiIcons.Queue, null) }, title = { Text("Antrean · ${playback.queue.size} audio") },
        text = {
            if (playback.queue.isEmpty()) Text("Antrean sesi pemutar belum tersedia. Pilih audio dari Pustaka.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(playback.queue, key = { it.index }) { entry ->
                    val track = byId[entry.mediaId]
                    val title = track?.let { UiPresentation.title(it.displayName) }
                        ?: entry.title?.takeIf { it.isNotBlank() }?.let { UiPresentation.title(it) } ?: "Audio tidak tersedia"
                    val active = entry.index == playback.currentIndex
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(enabled = playback.connected && playback.canChooseQueue, onClickLabel = "Putar $title") {
                            connectedCommand(controller, Player.COMMAND_SEEK_TO_MEDIA_ITEM) { p ->
                                if (entry.index in 0 until p.mediaItemCount && p.getMediaItemAt(entry.index).mediaId == entry.mediaId) {
                                    if (p.currentMediaItemIndex == entry.index) toggleSwarariaPlayback(p)
                                    else {
                                        p.seekTo(entry.index, C.TIME_UNSET)
                                        if (p.playbackState == Player.STATE_IDLE && p.isCommandAvailable(Player.COMMAND_PREPARE)) p.prepare()
                                        if (p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) p.play()
                                    }
                                }
                            }
                        }.semantics { selected = active }.padding(horizontal = 10.dp, vertical = 12.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${entry.index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 20.dp))
                        Text(title, style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (active) Icon(UiIcons.Check, "Audio saat ini", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.swararia_logo), contentDescription = null,
        modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
private fun TrackArtwork(id: Long?, modifier: Modifier = Modifier) {
    val image = if (id == null) R.drawable.swararia_logo else when (UiPresentation.artworkIndex(id)) {
        0 -> R.drawable.cover_0
        1 -> R.drawable.cover_1
        2 -> R.drawable.cover_2
        3 -> R.drawable.cover_3
        else -> R.drawable.cover_4
    }
    Image(painterResource(image), contentDescription = null, modifier = modifier,
        contentScale = if (id == null) ContentScale.Fit else ContentScale.Crop)
}

@Composable
private fun GlyphButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    iconSize: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (filled) Modifier.background(if (enabled) colors.primary else colors.surfaceVariant, CircleShape) else Modifier)) {
        Icon(icon, contentDescription = description,
            tint = if (!enabled) colors.onSurface.copy(alpha = .38f) else if (filled) colors.onPrimary else colors.onSurface,
            modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun EmptyMessage(title: String, description: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}
