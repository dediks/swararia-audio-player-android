package com.swararia.audiolab

import java.util.Locale

internal data class RemoteNetworkAddress(
    val ipv4: String,
    val prefixLength: Int,
)

internal data class RemoteWifiNetworkCandidate<T>(
    val token: T,
    val isPeerSpecified: Boolean,
    val isWifiTransport: Boolean,
    val isEthernetTransport: Boolean,
    val addresses: List<RemoteNetworkAddress>,
)

/** Pure Kotlin subnet matching, network selection, and interface priority rules for FOH LAN/P2P transport. */
internal object RemoteNetworkRoutingRules {
    fun parseIpv4Int(host: String): Int? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (piece in parts) {
            if (!Regex("0|[1-9][0-9]{0,2}").matches(piece)) return null
            val octet = piece.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            acc = (acc shl 8) or octet
        }
        return acc
    }

    fun isSameSubnet(localIpv4: String, prefixLength: Int, targetIpv4: String): Boolean {
        if (prefixLength !in 1..32) return false
        val local = parseIpv4Int(localIpv4) ?: return false
        val target = parseIpv4Int(targetIpv4) ?: return false
        val mask = if (prefixLength == 32) -1 else (-1 shl (32 - prefixLength))
        return (local and mask) == (target and mask)
    }

    fun <T> selectBestNetwork(
        candidates: List<RemoteWifiNetworkCandidate<T>>,
        targetHost: String,
        defaultIsWifiOrEthernet: Boolean,
    ): RemoteWifiNetworkCandidate<T>? {
        if (targetHost == "127.0.0.1") return null
        if (parseIpv4Int(targetHost) == null) return null

        // 1. Prefer an active app-requested peer Wi-Fi network (WifiNetworkSpecifier on Android 10+)
        candidates.firstOrNull { it.isPeerSpecified }?.let { return it }

        // 2. Prefer a Wi-Fi / Ethernet network whose IPv4 subnet directly contains the target host
        candidates.firstOrNull { candidate ->
            (candidate.isWifiTransport || candidate.isEthernetTransport) &&
                candidate.addresses.any { addr -> isSameSubnet(addr.ipv4, addr.prefixLength, targetHost) }
        }?.let { return it }

        // 3. If Android routed default traffic to cellular, force any active Wi-Fi interface for private LAN IPs
        if (!defaultIsWifiOrEthernet) {
            candidates.firstOrNull { it.isWifiTransport }?.let { return it }
        }

        return null
    }

    fun classifyInterfaceCandidate(ip: String, ifaceName: String): Pair<String, Int>? {
        val lower = ifaceName.lowercase(Locale.ROOT)
        // Skip cellular/VPN virtual interfaces; keep Wi-Fi, Hotspot AP, Wi-Fi Direct, Ethernet, USB tethering.
        if (lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("tun")) return null
        return when {
            lower.startsWith("ap") || lower.startsWith("swlan") || lower.startsWith("softap") ||
                ip.startsWith("192.168.43.") -> "Hotspot HP ($ifaceName)" to 0
            lower.startsWith("p2p") || ip.startsWith("192.168.49.") -> "Wi-Fi Direct ($ifaceName)" to 1
            lower.startsWith("wlan") -> "Wi-Fi ($ifaceName)" to 2
            lower.startsWith("rndis") || lower.startsWith("usb") -> "USB Tethering ($ifaceName)" to 3
            lower.startsWith("eth") -> "LAN Ethernet ($ifaceName)" to 4
            else -> "Jaringan Lokal (${ifaceName.ifBlank { "LAN" }})" to 5
        }
    }

    fun sanitizeNsdServiceName(rawModel: String?): String {
        val clean = rawModel.orEmpty()
            .map { ch -> if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == ' ') ch else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(24)
        return if (clean.isBlank()) "SWR-Player" else "SWR-$clean"
    }
}
