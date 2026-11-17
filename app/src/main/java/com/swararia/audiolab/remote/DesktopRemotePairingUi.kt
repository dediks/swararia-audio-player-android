package com.swararia.audiolab.remote

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons

/** Disconnected pairing onboarding, pending approval card, and session modal dialogs. */
@Composable
internal fun DisconnectedPairingSection(
    input: String,
    scanning: Boolean,
    busy: Boolean,
    scanFeedback: String?,
    manualFeedback: String?,
    errorMessage: String?,
    peerState: RemoteWifiPeerState = RemoteWifiPeerState(),
    wifiStatus: RemoteWifiTransportStatus = RemoteWifiTransportStatus(),
    mdnsServers: List<RemoteMdnsServer> = emptyList(),
    onInputChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onPasteManual: () -> Unit,
    onConnectManual: () -> Unit,
    onConfirmLegacyWifiJoin: () -> Unit = {},
    onCancelLegacyWifiJoin: () -> Unit = {},
    onReleasePeerNetwork: () -> Unit = {},
    onDiscoverP2p: () -> Unit = {},
    onConnectP2p: (String) -> Unit = {},
    onDisconnectP2p: () -> Unit = {},
) {
    val foh = FohTheme.colors
    val context = LocalContext.current
    val validPreviewLabel = remember(input) {
        if (input.isBlank()) null
        else when (val parsed = RemoteSmartQrCodec.parseQrInput(input)) {
            is ParsedRemoteQr.DirectPairing -> runCatching { DesktopRemotePairing.parse(parsed.rawPairing).endpoint }.getOrNull()
            is ParsedRemoteQr.SmartWifiAndPairing -> {
                val ep = runCatching { DesktopRemotePairing.parse(parsed.rawPairing).endpoint }.getOrNull()
                if (ep != null) "Smart Join ${parsed.ssid} → $ep" else null
            }
            is ParsedRemoteQr.WifiOnly -> "Wi-Fi ${parsed.ssid}"
            null -> null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RemoteScopePill("HP ↔ HP", highlighted = true)
        RemoteScopePill("HP ↔ Laptop / PC")
        RemoteScopePill("Lokal LAN / Hotspot")
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = foh.safeBg,
        border = BorderStroke(1.dp, foh.safeBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.Link, contentDescription = null, tint = foh.safeAccent, modifier = Modifier.size(21.dp))
                Text(
                    text = "Hubungkan ke Pemutar FOH",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = foh.safeAccent,
                )
            }
            Text(
                text = "Buka Mode Server di perangkat target (HP SWR Player lain atau menu Android Remote di Laptop/PC), lalu pindai kode QR-nya.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onScanQr,
                enabled = !busy && !scanning,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) {
                Icon(UiIcons.QrScan, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Menyiapkan Scanner…" else "Pindai QR Perangkat")
            }
            if (scanning || busy) {
                FohIndeterminateBar(
                    color = FohTheme.colors.safeAccent,
                    trackColor = FohTheme.colors.surfaceDeep,
                    height = 4.dp,
                )
            }
            scanFeedback?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = foh.alertAccent)
            }
        }
    }

    errorMessage?.let { NoticeCard(it, isError = true) }

    if (peerState.clientSmartConnecting || peerState.clientSmartStatus != null || peerState.clientSmartError != null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (peerState.clientSmartError != null) foh.alertBg else foh.safeBg,
            border = BorderStroke(1.dp, if (peerState.clientSmartError != null) foh.alertBorder else foh.safeBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = peerState.clientSmartError ?: peerState.clientSmartStatus ?: "Menyambungkan Wi-Fi lokal…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peerState.clientSmartError != null) foh.alertAccent else foh.safeAccent,
                )
                if (peerState.clientSmartConnecting) {
                    FohIndeterminateBar(height = 4.dp)
                } else if (wifiStatus.peerBound) {
                    OutlinedButton(
                        onClick = onReleasePeerNetwork,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Lepas Ikatan Wi-Fi Lokal")
                    }
                }
            }
        }
    }

    peerState.pendingLegacyWifiJoin?.let { pendingWifi ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = foh.warnBg,
            border = BorderStroke(1.dp, foh.warnBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Sambung Wi-Fi (Android 8–9)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = foh.warnAccent,
                )
                Text(
                    text = "1. Hubungkan Wi-Fi HP ini ke SSID: ${pendingWifi.ssid}\n2. Setelah tersambung, ketuk Lanjut Hubungkan (data seluler tetap dapat aktif karena socket terikat ke Wi-Fi).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            copyPlainTextToClipboard(context, "Password Wi-Fi", pendingWifi.passphrase())
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Salin Sandi & Buka Wi-Fi", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onConfirmLegacyWifiJoin,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(0.9f),
                    ) {
                        Text("Lanjut Hubungkan", maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = onCancelLegacyWifiJoin) {
                    Text("Batal", color = foh.alertAccent)
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Atau Pairing Manual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                RemoteScopePill("Tanpa Kamera")
            }
            Text(
                text = "Tempel kode pairing lokal swararia1|… dari perangkat target. Jangan bagikan kode lewat chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Kode pairing swararia1|…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (input.isNotEmpty() && !busy && !scanning) {
                            onConnectManual()
                        }
                    },
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (input.isNotEmpty()) {
                            IconButton(
                                onClick = { onInputChange("") },
                                enabled = !busy && !scanning,
                            ) {
                                Icon(
                                    imageVector = UiIcons.Close,
                                    contentDescription = "Hapus kode pairing",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = onPasteManual,
                            enabled = !busy && !scanning,
                        ) {
                            Icon(
                                imageVector = UiIcons.Paste,
                                contentDescription = "Tempel kode dari clipboard",
                                tint = foh.safeAccent,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                },
            )
            if (manualFeedback != null) {
                Text(
                    text = manualFeedback,
                    style = MaterialTheme.typography.labelSmall,
                    color = foh.alertAccent,
                )
            } else if (validPreviewLabel != null) {
                Text(
                    text = "Target terdeteksi: $validPreviewLabel",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = foh.safeAccent,
                )
            } else if (input.isNotEmpty()) {
                Text(
                    text = "Format valid: swararia1|… atau swararia2|…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPasteManual,
                    enabled = !busy && !scanning,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, foh.safeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = foh.safeBg,
                        contentColor = foh.safeAccent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                ) {
                    Icon(UiIcons.Paste, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tempel", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onConnectManual,
                    enabled = input.isNotEmpty() && !busy && !scanning,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.25f)
                        .heightIn(min = 46.dp),
                ) {
                    Text("Hubungkan", maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // Dual-network Wi-Fi binding, mDNS LAN discovery, and Wi-Fi Direct (P2P) peer tools.
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Jaringan Wi-Fi, mDNS & P2P", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                RemoteScopePill(
                    label = when {
                        wifiStatus.peerBound -> "Wi-Fi Lokal Terikat"
                        wifiStatus.wifiConnected && wifiStatus.cellularActive -> "Wi-Fi + Seluler Aktif"
                        wifiStatus.wifiConnected -> "Wi-Fi Aktif"
                        else -> "Wi-Fi Belum Aktif"
                    },
                    highlighted = wifiStatus.wifiConnected || wifiStatus.peerBound,
                )
            }
            Text(
                text = "Socket remote otomatis diikat ke jalur Wi-Fi/P2P agar internet data seluler HP tetap berjalan tanpa mengganggu koneksi FOH.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mdnsServers.isNotEmpty()) {
                HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                Text(
                    text = "Server terdeteksi di Wi-Fi (mDNS):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = foh.safeAccent,
                )
                mdnsServers.forEach { srv ->
                    Text(
                        text = "• ${srv.serviceName} — ${srv.hostIp}:${srv.port}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Wi-Fi Direct (P2P · Android 8+)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (peerState.p2pConnected) {
                    TextButton(onClick = onDisconnectP2p) {
                        Text("Putus P2P", color = foh.alertAccent)
                    }
                }
            }
            peerState.p2pStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peerState.p2pConnected) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            peerState.p2pError?.let { err ->
                Text(text = err, style = MaterialTheme.typography.bodySmall, color = foh.alertAccent)
            }
            if (peerState.p2pDiscovering) {
                FohIndeterminateBar(height = 3.dp)
            }
            if (peerState.p2pPeers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    peerState.p2pPeers.forEach { peer ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = foh.surfaceDeep,
                            border = BorderStroke(1.dp, foh.borderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.deviceName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "${peer.statusLabel} • ${peer.deviceAddress}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { onConnectP2p(peer.deviceAddress) }) {
                                    Text("Sambung")
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onDiscoverP2p,
                enabled = !busy && !scanning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (peerState.p2pDiscovering) "Pindai Ulang Wi-Fi Direct" else "Cari Perangkat Wi-Fi Direct")
            }
        }
    }
}

@Composable
internal fun PendingApprovalCard(state: DesktopRemoteUiState, onRefresh: () -> Unit, onCancel: () -> Unit) {
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = foh.warnBg,
        border = BorderStroke(1.dp, foh.warnBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.remotePhase == RemotePhase.CONNECTING) "Menghubungkan ke Target…" else "Menunggu Persetujuan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = foh.warnAccent,
            )
            FohIndeterminateBar(
                color = foh.warnAccent,
                trackColor = FohTheme.colors.surfaceDeep,
                height = 4.dp,
            )
            Text(
                if (state.remotePhase == RemotePhase.CONNECTING) {
                    "Memverifikasi identitas TLS dan mendaftarkan sesi pairing."
                } else {
                    "Di perangkat target (Mode Server atau jendela SWR Player Laptop), ketuk Izinkan Control atau View-Only untuk menyetujui perangkat ini."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = foh.alertAccent) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Cek Status") }
                TextButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Batalkan", color = foh.alertAccent) }
            }
        }
    }
}

