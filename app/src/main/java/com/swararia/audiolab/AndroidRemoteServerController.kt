package com.swararia.audiolab

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class RemoteInterfaceCandidate(
    val ip: String,
    val label: String,
    val priority: Int,
)

data class RemoteQueueEntry(
    val numericId: Long,
    val uriString: String,
    val title: String,
)

interface RemoteServicePlaybackHost {
    fun currentSnapshot(): RemoteServerPlaybackSnapshot
    fun executeRemoteCommand(
        request: RemoteCommandExecutionRequest,
        queueEntries: List<RemoteQueueEntry>,
    ): RemoteCommandExecutionResult
}

data class RemoteServerUiState(
    val interfaces: List<RemoteInterfaceCandidate> = emptyList(),
    val snapshot: RemoteEngineUiSnapshot = RemoteEngineUiSnapshot(),
    val activeTransfers: Int = 0,
    val transferStatus: String? = null,
    val serviceError: String? = null,
    val busy: Boolean = false,
)

/**
 * Platform owner for Swararia Android Server Mode:
 * - Discovers private Wi-Fi/Hotspot IPv4 interfaces
 * - Owns ephemeral TLS identity, HTTP/1.1 listener lifecycle, and upload staging directory
 * - Bridges PlaybackService on Main Looper and MediaLibrary SHA-256 sync/transfer on IO
 */
