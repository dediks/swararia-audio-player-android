package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
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
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountActions
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountState
import com.swararia.audiolab.ytmusic.YoutubeMusicAuthSource
import com.swararia.audiolab.ytmusic.YoutubeMusicPremiumTier
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
    onOpenPlaylistUrl: (String, Boolean) -> Unit = { _, _ -> },
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var authorized by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val validUrl = remember(url) { runCatching { DownloadQueueRules.validatedPublicSourceUrl(url) }.getOrNull() }
    val validPlaylistUrl = remember(url) { runCatching { DownloadQueueRules.validatedPublicPlaylistUrl(url) }.getOrNull() }
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
                    text = "Salin link lagu atau playlist publik dari YouTube / YT Music, lalu tempel di bawah ini.",
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
                if (validPlaylistUrl != null) {
                    Text(
                        text = "Link playlist terdeteksi — langsung unduh semua lagu atau buka daftar lagunya dulu.",
                        color = FohTheme.colors.safeAccent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            if (!busy && authorized) {
                                authorized = false
                                onOpenPlaylistUrl(validPlaylistUrl, false)
                            }
                        },
                        enabled = !busy && authorized,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Lihat Daftar Lagu Playlist")
                    }
                } else if (url.isNotBlank() && validUrl == null) {
                    Text(
                        text = "Gunakan link lagu atau playlist YouTube / YT Music publik.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            when {
                validPlaylistUrl != null -> TextButton(
                    onClick = {
                        if (!busy && authorized) {
                            authorized = false
                            onOpenPlaylistUrl(validPlaylistUrl, true)
                        }
                    },
                    enabled = !busy && authorized,
                ) { Text("Unduh Semua") }
                else -> TextButton(
                    onClick = { if (!busy && authorized && validUrl != null) { authorized = false; onDownload(validUrl) } },
                    enabled = !busy && authorized && validUrl != null,
                ) { Text("Download") }
            }
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
