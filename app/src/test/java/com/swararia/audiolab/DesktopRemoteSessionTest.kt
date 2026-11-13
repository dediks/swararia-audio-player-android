package com.swararia.audiolab

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DesktopRemoteSessionTest {
    private val pairing = "swararia1|192.168.1.2:8766|" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) + "|" + "i".repeat(43)
    private class Port : DesktopRemoteApi {
        var role: String? = null
        var revoked = false
        var trustChanged = false
        var unavailable = false
        var losePost = false
        var pairs = 0
        var posts = 0
        var reads = 0
        var expiry = 4e9
        var pairGate: CompletableDeferred<Unit>? = null
        override suspend fun pair(pairing: DesktopRemotePairing): DesktopRemoteGrant {
            pairs++
            pairGate?.let { withContext(NonCancellable) { it.await() } }
            return DesktopRemoteGrant("d".repeat(24), DesktopRemoteSecret("t".repeat(43)))
        }
        override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteApproval {
            reads++
            if (trustChanged) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.TRUST)
            if (unavailable) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.UNAVAILABLE)
            if (revoked) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
            return DesktopRemoteApproval(grant.deviceId, if (role == null) "pending" else "active", role, expiry)
        }
        override suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = setOf("play", "pause", "stop")
        override suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = DesktopRemotePlayback(role!!, true, false, "PAUSED", 0.0, 10.0, "Track")
        override suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String): DesktopRemoteCommand {
            posts++; if (losePost) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.UNAVAILABLE)
            return DesktopRemoteCommand("c".repeat(24), "accepted")
        }
        override suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String) = DesktopRemoteCommand(id, "applied")
    }
    private suspend fun settle() { repeat(8) { yield() } }
    @Test fun pendingApprovalViewOnlyAndRevocationFailClosed() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val port = Port(); val owner = DesktopRemoteSession(port, scope, pollMillis = 100_000)
            owner.visible(Any()); owner.connect(pairing); settle()
            assertEquals("pending", owner.state.value.phase)
            owner.command("play"); settle(); assertEquals(0, port.posts)
            port.role = "view_only"; owner.refresh(); settle()
            assertEquals("view_only", owner.state.value.role)
            owner.command("play"); settle(); assertEquals(0, port.posts)
            port.role = "control"; owner.refresh(); settle(); assertTrue(owner.state.value.canControl)
            port.revoked = true; owner.refresh(); settle()
            assertEquals("disconnected", owner.state.value.phase); assertFalse(owner.state.value.canControl)
        } finally { scope.cancel() }
    }
    @Test fun lostPostIsUnknownNeverReplayedAndDisconnectNeverStops() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val port = Port().apply { role = "control"; losePost = true }
            val owner = DesktopRemoteSession(port, scope, pollMillis = 100_000)
            owner.visible(Any()); owner.connect(pairing); settle()
            owner.command("play"); owner.command("play"); settle()
            assertEquals(1, port.posts); assertEquals("unknown", owner.state.value.outcome)
            owner.refresh(); settle(); assertEquals(1, port.posts)
            owner.disconnect(); assertEquals(1, port.posts)
            assertEquals("disconnected", owner.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun visibilityLeaseAndStalePairCannotResurrectDisconnectedSession() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val port = Port(); val owner = DesktopRemoteSession(port, scope, pollMillis = 10)
            val old = Any(); val fresh = Any()
            owner.visible(old); owner.connect(pairing); settle()
            owner.visible(fresh); owner.hidden(old); settle()
            val before = port.reads; delay(30); assertTrue(port.reads > before)
            owner.hidden(fresh); val paused = port.reads; delay(30); assertEquals(paused, port.reads)
            owner.disconnect(); port.pairGate = CompletableDeferred()
            owner.visible(fresh); owner.connect(pairing); settle(); owner.disconnect()
            port.pairGate!!.complete(Unit); settle()
            assertEquals("disconnected", owner.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun acceptedIsNotAppliedUntilGetAcknowledges() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val port = Port().apply { role = "control" }
            val owner = DesktopRemoteSession(port, scope, pollMillis = 100_000)
            owner.visible(Any()); owner.connect(pairing); settle()
            owner.command("pause"); settle(); assertEquals("accepted", owner.state.value.outcome)
            assertFalse(owner.state.value.canControl)
            owner.command("pause"); settle(); assertEquals(1, port.posts)
            owner.refresh(); settle(); assertEquals("applied", owner.state.value.outcome)
            assertEquals(1, port.posts)
        } finally { scope.cancel() }
    }

    private class MemorySessionStore : DesktopRemoteSessionStore {
        var encoded: ByteArray? = null
        var loads = 0
        var saves = 0
        var clears = 0

        override fun load(): PersistedDesktopRemoteSession? {
            loads++
            val raw = encoded ?: return null
            return runCatching { DesktopRemoteJson.decodePersistedSession(raw) }.getOrElse {
                encoded = null
                null
            }
        }

        override fun save(session: PersistedDesktopRemoteSession) {
            saves++
            encoded = DesktopRemoteJson.encodePersistedSession(session)
        }

        override fun clear() {
            clears++
            encoded = null
        }
    }

    @Test fun expiredGrantIsForgottenWithoutSendingAnyTransportAction() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val time = doubleArrayOf(1000.0)
            val port = Port().apply { role = "control"; expiry = 1001.0 }
            val owner = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] })
            owner.visible(Any()); owner.connect(pairing); settle()
            assertTrue(owner.state.value.canControl)
            time[0] = 1002.0
            owner.command("play"); settle()
            assertEquals(0, port.posts)
            owner.refresh(); settle()
            assertEquals("disconnected", owner.state.value.phase)
            assertFalse(owner.state.value.canControl)
            assertEquals(0, port.posts)
        } finally { scope.cancel() }
    }

    @Test fun autoReconnectsAfterReleaseWithoutRepairingAndStaysLockedUntilLiveConfirm() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val time = doubleArrayOf(1000.0)
            val store = MemorySessionStore()
            val port = Port()
            val first = DesktopRemoteSession(
                port,
                scope,
                pollMillis = 100_000,
                now = { time[0] },
                store = store,
                ioDispatcher = Dispatchers.Unconfined,
            )
            first.visible(Any()); first.connect(pairing); settle()
            assertEquals("pending", first.state.value.phase)
            assertEquals(0, store.saves)

            port.role = "control"
            first.refresh(); settle()
            assertTrue(first.state.value.canControl)
            assertEquals(1, store.saves)
            assertNotNull(store.encoded)

            // App closed / ViewModel cleared: RAM dropped, encrypted store preserved.
            first.release()
            assertEquals(0, store.clears)
            assertEquals(1, port.pairs)

            // Simulate PC temporarily offline at reopen: controls stay locked while reconnecting, store preserved.
            port.unavailable = true
            val offlineReopen = DesktopRemoteSession(
                port,
                scope,
                pollMillis = 100_000,
                now = { time[0] },
                store = store,
                ioDispatcher = Dispatchers.Unconfined,
            )
            settle()
            assertEquals("active", offlineReopen.state.value.phase)
            assertNull(offlineReopen.state.value.role)
            assertFalse(offlineReopen.state.value.fresh)
            assertFalse(offlineReopen.state.value.canControl)

            offlineReopen.visible(Any()); settle()
            assertFalse(offlineReopen.state.value.canControl)
            assertFalse(offlineReopen.state.value.fresh)
            assertEquals(0, store.clears)
            assertEquals(1, port.pairs)

            // PC comes back online: refresh confirms identity/role and restores controls without re-pairing.
            port.unavailable = false
            offlineReopen.refresh(); settle()
            assertTrue(offlineReopen.state.value.canControl)
            assertEquals("control", offlineReopen.state.value.role)
            assertEquals(1, port.pairs)
            assertEquals(1, store.saves)
        } finally { scope.cancel() }
    }

    @Test fun disconnectRevocationTrustMismatchAndExpiryClearPersistedStore() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val time = doubleArrayOf(1000.0)
            val store = MemorySessionStore()
            val port = Port().apply { role = "control"; expiry = 2000.0 }

            // 1. Explicit disconnect wipes store.
            val first = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            first.visible(Any()); first.connect(pairing); settle()
            assertEquals(1, store.saves)
            first.disconnect(); settle()
            assertEquals(1, store.clears)
            assertNull(store.encoded)

            // Re-pair for next scenarios.
            first.connect(pairing); settle()
            assertNotNull(store.encoded)
            first.release()

            // 2. Revoked grant clears store on reopen.
            port.revoked = true
            val revokedReopen = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            revokedReopen.visible(Any()); settle()
            assertEquals("disconnected", revokedReopen.state.value.phase)
            assertNull(store.encoded)

            // 3. Re-pair, then PC restarts with new cert (TRUST mismatch) -> clears store on reopen.
            port.revoked = false
            val rePaired = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            rePaired.visible(Any()); rePaired.connect(pairing); settle()
            assertNotNull(store.encoded)
            rePaired.release()

            port.trustChanged = true
            val trustReopen = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            trustReopen.visible(Any()); settle()
            assertEquals("disconnected", trustReopen.state.value.phase)
            assertNull(store.encoded)

            // 4. Re-pair, then clock expires while app closed -> cleared immediately on reopen without network reads.
            port.trustChanged = false
            val beforeExpiry = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            beforeExpiry.visible(Any()); beforeExpiry.connect(pairing); settle()
            assertNotNull(store.encoded)
            beforeExpiry.release()

            val readsBeforeExpiredReopen = port.reads
            time[0] = 2001.0
            val expiredReopen = DesktopRemoteSession(port, scope, pollMillis = 100_000, now = { time[0] }, store = store, ioDispatcher = Dispatchers.Unconfined)
            expiredReopen.visible(Any()); settle()
            assertEquals("disconnected", expiredReopen.state.value.phase)
            assertNull(store.encoded)
            assertEquals(readsBeforeExpiredReopen, port.reads)
        } finally { scope.cancel() }
    }
}
