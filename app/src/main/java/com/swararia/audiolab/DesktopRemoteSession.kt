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
    val busy: Boolean = false, val fresh: Boolean = false, val message: String? = null,
    val outcome: String? = null,
) {
    val canControl: Boolean get() = phase == "active" && role == "control" && playback?.online == true && fresh && !busy && outcome !in setOf("accepted", "pending")
    val canFadeOut: Boolean get() = canControl && "fade_out" in commands && playback?.playing == true && playback?.transport != "FADING"
}

/** Android-free RAM owner. Like SearchSession, invoke on the caller's single UI thread.
 * Cancellation and disconnect forget credentials, never send Stop or replay a POST.
 */
class DesktopRemoteSession(private val api: DesktopRemoteApi, private val scope: CoroutineScope,
    private val pollMillis: Long = 1500, private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val mutable = MutableStateFlow(DesktopRemoteUiState())
    val state = mutable.asStateFlow()
    private val serial = Mutex()
    private var generation = 0L
    private var pairing: DesktopRemotePairing? = null
    private var grant: DesktopRemoteGrant? = null
    private var expiresAt = 0.0
    private var visibility: Any? = null
    private var poll: Job? = null
    private var mutation: Job? = null
    private var commandId: String? = null

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
    fun disconnect() = disconnectWith(null)
    private fun disconnectWith(message: String?) {
        generation++
        poll?.cancel(); poll = null
        mutation?.cancel(); mutation = null
        pairing?.forgetInvitation(); pairing = null; grant = null; commandId = null; expiresAt = 0.0
        mutable.value = DesktopRemoteUiState(message = message)
    }
    private fun startPolling() {
        val owner = visibility ?: return
        val p = pairing ?: return
        val g = grant ?: return
        if (poll != null) return
        val ticket = generation
        poll = scope.launch {
            while (isActive && visibility === owner && generation == ticket) {
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
                mutable.value = mutable.value.copy(phase = "pending", role = null, playback = null,
                    commands = emptySet(), fresh = false, message = "Setujui perangkat ini di PC.")
                return
            }
            if (approval.status != "active" || approval.role !in setOf("control", "view_only")) throw DesktopRemoteFailure()
            val supported = api.capabilities(p, g)
            current(ticket, owner)
            val playback = api.state(p, g)
            current(ticket, owner)
            if (playback.role != approval.role) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
            val id = commandId
            val outcome = if (id != null) api.commandStatus(p, g, id).also {
                if (it.id != id) throw DesktopRemoteFailure()
            }.status else mutable.value.outcome
            current(ticket, owner)
            if (outcome !in setOf("accepted", "pending")) commandId = null
            mutable.value = mutable.value.copy(phase = "active", role = approval.role, commands = supported,
                playback = playback, fresh = true, message = if (!playback.online) "Pemutar Desktop belum siap." else null,
                outcome = outcome)
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
        val owner = visibility ?: return
        val p = pairing ?: return
        val g = grant ?: return
        if (!before.canControl || action !in before.commands || expiresAt <= now()) return
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
                    val result = api.command(p, g, action, UUID.randomUUID().toString())
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
