package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** No saved state, clipboard reads, camera permission, discovery, or playback ownership. */
@Composable
fun DesktopRemoteScreen(session: DesktopRemoteSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf<String?>(null) }
    var pendingScanned by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val lease = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    session.visible(lease)
                    pendingScanned?.let { scanned ->
                        pendingScanned = null
                        session.connect(scanned)
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.hidden(lease)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.visible(lease)
        onDispose {
            pendingScanned = null
            session.hidden(lease)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == "pending" || state.phase == "active") {
            input = ""
            scanFeedback = null
            pendingScanned = null
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLogo(36.dp)
            Column {
                Text("Desktop Remote", style = MaterialTheme.typography.headlineSmall)
                Text(state.endpoint?.let { "Desktop • $it" } ?: "Kontrol pemutar di PC", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.phase == "disconnected") {
            EmptyMessage("Hubungkan Desktop", "Di PC: buka HP Remote (Android App) → Aktifkan Listener → Buat kode pairing, lalu scan QR dari sini. Jangan kirim kode pairing lewat chat.", UiIcons.Link)
            Button(
                onClick = {
                    if (scanning || state.busy) return@Button
                    scanning = true
                    scanFeedback = null
                    pendingScanned = null
                    coroutineScope.launch {
                        try {
                            when (val res = DesktopRemoteQrScanner.scan(context) { scanFeedback = it }) {
                                is DesktopRemoteScanResult.Scanned -> {
                                    scanFeedback = null
                                    input = ""
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                        pendingScanned = null
                                        session.connect(res.rawText)
                                    } else {
                                        // Scanner overlay may briefly pause the Activity; deliver right after ON_RESUME restores visibility.
                                        pendingScanned = res.rawText
                                    }
                                }
                                DesktopRemoteScanResult.Canceled -> {
                                    scanFeedback = null
                                }
                                is DesktopRemoteScanResult.Failed -> {
                                    scanFeedback = res.message
                                }
                            }
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !state.busy && !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(UiIcons.QrScan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Menyiapkan Scanner…" else "Scan QR dari PC")
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            scanFeedback?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Text("Atau masukkan kode manual", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = input, onValueChange = { if (it.length <= 256) { input = it; scanFeedback = null; pendingScanned = null } },
                label = { Text("Teks pairing swararia1|…") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            OutlinedButton(onClick = { scanFeedback = null; pendingScanned = null; session.connect(input) }, enabled = input.isNotEmpty() && !state.busy && !scanning) { Text("Connect") }
        } else {
            Text(when (state.phase) {
                "connecting" -> "Menghubungkan…"
                "pending" -> "Menunggu persetujuan di PC"
                else -> if (state.role == "view_only") "View only" else "Control • Desktop"
            }, style = MaterialTheme.typography.titleMedium)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.playback?.let { playback ->
                Text(playback.title ?: "Belum ada track Desktop", style = MaterialTheme.typography.titleLarge)
                Text(if (!state.fresh) "Status belum terverifikasi" else if (!playback.online) "Desktop player offline" else playback.transport ?: "Status pemutar belum tersedia")
                if (playback.time != null && playback.duration != null) Text("${playback.time.toLong()} / ${playback.duration.toLong()} detik • Desktop")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphButton(UiIcons.Play, "Play Desktop", { session.command("play") }, enabled = state.canControl && "play" in state.commands)
                GlyphButton(UiIcons.Pause, "Pause Desktop", { session.command("pause") }, enabled = state.canControl && "pause" in state.commands)
                GlyphButton(UiIcons.Stop, "Stop Desktop", { session.command("stop") }, enabled = state.canControl && "stop" in state.commands)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { session.refresh() }, enabled = !state.busy) { Text("Refresh") }
                TextButton(onClick = { session.disconnect(); input = "" }) { Text("Disconnect") }
            }
            Text("Disconnect / kembali / Home tidak menghentikan audio. Sesi hanya di RAM; tutup proses perlu pairing ulang.", style = MaterialTheme.typography.bodySmall)
        }
        state.outcome?.let { outcome -> Text(when (outcome) {
            "accepted" -> "Accepted • belum terbukti dijalankan"
            "pending" -> "Pending • menunggu hasil Desktop"
            "applied" -> "Applied • Desktop mengonfirmasi"
            "unknown" -> "Unknown • hasil tidak diketahui; tidak dikirim ulang otomatis"
            "expired" -> "Expired • batas waktu perintah habis"
            else -> "Rejected • perintah ditolak"
        }) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
