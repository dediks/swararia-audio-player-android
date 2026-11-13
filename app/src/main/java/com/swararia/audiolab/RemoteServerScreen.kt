package com.swararia.audiolab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
internal fun RemoteTabScreen(
    desktopRemote: DesktopRemoteSession,
    serverState: RemoteServerUiState,
    serverActions: RemoteServerUiActions,
    modifier: Modifier = Modifier,
) {
    var serverMode by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, serverMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && serverMode) {
                serverActions.onRefreshInterfaces()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(serverMode) {
        if (serverMode) {
            serverActions.onRefreshInterfaces()
        }
    }

    Column(modifier) {
        RemoteModeSwitcher(
            serverMode = serverMode,
            serverActive = serverState.snapshot.enabled,
            onSelectMode = { serverMode = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (serverMode) {
            RemoteServerScreen(
                state = serverState,
                actions = serverActions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            DesktopRemoteScreen(
                session = desktopRemote,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RemoteModeSwitcher(
    serverMode: Boolean,
    serverActive: Boolean,
    onSelectMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeChip(
                label = "Kendalikan PC",
                selected = !serverMode,
                onClick = { onSelectMode(false) },
                modifier = Modifier.weight(1f),
            )
            ModeChip(
                label = if (serverActive) "Server HP • Aktif" else "Jadikan HP Ini Server",
                selected = serverMode,
                onClick = { onSelectMode(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primary else Color.Transparent,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun RemoteServerScreen(
    state: RemoteServerUiState,
    actions: RemoteServerUiActions,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    var selectedIp by rememberSaveable { mutableStateOf("") }
    var portInput by rememberSaveable { mutableStateOf("8443") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.interfaces) {
        if (selectedIp.isBlank() || state.interfaces.none { it.ip == selectedIp }) {
            selectedIp = state.interfaces.firstOrNull()?.ip.orEmpty()
        }
    }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice != null) {
            delay(2200L)
            copiedNotice = null
        }
    }

    val parsedPort = portInput.toIntOrNull()
    val validPort = parsedPort != null && parsedPort in 1024..65535
    val pendingDevices = remember(snapshot.devices) { snapshot.devices.filter { it.status == "pending" } }
    val approvedDevices = remember(snapshot.devices) { snapshot.devices.filter { it.status == "active" } }

    fun copyText(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        copiedNotice = "$label disalin"
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Server FOH (HP)", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (snapshot.enabled) {
                            "Aktif di ${snapshot.bindIp}:${snapshot.port}"
                        } else {
                            "HP ini siap menjadi pemutar panggung yang dikendalikan HP/Laptop FOH"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (snapshot.enabled) "AKTIF" else "NONAKTIF",
                    active = snapshot.enabled,
                )
            }
        }

        if (copiedNotice != null) {
            item(key = "copied") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primaryContainer,
                ) {
                    Text(
                        text = copiedNotice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        state.serviceError?.let { err ->
            item(key = "error") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.errorContainer,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = actions.onClearError) {
                            Text("Tutup")
                        }
                    }
                }
            }
        }

        if (state.activeTransfers > 0) {
            item(key = "active-transfers") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.secondaryContainer,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Transfer Media Berjalan (${state.activeTransfers})",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSecondaryContainer,
                        )
                        state.transferStatus?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSecondaryContainer,
                            )
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item(key = "server-control") {
            ServerNetworkCard(
                snapshot = snapshot,
                interfaces = state.interfaces,
                selectedIp = selectedIp,
                portInput = portInput,
                validPort = validPort,
                busy = state.busy,
                onSelectIp = { selectedIp = it },
                onPortChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                onRefreshInterfaces = actions.onRefreshInterfaces,
                onStart = {
                    val port = parsedPort ?: return@ServerNetworkCard
                    if (selectedIp.isNotBlank()) actions.onStartServer(selectedIp, port)
                },
                onStop = actions.onStopServer,
            )
        }

        item(key = "transfer-policy") {
            TransferPolicyCard(
                mediaTransferEnabled = snapshot.mediaTransferEnabled,
                pauseDuringPlayback = snapshot.pauseTransferDuringPlayback,
                onSetTransferEnabled = actions.onSetMediaTransferEnabled,
                onSetPauseDuringPlayback = actions.onSetPauseTransferDuringPlayback,
            )
        }

        if (snapshot.enabled && snapshot.nativePairingUri != null) {
            item(key = "native-qr") {
                NativePairingCard(
                    pairingUri = snapshot.nativePairingUri,
                    expiresAt = snapshot.nativeInviteExpiresAt,
                    onCopyUri = { copyText("URI Pairing", snapshot.nativePairingUri) },
                    onRefreshInvite = actions.onRefreshNativeInvite,
                )
            }
        }

        if (snapshot.enabled && snapshot.webUrl != null) {
            item(key = "web-pin") {
                WebAccessCard(
                    webUrl = snapshot.webUrl,
                    webPin = snapshot.webPin,
                    expiresAt = snapshot.webPinExpiresAt,
                    fingerprintHex = snapshot.fingerprintHex,
                    onCopyUrl = { copyText("URL Web", snapshot.webUrl) },
                    onCopyPin = { snapshot.webPin?.let { copyText("PIN Web", it) } },
                    onCopyFingerprint = {
                        snapshot.fingerprintHex?.let { copyText("Fingerprint TLS", it) }
                    },
                    onRefreshPin = actions.onRefreshWebPin,
                )
            }
        }

        if (pendingDevices.isNotEmpty()) {
            item(key = "pending-header") {
                Text(
                    text = "Menunggu Persetujuan (${pendingDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(pendingDevices, key = { "pending-${it.deviceId}" }) { device ->
                PendingDeviceCard(
                    device = device,
                    transferToggleEnabled = snapshot.mediaTransferEnabled,
                    onApproveControl = { allowTransfer ->
                        actions.onApproveDevice(device.deviceId, "control", allowTransfer)
                    },
                    onApproveViewOnly = {
                        actions.onApproveDevice(device.deviceId, "view_only", false)
                    },
                    onDeny = { actions.onDenyOrRevokeDevice(device.deviceId, true) },
                )
            }
        }

        item(key = "approved-header") {
            Text(
                text = "Perangkat Terhubung (${approvedDevices.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (approvedDevices.isEmpty()) {
            item(key = "approved-empty") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = if (snapshot.enabled) {
                            "Belum ada remote yang terhubung. Scan QR dari Swararia Android lain atau buka URL HTTPS di browser FOH."
                        } else {
                            "Nyalakan Server HP untuk menerima koneksi remote."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        } else {
            items(approvedDevices, key = { "approved-${it.deviceId}" }) { device ->
                ConnectedDeviceCard(
                    device = device,
                    globalTransferEnabled = snapshot.mediaTransferEnabled,
                    onToggleTransfer = { allow ->
                        actions.onSetDeviceTransferAllowed(device.deviceId, allow)
                    },
                    onRevoke = { actions.onDenyOrRevokeDevice(device.deviceId, false) },
                )
            }
        }

        item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ServerNetworkCard(
    snapshot: RemoteEngineUiSnapshot,
    interfaces: List<RemoteInterfaceCandidate>,
    selectedIp: String,
    portInput: String,
    validPort: Boolean,
    busy: Boolean,
    onSelectIp: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRefreshInterfaces: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Jaringan & Endpoint", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!snapshot.enabled) {
                    TextButton(onClick = onRefreshInterfaces, enabled = !busy) {
                        Text("Segarkan IP")
                    }
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (snapshot.enabled) {
                Text(
                    text = "Endpoint HTTPS aktif di ${snapshot.webUrl ?: "https://${snapshot.bindIp}:${snapshot.port}/"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Server berjalan bersama layanan foreground pemutaran audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onStop,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Hentikan Server")
                }
            } else {
                if (interfaces.isEmpty()) {
                    Text(
                        text = "Tidak ada alamat IP Wi-Fi atau Hotspot lokal ditemukan. Nyalakan Hotspot HP atau hubungkan ke router Wi-Fi FOH, lalu ketuk Segarkan IP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(interfaces, key = { it.ip }) { candidate ->
                            FilterChip(
                                selected = candidate.ip == selectedIp,
                                onClick = { onSelectIp(candidate.ip) },
                                label = {
                                    Text(
                                        "${candidate.label} • ${candidate.ip}",
                                        maxLines = 1,
                                    )
                                },
                            )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Nyalakan Server HP")
                }
            }
        }
    }
}

@Composable
private fun TransferPolicyCard(
    mediaTransferEnabled: Boolean,
    pauseDuringPlayback: Boolean,
    onSetTransferEnabled: (Boolean) -> Unit,
    onSetPauseDuringPlayback: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Keamanan & Sinkronisasi Lagu", style = MaterialTheme.typography.titleMedium)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Izinkan Transfer & Sync File", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Default MATI. Memungkinkan perangkat berizin Control mengunduh & mengunggah lagu ke perpustakaan HP ini (cek duplikat SHA-256).",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mediaTransferEnabled,
                    onCheckedChange = onSetTransferEnabled,
                )
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tahan Transfer Saat Audio Diputar", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Menjaga pemutaran panggung tetap mulus dengan memblokir upload/download sementara saat lagu berstatus PLAYING.",
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
private fun NativePairingCard(
    pairingUri: String,
    expiresAt: Double?,
    onCopyUri: () -> Unit,
    onRefreshInvite: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val remainingSec = rememberRemainingSeconds(expiresAt)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pairing Swararia Android (QR)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (remainingSec > 0) {
                            "Berlaku ${remainingSec}s • Pindai di tab Remote → Kendalikan PC"
                        } else {
                            "Undangan QR kedaluwarsa — ketuk QR Baru"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remainingSec > 0) colors.onSurfaceVariant else colors.error,
                    )
                }
            }

            QrCodeBox(
                payload = pairingUri,
                modifier = Modifier
                    .size(210.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )

            Text(
                text = pairingUri,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCopyUri,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Salin URI")
                }
                Button(
                    onClick = onRefreshInvite,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("QR Baru")
                }
            }
        }
    }
}

@Composable
private fun WebAccessCard(
    webUrl: String,
    webPin: String?,
    expiresAt: Double?,
    fingerprintHex: String?,
    onCopyUrl: () -> Unit,
    onCopyPin: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onRefreshPin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val remainingSec = rememberRemainingSeconds(expiresAt)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Akses Browser HP / iPhone / Laptop", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Buka URL di bawah pada browser FOH, lanjutkan peringatan sertifikat lokal sekali, lalu masukkan PIN 8 karakter.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            Text(
                text = webUrl,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.primary,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = if (remainingSec > 0) "PIN WEB (${remainingSec}s)" else "PIN KEDALUWARSA",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSec > 0) colors.onSurfaceVariant else colors.error,
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
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("PIN Baru")
                }
            }

            if (fingerprintHex != null) {
                Text(
                    text = "SHA-256 TLS: $fingerprintHex",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCopyUrl,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Salin URL")
                }
                OutlinedButton(
                    onClick = onCopyPin,
                    enabled = webPin != null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Salin PIN")
                }
                if (fingerprintHex != null) {
                    OutlinedButton(
                        onClick = onCopyFingerprint,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("FP")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingDeviceCard(
    device: RemoteConnectedDeviceView,
    transferToggleEnabled: Boolean,
    onApproveControl: (allowTransfer: Boolean) -> Unit,
    onApproveViewOnly: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var grantTransfer by remember(device.deviceId, transferToggleEnabled) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (device.kind == RemoteClientKind.WEB) "Browser Web • ${device.deviceId.take(8)}"
                        else "Aplikasi Swararia • ${device.deviceId.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                StatusPill(text = device.kind.name, active = true)
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
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Control")
                }
                OutlinedButton(
                    onClick = onApproveViewOnly,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("View-Only")
                }
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Tolak", color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(
    device: RemoteConnectedDeviceView,
    globalTransferEnabled: Boolean,
    onToggleTransfer: (Boolean) -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceVariant.copy(alpha = 0.65f),
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
                    val kindLabel = if (device.kind == RemoteClientKind.WEB) "Browser Web" else "Aplikasi Android"
                    Text(
                        text = "$kindLabel • $roleLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRevoke) {
                    Text("Putuskan", color = colors.error)
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
private fun StatusPill(text: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) colors.primaryContainer else colors.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun QrCodeBox(payload: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(payload) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }
    Box(
        modifier = modifier.background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val cols = matrix.width.coerceAtLeast(1)
                val rows = matrix.height.coerceAtLeast(1)
                val cellW = size.width / cols
                val cellH = size.height / rows
                for (y in 0 until rows) {
                    for (x in 0 until cols) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f),
                            )
                        }
                    }
                }
            }
        } else {
            Text("QR Error", color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
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
