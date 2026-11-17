package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.swararia.audiolab.remote.readPlainTextFromClipboard
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteAudioDialog(track: AudioTrack, busy: Boolean, onDismiss: () -> Unit,
    onDelete: (AudioTrack, () -> Unit) -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Delete audio?", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackArtwork(track.artworkKey, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(UiPresentation.title(track.displayName), style = MaterialTheme.typography.titleMedium)
                    Text(track.displayName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("File salinan yang dikelola aplikasi di HP akan dihapus permanen. Sumber import asli tetap aman.")
            if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = { if (!busy) onDelete(track, onDismiss) }, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteBatchAudioDialog(
    tracks: List<AudioTrack>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDeleteBatch: (List<AudioTrack>, () -> Unit) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Delete ${tracks.size} audio?", style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tracks.take(5).forEach { track ->
                    Text(
                        "• ${UiPresentation.title(track.displayName)}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tracks.size > 5) {
                    Text(
                        "…dan ${tracks.size - 5} track lainnya",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${tracks.size} file salinan yang dikelola aplikasi di HP akan dihapus permanen. Sumber import asli tetap aman.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = { if (!busy) onDeleteBatch(tracks, onDismiss) },
                    enabled = !busy && tracks.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Delete (${tracks.size})") }
            }
        }
    }
}

@Composable
internal fun DownloadLinkDialog(
    busy: Boolean,
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var authorized by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val validUrl = remember(url) { runCatching { DownloadQueueRules.validatedPublicSourceUrl(url) }.getOrNull() }
    val pasteFromClipboard = {
        readPlainTextFromClipboard(context)?.let {
            url = it.trim()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unduh dari Link") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Salin link video dari YouTube / YT Music, lalu tempel di bawah ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !busy,
                    label = { Text("YouTube / YT Music link") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        GlyphButton(UiIcons.Paste, "Tempel link", { pasteFromClipboard() }, enabled = !busy)
                    },
                )
                if (url.isBlank()) {
                    OutlinedButton(
                        onClick = { pasteFromClipboard() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(UiIcons.Paste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tempel Link dari Clipboard")
                    }
                }
                if (url.isNotBlank() && validUrl == null) {
                    Text(
                        text = "Gunakan link video YouTube / YT Music publik biasa (bukan link playlist atau privat).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!busy && authorized && validUrl != null) { authorized = false; onDownload(validUrl) } },
                enabled = !busy && authorized && validUrl != null,
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Fresh composition for every request: state is scoped to this dialog instance. */
@Composable
internal fun DownloadConsentDialog(hits: List<SearchHit>, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var authorized by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download (${hits.size})?") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                hits.forEach { hit ->
                    Column {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            hit.channel.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (authorized && !busy) { authorized = false; onConfirm() } },
                enabled = authorized && !busy,
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RenameAudioDialog(
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
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(track.id, title, onDismiss) }, enabled = canSave) { Text("Simpan nama") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } })
}

@Composable
internal fun RenameHistoryDialog(record: RenameRecord?, busy: Boolean, onDismiss: () -> Unit, onUndo: () -> Unit) {
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
                if (busy) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
            }
        },
        confirmButton = { TextButton(onClick = onUndo, enabled = record != null && !busy) { Text("Urungkan nama terakhir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
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
internal fun EngineSettingsDialog(
    ingest: IngestState,
    searchBusy: Boolean,
    ytMusic: YoutubeMusicAccountState = YoutubeMusicAccountState(),
    playbackSettings: PlaybackSettingsState = PlaybackSettingsState(),
    onSetPlaybackFadeInMs: (Long) -> Unit = {},
    onSetPlaybackFadeOutMs: (Long) -> Unit = {},
    ytMusicActions: YoutubeMusicAccountActions = YoutubeMusicAccountActions(),
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { BrandLogo(44.dp) }, title = { Text("Pengaturan & Tentang SWR Player") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pemutaran & Transisi (Global)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Fade otomatis saat Play, Pause, Stop, Next/Prev, ganti lagu, dan Cue. Preview pencarian tetap instan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FadePresetSelectorRow(
                    title = "Fade In (Play / Resume)",
                    selectedMs = playbackSettings.fadeInMs,
                    onSelect = onSetPlaybackFadeInMs,
                )
                FadePresetSelectorRow(
                    title = "Fade Out (Pause / Stop / Skip)",
                    selectedMs = playbackSettings.fadeOutMs,
                    onSelect = onSetPlaybackFadeOutMs,
                )
                Text(
                    "Tekan Pause/Stop/Next kedua kali saat fading untuk potong instan. Tombol Fade Out memakai ${FadeRules.shortLabel(playbackSettings.effectiveManualFadeOutMs)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Akun YouTube Music (Opsional)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Opsional untuk akun YT Music Premium (peluang audio hingga 256k AAC M4A). Disarankan pakai akun sekunder / slot Family, bukan akun Google utama.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ytMusic.connected) {
                    val statusLabel = when {
                        ytMusic.needsReauth -> "Perlu masuk ulang (sesi kadaluarsa)"
                        ytMusic.tier == YoutubeMusicPremiumTier.PREMIUM -> "Terhubung • YT Music Premium"
                        ytMusic.tier == YoutubeMusicPremiumTier.FREE -> "Terhubung • Akun standar"
                        ytMusic.source == YoutubeMusicAuthSource.COOKIES_TXT -> "Terhubung (cookies.txt terenkripsi)"
                        else -> "Terhubung (sesi WebView terenkripsi)"
                    }
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ytMusic.needsReauth) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    if (!ytMusic.statusNote.isNullOrBlank()) {
                        Text(
                            ytMusic.statusNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ytMusic.checking) FohIndeterminateBar(Modifier.fillMaxWidth(), height = 4.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Gunakan saat mengunduh", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Pencarian & preview tetap tanpa akun agar aman.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ytMusic.enabledForDownloads,
                            onCheckedChange = ytMusicActions.onToggleEnabled,
                            enabled = !ingest.busy && !ytMusic.checking,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = if (ytMusic.needsReauth) ytMusicActions.onOpenSignIn else ytMusicActions.onVerifyAccount,
                            enabled = !ingest.busy && !ytMusic.checking,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (ytMusic.needsReauth) "Masuk ulang" else "Cek status")
                        }
                        TextButton(
                            onClick = ytMusicActions.onDisconnect,
                            enabled = !ingest.busy && !ytMusic.checking,
                        ) {
                            Text("Putuskan", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    if (!ytMusic.statusNote.isNullOrBlank()) {
                        Text(
                            ytMusic.statusNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ytMusic.needsReauth) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = ytMusicActions.onOpenSignIn,
                        enabled = !ingest.busy && !ytMusic.checking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    ) {
                        Text("Masuk YouTube Music")
                    }
                    TextButton(
                        onClick = ytMusicActions.onOpenImportCookies,
                        enabled = !ingest.busy && !ytMusic.checking,
                    ) {
                        Text("Impor cookies.txt (jika WebView diblokir)")
                    }
                }
                HorizontalDivider()
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
                Text("Tentang SWR Player", style = MaterialTheme.typography.titleMedium)
                Text("SWR Player menyimpan audio lokal di Music/SwarariaAudioLab. Impor membuat salinan tanpa mengubah file asli. Pemutar memakai file lokal; pencarian, tautan, dan pembaruan memerlukan internet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Default tanpa akun (opsional masuk YT Music Premium dienkripsi Android Keystore tanpa backup cloud), tanpa server luar, atau analitik aplikasi. Unduh hanya audio yang boleh Anda gunakan. Engine downloader dan FFmpeg memiliki kewajiban lisensi; lihat source dan catatan lisensi sebelum distribusi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
    if (details) DiagnosticsDialog(ingest, onDismiss = { details = false })
}

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
                                Text("${playlist.trackIds.size} track", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(membershipInfo, style = MaterialTheme.typography.bodySmall,
                                    color = if (allIncluded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(playlist.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FadePresetSelectorRow(
    title: String,
    selectedMs: Long,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlaybackFadePreset.entries.forEach { preset ->
                FilterChip(
                    selected = selectedMs == preset.durationMs,
                    onClick = { onSelect(preset.durationMs) },
                    label = { Text(preset.shortLabel, maxLines = 1) },
                )
            }
        }
    }
}
