package com.swararia.audiolab.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RemoteServerPeerMode {
    NONE,
    LOCAL_HOTSPOT,
    WIFI_DIRECT_GO,
}

data class RemoteP2pPeerView(
    val deviceName: String,
    val deviceAddress: String,
    val statusLabel: String,
    val isGroupOwner: Boolean,
)

data class LegacyWifiJoinRequest(
    val ssid: String,
    private val passphraseValue: String,
    val rawPairing: String,
) {
    fun passphrase(): String = passphraseValue
    override fun toString(): String = "LegacyWifiJoinRequest(ssid=$ssid, passphrase=[redacted])"
}

data class RemoteWifiPeerState(
    val serverPeerMode: RemoteServerPeerMode = RemoteServerPeerMode.NONE,
    val serverPeerBusy: Boolean = false,
    val serverSsid: String? = null,
    private val serverPassphraseValue: String? = null,
    val serverPeerInterface: String? = null,
    val serverPeerStatus: String? = null,
    val serverPeerError: String? = null,
    val clientSmartConnecting: Boolean = false,
    val clientSmartStatus: String? = null,
    val clientSmartError: String? = null,
    val pendingLegacyWifiJoin: LegacyWifiJoinRequest? = null,
    val p2pDiscovering: Boolean = false,
    val p2pPeers: List<RemoteP2pPeerView> = emptyList(),
    val p2pConnected: Boolean = false,
    val p2pGroupOwnerIp: String? = null,
    val p2pStatus: String? = null,
    val p2pError: String? = null,
) {
    fun serverPassphrase(): String? = serverPassphraseValue
    override fun toString(): String = "RemoteWifiPeerState(mode=$serverPeerMode, ssid=$serverSsid, p2p=$p2pConnected)"
}

internal interface RemoteWifiPeerStore {
    val state: StateFlow<RemoteWifiPeerState>
    fun requiredWifiPeerPermissions(): Array<String>
    fun hasWifiPeerPermissions(): Boolean
    fun startLocalOnlyHotspot()
    fun stopLocalOnlyHotspot()
    fun startWifiDirectGroup()
    fun stopWifiDirectGroup()
    fun handleClientQrInput(rawText: String, onDirectPairing: (String) -> Unit)
    fun confirmLegacyWifiJoin(onDirectPairing: (String) -> Unit)
    fun clearPendingLegacyWifiJoin()
    fun releaseClientPeerWifiNetwork()
    fun discoverWifiDirectPeers()
    fun connectWifiDirectPeer(deviceAddress: String)
    fun disconnectWifiDirectClient()
}

/**
 * Coordinates Local-Only Hotspot (`startLocalOnlyHotspot`, API 26+), `WifiNetworkSpecifier`
 * peer binding (API 29+), and Wi-Fi Direct (`WifiP2pManager`, API 26+) for FOH LAN connections
 * without interfering with cellular internet on either device.
 */
