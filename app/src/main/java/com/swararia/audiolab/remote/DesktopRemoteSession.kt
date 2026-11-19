package com.swararia.audiolab.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

enum class RemotePhase(val wire: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    PENDING("pending"),
    ACTIVE("active");

    companion object {
        fun fromWire(value: String?): RemotePhase = entries.firstOrNull { it.wire == value } ?: DISCONNECTED
    }
}

enum class RemoteRole(val wire: String) {
    CONTROL("control"),
    VIEW_ONLY("view_only");

    companion object {
        fun fromWire(value: String?): RemoteRole? = entries.firstOrNull { it.wire == value }
    }
}

enum class RemoteCommandOutcome(val wire: String, val inFlight: Boolean = false) {
    ACCEPTED("accepted", inFlight = true),
    PENDING("pending", inFlight = true),
    APPLIED("applied"),
    UNKNOWN("unknown"),
    EXPIRED("expired"),
    REJECTED("rejected");

    companion object {
        fun fromWire(value: String?): RemoteCommandOutcome? =
            if (value == null) null else entries.firstOrNull { it.wire == value } ?: REJECTED
    }
}

data class DesktopRemoteFolderSyncState(
    val loadingFolders: Boolean = false,
    val running: Boolean = false,
    val folders: List<DesktopRemoteSyncFolder> = emptyList(),
    val hostWarnings: List<String> = emptyList(),
    val excludedWavFlacCount: Int = 0,
    val oversizedCount: Int = 0,
    val currentFolderName: String? = null,
    val currentFileName: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val downloadedCount: Int = 0,
    val skippedDuplicateCount: Int = 0,
    val failedCount: Int = 0,
    val lastSummary: String? = null,
    val error: String? = null,
)

data class DesktopRemoteBatchUploadState(
    val running: Boolean = false,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String? = null,
    val currentFileSentBytes: Long = 0L,
    val currentFileTotalBytes: Long = 0L,
    val savedCount: Int = 0,
    val deduplicatedCount: Int = 0,
    val renamedCount: Int = 0,
    val wavFlacSkippedCount: Int = 0,
    val oversizedSkippedCount: Int = 0,
    val failedCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val lastSummary: String? = null,
    val error: String? = null,
)

data class DesktopRemoteUiState(
    val endpoint: String? = null, val phase: String = RemotePhase.DISCONNECTED.wire, val role: String? = null,
    val playback: DesktopRemotePlayback? = null, val commands: Set<String> = emptySet(),
    val mediaSupported: Boolean = false, val mediaMaxBytes: Long = 0L,
    val syncSupported: Boolean = false, val uploadSupported: Boolean = false,
    val playlist: String? = null, val activeGroupId: String? = null, val totalTracks: Int = 0,
    val groups: List<DesktopRemoteTrackGroup> = emptyList(),
    val currentTrackId: String? = null,
    val tracks: List<DesktopRemoteTrack> = emptyList(),
    val downloads: Map<String, DesktopRemoteTrackDownload> = emptyMap(),
    val folderSync: DesktopRemoteFolderSyncState = DesktopRemoteFolderSyncState(),
    val batchUpload: DesktopRemoteBatchUploadState = DesktopRemoteBatchUploadState(),
    val localTracks: List<DesktopRemoteLocalTrackSummary> = emptyList(),
    val localPlaylists: List<DesktopRemoteLocalPlaylistSummary> = emptyList(),
    val busy: Boolean = false, val fresh: Boolean = false, val message: String? = null,
    val outcome: String? = null,
) {
    val remotePhase: RemotePhase get() = RemotePhase.fromWire(phase)
    val remoteRole: RemoteRole? get() = RemoteRole.fromWire(role)
    val remoteOutcome: RemoteCommandOutcome? get() = RemoteCommandOutcome.fromWire(outcome)
    val waitingCommand: Boolean get() = busy || remoteOutcome?.inFlight == true
    val canControl: Boolean
        get() = remotePhase == RemotePhase.ACTIVE &&
            remoteRole == RemoteRole.CONTROL &&
            playback?.online == true &&
            fresh &&
            !waitingCommand
    val canFadeOut: Boolean get() = canControl && "fade_out" in commands && playback?.playing == true && playback?.transport != "FADING"
    val canSelectTrack: Boolean get() = canControl && "select_track" in commands
    val canSwitchGroup: Boolean get() = canControl && "select_group" in commands
    val canStepTrack: Boolean get() = canControl && playback?.title != null
    val canDownloadMedia: Boolean
        get() = remotePhase == RemotePhase.ACTIVE &&
            remoteRole == RemoteRole.CONTROL &&
            mediaSupported &&
            playback?.online == true
    val canSyncFolders: Boolean
        get() = remotePhase == RemotePhase.ACTIVE &&
            remoteRole == RemoteRole.CONTROL &&
            syncSupported
    val canUploadToDesktop: Boolean
        get() = remotePhase == RemotePhase.ACTIVE &&
            remoteRole == RemoteRole.CONTROL &&
            uploadSupported
}

/** Android-free RAM owner. Like SearchSession, invoke on the caller's single UI thread.
 * Disconnect forgets remembered credentials; release drops RAM only. Never sends Stop or replays a POST.
 */
