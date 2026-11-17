package com.swararia.audiolab

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection

data class RemoteWifiTransportStatus(
    val wifiConnected: Boolean = false,
    val cellularActive: Boolean = false,
    val peerBound: Boolean = false,
    val activeWifiIp: String? = null,
)

data class RemoteMdnsServer(
    val serviceName: String,
    val hostIp: String,
    val port: Int,
    val fingerprintPrefix: String?,
)

internal interface RemoteWifiTransportStore {
    val status: StateFlow<RemoteWifiTransportStatus>
    fun refreshStatus(): RemoteWifiTransportStatus
}

internal interface RemoteNsdDiscoveryStore {
    val servers: StateFlow<List<RemoteMdnsServer>>
    fun startDiscovery()
    fun stopDiscovery()
}

/**
 * Binds client remote HTTPS connections explicitly to the Wi-Fi (or local-only peer) Network
 * so both server and client phones can keep cellular internet active without hijacking LAN routes.
 */
class RemoteWifiTransport(context: Context) : RemoteHttpsConnectionOpener, RemoteWifiTransportStore {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var peerNetwork: Network? = null

    private val mutableStatus = MutableStateFlow(RemoteWifiTransportStatus())
    override val status: StateFlow<RemoteWifiTransportStatus> = mutableStatus.asStateFlow()

    private val monitorRegistered = AtomicBoolean(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refreshStatus() }
        override fun onLost(network: Network) {
            if (peerNetwork == network) peerNetwork = null
            refreshStatus()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshStatus() }
    }

    init {
        registerMonitor()
        refreshStatus()
    }

    private fun registerMonitor() {
        if (cm == null || !monitorRegistered.compareAndSet(false, true)) return
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        }.onFailure {
            monitorRegistered.set(false)
        }
    }

    fun setPeerNetwork(network: Network?) {
        peerNetwork = network
        refreshStatus()
    }

    override fun refreshStatus(): RemoteWifiTransportStatus {
        val manager = cm ?: return RemoteWifiTransportStatus().also { mutableStatus.value = it }
        @Suppress("DEPRECATION")
        val networks = runCatching { manager.allNetworks }.getOrNull().orEmpty()
        var hasWifi = false
        var hasCellular = false
        var wifiIp: String? = null
        val peer = peerNetwork
        var peerAlive = false

        for (net in networks) {
            val caps = runCatching { manager.getNetworkCapabilities(net) }.getOrNull() ?: continue
            if (net == peer && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                peerAlive = true
                hasWifi = true
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                hasWifi = true
                if (wifiIp == null) {
                    wifiIp = extractIpv4Addresses(manager, net).firstOrNull()?.ipv4
                }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                hasCellular = true
            }
        }
        if (peer != null && !peerAlive) {
            peerNetwork = null
        }

        val next = RemoteWifiTransportStatus(
            wifiConnected = hasWifi,
            cellularActive = hasCellular,
            peerBound = peerAlive,
            activeWifiIp = wifiIp,
        )
        mutableStatus.value = next
        return next
    }

    override fun open(url: URL, targetHost: String): Pair<HttpsURLConnection, SocketFactory?> {
        val selected = selectWifiNetwork(targetHost)
        if (selected != null) {
            val conn = runCatching { selected.openConnection(url) as? HttpsURLConnection }.getOrNull()
            if (conn != null) {
                return conn to selected.socketFactory
            }
        }
        return (url.openConnection() as HttpsURLConnection) to null
    }

    private fun selectWifiNetwork(targetHost: String): Network? {
        val manager = cm ?: return null
        if (targetHost == "127.0.0.1") return null
        @Suppress("DEPRECATION")
        val networks = runCatching { manager.allNetworks }.getOrNull().orEmpty()
        val peer = peerNetwork

        val candidates = ArrayList<RemoteWifiNetworkCandidate<Network>>(networks.size)
        for (net in networks) {
            val caps = runCatching { manager.getNetworkCapabilities(net) }.getOrNull() ?: continue
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifi && !isEth) continue
            candidates.add(
                RemoteWifiNetworkCandidate(
                    token = net,
                    isPeerSpecified = peer != null && net == peer,
                    isWifiTransport = isWifi,
                    isEthernetTransport = isEth,
                    addresses = extractIpv4Addresses(manager, net),
                ),
            )
        }
        val activeCaps = runCatching { manager.activeNetwork?.let(manager::getNetworkCapabilities) }.getOrNull()
        val defaultIsWifiOrEth = activeCaps != null && (
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
        return RemoteNetworkRoutingRules.selectBestNetwork(candidates, targetHost, defaultIsWifiOrEth)?.token
    }

    private fun extractIpv4Addresses(manager: ConnectivityManager, net: Network): List<RemoteNetworkAddress> {
        val props = runCatching { manager.getLinkProperties(net) }.getOrNull() ?: return emptyList()
        return props.linkAddresses.mapNotNull { la ->
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val ip = addr.hostAddress ?: return@mapNotNull null
                RemoteNetworkAddress(ipv4 = ip, prefixLength = la.prefixLength)
            } else {
                null
            }
        }
    }
}

