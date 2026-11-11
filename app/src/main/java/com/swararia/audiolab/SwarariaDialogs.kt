package com.swararia.audiolab

import androidx.compose.foundation.layout.*
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
