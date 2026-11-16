package com.swararia.audiolab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlin.math.ceil

internal fun copyPlainTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

internal fun readPlainTextFromClipboard(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

@Composable
internal fun RemoteTabScreen(
    desktopRemote: DesktopRemoteSession,
    serverState: RemoteServerUiState,
    serverActions: RemoteServerUiActions,
    modifier: Modifier = Modifier,
    localAudioBanner: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    var selectedRole by rememberSaveable { mutableStateOf("") }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }
    var qrScanRequestTick by remember { mutableIntStateOf(0) }
    val remoteState by desktopRemote.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effectiveRole = when {
        selectedRole.isNotEmpty() -> selectedRole
        serverState.snapshot.enabled -> "server"
        remoteState.phase == "active" -> "controller"
        else -> ""
    }
    val serverMode = effectiveRole == "server"

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RemoteHeader(
                effectiveRole = effectiveRole,
                serverMode = serverMode,
                serverState = serverState,
                remoteState = remoteState,
                onBack = {
                    if (selectedRole.isNotEmpty() && !serverState.snapshot.enabled && remoteState.phase != "active") {
                        selectedRole = ""
                    } else {
                        onBack?.invoke()
                    }
                },
                onSwitchRole = { selectedRole = "" },
                onTriggerScan = { qrScanRequestTick++ },
                onRefreshRemote = { desktopRemote.refresh() },
                onRefreshInterfaces = serverActions.onRefreshInterfaces,
                onOpenGuide = { showGuideDialog = true },
            )
        }
        localAudioBanner()
        if (effectiveRole.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = FohTheme.colors.surfaceCard,
                    border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RemoteRoleOptionRow(
                            icon = UiIcons.QrScan,
                            title = "HP ini jadi Remote",
                            subtitle = "Kendalikan pemutar lain via QR",
                            accentTint = FohTheme.colors.safeAccent,
                            chevronTint = FohTheme.colors.safeAccent,
                            onClick = { selectedRole = "controller" },
                        )
                        HorizontalDivider(color = FohTheme.colors.borderSubtle)
                        RemoteRoleOptionRow(
                            icon = UiIcons.Play,
                            title = "HP ini jadi Pemutar (Server)",
                            subtitle = "Putar audio & tampilkan QR kontrol",
                            accentTint = FohTheme.colors.safeAccent,
                            chevronTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { selectedRole = "server" },
                        )
                    }
                }
            }
        } else if (serverMode) {
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
                qrScanRequestTick = qrScanRequestTick,
            )
        }
    }

    if (showGuideDialog) {
        RemoteGuideDialog(onDismiss = { showGuideDialog = false })
    }
}