/**
 * Registers and discovers `_swararia._tcp.` mDNS services on local Wi-Fi/Hotspot/P2P networks.
 * Advertises only a short TLS fingerprint prefix and port — never invite secrets or tokens.
 */
class RemoteNsdCoordinator(context: Context) : RemoteNsdDiscoveryStore {
    private val appContext = context.applicationContext
    private val nsdManager by lazy { appContext.getSystemService(NsdManager::class.java) }
    private val lock = Any()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val discoveredMap = LinkedHashMap<String, RemoteMdnsServer>()

    private val mutableServers = MutableStateFlow<List<RemoteMdnsServer>>(emptyList())
    override val servers: StateFlow<List<RemoteMdnsServer>> = mutableServers.asStateFlow()

    fun registerServer(port: Int, fingerprintBase64Url: String) {
        val manager = nsdManager ?: return
        unregisterServer()
        val info = NsdServiceInfo().apply {
            serviceName = RemoteNetworkRoutingRules.sanitizeNsdServiceName(Build.MODEL)
            serviceType = SERVICE_TYPE
            setPort(port)
            runCatching {
                setAttribute("fp", fingerprintBase64Url.take(16))
                setAttribute("v", "1")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    registeredServiceName = nsdServiceInfo.serviceName
                }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { registrationListener = null }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { registeredServiceName = null }
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(lock) {
            registrationListener = listener
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            synchronized(lock) { registrationListener = null }
        }
    }

    fun unregisterServer() {
        val manager = nsdManager ?: return
        val listener = synchronized(lock) {
            val current = registrationListener
            registrationListener = null
            registeredServiceName = null
            current
        } ?: return
        runCatching { manager.unregisterService(listener) }
    }

    override fun startDiscovery() {
        val manager = nsdManager ?: return
        synchronized(lock) {
            if (discoveryListener != null) return
            discoveredMap.clear()
            mutableServers.value = emptyList()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_swararia._tcp")) return
                    synchronized(lock) {
                        if (discoveryListener == null) return
                        resolveQueue.addLast(serviceInfo)
                        pumpResolveQueueLocked(manager)
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    synchronized(lock) {
                        if (discoveredMap.remove(serviceInfo.serviceName) != null) {
                            mutableServers.value = discoveredMap.values.toList()
                        }
                    }
                }
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(lock) { discoveryListener = null }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            runCatching {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                discoveryListener = null
            }
        }
    }

    override fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = synchronized(lock) {
            val current = discoveryListener
            discoveryListener = null
            resolveQueue.clear()
            resolving = false
            current
        } ?: return
        runCatching { manager.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveQueueLocked(manager: NsdManager) {
        if (resolving || discoveryListener == null) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) {
                            resolving = false
                            pumpResolveQueueLocked(manager)
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val hostAddr = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        val fp = runCatching {
                            serviceInfo.attributes["fp"]?.let { String(it, Charsets.UTF_8) }
                        }.getOrNull()
                        val validIp = hostAddr?.takeIf {
                            it != "127.0.0.1" && AndroidRemoteTlsIdentity.parsePrivateOrLoopbackIpv4(it) != null
                        }
                        synchronized(lock) {
                            if (validIp != null && port in 1024..65535) {
                                discoveredMap[serviceInfo.serviceName] = RemoteMdnsServer(
                                    serviceName = serviceInfo.serviceName,
                                    hostIp = validIp,
                                    port = port,
                                    fingerprintPrefix = fp,
                                )
                                mutableServers.value = discoveredMap.values.toList()
                            }
                            resolving = false
                            pumpResolveQueueLocked(manager)
                        }
                    }
                },
            )
        }.onFailure {
            resolving = false
        }
    }

    companion object {
        const val SERVICE_TYPE = "_swararia._tcp."
    }
}
