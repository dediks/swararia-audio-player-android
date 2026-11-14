package com.swararia.audiolab

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import org.junit.Test
import org.junit.Assert.*

class DesktopRemotePersistenceTest {
    private class QueuedIO : CoroutineDispatcher() {
        val pending = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun first() { pending.removeAt(0).run() }
        fun last() { pending.removeAt(pending.lastIndex).run() }
    }
    private class Store : DesktopRemoteSessionStore {
        var saved: PersistedDesktopRemoteSession? = null
        var onSave: (() -> Unit)? = null
        var failSave = false
        var failClear = false
        override fun load() = saved
        override fun save(session: PersistedDesktopRemoteSession) {
            onSave?.invoke()
            if (failSave) throw java.io.IOException("fixture save failure")
            saved = session
        }
        override fun clear() {
            if (failClear) throw java.io.IOException("fixture clear failure")
            saved = null
        }
    }
    private class Api : DesktopRemoteApi {
        override suspend fun pair(pairing: DesktopRemotePairing) = DesktopRemoteGrant("d".repeat(24), DesktopRemoteSecret("t".repeat(43)))
        override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = DesktopRemoteApproval(grant.deviceId, "active", "control", 4e9)
        override suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = setOf("play")
        override suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = DesktopRemotePlayback("control", true, false, "PAUSED", 0.0, 1.0, "Track")
        override suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String) = DesktopRemoteCommand("c".repeat(24), "accepted")
        override suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String) = DesktopRemoteCommand(id, "applied")
    }
    private val pairing = "swararia1|192.168.1.2:8766|" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) + "|" + "i".repeat(43)
    private suspend fun settle() { repeat(12) { yield() } }

    @Test fun disconnectThenOwnerShutdownMustNotRestoreForgottenCredential() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val io = QueuedIO(); val store = Store()
        val session = DesktopRemoteSession(Api(), scope, 100_000, store=store, ioDispatcher=io)
        settle(); io.first(); settle() // restore empty
        session.visible(Any()); session.connect(pairing); settle()
        io.first(); settle() // save active session
        assertNotNull(store.saved)
        session.disconnect()
        session.release(); scope.cancel()
        while(io.pending.isNotEmpty()) io.first()
        assertNull("Explicit disconnect was cancelled with ViewModel scope; credential survives", store.saved)
    }
    @Test fun delayedOldClearMustNotEraseNewApprovedSession() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val io = QueuedIO(); val store = Store()
            val session = DesktopRemoteSession(Api(), scope, 100_000, store=store, ioDispatcher=io)
            settle(); io.first(); settle()
            session.visible(Any()); session.connect(pairing); settle(); io.first(); settle()
            session.disconnect() // clear now queued, but not executed
            session.connect(pairing); settle() // new save queued after clear
            io.last(); settle() // non-FIFO scheduler must not reorder disk intents
            repeat(12) { while (io.pending.isNotEmpty()) io.first(); settle() }
            assertTrue(session.state.value.canControl)
            assertNotNull("Previous session clear erased newly approved persisted session", store.saved)
        } finally { scope.cancel() }
    }
    @Test fun forgottenSessionCannotRestoreAcrossOwnersOrBeErasedByRetiredOwner() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val io = QueuedIO(); val store = Store()
            val persistence = DesktopRemotePersistence(store, io)
            suspend fun drain() { repeat(16) { while (io.pending.isNotEmpty()) io.last(); settle() } }
            val old = DesktopRemoteSession(Api(), scope, 100_000, persistence = persistence)
            drain(); old.visible(Any()); old.connect(pairing); drain()
            assertNotNull(store.saved)
            old.disconnect(); old.release()
            val fresh = DesktopRemoteSession(Api(), scope, 100_000, persistence = persistence)
            drain()
            assertEquals("disconnected", fresh.state.value.phase)
            assertNull(store.saved)
            fresh.visible(Any()); fresh.connect(pairing); drain()
            assertNotNull(store.saved)
            old.disconnect(); drain()
            assertNotNull("Retired owner must not forget replacement", store.saved)
        } finally { scope.cancel() }
    }

    @Test fun submittedSaveFollowedByForgetSurvivesCallerCancellation() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val io = QueuedIO(); val store = Store()
        val session = DesktopRemoteSession(Api(), scope, 100_000, store = store, ioDispatcher = io)
        settle(); io.first(); settle()
        session.visible(Any()); session.connect(pairing); settle()
        session.disconnect(); session.release(); scope.cancel()
        while (io.pending.isNotEmpty()) io.last()
        assertNull(store.saved)
    }

    @Test fun inFlightSaveCannotResurrectAfterDisconnectAndOwnerReplacement() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val io = QueuedIO(); val store = Store()
            val persistence = DesktopRemotePersistence(store, io)
            suspend fun drain() { repeat(16) { while (io.pending.isNotEmpty()) io.last(); settle() } }
            val old = DesktopRemoteSession(Api(), scope, 100_000, persistence = persistence)
            drain(); old.visible(Any()); old.connect(pairing); settle()
            var fresh: DesktopRemoteSession? = null
            store.onSave = {
                store.onSave = null
                old.disconnect(); old.release()
                fresh = DesktopRemoteSession(Api(), scope, 100_000, persistence = persistence)
            }
            drain()
            assertNotNull(fresh)
            assertNull(store.saved)
            assertEquals("disconnected", fresh!!.state.value.phase)
            fresh!!.visible(Any()); fresh!!.connect(pairing); drain()
            assertNotNull(store.saved)
            old.disconnect(); drain(); assertNotNull(store.saved)
        } finally { scope.cancel() }
    }

    @Test fun storageExceptionsDoNotPoisonOrderingOrMarkSaveAsRemembered() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = Store().apply { failSave = true }
            val session = DesktopRemoteSession(Api(), scope, 100_000, store = store, ioDispatcher = Dispatchers.Unconfined)
            session.visible(Any()); session.connect(pairing); settle()
            assertTrue(session.state.value.canControl); assertNull(store.saved)
            store.failSave = false; session.refresh(); settle(); assertNotNull(store.saved)
            store.failClear = true; session.disconnect(); settle()
            store.failClear = false; session.connect(pairing); settle()
            assertTrue(session.state.value.canControl)
            session.disconnect(); settle(); assertNull(store.saved)
        } finally { scope.cancel() }
    }

}