@Composable
private fun RemoteHeader(
    effectiveRole: String,
    serverMode: Boolean,
    serverState: RemoteServerUiState,
    remoteState: DesktopRemoteUiState,
    onBack: (() -> Unit)? = null,
    onSwitchRole: () -> Unit = {},
    onTriggerScan: () -> Unit,
    onRefreshRemote: () -> Unit,
    onRefreshInterfaces: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val activeBadge = remember(remoteState) { resolveConnectionBadge(remoteState) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            CompactCircleButton(
                icon = UiIcons.Back,
                description = "Kembali",
                onClick = onBack,
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Text(
            text = when (effectiveRole) {
                "controller" -> "HP ini: Remote"
                "server" -> "HP ini: Pemutar"
                else -> "Remote Multi-Device"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (effectiveRole.isNotEmpty()) {
            FohHeaderChipButton(
                label = "Ubah Peran",
                icon = UiIcons.Refresh,
                active = false,
                onClick = onSwitchRole,
            )
        }
        if (effectiveRole.isEmpty()) {
            // Only show info button when on role picker
        } else if (serverMode) {
            if (serverState.snapshot.enabled) {
                FohHeaderChipButton(
                    label = "Server On",
                    icon = UiIcons.Check,
                    active = true,
                    onClick = onOpenGuide,
                )
            } else {
                FohHeaderChipButton(
                    label = "Segarkan IP",
                    icon = UiIcons.Refresh,
                    active = false,
                    enabled = !serverState.busy,
                    onClick = onRefreshInterfaces,
                )
            }
        } else {
            when (remoteState.phase) {
                "disconnected" -> {
                    FohHeaderChipButton(
                        label = "Scan QR",
                        icon = UiIcons.QrScan,
                        active = true,
                        enabled = !remoteState.busy,
                        onClick = onTriggerScan,
                    )
                }
                "active" -> {
                    FohHeaderChipButton(
                        label = activeBadge?.label ?: "FOH Ready",
                        icon = if (remoteState.playback?.online == true) UiIcons.Check else UiIcons.Info,
                        active = activeBadge?.highlighted == true,
                        onClick = onOpenGuide,
                    )
                    CompactCircleButton(
                        icon = UiIcons.Refresh,
                        description = "Segarkan status target",
                        onClick = onRefreshRemote,
                        enabled = !remoteState.busy,
                        size = 36.dp,
                        iconSize = 18.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                else -> {
                    FohHeaderChipButton(
                        label = "Cek Status",
                        icon = UiIcons.Refresh,
                        active = false,
                        enabled = !remoteState.busy,
                        onClick = onRefreshRemote,
                    )
                }
            }
        }
        CompactCircleButton(
            icon = UiIcons.Info,
            description = "Panduan Remote FOH",
            onClick = onOpenGuide,
            size = 36.dp,
            iconSize = 18.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RemoteGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Panduan Remote FOH") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Remote mendukung kendali lintas perangkat pada jaringan Wi-Fi atau Hotspot lokal yang sama (HP ↔ HP, HP ↔ Laptop/PC, atau Browser Web ↔ HP).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• Kendalikan: Pindai QR atau tempel kode swararia1|... untuk memutar, menyiapkan lagu (cue), dan mengunduh file dari perangkat target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "• Mode Server: Jadikan perangkat ini pemutar panggung agar bisa dikendalikan dari aplikasi SWR Player lain (QR) atau browser HP/Laptop/iPhone (URL HTTPS + PIN).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
internal fun RemoteServerScreen(
    state: RemoteServerUiState,
    actions: RemoteServerUiActions,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    val context = LocalContext.current

    var selectedIp by rememberSaveable { mutableStateOf("") }
    var portInput by rememberSaveable { mutableStateOf("8443") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.interfaces, snapshot.enabled, snapshot.bindIp) {
        val activeBindIp = snapshot.bindIp
        if (snapshot.enabled && !activeBindIp.isNullOrBlank()) {
            selectedIp = activeBindIp
        } else {
            val preferred = state.interfaces.firstOrNull { it.ip != "127.0.0.1" } ?: state.interfaces.firstOrNull()
            if (selectedIp.isBlank() ||
                state.interfaces.none { it.ip == selectedIp } ||
                (selectedIp == "127.0.0.1" && preferred != null && preferred.ip != "127.0.0.1")
            ) {
                selectedIp = preferred?.ip.orEmpty()
            }
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
        copyPlainTextToClipboard(context, label, value)
        copiedNotice = "$label disalin ke clipboard"
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (copiedNotice != null) {
            item(key = "copied") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = foh.safeBg,
                    border = BorderStroke(1.dp, foh.safeBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(UiIcons.Check, null, tint = foh.safeAccent, modifier = Modifier.size(16.dp))
                        Text(
                            text = copiedNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = foh.safeAccent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        state.serviceError?.let { err ->
            item(key = "error") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = foh.alertBg,
                    border = BorderStroke(1.dp, foh.alertBorder),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = foh.alertAccent,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = actions.onClearError) {
                            Text("Tutup", color = foh.alertAccent)
                        }
                    }
                }
            }
        }

        if (state.activeTransfers > 0) {
            item(key = "active-transfers") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = foh.btBg,
                    border = BorderStroke(1.dp, foh.btBorder),
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
                            color = foh.btAccent,
                        )
                        state.transferStatus?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        FohIndeterminateBar(height = 4.dp)
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
                onStart = {
                    val port = parsedPort ?: return@ServerNetworkCard
                    if (selectedIp.isNotBlank()) actions.onStartServer(selectedIp, port)
                },
                onStop = actions.onStopServer,
            )
        }

        if (pendingDevices.isNotEmpty()) {
            item(key = "pending-header") {
                Text(
                    text = "Menunggu Persetujuan (${pendingDevices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = foh.warnAccent,
                    modifier = Modifier.padding(top = 2.dp),
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

        if (snapshot.enabled && (snapshot.nativePairingUri != null || snapshot.webUrl != null)) {
            item(key = "server-invite") {
                ServerInviteCard(
                    pairingUri = snapshot.nativePairingUri,
                    nativeExpiresAt = snapshot.nativeInviteExpiresAt,
                    webUrl = snapshot.webUrl,
                    webPin = snapshot.webPin,
                    webExpiresAt = snapshot.webPinExpiresAt,
                    fingerprintHex = snapshot.fingerprintHex,
                    onCopyUri = { snapshot.nativePairingUri?.let { copyText("Kode pairing", it) } },
                    onRefreshInvite = actions.onRefreshNativeInvite,
                    onCopyUrl = { snapshot.webUrl?.let { copyText("URL Web", it) } },
                    onCopyPin = { snapshot.webPin?.let { copyText("PIN Web", it) } },
                    onCopyFingerprint = {
                        snapshot.fingerprintHex?.let { copyText("Fingerprint TLS", it) }
                    },
                    onRefreshPin = actions.onRefreshWebPin,
                )
            }
        }

        item(key = "transfer-policy") {
            TransferPolicyCard(
                mediaTransferEnabled = snapshot.mediaTransferEnabled,
                pauseDuringPlayback = snapshot.pauseTransferDuringPlayback,
                onSetTransferEnabled = actions.onSetMediaTransferEnabled,
                onSetPauseDuringPlayback = actions.onSetPauseTransferDuringPlayback,
            )
        }

        item(key = "approved-header") {
            Text(
                text = "Perangkat Terhubung (${approvedDevices.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (approvedDevices.isEmpty()) {
            item(key = "approved-empty") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = foh.surfaceDeep,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                ) {
                    Text(
                        text = if (snapshot.enabled) {
                            "Belum ada perangkat terhubung. Pindai QR dari menu Remote di perangkat SWR Player lain atau buka URL HTTPS di browser FOH."
                        } else {
                            "Nyalakan Server FOH agar HP lain, Laptop/PC, atau browser dapat terhubung."
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
    onStart: () -> Unit,
    onStop: () -> Unit,
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
                StatusPill(
                    text = if (snapshot.enabled) "● AKTIF" else "NONAKTIF",
                    active = snapshot.enabled,
                )
            }

            if (busy) {
                FohIndeterminateBar(height = 4.dp)
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
private fun TransferPolicyCard(
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
private fun ServerInviteCard(
    pairingUri: String?,
    nativeExpiresAt: Double?,
    webUrl: String?,
    webPin: String?,
    webExpiresAt: Double?,
    fingerprintHex: String?,
    onCopyUri: () -> Unit,
    onRefreshInvite: () -> Unit,
    onCopyUrl: () -> Unit,
    onCopyPin: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onRefreshPin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    var inviteTab by rememberSaveable { mutableIntStateOf(0) }
    val showBothTabs = webUrl != null
    val effectiveTab = if (showBothTabs) inviteTab else 0
    val remainingSec = rememberRemainingSeconds(if (effectiveTab == 0) nativeExpiresAt else webExpiresAt)
    val showStatusPill = (effectiveTab == 0 && pairingUri != null) || (effectiveTab == 1 && webPin != null)

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
                StatusPill(
                    text = when {
                        !showStatusPill -> "Terpakai / Kosong"
                        remainingSec > 0 -> if (effectiveTab == 0) "${remainingSec}s" else "PIN ${remainingSec}s"
                        else -> "Kedaluwarsa"
                    },
                    active = showStatusPill && remainingSec > 0,
                    warn = !showStatusPill || remainingSec <= 0,
                )
            }

            if (showBothTabs) {
                FohSegmentedBar(
                    options = listOf(0, 1),
                    selected = inviteTab,
                    label = { if (it == 0) "QR Aplikasi" else "Browser (URL & PIN)" },
                    onSelect = { inviteTab = it },
                )
            }

            if (effectiveTab == 0 && pairingUri != null) {
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
private fun PendingDeviceCard(
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
                StatusPill(text = device.kind.name, active = false, warn = true)
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
private fun ConnectedDeviceCard(
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
private fun StatusPill(text: String, active: Boolean, warn: Boolean = false) {
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

@Composable
private fun RemoteRoleOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentTint: Color,
    chevronTint: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = FohTheme.colors.safeBg,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentTint,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(UiIcons.ChevronRight, contentDescription = null, tint = chevronTint)
        }
    }
}
