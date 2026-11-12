package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** No saved state, clipboard reads, camera, discovery, or playback ownership. */
@Composable
fun DesktopRemoteScreen(session: DesktopRemoteSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val lease = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session.visible(lease)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.hidden(lease)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.visible(lease)
        onDispose { session.hidden(lease); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.phase) { if (state.phase == "pending" || state.phase == "active") input = "" }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLogo(36.dp)
            Column {
                Text("Desktop Remote", style = MaterialTheme.typography.headlineSmall)
                Text(state.endpoint?.let { "Desktop • $it" } ?: "Kontrol pemutar di PC", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.phase == "disconnected") {
            EmptyMessage("Hubungkan Desktop", "Di PC: Player → HP Remote → Aktifkan HTTPS. Salin teks pairing langsung dari PC. QR berisi teks, bukan URL; scanner eksternal dapat menyalinnya. Jangan kirim kode lewat Telegram.", UiIcons.Link)
            OutlinedTextField(value = input, onValueChange = { if (it.length <= 256) input = it },
                label = { Text("Teks pairing swararia1|…") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Button(onClick = { session.connect(input) }, enabled = input.isNotEmpty() && !state.busy) { Text("Connect") }
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
