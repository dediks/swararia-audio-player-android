package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class DesktopRemoteUiState(
    val endpoint: String? = null, val phase: String = "disconnected", val role: String? = null,
    val playback: DesktopRemotePlayback? = null, val commands: Set<String> = emptySet(),
    val playlist: String? = null, val activeGroupId: String? = null, val totalTracks: Int = 0,
    val groups: List<DesktopRemoteTrackGroup> = emptyList(),
    val currentTrackId: String? = null,
    val tracks: List<DesktopRemoteTrack> = emptyList(),
    val busy: Boolean = false, val fresh: Boolean = false, val message: String? = null,
    val outcome: String? = null,
) {
    val canControl: Boolean get() = phase == "active" && role == "control" && playback?.online == true && fresh && !busy && outcome !in setOf("accepted", "pending")
    val canFadeOut: Boolean get() = canControl && "fade_out" in commands && playback?.playing == true && playback?.transport != "FADING"
    val canSelectTrack: Boolean get() = canControl && "select_track" in commands
    val canSwitchGroup: Boolean get() = canControl && "select_group" in commands
    val canStepTrack: Boolean get() = canControl && playback?.title != null
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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistence: DesktopRemotePersistence? = store?.let { DesktopRemotePersistence(it, ioDispatcher) },
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

    init {
        restore()
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
            mutable.value = DesktopRemoteUiState(
                endpoint = savedPairing.endpoint,
                phase = "active",
                message = "Menyambung ulang ke Desktop…",
            )
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
        mutable.value = mutable.value.copy(fresh = false)
        startPolling()
    }
    fun hidden(owner: Any) {
        if (visibility !== owner) return
        visibility = null
        poll?.cancel(); poll = null
        mutable.value = mutable.value.copy(fresh = false)
    }
    fun connect(input: String) {
        if (mutable.value.busy || grant != null || visibility == null) return
        val parsed = try { DesktopRemotePairing.parse(input) } catch (_: DesktopRemoteFailure) {
            mutable.value = mutable.value.copy(message = "Kode pairing tidak valid."); return
        }
        val ticket = ++generation
        mutable.value = DesktopRemoteUiState(endpoint = parsed.endpoint, phase = "connecting", busy = true)
        mutation = scope.launch {
            try {
                serial.withLock {
                    val issued = api.pair(parsed)
                    currentCoroutineContext().ensureActive()
                    if (generation != ticket) return@withLock
                    parsed.forgetInvitation()
                    pairing = parsed; grant = issued
                    mutable.value = DesktopRemoteUiState(endpoint = parsed.endpoint, phase = "pending",
                        message = "Setujui perangkat ini di PC.")
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { if (generation == ticket) disconnectWith("Pairing gagal atau hasil tidak diketahui. Buat invite baru di PC; tidak dicoba ulang.") }
            finally {
                parsed.forgetInvitation()
                if (generation == ticket) {
                    mutation = null
                    mutable.value = mutable.value.copy(busy = false)
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

    private fun clearMemoryState(message: String?) {
        generation++
        poll?.cancel(); poll = null
        mutation?.cancel(); mutation = null
        pairing?.forgetInvitation(); pairing = null; grant = null; commandId = null
        expiresAt = 0.0; persistedKey = null
        mutable.value = DesktopRemoteUiState(message = message)
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
            disconnectWith("Sesi kedaluwarsa. Pair ulang di PC.")
            return
        }
        val ticket = generation
        poll = scope.launch {
            while (isActive && visibility === owner && generation == ticket) {
                if (expiresAt > 0.0 && expiresAt <= now()) {
                    disconnectWith("Sesi kedaluwarsa. Pair ulang di PC.")
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
                if (persistedKey != null) {
                    persistedKey = null
                    clearStore()
                }
                mutable.value = mutable.value.copy(phase = "pending", role = null, playback = null,
                    commands = emptySet(), playlist = null, activeGroupId = null, totalTracks = 0,
                    groups = emptyList(), currentTrackId = null, tracks = emptyList(),
                    fresh = false, message = "Setujui perangkat ini di PC.")
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
            val supported = api.capabilities(p, g)
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
            mutable.value = mutable.value.copy(phase = "active", role = approval.role, commands = availableCommands,
                playback = playback, playlist = catalog?.playlist, activeGroupId = catalog?.activeGroupId,
                totalTracks = catalog?.totalTracks ?: 0, groups = catalog?.groups ?: emptyList(),
                currentTrackId = catalog?.currentTrackId,
                tracks = catalog?.tracks ?: emptyList(), fresh = true,
                message = if (catalogUnavailable) "Katalog Desktop tidak tersedia. Refresh untuk mencoba lagi."
                    else if (!playback.online) "Pemutar Desktop belum siap." else null, outcome = outcome)
        } catch (cancel: CancellationException) { throw cancel }
          catch (failure: Exception) {
            current(ticket, owner)
            if (failure is DesktopRemoteFailure && failure.kind != DesktopRemoteFailure.Kind.UNAVAILABLE) {
                disconnectWith("Sesi ditolak, kedaluwarsa, atau identitas/protokol Desktop berubah. Pair ulang di PC.")
            } else {
                mutable.value = mutable.value.copy(fresh = false, message = "Desktop tidak dapat dijangkau. Refresh hanya membaca status.")
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

    private fun dispatchControlAction(
        before: DesktopRemoteUiState,
        send: suspend (DesktopRemotePairing, DesktopRemoteGrant) -> DesktopRemoteCommand,
    ) {
        val owner = visibility ?: return
        val p = pairing ?: return
        val g = grant ?: return
        if (expiresAt <= now()) return
        val ticket = generation
        mutable.value = before.copy(busy = true)
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
                    mutable.value = mutable.value.copy(outcome = result.status)
                }
            } catch (cancel: CancellationException) {
                if (generation == ticket && dispatched) mutable.value = mutable.value.copy(outcome = "unknown", fresh = false)
                throw cancel
            } catch (failure: Exception) {
                if (generation == ticket) {
                    commandId = null
                    mutable.value = mutable.value.copy(outcome = if (dispatched) "unknown" else mutable.value.outcome,
                        fresh = false, message = "Hasil perintah tidak diketahui atau ditolak. Tidak dikirim ulang otomatis.")
                    if (failure is DesktopRemoteFailure && failure.kind in setOf(DesktopRemoteFailure.Kind.AUTH, DesktopRemoteFailure.Kind.TRUST, DesktopRemoteFailure.Kind.PROTOCOL)) {
                        val outcome = mutable.value.outcome
                        disconnectWith("Sesi/perintah ditolak. Pair ulang di PC.")
                        mutable.value = mutable.value.copy(outcome = outcome)
                    }
                }
            } finally {
                if (generation == ticket) { mutation = null; mutable.value = mutable.value.copy(busy = false) }
            }
        }
    }
}