class DesktopRemoteSession(
    private val api: DesktopRemoteApi,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1500,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    store: DesktopRemoteSessionStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistence: DesktopRemotePersistence? = store?.let { DesktopRemotePersistence(it, ioDispatcher) },
    private val mediaImporter: DesktopRemoteMediaImporter? = null,
    private val mediaUploader: DesktopRemoteMediaUploader? = null,
) {
    private val mutable = MutableStateFlow(DesktopRemoteUiState())
    val state = mutable.asStateFlow()
    private val serial = Mutex()
    private var generation = 0L
    private val storeOwner = persistence?.claim()
    private var pairing: DesktopRemotePairing? = null
    private var grant: DesktopRemoteGrant? = null
    private var expiresAt = 0.0
    private var persistedKey: String? = null
    private var visibility: Any? = null
    private var poll: Job? = null
    private var mutation: Job? = null
    private var commandId: String? = null
    private val downloadJobs = HashMap<String, Job>()
    private var folderSyncJob: Job? = null
    private var batchUploadJob: Job? = null

    init {
        restore()
        mediaUploader?.let { uploader ->
            scope.launch {
                uploader.localTracksFlow.collect { items ->
                    mutable.update { it.copy(localTracks = items) }
                }
            }
            scope.launch {
                uploader.localPlaylistsFlow.collect { items ->
                    mutable.update { it.copy(localPlaylists = items) }
                }
            }
        }
    }

    private fun restore() {
        val activeStore = persistence ?: return
        val ticket = generation
        scope.launch {
            val loaded = activeStore.load(storeOwner!!).await().getOrNull() ?: return@launch
            if (generation != ticket || pairing != null || grant != null) return@launch
            if (loaded.expiresAt <= now()) {
                clearStore()
                return@launch
            }
            val savedPairing = runCatching {
                DesktopRemotePairing.fromSaved(loaded.endpoint, loaded.fingerprint())
            }.getOrNull() ?: run {
                clearStore()
                return@launch
            }
            pairing = savedPairing
            grant = DesktopRemoteGrant(loaded.deviceId, loaded.secret())
            expiresAt = loaded.expiresAt
            persistedKey = sessionKey(savedPairing.endpoint, loaded.deviceId, loaded.expiresAt)
            mutable.update { prev ->
                DesktopRemoteUiState(
                    endpoint = savedPairing.endpoint,
                    phase = "active",
                    message = "Menyambung ulang ke perangkat target…",
                    localTracks = prev.localTracks,
                    localPlaylists = prev.localPlaylists,
                )
            }
            startPolling()
        }
    }

    private fun sessionKey(endpoint: String, deviceId: String, expiresAt: Double): String =
        "$endpoint|$deviceId|$expiresAt"

    private fun clearStore() {
        val activeStore = persistence ?: return
        activeStore.clear(storeOwner!!)
    }

    fun visible(owner: Any) {
        visibility = owner
        poll?.cancel(); poll = null
        mutable.update { it.copy(fresh = false) }
        startPolling()
    }
    fun hidden(owner: Any) {
        if (visibility !== owner) return
        visibility = null
        poll?.cancel(); poll = null
        mutable.update { it.copy(fresh = false) }
    }
    fun connect(input: String) {
        if (mutable.value.busy || grant != null || visibility == null) return
        val parsed = try { DesktopRemotePairing.parse(input) } catch (_: DesktopRemoteFailure) {
            mutable.update { it.copy(message = "Kode pairing tidak valid.") }; return
        }
        val ticket = ++generation
        mutable.update { prev ->
            DesktopRemoteUiState(
                endpoint = parsed.endpoint,
                phase = "connecting",
                busy = true,
                localTracks = prev.localTracks,
                localPlaylists = prev.localPlaylists,
            )
        }
        mutation = scope.launch {
            try {
                serial.withLock {
                    val issued = api.pair(parsed)
                    currentCoroutineContext().ensureActive()
                    if (generation != ticket) return@withLock
                    parsed.forgetInvitation()
                    pairing = parsed; grant = issued
                    mutable.update { prev ->
                        DesktopRemoteUiState(
                            endpoint = parsed.endpoint,
                            phase = "pending",
                            message = "Setujui perangkat ini di perangkat target.",
                            localTracks = prev.localTracks,
                            localPlaylists = prev.localPlaylists,
                        )
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) { if (generation == ticket) disconnectWith(pairFailureMessage(failure)) }
            finally {
                parsed.forgetInvitation()
                if (generation == ticket) {
                    mutation = null
                    mutable.update { it.copy(busy = false) }
                    startPolling()
                }
            }
        }
    }
    /** Drops RAM state when the ViewModel clears without removing encrypted auto-reconnect credentials. */
    fun release() {
        visibility = null
        clearMemoryState(null)
        storeOwner?.let { persistence?.release(it) }
    }

    fun disconnect() = disconnectWith(null)

    private fun cancelActiveDownloads() {
        downloadJobs.values.toList().forEach { it.cancel() }
        downloadJobs.clear()
    }

    private fun cancelTransferBatchJobs() {
        folderSyncJob?.cancel(); folderSyncJob = null
        batchUploadJob?.cancel(); batchUploadJob = null
    }

    private fun clearMemoryState(message: String?) {
        generation++
        poll?.cancel(); poll = null
        mutation?.cancel(); mutation = null
        cancelActiveDownloads()
        cancelTransferBatchJobs()
        pairing?.forgetInvitation(); pairing = null; grant = null; commandId = null
        expiresAt = 0.0; persistedKey = null
        mutable.update { prev ->
            DesktopRemoteUiState(
                message = message,
                localTracks = prev.localTracks,
                localPlaylists = prev.localPlaylists,
            )
        }
    }

    private fun disconnectWith(message: String?) {
        clearMemoryState(message)
        clearStore()
    }

    private fun startPolling() {
        val owner = visibility ?: return
        val p = pairing ?: return
        val g = grant ?: return
        if (poll != null) return
        if (expiresAt > 0.0 && expiresAt <= now()) {
            disconnectWith("Sesi kedaluwarsa. Pair ulang dengan perangkat target.")
            return
        }
        val ticket = generation
        poll = scope.launch {
            while (isActive && visibility === owner && generation == ticket) {
                if (expiresAt > 0.0 && expiresAt <= now()) {
                    disconnectWith("Sesi kedaluwarsa. Pair ulang dengan perangkat target.")
                    break
                }
                serial.withLock { read(p, g, ticket, owner) }
                delay(pollMillis)
            }
        }
    }
    fun refresh() {
        if (visibility == null || mutable.value.busy) return
        poll?.cancel(); poll = null
        startPolling() // GET only; never resubmit pairing/command.
    }
    private suspend fun current(ticket: Long, owner: Any) {
        currentCoroutineContext().ensureActive()
        if (generation != ticket || visibility !== owner) throw CancellationException()
    }
    private suspend fun read(p: DesktopRemotePairing, g: DesktopRemoteGrant, ticket: Long, owner: Any) {
        try {
            val approval = api.session(p, g)
            current(ticket, owner)
            if (approval.deviceId != g.deviceId || approval.expiresAt <= now()) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
            expiresAt = approval.expiresAt
            if (approval.status == "pending") {
                cancelActiveDownloads()
                cancelTransferBatchJobs()
                if (persistedKey != null) {
                    persistedKey = null
                    clearStore()
                }
                mutable.update {
                    it.copy(
                        phase = "pending", role = null, playback = null,
                        commands = emptySet(), mediaSupported = false, mediaMaxBytes = 0L,
                        syncSupported = false, uploadSupported = false,
                        playlist = null, activeGroupId = null, totalTracks = 0,
                        groups = emptyList(), currentTrackId = null, tracks = emptyList(),
                        downloads = emptyMap(),
                        folderSync = DesktopRemoteFolderSyncState(),
                        batchUpload = DesktopRemoteBatchUploadState(),
                        fresh = false, message = "Setujui perangkat ini di perangkat target.",
                    )
                }
                return
            }
            if (approval.status != "active" || approval.role !in setOf("control", "view_only")) throw DesktopRemoteFailure()
            val activeStore = persistence
            val snapshotKey = sessionKey(p.endpoint, g.deviceId, approval.expiresAt)
            if (activeStore != null && persistedKey != snapshotKey) {
                val snapshot = PersistedDesktopRemoteSession(
                    endpoint = p.endpoint,
                    pinBytes = p.fingerprint(),
                    deviceId = g.deviceId,
                    credential = g.authorization(),
                    expiresAt = approval.expiresAt,
                )
                val saved = activeStore.save(storeOwner!!, snapshot).await().isSuccess
                current(ticket, owner)
                if (saved) persistedKey = snapshotKey
            }
            val caps = api.capabilitiesInfo(p, g)
            val supported = caps.commands
            val mediaAllowed = caps.media && approval.role == "control"
            val syncAllowed = caps.sync && approval.role == "control"
            val uploadAllowed = caps.upload && approval.role == "control"
            if (!mediaAllowed) cancelActiveDownloads()
            if (!syncAllowed) { folderSyncJob?.cancel(); folderSyncJob = null }
            if (!uploadAllowed) { batchUploadJob?.cancel(); batchUploadJob = null }
            current(ticket, owner)
            val playback = api.state(p, g)
            current(ticket, owner)
            if (playback.role != approval.role) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
            var catalogUnavailable = false
            val catalog = if ("select_track" in supported) {
                try {
                    api.tracks(p, g).also { current(ticket, owner) }
                } catch (cancel: CancellationException) { throw cancel }
                  catch (failure: Exception) {
                    current(ticket, owner)
                    if (failure is DesktopRemoteFailure && failure.kind in setOf(
                            DesktopRemoteFailure.Kind.AUTH, DesktopRemoteFailure.Kind.TRUST)) throw failure
                    catalogUnavailable = true
                    null
                }
            } else null
            val availableCommands = if (catalogUnavailable) supported - setOf("select_track", "select_group") else supported
            val id = commandId
            val outcome = if (id != null) api.commandStatus(p, g, id).also {
                if (it.id != id) throw DesktopRemoteFailure()
            }.status else mutable.value.outcome
            current(ticket, owner)
            if (outcome !in setOf("accepted", "pending")) commandId = null
            mutable.update {
                it.copy(
                    phase = "active", role = approval.role, commands = availableCommands,
                    mediaSupported = mediaAllowed, mediaMaxBytes = caps.mediaMaxBytes,
                    syncSupported = syncAllowed, uploadSupported = uploadAllowed,
                    playback = playback, playlist = catalog?.playlist, activeGroupId = catalog?.activeGroupId,
                    totalTracks = catalog?.totalTracks ?: 0, groups = catalog?.groups ?: emptyList(),
                    currentTrackId = catalog?.currentTrackId,
                    tracks = catalog?.tracks ?: emptyList(), fresh = true,
                    message = if (catalogUnavailable) "Katalog Desktop tidak tersedia. Refresh untuk mencoba lagi."
                        else if (!playback.online) "Pemutar Desktop belum siap." else null,
                    outcome = outcome,
                )
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (failure: Exception) {
            current(ticket, owner)
            if (failure is DesktopRemoteFailure && failure.kind != DesktopRemoteFailure.Kind.UNAVAILABLE) {
                disconnectWith("Sesi ditolak, kedaluwarsa, atau identitas/protokol Desktop berubah. Pair ulang di PC.")
            } else {
                mutable.update { it.copy(fresh = false, message = "Desktop tidak dapat dijangkau. Refresh hanya membaca status.") }
            }
        }
    }
    fun command(action: String) {
        val before = mutable.value
        if (!before.canControl || action !in before.commands || action in setOf("select_track", "select_group")) return
        dispatchControlAction(before) { p, g ->
            api.command(p, g, action, UUID.randomUUID().toString())
        }
    }

    fun selectTrack(trackId: String, autoPlay: Boolean) {
        val before = mutable.value
        if (!before.canSelectTrack || before.tracks.none { it.id == trackId }) return
        dispatchControlAction(before) { p, g ->
            api.selectTrack(p, g, trackId, autoPlay, UUID.randomUUID().toString())
        }
    }

    fun selectGroup(groupId: String) {
        val before = mutable.value
        if (!before.canSwitchGroup || before.groups.none { it.id == groupId }) return
        dispatchControlAction(before) { p, g ->
            api.selectGroup(p, g, groupId, UUID.randomUUID().toString())
        }
    }

    fun downloadTrack(trackId: String) {
        val importer = mediaImporter ?: return
        val snapshot = mutable.value
        val p = pairing ?: return
        val g = grant ?: return
        if (!snapshot.canDownloadMedia || snapshot.tracks.none { it.id == trackId }) return
        if (expiresAt <= now() || downloadJobs[trackId]?.isActive == true) return
        val ticket = generation
        val maxBytes = snapshot.mediaMaxBytes.takeIf { it > 0L } ?: (150L * 1024L * 1024L)
        setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(DesktopRemoteTrackDownload.Status.CHECKING))
        downloadJobs[trackId] = scope.launch {
            var tempFile: File? = null
            try {
                val head = api.headTrackMedia(p, g, trackId)
                if (generation != ticket) return@launch
                val exists = withContext(ioDispatcher) { importer.hasTrack(head.sha256, head.sizeBytes) }
                if (generation != ticket) return@launch
                if (exists) {
                    setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                        status = DesktopRemoteTrackDownload.Status.ALREADY_IN_LIBRARY,
                        downloadedBytes = head.sizeBytes,
                        totalBytes = head.sizeBytes,
                        detail = "Sudah ada di Library HP",
                    ))
                    return@launch
                }
                val hasSpace = withContext(ioDispatcher) { importer.hasEnoughStorage(head.sizeBytes) }
                if (generation != ticket) return@launch
                if (!hasSpace) {
                    setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                        status = DesktopRemoteTrackDownload.Status.FAILED,
                        detail = "Ruang penyimpanan HP tidak cukup untuk mengunduh lagu ini.",
                    ))
                    return@launch
                }
                setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                    status = DesktopRemoteTrackDownload.Status.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = head.sizeBytes,
                ))
                tempFile = withContext(ioDispatcher) { importer.createTempDownloadFile() }
                val staged = tempFile
                val verified = api.downloadTrackMedia(p, g, trackId, staged, maxBytes) { readBytes, totalBytes ->
                    setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                        status = DesktopRemoteTrackDownload.Status.DOWNLOADING,
                        downloadedBytes = readBytes,
                        totalBytes = totalBytes,
                    ))
                }
                if (generation != ticket) return@launch
                setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                    status = DesktopRemoteTrackDownload.Status.IMPORTING,
                    downloadedBytes = verified.sizeBytes,
                    totalBytes = verified.sizeBytes,
                ))
                val outcome = withContext(ioDispatcher) {
                    importer.importDownloadedAudio(staged, verified.filename, verified.sha256)
                }
                if (generation != ticket) return@launch
                val saved = outcome is DesktopRemoteMediaImportOutcome.Saved
                setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                    status = if (saved) DesktopRemoteTrackDownload.Status.SAVED else DesktopRemoteTrackDownload.Status.ALREADY_IN_LIBRARY,
                    downloadedBytes = verified.sizeBytes,
                    totalBytes = verified.sizeBytes,
                    detail = if (saved) "Tersimpan di Library HP" else "Sudah ada di Library HP",
                ))
            } catch (cancel: CancellationException) {
                if (generation == ticket) clearDownloadState(trackId)
                throw cancel
            } catch (failure: Exception) {
                if (generation == ticket) {
                    setDownloadState(ticket, trackId, DesktopRemoteTrackDownload(
                        status = DesktopRemoteTrackDownload.Status.FAILED,
                        detail = downloadFailureMessage(failure),
                    ))
                }
            } finally {
                downloadJobs.remove(trackId)
                tempFile?.let { file ->
                    withContext(NonCancellable + ioDispatcher) {
                        runCatching { if (file.exists()) file.delete() }
                    }
                }
            }
        }
    }

    fun cancelDownload(trackId: String) {
        downloadJobs.remove(trackId)?.cancel()
        clearDownloadState(trackId)
    }

    fun clearDownloadState(trackId: String) {
        mutable.update { prev ->
            if (trackId !in prev.downloads) prev
            else prev.copy(downloads = prev.downloads - trackId)
        }
    }

    private fun setDownloadState(ticket: Long, trackId: String, status: DesktopRemoteTrackDownload) {
        if (generation != ticket) return
        mutable.update { prev -> prev.copy(downloads = prev.downloads + (trackId to status)) }
    }

    fun loadSyncFolders() {
        val snapshot = mutable.value
        val p = pairing ?: return
        val g = grant ?: return
        if (!snapshot.canSyncFolders || expiresAt <= now()) return
        val ticket = generation
        mutable.update { it.copy(folderSync = it.folderSync.copy(loadingFolders = true, error = null)) }
        scope.launch {
            try {
                val res = api.syncFolders(p, g)
                if (generation != ticket) return@launch
                mutable.update {
                    it.copy(
                        folderSync = it.folderSync.copy(
                            loadingFolders = false,
                            folders = res.folders,
                            hostWarnings = res.warnings,
                            excludedWavFlacCount = res.excludedWavFlacCount,
                            oversizedCount = res.oversizedCount,
                            error = null,
                        ),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (generation == ticket) {
                    mutable.update {
                        it.copy(folderSync = it.folderSync.copy(loadingFolders = false, error = downloadFailureMessage(failure)))
                    }
                }
            }
        }
    }

    fun syncRemoteFolders(folderIds: List<String>) {
        val importer = mediaImporter ?: return
        val snapshot = mutable.value
        val p = pairing ?: return
        val g = grant ?: return
        val cleanIds = folderIds.distinct().filter { DesktopRemoteJson.isValidSyncFolderId(it) }
        if (!snapshot.canSyncFolders || cleanIds.isEmpty() || expiresAt <= now() || folderSyncJob?.isActive == true) return
        val ticket = generation
        val maxBytes = snapshot.mediaMaxBytes.takeIf { it > 0L } ?: (150L * 1024L * 1024L)
        mutable.update {
            it.copy(
                folderSync = it.folderSync.copy(
                    running = true,
                    currentFolderName = null,
                    currentFileName = "Mengambil manifest folder…",
                    completedFiles = 0,
                    totalFiles = 0,
                    downloadedCount = 0,
                    skippedDuplicateCount = 0,
                    failedCount = 0,
                    lastSummary = null,
                    error = null,
                ),
            )
        }
        folderSyncJob = scope.launch {
            try {
                data class QueuedSyncEntry(val folderLabel: String, val playlistName: String, val entry: DesktopRemoteSyncFile)
                val queue = ArrayList<QueuedSyncEntry>()
                var skippedWav = 0
                var skippedOversized = 0
                val manifestWarnings = LinkedHashSet<String>()

                for (fid in cleanIds) {
                    var offset = 0
                    while (true) {
                        val page = api.syncManifest(p, g, fid, offset = offset, limit = 100)
                        if (generation != ticket) return@launch
                        if (offset == 0) {
                            skippedWav += page.excludedWavFlacCount
                            skippedOversized += page.oversizedCount
                            manifestWarnings.addAll(page.warnings)
                        }
                        val targetPlaylist = safePlaylistNameFromFolder(page.relFolder, page.folderName)
                        for (file in page.files) {
                            queue.add(QueuedSyncEntry(page.folderName, targetPlaylist, file))
                        }
                        offset = page.nextOffset ?: break
                    }
                }

                if (queue.isEmpty()) {
                    val emptyMsg = if (skippedWav > 0) {
                        "$skippedWav file .wav/.flac di folder terpilih dilewati (WAV/FLAC belum didukung fase ini)."
                    } else {
                        "Tidak ada file audio terkompresi (.mp3/.m4a/.aac/.opus/.ogg/.webm) di folder terpilih."
                    }
                    mutable.update {
                        it.copy(
                            folderSync = it.folderSync.copy(
                                running = false,
                                currentFileName = null,
                                hostWarnings = manifestWarnings.toList(),
                                lastSummary = emptyMsg,
                            ),
                        )
                    }
                    return@launch
                }

                var downloaded = 0
                var duplicates = 0
                var failed = 0
                var done = 0
                var storageError: String? = null
                val total = queue.size

                mutable.update {
                    it.copy(
                        folderSync = it.folderSync.copy(
                            totalFiles = total,
                            hostWarnings = manifestWarnings.toList(),
                            excludedWavFlacCount = skippedWav,
                            oversizedCount = skippedOversized,
                        ),
                    )
                }

                for (item in queue) {
                    currentCoroutineContext().ensureActive()
                    if (generation != ticket) return@launch
                    mutable.update {
                        it.copy(
                            folderSync = it.folderSync.copy(
                                currentFolderName = item.playlistName,
                                currentFileName = item.entry.name,
                                completedFiles = done,
                            ),
                        )
                    }
                    var tempFile: File? = null
                    try {
                        val exists = withContext(ioDispatcher) {
                            importer.hasTrack(item.entry.sha256, item.entry.sizeBytes)
                        }
                        if (exists) {
                            duplicates++
                        } else {
                            val hasSpace = withContext(ioDispatcher) { importer.hasEnoughStorage(item.entry.sizeBytes) }
                            if (!hasSpace) {
                                storageError = "Ruang penyimpanan HP penuh saat menyinkronkan ${item.entry.name}."
                                failed++
                                break
                            }
                            tempFile = withContext(ioDispatcher) { importer.createTempDownloadFile() }
                            val staged = tempFile
                            val verified = api.downloadSyncFile(p, g, item.entry.fileId, staged, maxBytes)
                            if (generation != ticket) return@launch
                            val outcome = withContext(ioDispatcher) {
                                importer.importDownloadedAudio(
                                    stagedFile = staged,
                                    requestedName = verified.filename,
                                    expectedSha256 = verified.sha256,
                                    playlistName = item.playlistName,
                                )
                            }
                            if (outcome is DesktopRemoteMediaImportOutcome.Saved) downloaded++ else duplicates++
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed++
                    } finally {
                        tempFile?.let { file ->
                            withContext(NonCancellable + ioDispatcher) {
                                runCatching { if (file.exists()) file.delete() }
                            }
                        }
                    }
                    done++
                    mutable.update {
                        it.copy(
                            folderSync = it.folderSync.copy(
                                completedFiles = done,
                                downloadedCount = downloaded,
                                skippedDuplicateCount = duplicates,
                                failedCount = failed,
                            ),
                        )
                    }
                }

                if (generation == ticket) {
                    val extras = buildList {
                        if (duplicates > 0) add("$duplicates duplikat dilewati")
                        if (skippedWav > 0) add("$skippedWav WAV/FLAC dilewati")
                        if (skippedOversized > 0) add("$skippedOversized >150 MB dilewati")
                        if (failed > 0) add("$failed gagal")
                    }
                    val suffix = if (extras.isNotEmpty()) " (${extras.joinToString(", ")})" else ""
                    mutable.update {
                        it.copy(
                            folderSync = it.folderSync.copy(
                                running = false,
                                currentFolderName = null,
                                currentFileName = null,
                                error = storageError,
                                lastSummary = "$downloaded lagu tersimpan ke playlist Library HP$suffix.",
                            ),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                if (generation == ticket) {
                    mutable.update {
                        it.copy(folderSync = it.folderSync.copy(running = false, currentFileName = null))
                    }
                }
                throw cancel
            } catch (failure: Exception) {
                if (generation == ticket) {
                    mutable.update {
                        it.copy(
                            folderSync = it.folderSync.copy(
                                running = false,
                                currentFileName = null,
                                error = downloadFailureMessage(failure),
                            ),
                        )
                    }
                }
            } finally {
                folderSyncJob = null
            }
        }
    }

    fun cancelFolderSync() {
        folderSyncJob?.cancel()
        folderSyncJob = null
    }

    fun uploadTracksToDesktop(trackIds: List<Long>, targetPlaylistName: String?) {
        val uploader = mediaUploader ?: return
        val snapshot = mutable.value
        val p = pairing ?: return
        val g = grant ?: return
        val cleanIds = trackIds.distinct()
        if (!snapshot.canUploadToDesktop || cleanIds.isEmpty() || expiresAt <= now() || batchUploadJob?.isActive == true) return
        val ticket = generation
        val maxBytes = snapshot.mediaMaxBytes.takeIf { it > 0L } ?: (150L * 1024L * 1024L)
        val byId = snapshot.localTracks.associateBy { it.trackId }
        val selected = cleanIds.mapNotNull { byId[it] }
        if (selected.isEmpty()) return

        var wavSkipped = 0
        var overSkipped = 0
        val warnList = ArrayList<String>()
        val validTracks = ArrayList<DesktopRemoteLocalTrackSummary>()
        for (item in selected) {
            when {
                item.isWavOrFlac -> {
                    wavSkipped++
                    if (warnList.size < 6) {
                        warnList.add("${item.displayName}: format WAV/FLAC dilewati untuk fase ini.")
                    }
                }
                !item.isSupportedCompressedAudio -> {
                    if (warnList.size < 6) warnList.add("${item.displayName}: format tidak didukung.")
                }
                item.sizeBytes > maxBytes -> {
                    overSkipped++
                    if (warnList.size < 6) warnList.add("${item.displayName}: ukuran melebihi 150 MB.")
                }
                else -> validTracks.add(item)
            }
        }
        if (wavSkipped > 6) warnList.add("Total $wavSkipped file .wav/.flac dilewati.")

        if (validTracks.isEmpty()) {
            val summaryMsg = if (wavSkipped > 0) {
                "$wavSkipped file .wav/.flac dilewati — pilih file MP3/M4A/AAC/OPUS/OGG/WEBM untuk diunggah ke PC."
            } else {
                "Tidak ada file audio terkompresi valid untuk diunggah."
            }
            mutable.update {
                it.copy(
                    batchUpload = DesktopRemoteBatchUploadState(
                        wavFlacSkippedCount = wavSkipped,
                        oversizedSkippedCount = overSkipped,
                        warnings = warnList,
                        lastSummary = summaryMsg,
                    ),
                )
            }
            return
        }

        val sanitizedPlaylist = sanitizeTargetPlaylistName(targetPlaylistName)
        mutable.update {
            it.copy(
                batchUpload = DesktopRemoteBatchUploadState(
                    running = true,
                    totalFiles = validTracks.size,
                    wavFlacSkippedCount = wavSkipped,
                    oversizedSkippedCount = overSkipped,
                    warnings = warnList,
                ),
            )
        }

        batchUploadJob = scope.launch {
            var saved = 0
            var dedup = 0
            var renamed = 0
            var failed = 0
            var done = 0
            var lastErr: String? = null
            try {
                for (item in validTracks) {
                    currentCoroutineContext().ensureActive()
                    if (generation != ticket) return@launch
                    mutable.update {
                        it.copy(
                            batchUpload = it.batchUpload.copy(
                                currentFileName = item.displayName,
                                currentFileSentBytes = 0L,
                                currentFileTotalBytes = item.sizeBytes,
                            ),
                        )
                    }
                    try {
                        val opened = withContext(ioDispatcher) { uploader.openUploadStream(item.trackId) }
                        if (opened == null) {
                            failed++
                        } else {
                            val res = api.uploadLibraryTrack(
                                pairing = p,
                                grant = g,
                                filename = opened.filename,
                                sha256 = opened.sha256,
                                sizeBytes = opened.sizeBytes,
                                playlistName = sanitizedPlaylist,
                                inputStream = opened.inputStream,
                                onProgress = { sentBytes, totalBytes ->
                                    if (generation == ticket) {
                                        mutable.update { prev ->
                                            prev.copy(
                                                batchUpload = prev.batchUpload.copy(
                                                    currentFileSentBytes = sentBytes,
                                                    currentFileTotalBytes = totalBytes,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                            if (res.deduplicated) dedup++ else {
                                saved++
                                if (res.renamed) renamed++
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        failed++
                        lastErr = downloadFailureMessage(failure)
                    }
                    done++
                    mutable.update {
                        it.copy(
                            batchUpload = it.batchUpload.copy(
                                completedFiles = done,
                                savedCount = saved,
                                deduplicatedCount = dedup,
                                renamedCount = renamed,
                                failedCount = failed,
                            ),
                        )
                    }
                }
                if (generation == ticket) {
                    val extras = buildList {
                        if (dedup > 0) add("$dedup duplikat dilewati")
                        if (renamed > 0) add("$renamed di-rename otomatis")
                        if (wavSkipped > 0) add("$wavSkipped WAV/FLAC dilewati")
                        if (overSkipped > 0) add("$overSkipped >150 MB dilewati")
                        if (failed > 0) add("$failed gagal")
                    }
                    val suffix = if (extras.isNotEmpty()) " (${extras.joinToString(", ")})" else ""
                    mutable.update {
                        it.copy(
                            batchUpload = it.batchUpload.copy(
                                running = false,
                                currentFileName = null,
                                error = lastErr,
                                lastSummary = "$saved lagu diunggah ke PC$suffix.",
                            ),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                if (generation == ticket) {
                    mutable.update { it.copy(batchUpload = it.batchUpload.copy(running = false, currentFileName = null)) }
                }
                throw cancel
            } finally {
                batchUploadJob = null
            }
        }
    }

    fun cancelDesktopUpload() {
        batchUploadJob?.cancel()
        batchUploadJob = null
    }

    private fun safePlaylistNameFromFolder(relFolder: String, fallbackName: String): String {
        val raw = relFolder.trim().substringAfterLast('/').ifBlank {
            fallbackName.removePrefix("Root (").removeSuffix(")").trim().ifBlank { "Sync PC" }
        }
        return sanitizeTargetPlaylistName(raw) ?: "Sync PC"
    }

    private fun sanitizeTargetPlaylistName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw.replace('\\', ' ').replace('/', ' ')) {
                if (!ch.isISOControl()) append(ch)
            }
        }.trim()
        if (cleaned.isEmpty()) return null
        var limit = cleaned.length
        while (limit > 0 && cleaned.substring(0, limit).toByteArray(Charsets.UTF_8).size > 60) {
            limit--
        }
        return cleaned.substring(0, limit).trim().takeIf { it.isNotEmpty() }
    }

    private fun pairFailureMessage(failure: Exception): String = when ((failure as? DesktopRemoteFailure)?.kind) {
        DesktopRemoteFailure.Kind.TRUST ->
            "Identitas TLS atau jam perangkat target tidak cocok. Periksa QR & waktu perangkat."
        DesktopRemoteFailure.Kind.AUTH ->
            "Kode pairing tidak valid, sudah dipakai, atau kedaluwarsa. Buat kode baru di target."
        DesktopRemoteFailure.Kind.UNAVAILABLE ->
            "Perangkat target tidak dapat dijangkau. Pastikan satu Wi-Fi/Hotspot & Firewall/Bridge PC aktif."
        else ->
            "Pairing gagal atau hasil tidak diketahui. Buat kode baru di perangkat target; tidak dicoba ulang."
    }

    private fun downloadFailureMessage(failure: Exception): String = when (failure) {
        is DesktopRemoteFailure -> when (failure.reason) {
            DesktopRemoteFailure.Reason.MEDIA_DISABLED -> "Fitur unduh dimatikan di perangkat target."
            DesktopRemoteFailure.Reason.SYNC_DISABLED -> "Sinkronisasi folder dimatikan di perangkat target."
            DesktopRemoteFailure.Reason.UPLOAD_DISABLED -> "Fitur unggah ke PC belum diaktifkan di modal Android Remote PC."
            DesktopRemoteFailure.Reason.WAV_FLAC_EXCLUDED -> "Format WAV/FLAC sementara dilewati pada fase transfer ini."
            DesktopRemoteFailure.Reason.SHA256_MISMATCH -> "Checksum SHA-256 tidak cocok saat transfer."
            DesktopRemoteFailure.Reason.MEDIA_VIEW_ONLY -> "Mode Pantau Saja tidak bisa mentransfer audio."
            DesktopRemoteFailure.Reason.MEDIA_BUSY -> "Antrean transfer perangkat target penuh. Coba sebentar lagi."
            DesktopRemoteFailure.Reason.MEDIA_TOO_LARGE -> "Ukuran file melebihi batas 150 MB."
            DesktopRemoteFailure.Reason.MEDIA_UNSUPPORTED -> "Format audio tidak didukung di perangkat target."
            DesktopRemoteFailure.Reason.MEDIA_UNAVAILABLE -> "File atau folder tidak tersedia di perangkat target."
            DesktopRemoteFailure.Reason.NONE -> when (failure.kind) {
                DesktopRemoteFailure.Kind.AUTH -> "Akses transfer ditolak perangkat target."
                DesktopRemoteFailure.Kind.TRUST -> "Identitas TLS perangkat target tidak cocok."
                DesktopRemoteFailure.Kind.UNAVAILABLE -> "Koneksi terputus saat mentransfer audio."
                else -> "Transfer audio gagal diverifikasi."
            }
        }
        else -> "Gagal mentransfer audio."
    }

    private fun dispatchControlAction(
        before: DesktopRemoteUiState,
        send: suspend (DesktopRemotePairing, DesktopRemoteGrant) -> DesktopRemoteCommand,
    ) {
        val owner = visibility ?: return
        val p = pairing ?: return
        val g = grant ?: return
        if (expiresAt <= now()) return
        val ticket = generation
        mutable.update { before.copy(busy = true, downloads = it.downloads) }
        mutation = scope.launch {
            var dispatched = false
            try {
                serial.withLock {
                    current(ticket, owner)
                    val approval = api.session(p, g)
                    current(ticket, owner)
                    if (approval.deviceId != g.deviceId || approval.status != "active" || approval.role != "control" || approval.expiresAt <= now()) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
                    dispatched = true
                    val result = send(p, g)
                    currentCoroutineContext().ensureActive()
                    if (generation != ticket) return@withLock
                    commandId = result.id
                    mutable.update { it.copy(outcome = result.status) }
                }
            } catch (cancel: CancellationException) {
                if (generation == ticket && dispatched) mutable.update { it.copy(outcome = "unknown", fresh = false) }
                throw cancel
            } catch (failure: Exception) {
                if (generation == ticket) {
                    commandId = null
                    mutable.update {
                        it.copy(
                            outcome = if (dispatched) "unknown" else it.outcome,
                            fresh = false,
                            message = "Hasil perintah tidak diketahui atau ditolak. Tidak dikirim ulang otomatis.",
                        )
                    }
                    if (failure is DesktopRemoteFailure && failure.kind in setOf(DesktopRemoteFailure.Kind.AUTH, DesktopRemoteFailure.Kind.TRUST, DesktopRemoteFailure.Kind.PROTOCOL)) {
                        val outcome = mutable.value.outcome
                        disconnectWith("Sesi/perintah ditolak. Pair ulang dengan perangkat target.")
                        mutable.update { it.copy(outcome = outcome) }
                    }
                }
            } finally {
                if (generation == ticket) { mutation = null; mutable.update { it.copy(busy = false) } }
            }
        }
    }
}
