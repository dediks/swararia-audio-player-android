package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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

@Composable
internal fun DownloadLinkDialog(busy: Boolean, onDismiss: () -> Unit, onDownload: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val validUrl = remember(url) { runCatching { DownloadQueueRules.validatedPublicSourceUrl(url) }.getOrNull() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Download link") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it; authorized = false }, enabled = !busy,
                    label = { Text("YouTube / YT Music link") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = { GlyphButton(UiIcons.Paste, "Paste link", {
                        clipboard.getText()?.text?.let { url = it.trim(); authorized = false }
                    }, enabled = !busy) })
                if (url.isNotBlank() && validUrl == null) Text("Gunakan link video publik YouTube / YT Music, bukan Playlist atau link bertoken.", color = MaterialTheme.colorScheme.error)
                RightsConsent(authorized, { authorized = it }, !busy)
            }
        }, confirmButton = { TextButton(onClick = {
            if (!busy && authorized && validUrl != null) { authorized = false; onDownload(validUrl) }
        }, enabled = !busy && authorized && validUrl != null) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RightsConsent(checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = change, enabled = enabled)
        Text("Saya memiliki izin untuk download audio ini.", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
    }
}

/** Fresh composition for every request: rights are never remembered across downloads. */
@Composable
internal fun DownloadConsentDialog(hits: List<SearchHit>, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var authorized by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Download (${hits.size})?") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                hits.forEach { hit ->
                    Column {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(hit.channel.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RightsConsent(authorized, { authorized = it }, !busy)
            }
        }, confirmButton = {
            TextButton(onClick = { if (authorized && !busy) { authorized = false; onConfirm() } }, enabled = authorized && !busy) { Text("Download") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
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
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