class RemoteWifiPeerController(
    context: Context,
    private val wifiTransport: RemoteWifiTransport,
    private val onInterfacesMayHaveChanged: () -> Unit,
) : RemoteWifiPeerStore {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val wifiManager by lazy { appContext.getSystemService(WifiManager::class.java) }
    private val cm by lazy { appContext.getSystemService(ConnectivityManager::class.java) }
    private val p2pManager by lazy { appContext.getSystemService(WifiP2pManager::class.java) }

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var peerNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var interfacePollJob: Job? = null

    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiverRegistered = false
    private var p2pOwnerRole = false

    private val mutableState = MutableStateFlow(RemoteWifiPeerState())
    override val state: StateFlow<RemoteWifiPeerState> = mutableState.asStateFlow()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val stateInt = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (stateInt == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        handleP2pDisabled()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!p2pOwnerRole && hasWifiPeerPermissions()) {
                        requestP2pPeers()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    refreshP2pConnectionAndGroup()
                }
            }
        }
    }

    override fun requiredWifiPeerPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    override fun hasWifiPeerPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun needsSystemLocationToggle(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !lm.isLocationEnabled
        } else {
            !(lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }
    }

    fun supportsDirectWifiSpecifier(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // --- Server Mode: Local-Only Hotspot (API 26+) ---

    @SuppressLint("MissingPermission")
    override fun startLocalOnlyHotspot() {
        val wm = wifiManager ?: run {
            mutableState.update { it.copy(serverPeerError = "Perangkat tidak memiliki adaptor Wi-Fi.") }
            return
        }
        if (!hasWifiPeerPermissions()) {
            mutableState.update { it.copy(serverPeerError = "Berikan izin Perangkat Terdekat / Lokasi untuk menyalakan Hotspot Lokal.") }
            return
        }
        if (needsSystemLocationToggle()) {
            mutableState.update { it.copy(serverPeerError = "Aktifkan GPS/Lokasi HP sementara (aturan Android 8–12 untuk Hotspot Lokal).") }
            return
        }
        stopWifiDirectServerGroupInternal()
        stopLocalOnlyHotspotInternal(notifyUi = false)

        mutableState.update {
            it.copy(
                serverPeerBusy = true,
                serverPeerError = null,
                serverPeerStatus = "Menyiapkan Hotspot Lokal tanpa kuota…",
            )
        }

        runCatching {
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        if (reservation == null) {
                            mutableState.update {
                                it.copy(
                                    serverPeerBusy = false,
                                    serverPeerMode = RemoteServerPeerMode.NONE,
                                    serverPeerError = "Hotspot Lokal gagal dimulai.",
                                )
                            }
                            return
                        }
                        hotspotReservation = reservation
                        val (ssid, pass) = extractHotspotCredentials(reservation)
                        mutableState.update {
                            it.copy(
                                serverPeerMode = RemoteServerPeerMode.LOCAL_HOTSPOT,
                                serverPeerBusy = false,
                                serverSsid = ssid,
                                serverPassphraseValue = pass,
                                serverPeerInterface = null,
                                serverPeerStatus = "Hotspot Lokal aktif (kuota seluler tetap aman). Ketuk Segarkan IP bila IP belum muncul.",
                                serverPeerError = null,
                            )
                        }
                        scheduleInterfaceRefreshBurst()
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                        mutableState.update { current ->
                            if (current.serverPeerMode == RemoteServerPeerMode.LOCAL_HOTSPOT) {
                                current.copy(
                                    serverPeerMode = RemoteServerPeerMode.NONE,
                                    serverPeerBusy = false,
                                    serverSsid = null,
                                    serverPassphraseValue = null,
                                    serverPeerInterface = null,
                                    serverPeerStatus = null,
                                )
                            } else current
                        }
                        onInterfacesMayHaveChanged()
                    }

                    override fun onFailed(reason: Int) {
                        hotspotReservation = null
                        val msg = when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                                "Matikan Hotspot sistem atau Wi-Fi Direct terlebih dahulu sebelum menyalakan Hotspot Lokal."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                                "Hotspot Lokal tidak diizinkan oleh kebijakan perangkat ini."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                                "Channel Wi-Fi sibuk atau Lokasi belum aktif. Coba nyalakan Wi-Fi lalu ulangi."
                            else -> "Gagal menyalakan Hotspot Lokal (kode $reason)."
                        }
                        mutableState.update {
                            it.copy(
                                serverPeerMode = RemoteServerPeerMode.NONE,
                                serverPeerBusy = false,
                                serverPeerStatus = null,
                                serverPeerError = msg,
                            )
                        }
                    }
                },
                mainHandler,
            )
        }.onFailure { err ->
            mutableState.update {
                it.copy(
                    serverPeerMode = RemoteServerPeerMode.NONE,
                    serverPeerBusy = false,
                    serverPeerStatus = null,
                    serverPeerError = "Gagal memulai Hotspot Lokal: ${err.message ?: err.javaClass.simpleName}",
                )
            }
        }
    }

    override fun stopLocalOnlyHotspot() {
        stopLocalOnlyHotspotInternal(notifyUi = true)
        onInterfacesMayHaveChanged()
    }

    private fun stopLocalOnlyHotspotInternal(notifyUi: Boolean) {
        interfacePollJob?.cancel()
        interfacePollJob = null
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        if (notifyUi) {
            mutableState.update { current ->
                if (current.serverPeerMode == RemoteServerPeerMode.LOCAL_HOTSPOT) {
                    current.copy(
                        serverPeerMode = RemoteServerPeerMode.NONE,
                        serverPeerBusy = false,
                        serverSsid = null,
                        serverPassphraseValue = null,
                        serverPeerInterface = null,
                        serverPeerStatus = null,
                        serverPeerError = null,
                    )
                } else current
            }
        }
    }

    private fun extractHotspotCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cfg = reservation.softApConfiguration
            val ssid = cfg.wifiSsid?.bytes?.toString(Charsets.UTF_8)?.trim()?.ifBlank { null }
            return ssid to cfg.passphrase
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = reservation.softApConfiguration
            @Suppress("DEPRECATION")
            return cfg.ssid?.ifBlank { null } to cfg.passphrase
        }
        @Suppress("DEPRECATION")
        val legacy = reservation.wifiConfiguration
        @Suppress("DEPRECATION")
        val ssid = legacy?.SSID?.removeSurrounding("\"")?.ifBlank { null }
        @Suppress("DEPRECATION")
        val pass = legacy?.preSharedKey?.removeSurrounding("\"")?.ifBlank { null }
        return ssid to pass
    }

    // --- Server Mode: Wi-Fi Direct Group Owner (API 26+) ---

    @SuppressLint("MissingPermission")
    override fun startWifiDirectGroup() {
        val manager = p2pManager ?: run {
            mutableState.update { it.copy(serverPeerError = "Perangkat tidak mendukung Wi-Fi Direct.") }
            return
        }
        if (!hasWifiPeerPermissions()) {
            mutableState.update { it.copy(serverPeerError = "Berikan izin Perangkat Terdekat / Lokasi untuk Wi-Fi Direct.") }
            return
        }
        if (needsSystemLocationToggle()) {
            mutableState.update { it.copy(serverPeerError = "Aktifkan GPS/Lokasi HP sementara (aturan Android 8–12 untuk Wi-Fi Direct).") }
            return
        }
        stopLocalOnlyHotspotInternal(notifyUi = false)
        val channel = ensureP2pChannel(manager) ?: run {
            mutableState.update { it.copy(serverPeerError = "Gagal inisialisasi Wi-Fi Direct.") }
            return
        }
        ensureP2pReceiverRegistered()
        p2pOwnerRole = true
        mutableState.update {
            it.copy(
                serverPeerBusy = true,
                serverPeerError = null,
                serverPeerStatus = "Membuat grup Wi-Fi Direct (P2P)…",
            )
        }
        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() { createP2pGroupNow(manager, channel) }
                override fun onFailure(reason: Int) { createP2pGroupNow(manager, channel) }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun createP2pGroupNow(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.createGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mutableState.update {
                        it.copy(
                            serverPeerMode = RemoteServerPeerMode.WIFI_DIRECT_GO,
                            serverPeerBusy = false,
                            serverPeerStatus = "Grup Wi-Fi Direct aktif. Mengambil info jaringan…",
                            serverPeerError = null,
                        )
                    }
                    refreshP2pConnectionAndGroup()
                    scheduleInterfaceRefreshBurst()
                }

                override fun onFailure(reason: Int) {
                    p2pOwnerRole = false
                    mutableState.update {
                        it.copy(
                            serverPeerMode = RemoteServerPeerMode.NONE,
                            serverPeerBusy = false,
                            serverPeerStatus = null,
                            serverPeerError = "Gagal membuat grup Wi-Fi Direct (${p2pReasonLabel(reason)}). Pastikan Wi-Fi HP menyala.",
                        )
                    }
                }
            },
        )
    }

    override fun stopWifiDirectGroup() {
        stopWifiDirectServerGroupInternal()
        onInterfacesMayHaveChanged()
    }

    private fun stopWifiDirectServerGroupInternal() {
        interfacePollJob?.cancel()
        interfacePollJob = null
        val wasOwner = p2pOwnerRole
        p2pOwnerRole = false
        if (wasOwner) {
            val manager = p2pManager
            val channel = p2pChannel
            if (manager != null && channel != null) {
                runCatching { manager.removeGroup(channel, null) }
            }
            unregisterP2pReceiverIfIdle()
        }
        mutableState.update { current ->
            if (current.serverPeerMode == RemoteServerPeerMode.WIFI_DIRECT_GO) {
                current.copy(
                    serverPeerMode = RemoteServerPeerMode.NONE,
                    serverPeerBusy = false,
                    serverSsid = null,
                    serverPassphraseValue = null,
                    serverPeerInterface = null,
                    serverPeerStatus = null,
                    serverPeerError = null,
                )
            } else current
        }
    }

    // --- Client Mode: Smart QR Auto-Join (`WifiNetworkSpecifier` API 29+ / Guide API 26-28) ---

    override fun handleClientQrInput(
        rawText: String,
        onDirectPairing: (String) -> Unit,
    ) {
        when (val parsed = RemoteSmartQrCodec.parseQrInput(rawText)) {
            is ParsedRemoteQr.DirectPairing -> {
                clearPendingLegacyWifiJoin()
                onDirectPairing(parsed.rawPairing)
            }
            is ParsedRemoteQr.SmartWifiAndPairing -> {
                connectClientSmartWifi(parsed, onDirectPairing)
            }
            is ParsedRemoteQr.WifiOnly -> {
                connectClientWifiOnly(parsed)
            }
            null -> {
                // Let DesktopRemoteSession validate and emit standard error for invalid/malformed inputs.
                onDirectPairing(rawText)
            }
        }
    }

    private fun connectClientSmartWifi(
        parsed: ParsedRemoteQr.SmartWifiAndPairing,
        onPairingReady: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mutableState.update {
                it.copy(
                    clientSmartConnecting = false,
                    clientSmartError = null,
                    clientSmartStatus = "Android 8/9: Sambungkan Wi-Fi ke ${parsed.ssid} lalu ketuk Lanjut Hubungkan.",
                    pendingLegacyWifiJoin = LegacyWifiJoinRequest(
                        ssid = parsed.ssid,
                        passphraseValue = parsed.passphrase(),
                        rawPairing = parsed.rawPairing,
                    ),
                )
            }
            return
        }
        requestLocalPeerWifiNetwork(
            ssid = parsed.ssid,
            passphrase = parsed.passphrase(),
            onReady = {
                mainHandler.post { onPairingReady(parsed.rawPairing) }
            },
        )
    }

    private fun connectClientWifiOnly(parsed: ParsedRemoteQr.WifiOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mutableState.update {
                it.copy(
                    clientSmartConnecting = false,
                    clientSmartError = null,
                    clientSmartStatus = "QR Wi-Fi terbaca (${parsed.ssid}). Sambungkan lewat Pengaturan Wi-Fi, lalu pindai QR Aplikasi.",
                )
            }
            return
        }
        requestLocalPeerWifiNetwork(
            ssid = parsed.ssid,
            passphrase = parsed.passphrase(),
            onReady = {
                mutableState.update {
                    it.copy(clientSmartStatus = "Wi-Fi ${parsed.ssid} terhubung (tanpa mengganggu data seluler). Lanjut pindai QR Aplikasi.")
                }
            },
        )
    }

    @SuppressLint("NewApi")
    private fun requestLocalPeerWifiNetwork(
        ssid: String,
        passphrase: String,
        onReady: () -> Unit,
    ) {
        val manager = cm ?: run {
            mutableState.update { it.copy(clientSmartError = "ConnectivityManager tidak tersedia.") }
            return
        }
        releaseClientPeerWifiNetwork()
        mutableState.update {
            it.copy(
                clientSmartConnecting = true,
                clientSmartError = null,
                clientSmartStatus = "Meminta Android menyambungkan Wi-Fi lokal $ssid (data seluler tetap aktif)…",
                pendingLegacyWifiJoin = null,
            )
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        var triggered = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiTransport.setPeerNetwork(network)
                mutableState.update {
                    it.copy(
                        clientSmartConnecting = false,
                        clientSmartError = null,
                        clientSmartStatus = "Wi-Fi lokal $ssid terikat (internet seluler tetap berjalan).",
                    )
                }
                if (!triggered) {
                    triggered = true
                    onReady()
                }
            }

            override fun onUnavailable() {
                wifiTransport.setPeerNetwork(null)
                mutableState.update {
                    it.copy(
                        clientSmartConnecting = false,
                        clientSmartStatus = null,
                        clientSmartError = "Koneksi otomatis ke $ssid dibatalkan atau tidak ditemukan. Pastikan Hotspot Lokal di HP Server menyala.",
                    )
                }
            }

            override fun onLost(network: Network) {
                wifiTransport.setPeerNetwork(null)
                mutableState.update {
                    it.copy(
                        clientSmartConnecting = false,
                        clientSmartStatus = "Koneksi Wi-Fi lokal $ssid terputus.",
                    )
                }
            }
        }
        peerNetworkCallback = callback
        runCatching {
            manager.requestNetwork(request, callback, 25_000)
        }.onFailure { err ->
            peerNetworkCallback = null
            mutableState.update {
                it.copy(
                    clientSmartConnecting = false,
                    clientSmartStatus = null,
                    clientSmartError = "Gagal meminta jaringan Wi-Fi lokal: ${err.message ?: err.javaClass.simpleName}",
                )
            }
        }
    }

    override fun confirmLegacyWifiJoin(onDirectPairing: (String) -> Unit) {
        val pending = mutableState.value.pendingLegacyWifiJoin ?: return
        wifiTransport.refreshStatus()
        clearPendingLegacyWifiJoin()
        onDirectPairing(pending.rawPairing)
    }

    override fun clearPendingLegacyWifiJoin() {
        mutableState.update { it.copy(pendingLegacyWifiJoin = null) }
    }

    override fun releaseClientPeerWifiNetwork() {
        val callback = peerNetworkCallback
        peerNetworkCallback = null
        if (callback != null) {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        wifiTransport.setPeerNetwork(null)
        mutableState.update {
            it.copy(
                clientSmartConnecting = false,
                clientSmartStatus = null,
                clientSmartError = null,
                pendingLegacyWifiJoin = null,
            )
        }
    }

    // --- Client Mode: Wi-Fi Direct Peer Discovery & Connect (API 26+) ---

    @SuppressLint("MissingPermission")
    override fun discoverWifiDirectPeers() {
        val manager = p2pManager ?: run {
            mutableState.update { it.copy(p2pError = "Perangkat tidak mendukung Wi-Fi Direct.") }
            return
        }
        if (!hasWifiPeerPermissions()) {
            mutableState.update { it.copy(p2pError = "Berikan izin Perangkat Terdekat / Lokasi untuk mencari perangkat Wi-Fi Direct.") }
            return
        }
        if (needsSystemLocationToggle()) {
            mutableState.update { it.copy(p2pError = "Aktifkan GPS/Lokasi HP sementara (aturan Android 8–12 untuk pencarian Wi-Fi Direct).") }
            return
        }
        val channel = ensureP2pChannel(manager) ?: run {
            mutableState.update { it.copy(p2pError = "Gagal inisialisasi Wi-Fi Direct.") }
            return
        }
        ensureP2pReceiverRegistered()
        mutableState.update {
            it.copy(
                p2pDiscovering = true,
                p2pError = null,
                p2pStatus = "Mencari perangkat Wi-Fi Direct di sekitar…",
            )
        }
        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    requestP2pPeers()
                    refreshP2pConnectionAndGroup()
                }

                override fun onFailure(reason: Int) {
                    mutableState.update {
                        it.copy(
                            p2pDiscovering = false,
                            p2pError = "Pencarian Wi-Fi Direct gagal (${p2pReasonLabel(reason)}). Pastikan Wi-Fi menyala.",
                        )
                    }
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    override fun connectWifiDirectPeer(deviceAddress: String) {
        val manager = p2pManager ?: return
        val channel = ensureP2pChannel(manager) ?: return
        if (!hasWifiPeerPermissions()) {
            mutableState.update { it.copy(p2pError = "Izin Perangkat Terdekat / Lokasi belum diberikan.") }
            return
        }
        ensureP2pReceiverRegistered()
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        mutableState.update {
            it.copy(
                p2pError = null,
                p2pStatus = "Menghubungkan Wi-Fi Direct… (setujui popup di HP tujuan bila muncul)",
            )
        }
        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mutableState.update {
                        it.copy(p2pStatus = "Permintaan Wi-Fi Direct terkirim. Menunggu alamat IP grup…")
                    }
                }

                override fun onFailure(reason: Int) {
                    mutableState.update {
                        it.copy(p2pError = "Gagal menyambung Wi-Fi Direct (${p2pReasonLabel(reason)}).")
                    }
                }
            },
        )
    }

    override fun disconnectWifiDirectClient() {
        val manager = p2pManager
        val channel = p2pChannel
        if (manager != null && channel != null && !p2pOwnerRole) {
            runCatching { manager.stopPeerDiscovery(channel, null) }
            runCatching { manager.cancelConnect(channel, null) }
            runCatching { manager.removeGroup(channel, null) }
        }
        mutableState.update {
            it.copy(
                p2pDiscovering = false,
                p2pPeers = emptyList(),
                p2pConnected = false,
                p2pGroupOwnerIp = null,
                p2pStatus = null,
                p2pError = null,
            )
        }
        unregisterP2pReceiverIfIdle()
        wifiTransport.refreshStatus()
    }

    @SuppressLint("MissingPermission")
    private fun requestP2pPeers() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        if (!hasWifiPeerPermissions()) return
        manager.requestPeers(channel) { list ->
            val peers = list?.deviceList.orEmpty()
                .sortedBy { it.deviceName.orEmpty() }
                .map { dev ->
                    RemoteP2pPeerView(
                        deviceName = dev.deviceName?.ifBlank { dev.deviceAddress } ?: dev.deviceAddress,
                        deviceAddress = dev.deviceAddress,
                        statusLabel = p2pDeviceStatusLabel(dev.status),
                        isGroupOwner = dev.isGroupOwner,
                    )
                }
            mutableState.update { current ->
                current.copy(
                    p2pPeers = peers,
                    p2pStatus = if (peers.isEmpty() && current.p2pDiscovering) {
                        "Belum ada perangkat Wi-Fi Direct ditemukan. Pastikan Wi-Fi Direct di HP Server aktif."
                    } else current.p2pStatus,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshP2pConnectionAndGroup() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestConnectionInfo(channel) { info ->
            val formed = info?.groupFormed == true
            val goIp = info?.groupOwnerAddress?.hostAddress
            if (formed && info.isGroupOwner && p2pOwnerRole) {
                if (hasWifiPeerPermissions()) {
                    manager.requestGroupInfo(channel) { group ->
                        mutableState.update {
                            it.copy(
                                serverPeerMode = RemoteServerPeerMode.WIFI_DIRECT_GO,
                                serverPeerBusy = false,
                                serverSsid = group?.networkName?.ifBlank { null },
                                serverPassphraseValue = group?.passphrase?.ifBlank { null },
                                serverPeerInterface = group?.`interface`,
                                serverPeerStatus = "Wi-Fi Direct aktif (${group?.`interface` ?: "P2P"}). Pilih IP ${goIp ?: "192.168.49.1"} lalu Nyalakan Server.",
                                serverPeerError = null,
                            )
                        }
                        onInterfacesMayHaveChanged()
                    }
                }
            } else if (formed && !info.isGroupOwner) {
                wifiTransport.refreshStatus()
                mutableState.update {
                    it.copy(
                        p2pDiscovering = false,
                        p2pConnected = true,
                        p2pGroupOwnerIp = goIp,
                        p2pStatus = goIp?.let { ip -> "Wi-Fi Direct terhubung · IP Server: $ip" } ?: "Wi-Fi Direct terhubung.",
                        p2pError = null,
                    )
                }
            } else if (!formed && !p2pOwnerRole) {
                mutableState.update {
                    it.copy(p2pConnected = false, p2pGroupOwnerIp = null)
                }
            }
        }
    }

    private fun handleP2pDisabled() {
        p2pOwnerRole = false
        mutableState.update {
            it.copy(
                serverPeerMode = if (it.serverPeerMode == RemoteServerPeerMode.WIFI_DIRECT_GO) RemoteServerPeerMode.NONE else it.serverPeerMode,
                serverPeerBusy = false,
                p2pDiscovering = false,
                p2pConnected = false,
                p2pGroupOwnerIp = null,
            )
        }
    }

    private fun ensureP2pChannel(manager: WifiP2pManager): WifiP2pManager.Channel? {
        if (p2pChannel == null) {
            p2pChannel = runCatching {
                manager.initialize(appContext, Looper.getMainLooper(), null)
            }.getOrNull()
        }
        return p2pChannel
    }

    private fun ensureP2pReceiverRegistered() {
        if (p2pReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        p2pReceiverRegistered = true
    }

    private fun unregisterP2pReceiverIfIdle() {
        val current = mutableState.value
        if (p2pReceiverRegistered && !p2pOwnerRole && !current.p2pDiscovering && !current.p2pConnected) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
            p2pReceiverRegistered = false
        }
    }

    private fun scheduleInterfaceRefreshBurst() {
        interfacePollJob?.cancel()
        interfacePollJob = scope.launch {
            repeat(8) {
                delay(350L)
                onInterfacesMayHaveChanged()
            }
        }
    }

    fun release() {
        stopLocalOnlyHotspotInternal(notifyUi = false)
        stopWifiDirectServerGroupInternal()
        releaseClientPeerWifiNetwork()
        if (p2pReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
            p2pReceiverRegistered = false
        }
    }

    private fun p2pDeviceStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "Terhubung"
        WifiP2pDevice.INVITED -> "Diundang"
        WifiP2pDevice.FAILED -> "Gagal"
        WifiP2pDevice.AVAILABLE -> "Tersedia"
        WifiP2pDevice.UNAVAILABLE -> "Tidak tersedia"
        else -> "Siap"
    }

    private fun p2pReasonLabel(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "tidak didukung"
        WifiP2pManager.BUSY -> "Wi-Fi sibuk"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "tidak ada permintaan"
        else -> "error $reason"
    }
}
