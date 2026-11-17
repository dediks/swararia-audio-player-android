package com.swararia.audiolab.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.FohFilterChip
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohSegmentedBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Section cards for configuring, inviting, approving, and managing clients on Remote Server mode. */
@Composable
internal fun ServerNetworkCard(
    snapshot: RemoteEngineUiSnapshot,
    interfaces: List<RemoteInterfaceCandidate>,
    selectedIp: String,
    portInput: String,
    validPort: Boolean,
    busy: Boolean,
    peerState: RemoteWifiPeerState = RemoteWifiPeerState(),
    onSelectIp: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartLocalHotspot: () -> Unit = {},
    onStopLocalHotspot: () -> Unit = {},
    onStartWifiDirect: () -> Unit = {},
    onStopWifiDirect: () -> Unit = {},
    onCopySsid: () -> Unit = {},
    onCopyPassphrase: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (snapshot.enabled) foh.safeBg else foh.surfaceCard,
        border = BorderStroke(1.dp, if (snapshot.enabled) foh.safeBorder else foh.borderSubtle),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (snapshot.enabled) "Server FOH Berjalan" else "Server Pemutar Lokal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot.enabled) foh.safeAccent else colors.onSurface,
                    )
                    Text(
                        text = if (snapshot.enabled) {
                            snapshot.webUrl ?: "https://${snapshot.bindIp}:${snapshot.port}/"
                        } else {
                            "Jadikan perangkat ini pemutar panggung yang dikendalikan HP lain, Laptop, atau browser FOH."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (snapshot.enabled) FontFamily.Monospace else FontFamily.Default,
                        color = if (snapshot.enabled) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
                RemoteServerStatusPill(
                    text = if (snapshot.enabled) "● AKTIF" else "NONAKTIF",
                    active = snapshot.enabled,
                )
            }

            if (busy || peerState.serverPeerBusy) {
                FohIndeterminateBar(height = 4.dp)
            }

            if (peerState.serverPeerMode != RemoteServerPeerMode.NONE || peerState.serverPeerError != null || peerState.serverPeerStatus != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (peerState.serverPeerError != null) foh.alertBg else foh.surfaceDeep,
                    border = BorderStroke(1.dp, if (peerState.serverPeerError != null) foh.alertBorder else foh.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val modeTitle = when (peerState.serverPeerMode) {
                            RemoteServerPeerMode.LOCAL_HOTSPOT -> "Hotspot Lokal Tanpa Kuota Aktif"
                            RemoteServerPeerMode.WIFI_DIRECT_GO -> "Grup Wi-Fi Direct (P2P) Aktif"
                            RemoteServerPeerMode.NONE -> "Mode Jaringan Cepat"
                        }
                        Text(
                            text = modeTitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (peerState.serverPeerError != null) foh.alertAccent else foh.safeAccent,
                        )
                        (peerState.serverPeerError ?: peerState.serverPeerStatus)?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (peerState.serverPeerError != null) foh.alertAccent else colors.onSurfaceVariant,
                            )
                        }
                        if (peerState.serverSsid != null) {
                            Text(
                                text = "SSID: ${peerState.serverSsid}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurface,
                            )
                        }
                        if (peerState.serverPassphrase() != null) {
                            Text(
                                text = "Sandi Wi-Fi: ${peerState.serverPassphrase()}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (peerState.serverPeerMode != RemoteServerPeerMode.NONE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (peerState.serverSsid != null) {
                                    OutlinedButton(onClick = onCopySsid, modifier = Modifier.weight(1f)) {
                                        Text("Salin SSID", maxLines = 1, softWrap = false)
                                    }
                                }
                                if (peerState.serverPassphrase() != null) {
                                    OutlinedButton(onClick = onCopyPassphrase, modifier = Modifier.weight(1f)) {
                                        Text("Salin Sandi", maxLines = 1, softWrap = false)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (peerState.serverPeerMode == RemoteServerPeerMode.LOCAL_HOTSPOT) onStopLocalHotspot()
                                        else onStopWifiDirect()
                                    },
                                    border = BorderStroke(1.dp, foh.alertBorder),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Matikan", color = foh.alertAccent, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            if (snapshot.enabled) {
                Text(
                    text = "Server berjalan bersama layanan foreground pemutaran audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onStop,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, foh.alertBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = foh.alertBg.copy(alpha = 0.65f),
                        contentColor = foh.alertAccent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                ) {
                    Icon(UiIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Hentikan Server")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Alamat IP Jaringan FOH",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (peerState.serverPeerMode == RemoteServerPeerMode.LOCAL_HOTSPOT) onStopLocalHotspot()
                                else onStartLocalHotspot()
                            },
                            enabled = !busy && !peerState.serverPeerBusy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (peerState.serverPeerMode == RemoteServerPeerMode.LOCAL_HOTSPOT) "Stop Hotspot Lokal" else "Hotspot Lokal",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (peerState.serverPeerMode == RemoteServerPeerMode.WIFI_DIRECT_GO) onStopWifiDirect()
                                else onStartWifiDirect()
                            },
                            enabled = !busy && !peerState.serverPeerBusy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (peerState.serverPeerMode == RemoteServerPeerMode.WIFI_DIRECT_GO) "Stop P2P" else "Wi-Fi Direct (P2P)",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (interfaces.isEmpty()) {
                        Text(
                            text = "Tidak ada IP Wi-Fi atau Hotspot lokal ditemukan. Nyalakan Hotspot atau hubungkan ke router Wi-Fi FOH, lalu ketuk Segarkan IP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = foh.alertAccent,
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(interfaces, key = { it.ip }) { candidate ->
                                FohFilterChip(
                                    label = "${candidate.label} • ${candidate.ip}",
                                    selected = candidate.ip == selectedIp,
                                    onClick = { onSelectIp(candidate.ip) },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortChange,
                    label = { Text("Port HTTPS Lokal (1024 - 65535)") },
                    singleLine = true,
                    isError = !validPort,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (!validPort) {
                        { Text("Gunakan angka 1024 sampai 65535 (disarankan 8443)") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = onStart,
                    enabled = selectedIp.isNotBlank() && validPort && !busy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(UiIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nyalakan Server FOH")
                }
            }
        }
    }
}

@Composable
internal fun TransferPolicyCard(
    mediaTransferEnabled: Boolean,
    pauseDuringPlayback: Boolean,
    onSetTransferEnabled: (Boolean) -> Unit,
    onSetPauseDuringPlayback: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Keamanan & Sinkronisasi Media", style = MaterialTheme.typography.titleMedium)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Izinkan Transfer & Sync File", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Default MATI. Memungkinkan perangkat berizin Control mengunduh & mengirim lagu ke perpustakaan perangkat ini (cek hash SHA-256).",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mediaTransferEnabled,
                    onCheckedChange = onSetTransferEnabled,
                )
            }

            HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tahan Transfer Saat Audio Diputar", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Menjaga pemutaran panggung tetap mulus dengan menahan upload/download sementara saat lagu berstatus PLAYING.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = pauseDuringPlayback,
                    enabled = mediaTransferEnabled,
                    onCheckedChange = onSetPauseDuringPlayback,
                )
            }
        }
    }
}

