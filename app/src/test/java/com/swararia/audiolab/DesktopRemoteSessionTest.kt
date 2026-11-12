package com.swararia.audiolab

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DesktopRemoteSessionTest {
    private val pairing = "swararia1|192.168.1.2:8766|" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) + "|" + "i".repeat(43)
    private class Port : DesktopRemoteApi {
        var role: String? = null
        var revoked = false
        var losePost = false
        var posts = 0
        var reads = 0
        var expiry = 4e9
        var pairGate: CompletableDeferred<Unit>? = null
        override suspend fun pair(pairing: DesktopRemotePairing): DesktopRemoteGrant {
            pairGate?.let { withContext(NonCancellable) { it.await() } }
            return DesktopRemoteGrant("d".repeat(24), DesktopRemoteSecret("t".repeat(43)))
        }
        override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteApproval {
            reads++
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
}