@Composable
internal fun ReplacePlayingTrackDialog(
    currentTitle: String,
    targetTrack: DesktopRemoteTrack,
    preferredAutoPlay: Boolean,
    onCueOnly: () -> Unit,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti Lagu Target?") },
        text = {
            Text("Perangkat target sedang aktif pada \"$currentTitle\". Memilih \"${targetTrack.title}\" akan menghentikan lagu tersebut.")
        },
        confirmButton = {
            Button(onClick = if (preferredAutoPlay) onPlayNow else onCueOnly) {
                Text(if (preferredAutoPlay) "Putar Sekarang" else "Siapkan (Cue)")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDismiss) { Text("Batal") }
                OutlinedButton(onClick = if (preferredAutoPlay) onCueOnly else onPlayNow) {
                    Text(if (preferredAutoPlay) "Cue Saja" else "Putar Langsung")
                }
            }
        },
    )
}

@Composable
internal fun DisconnectConfirmDialog(
    endpoint: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Putuskan Sesi Remote?") },
        text = {
            Text(
                "Sesi terenkripsi dengan ${endpoint ?: "perangkat target"} akan dihapus dari perangkat ini. " +
                    "Audio yang sedang berjalan di perangkat target tetap berlanjut.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Putuskan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
internal fun SessionInfoDialog(
    state: DesktopRemoteUiState,
    onDismiss: () -> Unit,
) {
    val roleLabel = when (state.role) {
        "control" -> "Kontrol penuh"
        "view_only" -> "Pantau saja (View-Only)"
        else -> "-"
    }
    val mediaLabel = if (state.mediaSupported) {
        "Aktif di target (ketuk ikon unduh pada baris lagu untuk menyimpan ke Library)"
    } else {
        "Nonaktif (aktifkan izin Transfer & Sync pada server target bila perlu mengunduh)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info & Keamanan Sesi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Endpoint: ${state.endpoint ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Text("Peran perangkat ini: $roleLabel", style = MaterialTheme.typography.bodySmall)
                Text("Unduh file lagu: $mediaLabel", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Menutup aplikasi tidak menghentikan audio di perangkat target. Token sesi dienkripsi dengan Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
