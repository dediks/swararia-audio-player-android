package com.swararia.audiolab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen-level ViewModel for [RemoteTabScreen] network peer, transport status, and mDNS discovery.
 * Keeps Wi-Fi Hotspot / Wi-Fi Direct / NsdManager coordination out of Compose screens.
 */
internal class RemoteNetworkViewModel(
    private val peer: RemoteWifiPeerStore,
    private val transport: RemoteWifiTransportStore,
    private val nsd: RemoteNsdDiscoveryStore,
) : ViewModel() {
    val peerState: StateFlow<RemoteWifiPeerState> = peer.state
    val wifiStatus: StateFlow<RemoteWifiTransportStatus> = transport.status
    val mdnsServers: StateFlow<List<RemoteMdnsServer>> = nsd.servers

    fun hasWifiPeerPermissions(): Boolean = peer.hasWifiPeerPermissions()
    fun requiredWifiPeerPermissions(): Array<String> = peer.requiredWifiPeerPermissions()

    fun onHostResume(effectiveRole: String, onRefreshServerInterfaces: () -> Unit) {
        transport.refreshStatus()
        when (effectiveRole) {
            "server" -> onRefreshServerInterfaces()
            "controller" -> nsd.startDiscovery()
        }
    }

    fun onHostPauseOrStop() {
        nsd.stopDiscovery()
    }

    fun syncControllerDiscovery(active: Boolean) {
        if (active) {
            nsd.startDiscovery()
        } else {
            nsd.stopDiscovery()
        }
    }

    fun startLocalOnlyHotspot() = peer.startLocalOnlyHotspot()
    fun stopLocalOnlyHotspot() = peer.stopLocalOnlyHotspot()
    fun startWifiDirectGroup() = peer.startWifiDirectGroup()
    fun stopWifiDirectGroup() = peer.stopWifiDirectGroup()
    fun handleClientQrInput(rawText: String, onDirectPairing: (String) -> Unit) =
        peer.handleClientQrInput(rawText, onDirectPairing)
    fun confirmLegacyWifiJoin(onDirectPairing: (String) -> Unit) =
        peer.confirmLegacyWifiJoin(onDirectPairing)
    fun clearPendingLegacyWifiJoin() = peer.clearPendingLegacyWifiJoin()
    fun releaseClientPeerWifiNetwork() = peer.releaseClientPeerWifiNetwork()
    fun discoverWifiDirectPeers() = peer.discoverWifiDirectPeers()
    fun connectWifiDirectPeer(deviceAddress: String) = peer.connectWifiDirectPeer(deviceAddress)
    fun disconnectWifiDirectClient() = peer.disconnectWifiDirectClient()

    override fun onCleared() {
        nsd.stopDiscovery()
        super.onCleared()
    }
}

internal class RemoteNetworkViewModelFactory(
    private val createPeer: () -> RemoteWifiPeerStore,
    private val createTransport: () -> RemoteWifiTransportStore,
    private val createNsd: () -> RemoteNsdDiscoveryStore,
) : ViewModelProvider.Factory {
    constructor(container: AppContainer) : this(
        createPeer = { container.remoteWifiPeerController },
        createTransport = { container.remoteWifiTransport },
        createNsd = { container.remoteNsdCoordinator },
    )

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == RemoteNetworkViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return RemoteNetworkViewModel(createPeer(), createTransport(), createNsd()) as T
    }

    companion object {
        fun fromContext(context: Context): RemoteNetworkViewModelFactory {
            val app = context.applicationContext
            val container = (app as? SwarariaApp)?.container
            return if (container != null) {
                RemoteNetworkViewModelFactory(container)
            } else {
                RemoteNetworkViewModelFactory(
                    createPeer = { RemoteWifiPeerController(app, RemoteWifiTransport(app)) {} },
                    createTransport = { RemoteWifiTransport(app) },
                    createNsd = { RemoteNsdCoordinator(app) },
                )
            }
        }
    }
}