class AndroidRemoteServerController(
    context: Context,
    private val library: AudioLibraryRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()

    private val playbackHost = AtomicReference<RemoteServicePlaybackHost?>(null)
    @Volatile
    private var deletedCheck: (Long) -> Boolean = { false }

    @Volatile
    private var listener: AndroidRemoteHttpListener? = null
    private var catalogJob: Job? = null

    @Volatile
    private var latestTracks: List<AudioTrack> = emptyList()
    @Volatile
    private var latestPlaylists: List<LibraryPlaylist> = emptyList()

    private val uploadStagingDir = File(appContext.cacheDir, "remote_uploads").apply { mkdirs() }

    val engine: AndroidRemoteServerEngine = AndroidRemoteServerEngine(
        onStateChanged = { publishUiState() },
    )

    private val mutableUiState = MutableStateFlow(RemoteServerUiState())
    val state: StateFlow<RemoteServerUiState> = mutableUiState.asStateFlow()

    @Volatile
    private var activeTransfersCount = 0
    @Volatile
    private var transferMessage: String? = null
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var isBusy = false

    init {
        cleanUploadStagingDir()
        refreshInterfaces()
        catalogJob = scope.launch {
            combine(library.tracks, library.playlists) { tracks, playlists ->
                tracks to playlists
            }.collect { (tracks, playlists) ->
                latestTracks = tracks
                latestPlaylists = playlists
                pushCatalogToEngine()
            }
        }
    }

    fun attachPlaybackHost(host: RemoteServicePlaybackHost) {
        playbackHost.set(host)
        runOnMainBlocking(300L) { host.currentSnapshot() }?.let { engine.updatePlayback(it) }
    }

    fun detachPlaybackHost(host: RemoteServicePlaybackHost) {
        if (playbackHost.compareAndSet(host, null)) {
            engine.updatePlayback(RemoteServerPlaybackSnapshot(online = false, transportState = "STOPPED"))
        }
    }

    fun notifyPlaybackChanged(snapshot: RemoteServerPlaybackSnapshot) {
        engine.updatePlayback(snapshot)
    }

    fun attachDeletedTrackChecker(isDeleted: (Long) -> Boolean) {
        deletedCheck = isDeleted
        pushCatalogToEngine()
    }

    fun notifyLibraryDeletionsChanged() {
        pushCatalogToEngine()
    }

    fun refreshInterfaces(): List<RemoteInterfaceCandidate> {
        val discovered = LinkedHashMap<String, RemoteInterfaceCandidate>()
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            @Suppress("DEPRECATION")
            val networks = runCatching { cm.allNetworks }.getOrNull().orEmpty()
            for (net in networks) {
                val props = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: continue
                val ifaceName = props.interfaceName.orEmpty()
                for (la in props.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (AndroidRemoteTlsIdentity.parsePrivateOrLoopbackIpv4(ip) != null) {
                            addCandidate(discovered, ip, ifaceName)
                        }
                    }
                }
            }
        }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (AndroidRemoteTlsIdentity.parsePrivateOrLoopbackIpv4(ip) != null) {
                            addCandidate(discovered, ip, nif.name.orEmpty())
                        }
                    }
                }
            }
        }
        val sorted = discovered.values.sortedWith(compareBy<RemoteInterfaceCandidate> { it.priority }.thenBy { it.ip })
        publishUiState(interfaces = sorted)
        return sorted
    }

    fun startServer(bindIp: String, port: Int) {
        scope.launch {
            synchronized(lifecycleLock) {
                if (listener != null) return@launch
                if (AndroidRemoteTlsIdentity.parsePrivateOrLoopbackIpv4(bindIp) == null) {
                    lastError = "Pilih alamat IP Wi-Fi atau Hotspot yang valid."
                    publishUiState()
                    return@launch
                }
                if (port !in 1024..65535) {
                    lastError = "Port harus berada di antara 1024 dan 65535."
                    publishUiState()
                    return@launch
                }
                isBusy = true
                lastError = null
                publishUiState()
                try {
                    cleanUploadStagingDir()
                    val identity = AndroidRemoteTlsIdentity.generate(primaryIp = bindIp)
                    val http = AndroidRemoteHttpListener(
                        bindIp = bindIp,
                        requestedPort = port,
                        tlsIdentity = identity,
                        engine = engine,
                        commandExecutor = { request -> dispatchToPlaybackService(request) },
                        mediaPort = createMediaPort(),
                        webAssets = { path -> loadAllowlistedAsset(path) },
                        onBeforeStateRead = { syncPlaybackStateBeforeRead() },
                    )
                    listener = http
                    engine.activate(
                        bindIp = bindIp,
                        port = http.actualPort,
                        fingerprintBase64Url = identity.fingerprintBase64Url,
                        fingerprintHex = identity.formattedHexColon(),
                    )
                    engine.issueNativeInvite()
                    engine.issueWebPin()
                    syncPlaybackStateBeforeRead()
                    startPlaybackForegroundService("$bindIp:${http.actualPort}")
                } catch (err: Exception) {
                    runCatching { listener?.close() }
                    listener = null
                    engine.deactivate()
                    lastError = "Gagal menyalakan server di $bindIp:$port (${err.javaClass.simpleName}). Coba port lain."
                } finally {
                    isBusy = false
                    publishUiState()
                }
            }
            runCatching { library.scanMissingMetadata() }
        }
    }

    fun stopServer() {
        scope.launch {
            synchronized(lifecycleLock) {
                isBusy = true
                lastError = null
                publishUiState()
                try {
                    runCatching { listener?.close() }
                    listener = null
                    engine.deactivate()
                    cleanUploadStagingDir()
                    stopPlaybackForegroundService()
                } finally {
                    activeTransfersCount = 0
                    transferMessage = null
                    isBusy = false
                    publishUiState()
                }
            }
        }
    }

    fun refreshNativeInvite() {
        if (!engine.uiSnapshot().enabled) return
        engine.issueNativeInvite()
    }

    fun refreshWebPin() {
        if (!engine.uiSnapshot().enabled) return
        engine.issueWebPin()
    }

    fun setMediaTransferEnabled(allowed: Boolean) {
        engine.setMediaTransferEnabled(allowed)
    }

    fun setPauseTransferDuringPlayback(pause: Boolean) {
        engine.setPauseTransferDuringPlayback(pause)
    }

    fun approveDevice(deviceId: String, role: String, allowTransfer: Boolean) {
        runCatching { engine.approveDevice(deviceId, role, allowTransfer) }
            .onFailure {
                lastError = "Gagal menyetujui perangkat."
                publishUiState()
            }
    }

    fun setDeviceTransferAllowed(deviceId: String, allow: Boolean) {
        runCatching { engine.setDeviceTransferAllowed(deviceId, allow) }
            .onFailure {
                lastError = "Aktifkan izin transfer global dan peran Control terlebih dahulu."
                publishUiState()
            }
    }

    fun denyOrRevokeDevice(deviceId: String, deny: Boolean = false) {
        engine.revokeDevice(deviceId, deny = deny)
    }

    fun clearError() {
        lastError = null
        publishUiState()
    }

    private fun pushCatalogToEngine() {
        val deleted = deletedCheck
        val tracks = latestTracks
        val excluded = HashSet<String>()
        val sourceTracks = ArrayList<RemoteLibrarySourceTrack>(tracks.size)
        for (t in tracks) {
            if (deleted(t.id)) {
                excluded.add(t.id.toString())
            } else {
                sourceTracks.add(
                    RemoteLibrarySourceTrack(
                        id = t.id,
                        displayName = t.displayName,
                        sizeBytes = t.sizeBytes,
                        durationMs = t.durationMs,
                        artist = t.artist,
                        album = t.album,
                        sha256 = t.sha256,
                    ),
                )
            }
        }
        val sourcePlaylists = latestPlaylists.map { pl ->
            RemoteLibrarySourcePlaylist(id = pl.id, name = pl.name, trackIds = pl.trackIds)
        }
        engine.updateLibrary(sourceTracks, sourcePlaylists, excluded)
    }

    private fun syncPlaybackStateBeforeRead() {
        val host = playbackHost.get() ?: run {
            engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, transportState = "STOPPED"))
            return
        }
        runOnMainBlocking(400L) { host.currentSnapshot() }?.let { engine.updatePlayback(it) }
    }

    private fun dispatchToPlaybackService(request: RemoteCommandExecutionRequest): RemoteCommandExecutionResult {
        ensurePlaybackServiceStarted()
        val deadline = System.currentTimeMillis() + 1800L
        var host = playbackHost.get()
        while (host == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(40L)
            host = playbackHost.get()
        }
        val activeHost = host ?: return RemoteCommandExecutionResult(applied = false, errorCode = "player_unavailable")

        val deleted = deletedCheck
        val byId = latestTracks.associateBy { it.id }
        val entries = request.queueNumericIds.mapNotNull { id ->
            val track = byId[id] ?: return@mapNotNull null
            if (deleted(track.id)) return@mapNotNull null
            RemoteQueueEntry(
                numericId = track.id,
                uriString = track.uri,
                title = UiPresentation.title(track.displayName),
            )
        }
        val result = runOnMainBlocking(2200L) {
            activeHost.executeRemoteCommand(request, entries)
        }
        return result ?: RemoteCommandExecutionResult(applied = false, errorCode = "timeout")
    }

    private fun <T> runOnMainBlocking(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        val posted = mainHandler.post {
            try {
                result.set(runCatching(block))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result.get()?.getOrNull()
    }

    private fun ensurePlaybackServiceStarted() {
        val server = engine.uiSnapshot()
        if (playbackHost.get() != null) return
        val label = if (server.bindIp != null && server.port != null) "${server.bindIp}:${server.port}" else "LAN"
        startPlaybackForegroundService(label)
    }

    private fun startPlaybackForegroundService(endpointLabel: String) {
        val intent = Intent(appContext, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_REMOTE_SERVER_START
            putExtra(PlaybackService.EXTRA_REMOTE_ENDPOINT, endpointLabel)
        }
        runCatching { appContext.startForegroundService(intent) }
    }

    private fun stopPlaybackForegroundService() {
        val intent = Intent(appContext, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_REMOTE_SERVER_STOP
        }
        runCatching { appContext.startService(intent) }
    }

    private fun createMediaPort(): RemoteServerMediaPort = object : RemoteServerMediaPort {
        override fun createTempUploadFile(): File {
            uploadStagingDir.mkdirs()
            return File(uploadStagingDir, "upload-${UUID.randomUUID()}.part")
        }

        override fun isTrackDeletionBlocked(trackNumericId: Long): Boolean = deletedCheck(trackNumericId)

        override fun openMediaForDownload(trackNumericId: Long): RemoteDownloadMedia? = runBlocking {
            val readable = library.openReadableTrack(trackNumericId) ?: return@runBlocking null
            RemoteDownloadMedia(
                displayName = readable.track.displayName,
                mimeType = readable.mimeType,
                sizeBytes = readable.sizeBytes,
                inputStream = readable.inputStream,
            )
        }

        override fun importValidatedUpload(
            stagedFile: File,
            requestedName: String,
            expectedSha256: String,
            playlistName: String?,
        ): RemoteUploadImportResult = runBlocking {
            val outcome = library.importValidatedUpload(
                stagedFile = stagedFile,
                requestedName = requestedName,
                expectedSha256 = expectedSha256,
                playlistName = playlistName,
            )
            val track = outcome.track
            RemoteUploadImportResult(
                deduplicated = outcome is UploadImportOutcome.Deduplicated,
                trackId = AndroidRemoteServerEngine.trackIdString(track.id),
                displayName = track.displayName,
                title = UiPresentation.title(track.displayName),
                sizeBytes = track.sizeBytes,
                sha256 = track.sha256 ?: expectedSha256,
                linkedPlaylistId = outcome.linkedPlaylistId?.let(AndroidRemoteServerEngine::groupIdString),
            )
        }

        override fun onTransferStateChanged(activeTransfers: Int, message: String?) {
            activeTransfersCount = activeTransfers
            transferMessage = if (activeTransfers > 0) message else null
            publishUiState()
        }
    }

    private fun loadAllowlistedAsset(assetPath: String): Pair<String, ByteArray>? {
        val mime = ALLOWLISTED_WEB_ASSETS[assetPath] ?: return null
        val bytes = runCatching {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull() ?: return null
        return mime to bytes
    }

    private fun cleanUploadStagingDir() {
        runCatching {
            uploadStagingDir.listFiles()?.forEach { file ->
                if (file.isFile) runCatching { file.delete() }
            }
        }
    }

    private fun addCandidate(
        map: MutableMap<String, RemoteInterfaceCandidate>,
        ip: String,
        ifaceName: String,
    ) {
        if (ip in map) return
        val lower = ifaceName.lowercase(Locale.ROOT)
        // Skip cellular/VPN virtual interfaces; keep Wi-Fi, Hotspot AP, Ethernet, USB tethering.
        if (lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("tun")) return
        val (label, priority) = when {
            lower.startsWith("ap") || lower.startsWith("swlan") || lower.startsWith("softap") ||
                ip.startsWith("192.168.43.") -> "Hotspot HP ($ifaceName)" to 0
            lower.startsWith("wlan") -> "Wi-Fi ($ifaceName)" to 1
            lower.startsWith("rndis") || lower.startsWith("usb") -> "USB Tethering ($ifaceName)" to 2
            lower.startsWith("eth") -> "LAN Ethernet ($ifaceName)" to 3
            else -> "Jaringan Lokal (${ifaceName.ifBlank { "LAN" }})" to 4
        }
        map[ip] = RemoteInterfaceCandidate(ip = ip, label = label, priority = priority)
    }

    private fun publishUiState(interfaces: List<RemoteInterfaceCandidate>? = null) {
        val prev = mutableUiState.value
        mutableUiState.value = prev.copy(
            interfaces = interfaces ?: prev.interfaces,
            snapshot = engine.uiSnapshot(),
            activeTransfers = activeTransfersCount,
            transferStatus = transferMessage,
            serviceError = lastError,
            busy = isBusy,
        )
    }

    companion object {
        private val ALLOWLISTED_WEB_ASSETS = mapOf(
            "remote_server/index.html" to "text/html; charset=utf-8",
            "remote_server/remote.css" to "text/css; charset=utf-8",
            "remote_server/remote.js" to "application/javascript; charset=utf-8",
        )
    }
}
