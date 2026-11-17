package com.swararia.audiolab.remote

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRemoteWifiPeerStore : RemoteWifiPeerStore {
    private val mutableState = MutableStateFlow(RemoteWifiPeerState())
    override val state: StateFlow<RemoteWifiPeerState> = mutableState.asStateFlow()

    var permissionsGranted = true
    var startHotspotCalls = 0
    var stopHotspotCalls = 0
    var startWifiDirectCalls = 0
    var stopWifiDirectCalls = 0
    var lastQrInput: String? = null
    var confirmLegacyCalls = 0
    var clearLegacyCalls = 0
    var releaseNetworkCalls = 0
    var discoverP2pCalls = 0
    var lastConnectedP2p: String? = null
    var disconnectP2pCalls = 0

    override fun requiredWifiPeerPermissions(): Array<String> = arrayOf("android.permission.NEARBY_WIFI_DEVICES")
    override fun hasWifiPeerPermissions(): Boolean = permissionsGranted
    override fun startLocalOnlyHotspot() { startHotspotCalls++ }
    override fun stopLocalOnlyHotspot() { stopHotspotCalls++ }
    override fun startWifiDirectGroup() { startWifiDirectCalls++ }
    override fun stopWifiDirectGroup() { stopWifiDirectCalls++ }
    override fun handleClientQrInput(rawText: String, onDirectPairing: (String) -> Unit) {
        lastQrInput = rawText
        onDirectPairing(rawText)
    }
    override fun confirmLegacyWifiJoin(onDirectPairing: (String) -> Unit) {
        confirmLegacyCalls++
        onDirectPairing("confirmed")
    }
    override fun clearPendingLegacyWifiJoin() { clearLegacyCalls++ }
    override fun releaseClientPeerWifiNetwork() { releaseNetworkCalls++ }
    override fun discoverWifiDirectPeers() { discoverP2pCalls++ }
    override fun connectWifiDirectPeer(deviceAddress: String) { lastConnectedP2p = deviceAddress }
    override fun disconnectWifiDirectClient() { disconnectP2pCalls++ }
}

private class FakeRemoteWifiTransportStore : RemoteWifiTransportStore {
    private val mutableStatus = MutableStateFlow(RemoteWifiTransportStatus(wifiConnected = true))
    override val status: StateFlow<RemoteWifiTransportStatus> = mutableStatus.asStateFlow()
    var refreshCalls = 0

    override fun refreshStatus(): RemoteWifiTransportStatus {
        refreshCalls++
        return mutableStatus.value
    }
}

private class FakeRemoteNsdDiscoveryStore : RemoteNsdDiscoveryStore {
    private val mutableServers = MutableStateFlow<List<RemoteMdnsServer>>(emptyList())
    override val servers: StateFlow<List<RemoteMdnsServer>> = mutableServers.asStateFlow()
    var startCalls = 0
    var stopCalls = 0

    override fun startDiscovery() { startCalls++ }
    override fun stopDiscovery() { stopCalls++ }
}

class RemoteNetworkViewModelTest {
    @Test
    fun `onHostResume refreshes transport and routes discovery or server interfaces by role`() {
        val peer = FakeRemoteWifiPeerStore()
        val transport = FakeRemoteWifiTransportStore()
        val nsd = FakeRemoteNsdDiscoveryStore()
        val vm = RemoteNetworkViewModel(peer, transport, nsd)

        var refreshedInterfaces = 0
        vm.onHostResume("server") { refreshedInterfaces++ }
        assertEquals(1, transport.refreshCalls)
        assertEquals(1, refreshedInterfaces)
        assertEquals(0, nsd.startCalls)

        vm.onHostResume("controller") { refreshedInterfaces++ }
        assertEquals(2, transport.refreshCalls)
        assertEquals(1, refreshedInterfaces)
        assertEquals(1, nsd.startCalls)

        vm.onHostPauseOrStop()
        assertEquals(1, nsd.stopCalls)
    }

    @Test
    fun `syncControllerDiscovery and onCleared manage mDNS discovery lifecycle`() {
        val peer = FakeRemoteWifiPeerStore()
        val transport = FakeRemoteWifiTransportStore()
        val nsd = FakeRemoteNsdDiscoveryStore()
        val vm = RemoteNetworkViewModel(peer, transport, nsd)
        val store = ViewModelStore()
        store.put("remoteNet", vm)

        vm.syncControllerDiscovery(true)
        assertEquals(1, nsd.startCalls)
        vm.syncControllerDiscovery(false)
        assertEquals(1, nsd.stopCalls)

        store.clear()
        assertEquals(2, nsd.stopCalls)
    }

    @Test
    fun `peer hotspot wifi-direct and QR actions delegate through ViewModel`() {
        val peer = FakeRemoteWifiPeerStore()
        val transport = FakeRemoteWifiTransportStore()
        val nsd = FakeRemoteNsdDiscoveryStore()
        val vm = RemoteNetworkViewModel(peer, transport, nsd)

        var pairedPayload = ""
        assertTrue(vm.hasWifiPeerPermissions())
        vm.startLocalOnlyHotspot()
        vm.stopLocalOnlyHotspot()
        vm.startWifiDirectGroup()
        vm.stopWifiDirectGroup()
        vm.handleClientQrInput("swararia1|payload") { pairedPayload = it }
        vm.confirmLegacyWifiJoin {}
        vm.clearPendingLegacyWifiJoin()
        vm.releaseClientPeerWifiNetwork()
        vm.discoverWifiDirectPeers()
        vm.connectWifiDirectPeer("aa:bb:cc:dd:ee:ff")
        vm.disconnectWifiDirectClient()

        assertEquals(1, peer.startHotspotCalls)
        assertEquals(1, peer.stopHotspotCalls)
        assertEquals(1, peer.startWifiDirectCalls)
        assertEquals(1, peer.stopWifiDirectCalls)
        assertEquals("swararia1|payload", peer.lastQrInput)
        assertEquals("swararia1|payload", pairedPayload)
        assertEquals(1, peer.confirmLegacyCalls)
        assertEquals(1, peer.clearLegacyCalls)
        assertEquals(1, peer.releaseNetworkCalls)
        assertEquals(1, peer.discoverP2pCalls)
        assertEquals("aa:bb:cc:dd:ee:ff", peer.lastConnectedP2p)
        assertEquals(1, peer.disconnectP2pCalls)
    }
}
