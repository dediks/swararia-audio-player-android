package com.swararia.audiolab.remote

import android.Manifest
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohHeaderChipButton
import com.swararia.audiolab.FohIndeterminateBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.RemoteServerUiActions
import com.swararia.audiolab.UiIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

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
private fun rememberRemoteNetworkViewModel(): RemoteNetworkViewModel {
    val app = LocalContext.current.applicationContext
    val factory = remember(app) { RemoteNetworkViewModelFactory.fromContext(app) }
    return viewModel(factory = factory)
}

@Composable
internal fun RemoteTabScreen(
    desktopRemote: DesktopRemoteSession,
    serverState: RemoteServerUiState,
    serverActions: RemoteServerUiActions,
    modifier: Modifier = Modifier,
    localAudioBanner: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
    networkViewModel: RemoteNetworkViewModel = rememberRemoteNetworkViewModel(),
) {
    var selectedRole by rememberSaveable { mutableStateOf("") }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }
    var qrScanRequestTick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val remoteState by desktopRemote.state.collectAsStateWithLifecycle()
    val peerState by networkViewModel.peerState.collectAsStateWithLifecycle()
    val wifiStatus by networkViewModel.wifiStatus.collectAsStateWithLifecycle()
    val mdnsServers by networkViewModel.mdnsServers.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effectiveRole = when {
        selectedRole.isNotEmpty() -> selectedRole
        serverState.snapshot.enabled -> "server"
        remoteState.phase == "active" -> "controller"
        else -> ""
    }
    val serverMode = effectiveRole == "server"

    var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val action = pendingWifiAction
        pendingWifiAction = null
        val granted = results.values.all { it } || networkViewModel.hasWifiPeerPermissions()
        if (granted) {
            permissionNotice = null
            action?.invoke()
        } else {
            permissionNotice = "Izin Perangkat Terdekat / Lokasi diperlukan untuk Hotspot Lokal atau Wi-Fi Direct."
        }
    }

    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val action = pendingStorageAction
        pendingStorageAction = null
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || results.values.all { it } || (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            )
        if (granted) {
            permissionNotice = null
            action?.invoke()
        } else {
            permissionNotice = "Izin penyimpanan diperlukan di Android 8–9 untuk menyimpan lagu ke Music/SwarariaAudioLab."
        }
    }

    fun runWithWifiPermission(action: () -> Unit) {
        if (networkViewModel.hasWifiPeerPermissions()) {
            permissionNotice = null
            action()
        } else {
            pendingWifiAction = action
            wifiPermissionLauncher.launch(networkViewModel.requiredWifiPeerPermissions())
        }
    }

    fun runWithLegacyStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
            return
        }
        val readOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val writeOk = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (readOk && writeOk) {
            permissionNotice = null
            action()
        } else {
            pendingStorageAction = action
            legacyStorageLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    DisposableEffect(lifecycle, effectiveRole, networkViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                networkViewModel.onHostResume(effectiveRole, serverActions.onRefreshInterfaces)
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                networkViewModel.onHostPauseOrStop()
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            networkViewModel.syncControllerDiscovery(effectiveRole == "controller")
        }
        onDispose {
            networkViewModel.onHostPauseOrStop()
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(serverMode) {
        if (serverMode) {
            serverActions.onRefreshInterfaces()
        }
    }

    LaunchedEffect(effectiveRole, mdnsServers, remoteState.phase, remoteState.fresh, remoteState.endpoint) {
        if (effectiveRole == "controller" && remoteState.phase == "active" && !remoteState.fresh) {
            val ep = remoteState.endpoint ?: return@LaunchedEffect
            if (mdnsServers.any { "${it.hostIp}:${it.port}" == ep }) {
                desktopRemote.refresh()
            }
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
        permissionNotice?.let { notice ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = FohTheme.colors.alertBg,
                border = BorderStroke(1.dp, FohTheme.colors.alertBorder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = FohTheme.colors.alertAccent,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { permissionNotice = null }) {
                        Text("Tutup", color = FohTheme.colors.alertAccent)
                    }
                }
            }
        }
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
                peerState = peerState,
                onStartLocalHotspot = {
                    runWithWifiPermission { networkViewModel.startLocalOnlyHotspot() }
                },
                onStopLocalHotspot = networkViewModel::stopLocalOnlyHotspot,
                onStartWifiDirect = {
                    runWithWifiPermission { networkViewModel.startWifiDirectGroup() }
                },
                onStopWifiDirect = networkViewModel::stopWifiDirectGroup,
                onRequireLegacyStoragePermission = ::runWithLegacyStorage,
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
                peerState = peerState,
                wifiStatus = wifiStatus,
                mdnsServers = mdnsServers,
                onHandleQrInput = networkViewModel::handleClientQrInput,
                onConfirmLegacyWifiJoin = networkViewModel::confirmLegacyWifiJoin,
                onCancelLegacyWifiJoin = networkViewModel::clearPendingLegacyWifiJoin,
                onReleasePeerNetwork = networkViewModel::releaseClientPeerWifiNetwork,
                onDiscoverP2p = {
                    runWithWifiPermission { networkViewModel.discoverWifiDirectPeers() }
                },
                onConnectP2p = { addr ->
                    runWithWifiPermission { networkViewModel.connectWifiDirectPeer(addr) }
                },
                onDisconnectP2p = networkViewModel::disconnectWifiDirectClient,
                onRequireLegacyStoragePermission = ::runWithLegacyStorage,
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
    peerState: RemoteWifiPeerState = RemoteWifiPeerState(),
    onStartLocalHotspot: () -> Unit = {},
    onStopLocalHotspot: () -> Unit = {},
    onStartWifiDirect: () -> Unit = {},
    onStopWifiDirect: () -> Unit = {},
    onRequireLegacyStoragePermission: (() -> Unit) -> Unit = { it() },
) {
    val snapshot = state.snapshot
    val colors = MaterialTheme.colorScheme
    val foh = FohTheme.colors
    val context = LocalContext.current

    var selectedIp by rememberSaveable { mutableStateOf("") }
    var portInput by rememberSaveable { mutableStateOf("8443") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.interfaces, snapshot.enabled, snapshot.bindIp, peerState.serverPeerMode) {
        val activeBindIp = snapshot.bindIp
        if (snapshot.enabled && !activeBindIp.isNullOrBlank()) {
            selectedIp = activeBindIp
        } else {
            val preferred = state.interfaces.firstOrNull { it.ip != "127.0.0.1" } ?: state.interfaces.firstOrNull()
            val peerModePreferred = when (peerState.serverPeerMode) {
                RemoteServerPeerMode.LOCAL_HOTSPOT -> state.interfaces.firstOrNull { it.priority == 0 }
                RemoteServerPeerMode.WIFI_DIRECT_GO -> state.interfaces.firstOrNull { it.priority == 1 }
                RemoteServerPeerMode.NONE -> null
            }
            if (peerModePreferred != null) {
                selectedIp = peerModePreferred.ip
            } else if (selectedIp.isBlank() ||
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
                peerState = peerState,
                onSelectIp = { selectedIp = it },
                onPortChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                onStart = {
                    val port = parsedPort ?: return@ServerNetworkCard
                    if (selectedIp.isNotBlank()) actions.onStartServer(selectedIp, port)
                },
                onStop = actions.onStopServer,
                onStartLocalHotspot = onStartLocalHotspot,
                onStopLocalHotspot = onStopLocalHotspot,
                onStartWifiDirect = onStartWifiDirect,
                onStopWifiDirect = onStopWifiDirect,
                onCopySsid = { peerState.serverSsid?.let { copyText("SSID Wi-Fi", it) } },
                onCopyPassphrase = { peerState.serverPassphrase()?.let { copyText("Password Wi-Fi", it) } },
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
                    peerState = peerState,
                    onCopyUri = { snapshot.nativePairingUri?.let { copyText("Kode pairing", it) } },
                    onCopySmartUri = { smart -> copyText("Kode Smart Join", smart) },
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
                onSetTransferEnabled = { enable ->
                    if (enable) {
                        onRequireLegacyStoragePermission { actions.onSetMediaTransferEnabled(true) }
                    } else {
                        actions.onSetMediaTransferEnabled(false)
                    }
                },
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
internal fun QrCodeBox(payload: String, modifier: Modifier = Modifier) {
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