@Composable
internal fun ServerInviteCard(
    pairingUri: String?,
    nativeExpiresAt: Double?,
    webUrl: String?,
    webPin: String?,
    webExpiresAt: Double?,
    fingerprintHex: String?,
    peerState: RemoteWifiPeerState = RemoteWifiPeerState(),
    onCopyUri: () -> Unit,
    onCopySmartUri: (String) -> Unit = {},
    onRefreshInvite: () -> Unit,
    onCopyUrl: () -> Unit,
    onCopyPin: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onRefreshPin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    var inviteTab by rememberSaveable { mutableIntStateOf(0) }
    var showStandardWifiQr by rememberSaveable { mutableStateOf(false) }

    val smartJoinUri = remember(pairingUri, peerState.serverSsid, peerState.serverPassphrase(), peerState.serverPeerMode) {
        val ssid = peerState.serverSsid
        val pass = peerState.serverPassphrase()
        val kind = when (peerState.serverPeerMode) {
            RemoteServerPeerMode.LOCAL_HOTSPOT -> RemotePeerTransportKind.LOCAL_HOTSPOT
            RemoteServerPeerMode.WIFI_DIRECT_GO -> RemotePeerTransportKind.WIFI_DIRECT
            RemoteServerPeerMode.NONE -> null
        }
        if (pairingUri != null && ssid != null && pass != null && kind != null) {
            runCatching { RemoteSmartQrCodec.encodeSmartJoinQr(ssid, pass, kind, pairingUri) }.getOrNull()
        } else null
    }
    val standardWifiQr = remember(peerState.serverSsid, peerState.serverPassphrase()) {
        val ssid = peerState.serverSsid
        val pass = peerState.serverPassphrase()
        if (ssid != null && pass != null) {
            runCatching { RemoteSmartQrCodec.encodeStandardWifiQr(ssid, pass) }.getOrNull()
        } else null
    }
    val hasSmartTab = smartJoinUri != null
    val tabIds = remember(hasSmartTab, webUrl) {
        buildList {
            if (hasSmartTab) add(2) // Smart Join
            add(0) // QR Aplikasi
            if (webUrl != null) add(1) // Browser
        }
    }
    val effectiveTab = if (inviteTab in tabIds) inviteTab else tabIds.first()
    val remainingSec = rememberRemainingSeconds(if (effectiveTab == 1) webExpiresAt else nativeExpiresAt)
    val showStatusPill = ((effectiveTab == 0 || effectiveTab == 2) && pairingUri != null) || (effectiveTab == 1 && webPin != null)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Undang Perangkat FOH",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                RemoteServerStatusPill(
                    text = when {
                        !showStatusPill -> "Terpakai / Kosong"
                        remainingSec > 0 -> if (effectiveTab == 1) "PIN ${remainingSec}s" else "${remainingSec}s"
                        else -> "Kedaluwarsa"
                    },
                    active = showStatusPill && remainingSec > 0,
                    warn = !showStatusPill || remainingSec <= 0,
                )
            }

            if (tabIds.size > 1) {
                FohSegmentedBar(
                    options = tabIds,
                    selected = effectiveTab,
                    label = {
                        when (it) {
                            2 -> "Smart Join"
                            0 -> "QR Aplikasi"
                            else -> "Browser"
                        }
                    },
                    onSelect = { inviteTab = it },
                )
            }

            if (effectiveTab == 2 && smartJoinUri != null) {
                val activePayload = if (showStandardWifiQr && standardWifiQr != null) standardWifiQr else smartJoinUri
                QrCodeBox(
                    payload = activePayload,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Text(
                    text = if (showStandardWifiQr && standardWifiQr != null) {
                        "QR Wi-Fi Standar (SSID: ${peerState.serverSsid}) — pindai dari kamera HP/iPhone atau Android 8–9 untuk masuk ke Wi-Fi ini."
                    } else if (remainingSec > 0) {
                        "1x pindai dari SWR Player HP lain: Android 10+ langsung konek Wi-Fi lokal + pair tanpa ganggu data seluler; Android 8–9 tampilkan panduan Wi-Fi."
                    } else {
                        "Undangan QR kedaluwarsa — ketuk QR Baru"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remainingSec > 0 || showStandardWifiQr) colors.onSurfaceVariant else foh.alertAccent,
                    textAlign = TextAlign.Center,
                )
                if (standardWifiQr != null) {
                    OutlinedButton(
                        onClick = { showStandardWifiQr = !showStandardWifiQr },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showStandardWifiQr) "Kembali ke QR Smart Join" else "Tampilkan QR Wi-Fi Standar")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCopySmartUri(smartJoinUri) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("Salin Smart", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = onRefreshInvite,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("QR Baru", maxLines = 1, softWrap = false)
                    }
                }
            } else if (effectiveTab == 0 && pairingUri != null) {
                QrCodeBox(
                    payload = pairingUri,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )

                Text(
                    text = if (remainingSec > 0) {
                        "Pindai dari tab Remote → Kendalikan di HP Android lain."
                    } else {
                        "Undangan QR kedaluwarsa — ketuk QR Baru"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remainingSec > 0) colors.onSurfaceVariant else foh.alertAccent,
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = foh.surfaceDeep,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = pairingUri,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCopyUri,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("Salin Kode", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = onRefreshInvite,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("QR Baru", maxLines = 1, softWrap = false)
                    }
                }
            } else if (effectiveTab == 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = foh.surfaceDeep,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "QR undangan aplikasi terpakai atau kedaluwarsa",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Setiap QR aplikasi hanya berlaku satu kali pairing. Buat QR baru untuk menautkan perangkat SWR Player Android lain.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onRefreshInvite) {
                            Text("Buat QR Undangan Baru")
                        }
                    }
                }
            } else if (webUrl != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QrCodeBox(
                        payload = webUrl,
                        modifier = Modifier
                            .size(168.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Text(
                        text = "Pindai QR untuk membuka URL browser lokal di perangkat FOH lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = foh.surfaceDeep,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = webUrl,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = foh.safeAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = foh.surfaceDeep,
                        border = BorderStroke(1.dp, foh.borderSubtle),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(
                                text = if (remainingSec > 0) "PIN WEB (${remainingSec}s)" else "PIN KEDALUWARSA",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (remainingSec > 0) colors.onSurfaceVariant else foh.alertAccent,
                            )
                            Text(
                                text = webPin ?: "--------",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRefreshPin,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 46.dp),
                    ) {
                        Text("PIN Baru")
                    }
                }

                Text(
                    text = "Buka URL di browser Laptop/iPhone FOH, lanjutkan peringatan sertifikat lokal sekali, lalu masukkan PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (fingerprintHex != null) {
                    Text(
                        text = "SHA-256 TLS: $fingerprintHex",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCopyUrl,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("Salin URL", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onCopyPin,
                        enabled = webPin != null,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("Salin PIN", maxLines = 1, softWrap = false)
                    }
                    if (fingerprintHex != null) {
                        OutlinedButton(
                            onClick = onCopyFingerprint,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        ) {
                            Text("Salin TLS", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PendingDeviceCard(
    device: RemoteConnectedDeviceView,
    transferToggleEnabled: Boolean,
    onApproveControl: (allowTransfer: Boolean) -> Unit,
    onApproveViewOnly: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    var grantTransfer by remember(device.deviceId, transferToggleEnabled) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.warnBg,
        border = BorderStroke(1.dp, foh.warnBorder),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (device.kind == RemoteClientKind.WEB) "Browser Web • ${device.deviceId.take(8)}"
                        else "Aplikasi SWR Player • ${device.deviceId.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                RemoteServerStatusPill(text = device.kind.name, active = false, warn = true)
            }

            if (transferToggleEnabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Sekalian izinkan download & sync lagu", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = grantTransfer, onCheckedChange = { grantTransfer = it })
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApproveControl(transferToggleEnabled && grantTransfer) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    Text("Control", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onApproveViewOnly,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    Text("View-Only", maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("Tolak", color = foh.alertAccent)
                }
            }
        }
    }
}

@Composable
internal fun ConnectedDeviceCard(
    device: RemoteConnectedDeviceView,
    globalTransferEnabled: Boolean,
    onToggleTransfer: (Boolean) -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, foh.borderSubtle),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.label, style = MaterialTheme.typography.titleSmall)
                    val roleLabel = if (device.role == "control") "Kendali Penuh (Control)" else "Pantau Saja (View-Only)"
                    val kindLabel = if (device.kind == RemoteClientKind.WEB) "Browser Web" else "Aplikasi SWR Player"
                    Text(
                        text = "$kindLabel • $roleLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRevoke) {
                    Text("Putuskan", color = foh.alertAccent)
                }
            }

            if (globalTransferEnabled && device.role == "control") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Izinkan download & sync media",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Switch(
                        checked = device.transferAllowed,
                        onCheckedChange = onToggleTransfer,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteServerStatusPill(text: String, active: Boolean, warn: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    val container = when {
        warn -> foh.warnBg
        active -> foh.safeBg
        else -> foh.surfaceDeep
    }
    val border = when {
        warn -> foh.warnBorder
        active -> foh.safeBorder
        else -> foh.borderSubtle
    }
    val content = when {
        warn -> foh.warnAccent
        active -> foh.safeAccent
        else -> colors.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun rememberRemainingSeconds(expiresAt: Double?): Int {
    var nowSec by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(expiresAt) {
        while (expiresAt != null) {
            nowSec = System.currentTimeMillis() / 1000.0
            delay(1000L)
        }
    }
    if (expiresAt == null) return 0
    return ceil(expiresAt - nowSec).toInt().coerceAtLeast(0)
}
