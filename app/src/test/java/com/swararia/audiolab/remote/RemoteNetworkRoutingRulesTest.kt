package com.swararia.audiolab.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteNetworkRoutingRulesTest {
    @Test fun subnetMatchingMatchesExactPrefixesAndRejectsDifferentSubnets() {
        assertTrue(RemoteNetworkRoutingRules.isSameSubnet("192.168.43.15", 24, "192.168.43.1"))
        assertFalse(RemoteNetworkRoutingRules.isSameSubnet("192.168.43.15", 24, "192.168.49.1"))
        assertTrue(RemoteNetworkRoutingRules.isSameSubnet("172.20.10.4", 28, "172.20.10.1"))
        assertFalse(RemoteNetworkRoutingRules.isSameSubnet("172.20.10.4", 28, "172.20.10.30"))
        assertFalse(RemoteNetworkRoutingRules.isSameSubnet("192.168.1.2", 0, "192.168.1.1"))
    }

    @Test fun selectsPeerNetworkFirstThenMatchingSubnetThenWifiFallbackWhenDefaultIsCellular() {
        val peer = RemoteWifiNetworkCandidate(
            token = "peer",
            isPeerSpecified = true,
            isWifiTransport = true,
            isEthernetTransport = false,
            addresses = listOf(RemoteNetworkAddress("192.168.43.10", 24)),
        )
        val homeWifi = RemoteWifiNetworkCandidate(
            token = "wlan0",
            isPeerSpecified = false,
            isWifiTransport = true,
            isEthernetTransport = false,
            addresses = listOf(RemoteNetworkAddress("192.168.1.20", 24)),
        )
        val p2pWifi = RemoteWifiNetworkCandidate(
            token = "p2p0",
            isPeerSpecified = false,
            isWifiTransport = true,
            isEthernetTransport = false,
            addresses = listOf(RemoteNetworkAddress("192.168.49.2", 24)),
        )

        assertEquals(
            "peer",
            RemoteNetworkRoutingRules.selectBestNetwork(listOf(homeWifi, peer), "192.168.1.5", defaultIsWifiOrEthernet = true)?.token,
        )
        assertEquals(
            "p2p0",
            RemoteNetworkRoutingRules.selectBestNetwork(listOf(homeWifi, p2pWifi), "192.168.49.1", defaultIsWifiOrEthernet = false)?.token,
        )
        // When default route fell back to cellular, force Wi-Fi for private LAN IP even if prefix differs
        assertEquals(
            "wlan0",
            RemoteNetworkRoutingRules.selectBestNetwork(listOf(homeWifi), "10.0.0.5", defaultIsWifiOrEthernet = false)?.token,
        )
        // Never hijack loopback
        assertNull(RemoteNetworkRoutingRules.selectBestNetwork(listOf(peer, homeWifi), "127.0.0.1", defaultIsWifiOrEthernet = false))
    }

    @Test fun interfaceClassifierSkipsCellularAndPrioritizesHotspotAndP2p() {
        assertNull(RemoteNetworkRoutingRules.classifyInterfaceCandidate("10.12.0.2", "rmnet_data0"))
        assertNull(RemoteNetworkRoutingRules.classifyInterfaceCandidate("10.12.0.2", "ccmni0"))
        assertNull(RemoteNetworkRoutingRules.classifyInterfaceCandidate("10.8.0.2", "tun0"))
        assertEquals(0, RemoteNetworkRoutingRules.classifyInterfaceCandidate("192.168.43.1", "ap0")?.second)
        assertEquals(1, RemoteNetworkRoutingRules.classifyInterfaceCandidate("192.168.49.1", "p2p-wlan0-0")?.second)
        assertEquals(2, RemoteNetworkRoutingRules.classifyInterfaceCandidate("192.168.1.5", "wlan0")?.second)
        assertEquals("SWR-SM-M156B", RemoteNetworkRoutingRules.sanitizeNsdServiceName("SM-M156B!@#"))
    }
}
